package com.pueblo.catalog.inventory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.catalog.CatalogApplication;
import com.pueblo.catalog.data.CatalogImporter;
import com.pueblo.messaging.EventEnvelope;
import com.pueblo.messaging.OutboxDispatcher;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import static com.pueblo.catalog.inventory.InventoryCommand.Type.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class InventoryIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.7")
            .withUrlParam("connectionTimeZone", "UTC").withUrlParam("forceConnectionTimeZoneToSession", "true");
    @Container static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");
    static final String SECRET = "integration-only-".repeat(3);
    static ConfigurableApplicationContext first, second;
    static InventoryService a, b;
    static JdbcTemplate jdbc;
    static ObjectMapper mapper;
    static UUID sku;

    @BeforeAll static void startTwoApplications() throws Exception {
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(InventoryKafkaListener.COMMANDS_TOPIC, 2, (short) 1),
                    new NewTopic(InventoryService.EVENTS_TOPIC, 1, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        first = app("inventory-test-a");
        second = app("inventory-test-b");
        a = first.getBean(InventoryService.class); b = second.getBean(InventoryService.class);
        jdbc = first.getBean(JdbcTemplate.class); mapper = first.getBean(ObjectMapper.class);
        sku = UUID.fromString(CatalogImporter.id(CatalogImporter.id("DEMO", "1"), "black-m"));
    }
    static ConfigurableApplicationContext app(String name) {
        return new SpringApplicationBuilder(CatalogApplication.class).web(WebApplicationType.NONE).run(
                "--spring.datasource.url=" + MYSQL.getJdbcUrl(), "--spring.datasource.username=" + MYSQL.getUsername(),
                "--spring.datasource.password=" + MYSQL.getPassword(), "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.consumer.client-id=" + name, "--pueblo.messaging.enabled=true", "--pueblo.messaging.polling-enabled=false",
                "--pueblo.inventory.enabled=true", "--pueblo.inventory.listener-enabled=true", "--pueblo.inventory.command-secret=" + SECRET,
                "--pueblo.inventory.expiry-enabled=false", "--pueblo.docs.enabled=false", "--springdoc.api-docs.enabled=false",
                "--springdoc.swagger-ui.enabled=false");
    }
    @AfterAll static void closeApplications() { if (second != null) second.close(); if (first != null) first.close(); }
    @BeforeEach void seed() throws Exception {
        for (String table : List.of("inventory_reservation_item", "inventory_reservation", "inventory_command", "inventory_order",
                "outbox_event", "catalog_sku", "catalog_product_collection", "catalog_product", "catalog_brand", "catalog_category")) {
            jdbc.update("DELETE FROM " + table);
        }
        first.getBean(CatalogImporter.class).importJson(new ClassPathResource("catalog/demo.json").getContentAsByteArray());
        jdbc.update("UPDATE catalog_sku SET stock=1 WHERE id=?", sku.toString());
    }
    InventoryCommand reserve() { return new InventoryCommand(UUID.randomUUID(), RESERVE, UUID.randomUUID(), UUID.randomUUID(),
            1, List.of(new InventoryCommand.Item(sku, 1)), Instant.now().plusSeconds(25), null); }
    InventoryCommand action(InventoryCommand source, InventoryCommand.Type type) {
        return new InventoryCommand(UUID.randomUUID(), type, source.orderId(), source.reservationId(), source.generation(),
                List.of(), null, type == RESTORE ? UUID.randomUUID() : null);
    }
    int stock() { return jdbc.queryForObject("SELECT stock FROM catalog_sku WHERE id=?", Integer.class, sku.toString()); }
    long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class); }

    @Test void oneUnitSurvivesOneHundredConcurrentOrdersAcrossTwoApps() throws Exception {
        assertThat(first.getBean(javax.sql.DataSource.class)).isNotSameAs(second.getBean(javax.sql.DataSource.class));
        var start = new CountDownLatch(1);
        List<Future<InventoryResult>> futures = new ArrayList<>();
        long began = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < 100; i++) {
                InventoryService service = i % 2 == 0 ? a : b;
                InventoryCommand command = reserve();
                futures.add(executor.submit(() -> { start.await(); return service.execute(command); }));
            }
            start.countDown();
            int winners = 0;
            for (var future : futures) {
                var result = future.get(30, TimeUnit.SECONDS);
                if (result.eventType().equals("InventoryReserved")) winners++;
                else assertThat(result.reason()).isEqualTo("INVENTORY_UNAVAILABLE");
            }
            assertThat(winners).isEqualTo(1);
        }
        assertThat(stock()).isZero();
        assertThat(count("inventory_reservation_item")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(100);
        System.out.println("INVENTORY_RACE: requests=100 apps=2 winners=1 stock=0 elapsedMs=" + (System.nanoTime() - began) / 1_000_000);
    }

    @Test void duplicateCommandAndBusinessRetryDoNotDoubleDecrement() {
        var c = reserve(); var result = a.execute(c);
        assertThat(b.execute(c)).isEqualTo(result);
        assertThat(count("outbox_event")).isEqualTo(1);
        var repeat = new InventoryCommand(UUID.randomUUID(), RESERVE, c.orderId(), c.reservationId(), 1, c.items(), c.commandExpiresAt(), null);
        assertThat(b.execute(repeat).eventType()).isEqualTo("InventoryReserved");
        assertThat(stock()).isZero(); assertThat(count("inventory_reservation_item")).isEqualTo(1);
        var conflict = new InventoryCommand(c.commandId(), RELEASE, c.orderId(), c.reservationId(), 1, List.of(), null, null);
        assertThatThrownBy(() -> a.execute(conflict)).isInstanceOf(IllegalArgumentException.class);
        assertThat(stock()).isZero();
    }

    @Test void multiSkuFailureRollsBackEveryDeduction() {
        List<String> ids = jdbc.queryForList("SELECT id FROM catalog_sku WHERE active=TRUE ORDER BY id LIMIT 2", String.class);
        jdbc.update("UPDATE catalog_sku SET stock=1 WHERE id=?", ids.get(0));
        jdbc.update("UPDATE catalog_sku SET stock=0 WHERE id=?", ids.get(1));
        var c = reserve();
        var items = List.of(new InventoryCommand.Item(UUID.fromString(ids.get(0)), 1), new InventoryCommand.Item(UUID.fromString(ids.get(1)), 1));
        var request = new InventoryCommand(c.commandId(), RESERVE, c.orderId(), c.reservationId(), 1, items, c.commandExpiresAt(), null);
        assertThat(a.execute(request).reason()).isEqualTo("INVENTORY_UNAVAILABLE");
        assertThat(jdbc.queryForObject("SELECT stock FROM catalog_sku WHERE id=?", Integer.class, ids.get(0))).isEqualTo(1);
        assertThat(count("inventory_reservation_item")).isZero();
        assertThat(count("inventory_command")).isEqualTo(1); assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test void deadlineAndSalesStateAreChecked() {
        var c = reserve();
        var expired = new InventoryCommand(c.commandId(), RESERVE, c.orderId(), c.reservationId(), 1, c.items(), Instant.now().minusSeconds(1), null);
        assertThat(a.execute(expired).reason()).isEqualTo("COMMAND_EXPIRED");
        assertThat(stock()).isEqualTo(1);
        jdbc.update("UPDATE catalog_sku SET active=FALSE WHERE id=?", sku.toString());
        assertThat(a.execute(reserve()).reason()).isEqualTo("INVENTORY_UNAVAILABLE");
        jdbc.update("UPDATE catalog_sku SET active=TRUE WHERE id=?", sku.toString());
        jdbc.update("UPDATE catalog_product SET status='DRAFT'");
        assertThat(a.execute(reserve()).reason()).isEqualTo("INVENTORY_UNAVAILABLE");
        assertThat(stock()).isEqualTo(1);
    }

    @Test void quantitiesAreCombinedAndSkuLimitIsEnforced() {
        var c = reserve();
        assertThatThrownBy(() -> new InventoryCommand(c.commandId(), RESERVE, c.orderId(), c.reservationId(), 1,
                List.of(new InventoryCommand.Item(sku, 5), new InventoryCommand.Item(sku, 5)), c.commandExpiresAt(), null))
                .isInstanceOf(IllegalArgumentException.class);
        jdbc.update("UPDATE catalog_sku SET stock=10,purchase_limit=2 WHERE id=?", sku.toString());
        var tooMany = new InventoryCommand(c.commandId(), RESERVE, c.orderId(), c.reservationId(), 1,
                List.of(new InventoryCommand.Item(sku, 1), new InventoryCommand.Item(sku, 2)), c.commandExpiresAt(), null);
        assertThat(tooMany.items()).hasSize(1);
        assertThat(a.execute(tooMany).reason()).isEqualTo("INVENTORY_UNAVAILABLE");
        assertThat(stock()).isEqualTo(10);
    }

    @Test void releaseBeforeReserveAndOldGenerationCannotReleaseNewReservation() {
        var c = reserve();
        assertThat(a.execute(action(c, RELEASE)).state()).isEqualTo("RELEASED");
        assertThat(b.execute(c).reason()).isEqualTo("INVALID_STATE");
        assertThat(stock()).isEqualTo(1);
        var next = new InventoryCommand(UUID.randomUUID(), REACQUIRE, c.orderId(), UUID.randomUUID(), 2, c.items(), Instant.now().plusSeconds(25), null);
        assertThat(a.execute(next).state()).isEqualTo("RESERVED");
        assertThat(a.execute(action(c, RELEASE)).reason()).isEqualTo("STALE_GENERATION");
        assertThat(stock()).isZero();
        assertThat(a.execute(action(next, RELEASE)).state()).isEqualTo("RELEASED");
        assertThat(a.execute(action(next, RELEASE)).state()).isEqualTo("RELEASED");
        assertThat(stock()).isEqualTo(1);
    }

    @Test void aNewGenerationCannotLeakActiveOrCommittedStock() {
        var c = reserve(); a.execute(c);
        var next = new InventoryCommand(UUID.randomUUID(), REACQUIRE, c.orderId(), UUID.randomUUID(), 2, c.items(), Instant.now().plusSeconds(25), null);
        assertThat(b.execute(next).reason()).isEqualTo("ACTIVE_GENERATION");
        a.execute(action(c, COMMIT));
        assertThat(b.execute(new InventoryCommand(UUID.randomUUID(), REACQUIRE, c.orderId(), next.reservationId(), 2,
                c.items(), next.commandExpiresAt(), null)).reason()).isEqualTo("ACTIVE_GENERATION");
        assertThat(stock()).isZero();
    }

    @Test void commitDoesNotDeductAgainAndRefundRestoresExactlyOnce() {
        var c = reserve(); a.execute(c);
        assertThat(a.execute(action(c, COMMIT)).state()).isEqualTo("COMMITTED");
        assertThat(b.execute(action(c, COMMIT)).state()).isEqualTo("COMMITTED");
        assertThat(a.execute(action(c, RELEASE)).reason()).isEqualTo("INVALID_STATE");
        assertThat(stock()).isZero();
        var refund = action(c, RESTORE);
        assertThat(a.execute(refund).state()).isEqualTo("RESTORED");
        assertThat(b.execute(refund).state()).isEqualTo("RESTORED");
        assertThat(b.execute(new InventoryCommand(UUID.randomUUID(), RESTORE, c.orderId(), c.reservationId(), 1,
                List.of(), null, refund.refundId())).state()).isEqualTo("RESTORED");
        assertThat(a.execute(action(c, RESTORE)).reason()).isEqualTo("INVALID_STATE");
        assertThat(stock()).isEqualTo(1);
    }

    @Test void expirationPersistsAcrossWorkerRestartAndRejectsLateCommit() throws Exception {
        var c = reserve(); var result = a.execute(c);
        assertThat(Duration.between(result.reservedAt(), result.expiresAt())).isEqualTo(Duration.ofMinutes(15));
        second.close();
        second = app("inventory-test-restarted");
        b = second.getBean(InventoryService.class);
        jdbc.update("UPDATE inventory_reservation SET expires_at=TIMESTAMPADD(SECOND,-1,UTC_TIMESTAMP(6))");
        try (var executor = Executors.newFixedThreadPool(2)) {
            var expiration = executor.submit(b::expireBatch);
            var commit = executor.submit(() -> a.execute(action(c, COMMIT)));
            expiration.get(10, TimeUnit.SECONDS); assertThat(commit.get(10, TimeUnit.SECONDS).eventType()).isEqualTo("InventoryCommandRejected");
        }
        assertThat(stock()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM inventory_reservation WHERE id=?", String.class, c.reservationId().toString())).isEqualTo("EXPIRED");
        assertThat(b.expireBatch()).isZero();
        assertThat(a.execute(action(c, RESTORE)).reason()).isEqualTo("INVALID_STATE");
        assertThat(stock()).isEqualTo(1);
    }

    @Test void outboxFailureRollsBackStockAndCommandReceipt() {
        var c = reserve();
        var failingOutbox = org.mockito.Mockito.mock(com.pueblo.messaging.JdbcOutbox.class);
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("simulated outbox failure"))
                .when(failingOutbox).append(org.mockito.ArgumentMatchers.any());
        var failingService = new InventoryService(jdbc, mapper, failingOutbox,
                first.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        assertThatThrownBy(() -> failingService.execute(c)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(stock()).isEqualTo(1);
        assertThat(count("inventory_command")).isZero(); assertThat(count("inventory_reservation")).isZero();
        assertThat(a.execute(c).state()).isEqualTo("RESERVED");
    }

    @Test void commandAuthenticationRejectsTamperingMissingSignatureAndWrongProducer() throws Exception {
        var listener = first.getBean(InventoryKafkaListener.class);
        var c = reserve(); String body = envelope(c, "order-service");
        assertThatThrownBy(() -> listener.receive(new ConsumerRecord<>(InventoryKafkaListener.COMMANDS_TOPIC, 0, 0, c.orderId().toString(), body)))
                .isInstanceOf(IllegalArgumentException.class);
        var record = signedRecord(c, body + " ");
        record.headers().remove(InventoryKafkaListener.SIGNATURE_HEADER);
        record.headers().add(InventoryKafkaListener.SIGNATURE_HEADER, sign(body));
        assertThatThrownBy(() -> listener.receive(record)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> listener.receive(signedRecord(c, envelope(c, "payment-service")))).isInstanceOf(IllegalArgumentException.class);
        assertThat(count("inventory_command")).isZero(); assertThat(stock()).isEqualTo(1);
    }

    @Test void authenticatedKafkaCommandAndDuplicateProduceOneDurableResult() throws Exception {
        var c = reserve(); String body = envelope(c, "order-service");
        @SuppressWarnings("unchecked") KafkaTemplate<Object, Object> kafka = first.getBean(KafkaTemplate.class);
        org.apache.kafka.clients.producer.RecordMetadata last = null;
        for (int i = 0; i < 2; i++) {
            var record = new ProducerRecord<Object, Object>(InventoryKafkaListener.COMMANDS_TOPIC, c.orderId().toString(), body);
            record.headers().add(InventoryKafkaListener.SIGNATURE_HEADER, sign(body));
            last = kafka.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
        }
        await(() -> count("inventory_command") == 1 && count("outbox_event") == 1);
        var lastRecord = last;
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            await(() -> {
                try {
                    var offsets = admin.listConsumerGroupOffsets("catalog-inventory-v1").partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS);
                    var offset = offsets.get(new TopicPartition(lastRecord.topic(), lastRecord.partition()));
                    return offset != null && offset.offset() > lastRecord.offset();
                } catch (Exception ex) { throw new IllegalStateException(ex); }
            });
        }
        assertThat(stock()).isZero();
        assertThat(first.getBean(OutboxDispatcher.class).dispatchOne()).isTrue();
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(Map.of("bootstrap.servers", KAFKA.getBootstrapServers(),
                "group.id", "verify-" + UUID.randomUUID(), "enable.auto.commit", false,
                "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer",
                "value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer"))) {
            var partition = new TopicPartition(InventoryService.EVENTS_TOPIC, 0);
            reader.assign(List.of(partition)); reader.seekToBeginning(List.of(partition));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean found = false;
            while (!found && System.nanoTime() < deadline) {
                for (var record : reader.poll(Duration.ofMillis(100))) {
                    EventEnvelope result = mapper.readValue(record.value(), EventEnvelope.class);
                    if (c.commandId().toString().equals(result.causationId())) {
                        assertThat(result.eventType()).isEqualTo("InventoryReserved"); found = true;
                    }
                }
            }
            assertThat(found).isTrue();
        }
        // Direct replay also verifies persisted receipt after Kafka consumption.
        assertThat(b.execute(c).state()).isEqualTo("RESERVED");
        assertThat(count("outbox_event")).isEqualTo(1);
    }
    static String envelope(InventoryCommand c, String producer) throws Exception {
        return mapper.writeValueAsString(new EventEnvelope(c.commandId(), "InventoryCommand", 1, producer,
                c.orderId().toString(), 0, Instant.now(), c.orderId().toString(), null, null, mapper.valueToTree(c)));
    }
    static byte[] sign(String body) {
        return HexFormat.of().formatHex(InventoryKafkaListener.signature(body.getBytes(StandardCharsets.UTF_8), SECRET.getBytes(StandardCharsets.UTF_8)))
                .getBytes(StandardCharsets.US_ASCII);
    }
    static ConsumerRecord<String, String> signedRecord(InventoryCommand c, String body) {
        var record = new ConsumerRecord<>(InventoryKafkaListener.COMMANDS_TOPIC, 0, 0L, c.orderId().toString(), body);
        record.headers().add(InventoryKafkaListener.SIGNATURE_HEADER, sign(body)); return record;
    }
    static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertThat(condition.getAsBoolean()).isTrue();
    }
}
