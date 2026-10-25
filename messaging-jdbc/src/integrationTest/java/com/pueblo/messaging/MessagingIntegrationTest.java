package com.pueblo.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
@SpringBootTest(classes = MessagingIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.application.name=messaging-test", "pueblo.messaging.polling-enabled=false",
                "pueblo.messaging.max-attempts=3", "spring.kafka.producer.acks=all",
                "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer",
                "spring.kafka.producer.properties.max.block.ms=2000",
                "spring.kafka.producer.properties.request.timeout.ms=1000",
                "spring.kafka.producer.properties.delivery.timeout.ms=2000",
                "spring.kafka.producer.properties.linger.ms=0"})
class MessagingIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.7")
            .withDatabaseName("messaging_test")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");
    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.1.2");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.flyway.locations", () -> "filesystem:" + System.getProperty("pueblo.test.migration"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication { }

    @Autowired JdbcTemplate jdbc;
    @Autowired JdbcOutbox outbox;
    @Autowired JdbcEventConsumer consumer;
    @Autowired OutboxDispatcher dispatcher;
    @Autowired PlatformTransactionManager manager;
    @Autowired ObjectMapper mapper;
    @Autowired MessagingProperties properties;
    @Autowired KafkaTemplate<Object, Object> kafka;
    @Autowired Flyway flyway;
    TransactionTemplate transaction;

    @BeforeEach
    void reset() {
        assertThat(flyway.info().applied()).hasSize(1);
        transaction = new TransactionTemplate(manager);
        jdbc.execute("CREATE TABLE IF NOT EXISTS business_effect (id BIGINT AUTO_INCREMENT PRIMARY KEY, value_text VARCHAR(128))");
        jdbc.update("DELETE FROM consumed_event");
        jdbc.update("DELETE FROM outbox_event");
        jdbc.update("DELETE FROM business_effect");
    }

    @Test
    void appendRequiresBusinessTransaction() {
        assertThatThrownBy(() -> outbox.append(record("unpublished")))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void rollbackRemovesBusinessChangeAndOutbox() {
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            effect();
            outbox.append(record("unpublished"));
            throw new IllegalStateException("business failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("business_effect")).isZero();
        assertThat(count("outbox_event")).isZero();
    }

    @Test
    void consumerFailureRollsBackMarkerBusinessAndFollowupEvent() {
        UUID eventId = UUID.randomUUID();
        assertThatThrownBy(() -> consumer.consume("orders", eventId, () -> {
            effect();
            outbox.append(record("unpublished"));
            throw new IllegalStateException("retry me");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("consumed_event")).isZero();
        assertThat(count("business_effect")).isZero();
        assertThat(count("outbox_event")).isZero();
        assertThat(consumer.consume("orders", eventId, () -> {
            effect();
            outbox.append(record("unpublished"));
        })).isTrue();
        assertThat(count("consumed_event")).isEqualTo(1);
        assertThat(count("business_effect")).isEqualTo(1);
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void concurrentDuplicateConsumersApplyBusinessEffectOnce() throws Exception {
        UUID eventId = UUID.randomUUID();
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Callable<Boolean>> actions = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                actions.add(() -> consumer.consume("orders", eventId, this::effect));
            }
            int applied = 0;
            for (var result : pool.invokeAll(actions)) {
                if (result.get(10, TimeUnit.SECONDS)) applied++;
            }
            assertThat(applied).isEqualTo(1);
        }
        assertThat(count("business_effect")).isEqualTo(1);
    }

    @Test
    void deduplicationIsScopedToLogicalConsumer() {
        UUID eventId = UUID.randomUUID();
        assertThat(consumer.consume("orders", eventId, this::effect)).isTrue();
        assertThat(consumer.consume("inventory", eventId, this::effect)).isTrue();
        assertThat(consumer.consume("orders", eventId, this::effect)).isFalse();
        assertThat(count("business_effect")).isEqualTo(2);
    }

    @Test
    void multiplePollersClaimDifferentRows() throws Exception {
        transaction.executeWithoutResult(status -> {
            for (int i = 0; i < 12; i++) outbox.append(record("unpublished"));
        });
        var claimed = new HashSet<UUID>();
        try (var pool = Executors.newFixedThreadPool(4)) {
            // SKIP LOCKED may temporarily yield no row while another claimant holds locks.
            for (int round = 0; round < 20 && claimed.size() < 12; round++) {
                List<Callable<java.util.Optional<OutboxLease>>> claims = new ArrayList<>();
                for (int i = 0; i < 4; i++) claims.add(outbox::claimNext);
                for (var result : pool.invokeAll(claims)) {
                    var lease = result.get(10, TimeUnit.SECONDS);
                    if (lease.isPresent()) {
                        assertThat(claimed.add(lease.get().record().event().eventId())).isTrue();
                    }
                }
            }
        }
        assertThat(claimed).hasSize(12);
        assertThat(outbox.claimNext()).isEmpty();
    }

    @Test
    void expiredLeaseRecoversAndStaleWorkerCannotCompleteNewClaim() {
        append(record("unpublished"));
        OutboxLease oldLease = outbox.claimNext().orElseThrow();
        expireLease();
        // A new repository instance models a different worker using the durable DB record.
        JdbcOutbox restarted = new JdbcOutbox(jdbc, mapper, "messaging-test", manager, properties);
        OutboxLease newLease = restarted.claimNext().orElseThrow();
        assertThat(newLease.owner()).isNotEqualTo(oldLease.owner());
        assertThat(newLease.record().event().eventId()).isEqualTo(oldLease.record().event().eventId());
        assertThat(outbox.markPublished(oldLease)).isFalse();
        assertThat(outbox.markFailedAttempt(oldLease)).isFalse();
        assertThat(restarted.markPublished(newLease)).isTrue();
        assertThat(state()).isEqualTo("PUBLISHED");
    }

    @Test
    void lastAttemptAbandonedByWorkerIsEventuallyQuarantined() {
        append(record("unpublished"));
        jdbc.update("UPDATE outbox_event SET attempts = ?", properties.maxAttempts() - 1);
        outbox.claimNext().orElseThrow();
        expireLease();
        OutboxDispatcher recovery = new OutboxDispatcher(outbox, value -> fail("Must not send beyond the limit"), properties);
        assertThat(recovery.dispatchOne()).isTrue();
        assertThat(state()).isEqualTo("FAILED");
        assertThat(outbox.claimNext()).isEmpty();
    }

    @Test
    void failedSendsBackOffThenStopAtConfiguredLimit() {
        append(record("unpublished"));
        OutboxDispatcher failing = new OutboxDispatcher(outbox, value -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw new IllegalStateException("simulated broker failure");
        }, properties);
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            assertThat(failing.dispatchOne()).isTrue();
            assertThat(outbox.claimNext()).isEmpty();
            assertThat(state()).isEqualTo(attempt == properties.maxAttempts() ? "FAILED" : "PENDING");
            jdbc.update("UPDATE outbox_event SET next_attempt_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))");
        }
        assertThat(count("outbox_event")).isEqualTo(1);
    }

    @Test
    void crashAfterKafkaAckReplaysSameEventButConsumerAppliesOnce() throws Exception {
        String topic = createTopic();
        OutboxRecord event = record(topic);
        append(event);
        OutboxLease abandoned = outbox.claimNext().orElseThrow();
        new KafkaEventPublisher(kafka, mapper, properties.sendTimeoutMs()).publish(abandoned.record());
        // Broker acknowledged, but worker never marked the row published.
        expireLease();
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(state()).isEqualTo("PUBLISHED");
        List<String> deliveries = read(topic, 2);
        assertThat(deliveries).hasSize(2);
        for (String delivery : deliveries) {
            EventEnvelope received = mapper.readValue(delivery, EventEnvelope.class);
            assertThat(received.eventId()).isEqualTo(event.event().eventId());
            assertThat(received.payload()).isEqualTo(event.event().payload());
            assertThat(received.occurredAt()).isEqualTo(event.event().occurredAt());
            consumer.consume("orders", received.eventId(), this::effect);
        }
        assertThat(count("business_effect")).isEqualTo(1);
    }

    @Test
    void brokerTimeoutRetainsEventAndPublishesAfterRecovery() throws Exception {
        String topic = createTopic();
        OutboxRecord event = record(topic);
        append(event);
        kafka.partitionsFor(topic);
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            assertThat(dispatcher.dispatchOne()).isTrue();
            assertThat(state()).isEqualTo("PENDING");
            assertThat(count("outbox_event")).isEqualTo(1);
        } finally {
            KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        jdbc.update("UPDATE outbox_event SET next_attempt_at = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))");
        assertThat(dispatcher.dispatchOne()).isTrue();
        assertThat(state()).isEqualTo("PUBLISHED");
        List<String> deliveries = read(topic, 1);
        assertThat(deliveries).isNotEmpty();
        for (String delivery : deliveries) {
            EventEnvelope received = mapper.readValue(delivery, EventEnvelope.class);
            assertThat(received.eventId()).isEqualTo(event.event().eventId());
            consumer.consume("orders", received.eventId(), this::effect);
        }
        assertThat(count("business_effect")).isEqualTo(1);
    }

    @Test
    void failedListenerStopsAndReplaysUncommittedEventAfterRestart() throws Exception {
        String topic = createTopic();
        String group = "orders-" + UUID.randomUUID();
        OutboxRecord event = record(topic);
        var failureReached = new java.util.concurrent.CountDownLatch(1);
        var successReached = new java.util.concurrent.CountDownLatch(1);
        var failed = listener(topic, group, received -> consumer.consume("orders", received.eventId(), () -> {
            effect();
            failureReached.countDown();
            throw new IllegalStateException("simulated local transaction failure");
        }));
        try {
            failed.start();
            new KafkaEventPublisher(kafka, mapper, properties.sendTimeoutMs()).publish(event);
            assertThat(failureReached.await(15, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (failed.isRunning() && System.nanoTime() < deadline) Thread.sleep(50);
            assertThat(failed.isRunning()).isFalse();
        } finally {
            failed.stop();
        }
        assertThat(count("business_effect")).isZero();
        assertThat(count("consumed_event")).isZero();
        var recovered = listener(topic, group, received -> {
            consumer.consume("orders", received.eventId(), this::effect);
            successReached.countDown();
        });
        try {
            recovered.start();
            assertThat(successReached.await(15, TimeUnit.SECONDS)).isTrue();
        } finally {
            recovered.stop();
        }
        assertThat(count("business_effect")).isEqualTo(1);
        assertThat(count("consumed_event")).isEqualTo(1);
    }

    private org.springframework.kafka.listener.KafkaMessageListenerContainer<String, String> listener(
            String topic, String group, java.util.function.Consumer<EventEnvelope> action) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        var containerProperties = new org.springframework.kafka.listener.ContainerProperties(topic);
        containerProperties.setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.RECORD);
        containerProperties.setMessageListener((org.springframework.kafka.listener.MessageListener<String, String>) message -> {
            try {
                action.accept(mapper.readValue(message.value(), EventEnvelope.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new IllegalArgumentException("Invalid event envelope", ex);
            }
        });
        var container = new org.springframework.kafka.listener.KafkaMessageListenerContainer<>(
                new org.springframework.kafka.core.DefaultKafkaConsumerFactory<String, String>(config), containerProperties);
        container.setCommonErrorHandler(new org.springframework.kafka.listener.CommonContainerStoppingErrorHandler());
        return container;
    }

    private OutboxRecord record(String topic) {
        return new OutboxRecord(topic, "order-1", new EventEnvelope(UUID.randomUUID(),
                "PaymentSucceeded", 1, "messaging-test", "order-1", 1,
                // MySQL DATETIME(6) precision is the envelope timestamp contract.
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS),
                "order-1", null, "request-1", mapper.createObjectNode().put("amount", 10000)));
    }

    private void append(OutboxRecord record) {
        transaction.executeWithoutResult(status -> outbox.append(record));
    }

    private void effect() {
        jdbc.update("INSERT INTO business_effect (value_text) VALUES ('applied')");
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private String state() {
        return jdbc.queryForObject("SELECT status FROM outbox_event", String.class);
    }

    private void expireLease() {
        jdbc.update("UPDATE outbox_event SET lease_until = TIMESTAMPADD(SECOND, -1, UTC_TIMESTAMP(6))");
    }

    private String createTopic() throws Exception {
        String topic = "messaging-test-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get(15, TimeUnit.SECONDS);
        }
        return topic;
    }

    private List<String> read(String topic, int expected) {
        Map<String, Object> config = new HashMap<>();
        config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "verification-" + UUID.randomUUID());
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> reader = new KafkaConsumer<>(config)) {
            TopicPartition partition = new TopicPartition(topic, 0);
            reader.assign(List.of(partition));
            reader.seekToBeginning(List.of(partition));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (values.size() < expected && System.nanoTime() < deadline) {
                reader.poll(Duration.ofMillis(250)).forEach(message -> values.add(message.value()));
            }
        }
        return values;
    }
}
