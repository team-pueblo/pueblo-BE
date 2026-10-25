package com.pueblo.order;

import com.fasterxml.jackson.databind.*;
import com.pueblo.catalog.CatalogApplication;
import com.pueblo.catalog.data.CatalogImporter;
import com.pueblo.messaging.*;
import com.pueblo.order.checkout.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.KafkaContainer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderFlowIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withUsername("root").withPassword("integration-only")
            .withUrlParam("connectionTimeZone","UTC").withUrlParam("forceConnectionTimeZoneToSession","true");
    @Container static final KafkaContainer KAFKA=new KafkaContainer("apache/kafka:4.1.2");
    static final String SECRET="order-integration-only-key-32-bytes";
    static final String PAYMENT_TOKEN="payment-service-integration-only-credential";
    static final UUID USER=UUID.randomUUID(), OTHER=UUID.randomUUID();
    static ConfigurableApplicationContext catalog, order;
    static JdbcTemplate catalogDb, orderDb;
    static ObjectMapper mapper;
    static HttpServer member;
    static int memberPort, catalogPort, orderPort;
    static UUID product,sku;
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @BeforeAll static void start() throws Exception {
        // Separate users cannot read the other service's database, even in this harness.
        try(var c=DriverManager.getConnection(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());var sql=c.createStatement()) {
            for(String name:List.of("order","catalog")) {
                sql.execute("CREATE DATABASE test_"+name);
                sql.execute("CREATE USER 'app_"+name+"'@'%' IDENTIFIED BY 'test-only'");
                sql.execute("GRANT ALL ON test_"+name+".* TO 'app_"+name+"'@'%'");
            }
        }
        try(Admin admin=Admin.create(Map.of("bootstrap.servers",KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(OrderService.COMMANDS_TOPIC,1,(short)1),new NewTopic(OrderService.RESULTS_TOPIC,1,(short)1))).all().get(20,TimeUnit.SECONDS);
        }
        member=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        member.createContext("/api/v1/me",exchange->{
            String token=exchange.getRequestHeaders().getFirst("Authorization");
            UUID user="Bearer fixture-user".equals(token)?USER:"Bearer fixture-other".equals(token)?OTHER:null;
            byte[] body=(user==null?"{}":"{\"data\":{\"id\":\""+user+"\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type","application/json");
            exchange.sendResponseHeaders(user==null?401:200,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        member.start(); memberPort=member.getAddress().getPort();
        catalog=app("catalog",CatalogApplication.class,0);
        catalogPort=port(catalog); catalogDb=catalog.getBean(JdbcTemplate.class);
        catalog.getBean(CatalogImporter.class).importJson(new ClassPathResource("catalog/demo.json").getContentAsByteArray());
        product=UUID.fromString(CatalogImporter.id("DEMO","1")); sku=UUID.fromString(CatalogImporter.id(product.toString(),"black-m"));
        order=app("order",OrderApplication.class,catalogPort);
        orderPort=port(order); orderDb=order.getBean(JdbcTemplate.class); mapper=order.getBean(ObjectMapper.class);
    }
    static ConfigurableApplicationContext app(String service,Class<?> type,int catalogPort) {
        String root=System.getProperty("pueblo.repo.root");
        var args=new ArrayList<>(List.of("--spring.application.name="+service+"-service","--server.port=0",
                "--spring.datasource.url=jdbc:mysql://"+MYSQL.getHost()+":"+MYSQL.getMappedPort(3306)+"/test_"+service+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",
                "--spring.datasource.username=app_"+service,"--spring.datasource.password=test-only",
                "--spring.flyway.locations=filesystem:"+root+"/"+service+"-service/src/main/resources/db/migration",
                "--spring.kafka.bootstrap-servers="+KAFKA.getBootstrapServers(),"--pueblo.messaging.polling-enabled=false",
                "--pueblo.orders.enabled="+service.equals("order"),"--pueblo.orders.inventory-secret="+SECRET,
                "--pueblo.orders.payment-preparation.enabled="+service.equals("order"),"--pueblo.orders.payment-preparation.service-token="+PAYMENT_TOKEN,
                "--pueblo.orders.member-url=http://127.0.0.1:"+memberPort,"--pueblo.orders.catalog-url=http://127.0.0.1:"+catalogPort,
                "--pueblo.orders.timeout-poll-ms=3600000","--pueblo.inventory.enabled="+service.equals("catalog"),
                "--pueblo.inventory.listener-enabled="+service.equals("catalog"),"--pueblo.inventory.expiry-enabled=false",
                "--pueblo.inventory.command-secret="+SECRET,"--pueblo.docs.enabled=false","--springdoc.api-docs.enabled=false",
                "--springdoc.swagger-ui.enabled=false","--management.endpoint.health.group.readiness.include=readinessState,db",
                "--logging.level.org.apache.kafka=WARN","--logging.level.org.springframework.kafka=WARN"));
        return new SpringApplicationBuilder(type).web(WebApplicationType.SERVLET).run(args.toArray(String[]::new));
    }
    static int port(ConfigurableApplicationContext context) { return ((ServletWebServerApplicationContext)context).getWebServer().getPort(); }
    @AfterAll static void close() { if(order!=null) order.close(); if(catalog!=null) catalog.close(); if(member!=null) member.stop(0); }
    @BeforeEach void resetStock() { catalogDb.update("UPDATE catalog_sku SET stock=10 WHERE id=?",sku.toString()); }
    HttpResponse<String> request(String method,String path,Object body,String token,String key) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+orderPort+path)).timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(token!=null) builder.header("Authorization","Bearer "+token);
        if(key!=null) builder.header("Idempotency-Key",key);
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        return HTTP.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response,int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status); return mapper.readTree(response.body()).path("data");
    }
    UUID quote() throws Exception {
        var response=request("POST","/api/v1/checkout/quotes",Map.of("items",List.of(Map.of("productId",product,"skuId",sku,"quantity",1))),"fixture-user",null);
        return UUID.fromString(data(response,201).path("quoteId").asText());
    }
    Map<String,Object> body(UUID quote) { return Map.of("quoteId",quote,"shipping",Map.of("recipient","Tester","phone","01012345678","postalCode","12345","address1","Synthetic test address"),"purchaseTermsVersion","purchase-v1","agreed",true); }
    UUID create(UUID quote,String key) throws Exception { return UUID.fromString(data(request("POST","/api/v1/orders",body(quote),"fixture-user",key),202).path("orderId").asText()); }
    String state(UUID id) { return orderDb.queryForObject("SELECT status FROM customer_order WHERE id=?",String.class,id.toString()); }
    int stock() { return catalogDb.queryForObject("SELECT stock FROM catalog_sku WHERE id=?",Integer.class,sku.toString()); }
    void dispatch(ConfigurableApplicationContext app) { app.getBean(OutboxDispatcher.class).poll(); }
    void settle(UUID id,String expected) {
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(150)).untilAsserted(()->{
            dispatch(order); dispatch(catalog); assertThat(state(id)).isEqualTo(expected);
        });
    }
    @Test @Order(1) void reservesAndCancelsThroughBothSignedKafkaDirections() throws Exception {
        UUID quote=quote(); String key=UUID.randomUUID().toString(); UUID id=create(quote,key);
        assertThat(state(id)).isEqualTo("PREPARING");
        assertThat(create(quote,key)).isEqualTo(id);
        settle(id,"PENDING_PAYMENT"); assertThat(stock()).isEqualTo(9);
        data(request("POST","/api/v1/me/orders/"+id+"/cancellations",null,"fixture-user",null),202);
        assertThat(state(id)).isEqualTo("ABORTING");
        settle(id,"CANCELED"); assertThat(stock()).isEqualTo(10);
        data(request("POST","/api/v1/me/orders/"+id+"/cancellations",null,"fixture-user",null),202);
        assertThat(stock()).isEqualTo(10);
        assertThat(orderDb.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,id.toString())).isEqualTo(2);
    }
    @Test @Order(2) void enforcesAuthenticationOwnershipIdempotencyAndQuoteLifetime() throws Exception {
        assertThat(request("POST","/api/v1/checkout/quotes",Map.of(),null,null).statusCode()).isEqualTo(401);
        assertThat(request("GET","/api/v1/me/orders",null,"revoked",null).statusCode()).isEqualTo(401);
        UUID q=quote(); String key=UUID.randomUUID().toString(); UUID id=create(q,key);
        assertThat(request("GET","/api/v1/me/orders/"+id,null,"fixture-other",null).statusCode()).isEqualTo(404);
        assertThat(request("POST","/api/v1/me/orders/"+id+"/cancellations",null,"fixture-other",null).statusCode()).isEqualTo(404);
        assertThat(request("POST","/api/v1/orders",body(quote()),"fixture-user",key).body()).contains("IDEMPOTENCY_CONFLICT");
        assertThat(request("POST","/api/v1/orders",body(q),"fixture-user",UUID.randomUUID().toString()).body()).contains("QUOTE_ALREADY_USED");
        UUID expired=quote(); orderDb.update("UPDATE checkout_quote SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",expired.toString());
        assertThat(request("POST","/api/v1/orders",body(expired),"fixture-user",UUID.randomUUID().toString()).statusCode()).isEqualTo(410);
        settle(id,"PENDING_PAYMENT");
    }
    @Test @Order(3) void priceChangesBeforeOrderAndBeforeInventoryAreBothRejected() throws Exception {
        UUID q=quote(); catalogDb.update("UPDATE catalog_sku SET price_override=12345 WHERE id=?",sku.toString());
        assertThat(request("POST","/api/v1/orders",body(q),"fixture-user",UUID.randomUUID().toString()).body()).contains("PRICE_CHANGED");
        UUID id=create(quote(),UUID.randomUUID().toString());
        catalogDb.update("UPDATE catalog_sku SET price_override=12346 WHERE id=?",sku.toString());
        settle(id,"REJECTED"); assertThat(stock()).isEqualTo(10);
        assertThat(orderDb.queryForObject("SELECT reason FROM customer_order WHERE id=?",String.class,id.toString())).isEqualTo("PRICE_CHANGED");
        catalogDb.update("UPDATE catalog_sku SET price_override=NULL WHERE id=?",sku.toString());
    }
    @Test @Order(4) void lastUnitAcceptsOnlyOneOrder() throws Exception {
        catalogDb.update("UPDATE catalog_sku SET stock=1 WHERE id=?",sku.toString());
        UUID first=create(quote(),UUID.randomUUID().toString()),second=create(quote(),UUID.randomUUID().toString());
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->{
            dispatch(order); dispatch(catalog);
            assertThat(List.of(state(first),state(second))).containsExactlyInAnyOrder("PENDING_PAYMENT","REJECTED");
        });
        assertThat(stock()).isZero();
    }
    @Test @Order(5) void timeoutAndLateSuccessCannotReviveAbortedOrder() throws Exception {
        UUID id=create(quote(),UUID.randomUUID().toString());
        dispatch(order);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(catalogDb.queryForObject("SELECT COUNT(*) FROM inventory_reservation WHERE order_id=? AND status='RESERVED'",Integer.class,id.toString())).isEqualTo(1));
        orderDb.update("UPDATE customer_order SET prepare_deadline=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",id.toString());
        order.getBean(OrderService.class).expireBatch();
        assertThat(state(id)).isEqualTo("ABORTING");
        settle(id,"REJECTED"); assertThat(stock()).isEqualTo(10);
    }
    @Test @Order(6) void duplicateResultsAndForgedSignaturesDoNotChangeStock() throws Exception {
        UUID id=create(quote(),UUID.randomUUID().toString()); settle(id,"PENDING_PAYMENT");
        String raw=catalogDb.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id=? ORDER BY created_at LIMIT 1",String.class,id.toString());
        var result=mapper.readValue(raw,OrderModels.Result.class);
        var event=new EventEnvelope(UUID.randomUUID(),result.eventType(),1,"catalog-service",id.toString(),result.version(),Instant.now(),id.toString(),result.commandId().toString(),null,mapper.valueToTree(result));
        String json=mapper.writeValueAsString(event);
        var record=new ConsumerRecord<String,String>(OrderService.RESULTS_TOPIC,0,0,id.toString(),json);
        var listener=order.getBean(InventoryResultListener.class);
        assertThatThrownBy(()->listener.receive(record)).isInstanceOf(IllegalArgumentException.class);
        record.headers().add("pueblo-inventory-result-signature",new HmacMessageSignature(SECRET).sign(json));
        listener.receive(record); listener.receive(record);
        assertThat(state(id)).isEqualTo("PENDING_PAYMENT"); assertThat(stock()).isEqualTo(9);
    }
    @Test @Order(7) void unpaidExpirationReleasesInventory() throws Exception {
        UUID id=create(quote(),UUID.randomUUID().toString()); settle(id,"PENDING_PAYMENT");
        orderDb.update("UPDATE customer_order SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",id.toString());
        order.getBean(OrderService.class).expireBatch(); settle(id,"EXPIRED"); assertThat(stock()).isEqualTo(10);
    }
    @Test @Order(8) void restartRecoversPersistedTimeoutAndOutbox() throws Exception {
        UUID id=create(quote(),UUID.randomUUID().toString());
        order.close();
        order=app("order",OrderApplication.class,catalogPort); orderPort=port(order); orderDb=order.getBean(JdbcTemplate.class);
        orderDb.update("UPDATE customer_order SET prepare_deadline=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",id.toString());
        order.getBean(OrderService.class).expireBatch(); settle(id,"REJECTED"); assertThat(stock()).isEqualTo(10);
    }
    @Test @Order(9) void releaseArrivingBeforeReserveLeavesNoGhostReservation() throws Exception {
        UUID id=create(quote(),UUID.randomUUID().toString());
        data(request("POST","/api/v1/me/orders/"+id+"/cancellations",null,"fixture-user",null),202);
        var event=orderDb.queryForObject("SELECT * FROM outbox_event WHERE aggregate_id=? AND aggregate_version=2",(r,n)->{
            try {
                return new EventEnvelope(UUID.fromString(r.getString("event_id")),"InventoryCommand",1,"order-service",id.toString(),2,
                        r.getTimestamp("occurred_at").toInstant(),id.toString(),null,null,mapper.readTree(r.getString("payload")));
            } catch(Exception ex) { throw new IllegalStateException(ex); }
        },id.toString());
        order.getBean(EventPublisher.class).publish(new OutboxRecord(OrderService.COMMANDS_TOPIC,id.toString(),event));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(()->assertThat(catalogDb.queryForObject(
                "SELECT COUNT(*) FROM inventory_reservation WHERE order_id=? AND status='RELEASED'",Integer.class,id.toString())).isEqualTo(1));
        settle(id,"CANCELED");
        await().atMost(Duration.ofSeconds(15)).untilAsserted(()->{
            dispatch(order); dispatch(catalog);
            assertThat(catalogDb.queryForObject("SELECT COUNT(*) FROM inventory_command WHERE JSON_UNQUOTE(JSON_EXTRACT(result,'$.orderId'))=?",Integer.class,id.toString())).isEqualTo(2);
        });
        assertThat(state(id)).isEqualTo("CANCELED"); assertThat(stock()).isEqualTo(10);
    }
    @Test @Order(10) void kafkaOutagePreservesOrderAndRetriesAfterRecovery() throws Exception {
        UUID id=create(quote(),UUID.randomUUID().toString());
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            dispatch(order);
            assertThat(state(id)).isEqualTo("PREPARING");
            assertThat(orderDb.queryForObject("SELECT status FROM outbox_event WHERE aggregate_id=?",String.class,id.toString())).isEqualTo("PENDING");
        } finally { KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec(); }
        settle(id,"PENDING_PAYMENT"); assertThat(stock()).isEqualTo(9);
    }
    @Test @Order(11) void failedOutboxAppendRollsBackOrderAndIdempotencyReceipt() throws Exception {
        UUID q=quote();
        JdbcOutbox failing=org.mockito.Mockito.mock(JdbcOutbox.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("injected outbox failure")).when(failing).append(org.mockito.ArgumentMatchers.any());
        var service=new OrderService(orderDb,mapper,failing,order.getBean(ServiceClients.class),
                order.getBean(org.springframework.transaction.PlatformTransactionManager.class),"purchase-v1");
        var payload=mapper.convertValue(body(q),OrderModels.CreateRequest.class);
        String key=UUID.randomUUID().toString();
        assertThatThrownBy(()->service.create(USER,key,payload)).isInstanceOf(IllegalStateException.class);
        assertThat(orderDb.queryForObject("SELECT COUNT(*) FROM customer_order WHERE quote_id=?",Integer.class,q.toString())).isZero();
        assertThat(orderDb.queryForObject("SELECT COUNT(*) FROM order_idempotency WHERE request_key=?",Integer.class,key)).isZero();
    }
    @Test @Order(12) void validatesQuantityAndConsentsAndDoesNotTrustClientAmounts() throws Exception {
        var item=Map.of("productId",product,"skuId",sku,"quantity",5);
        var response=request("POST","/api/v1/checkout/quotes",Map.of("items",List.of(item,item)),"fixture-user",null);
        assertThat(response.statusCode()).isEqualTo(409); assertThat(response.body()).contains("QUANTITY_LIMIT_EXCEEDED");
        var invalid=new HashMap<>(body(quote())); invalid.put("agreed",false);
        assertThat(request("POST","/api/v1/orders",invalid,"fixture-user",UUID.randomUUID().toString()).statusCode()).isEqualTo(400);
        var dishonest=new HashMap<>(body(quote())); dishonest.put("totalAmount",1);
        var accepted=data(request("POST","/api/v1/orders",dishonest,"fixture-user",UUID.randomUUID().toString()),202);
        assertThat(accepted.path("totalAmount").asLong()).isEqualTo(catalogDb.queryForObject(
                "SELECT COALESCE(s.price_override,p.sale_price) FROM catalog_sku s JOIN catalog_product p ON p.id=s.product_id WHERE s.id=?",Long.class,sku.toString()));
        settle(UUID.fromString(accepted.path("orderId").asText()),"PENDING_PAYMENT");
    }

    HttpResponse<String> payment(String method,UUID id,Object body,String user,String secret) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+orderPort+"/internal/v1/orders/"+id+"/payment-attempts"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(user!=null) builder.header("Authorization","Bearer "+user);
        if(secret!=null) builder.header("X-Payment-Service-Token",secret);
        return HTTP.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    UUID readyOrder() throws Exception { UUID id=create(quote(),UUID.randomUUID().toString()); settle(id,"PENDING_PAYMENT"); return id; }
    Map<String,Object> paymentBody(UUID id,UUID paymentId) {
        return Map.of("paymentId",paymentId,"amount",orderDb.queryForObject("SELECT total_amount FROM customer_order WHERE id=?",Long.class,id.toString()),"currency","KRW");
    }
    void expirePayment(UUID id) {
        orderDb.update("UPDATE customer_order SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",id.toString());
        order.getBean(OrderService.class).expireBatch();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(()->{
            dispatch(order); dispatch(catalog);
            assertThat(state(id)).isEqualTo("RECONCILIATION_REQUIRED");
            assertThat(catalogDb.queryForObject("SELECT status FROM inventory_reservation WHERE order_id=?",String.class,id.toString())).isEqualTo("RELEASED");
        });
    }
    @Test @Order(13) void paymentRequiresMachineCredentialAndOwnerButRecoveryNeedsNoUserToken() throws Exception {
        UUID id=readyOrder(); var body=paymentBody(id,UUID.randomUUID());
        assertThat(payment("POST",id,body,"fixture-user",null).statusCode()).isEqualTo(401);
        assertThat(payment("POST",id,body,"fixture-user","incorrect").statusCode()).isEqualTo(401);
        assertThat(payment("POST",id,body,null,PAYMENT_TOKEN).statusCode()).isEqualTo(401);
        assertThat(payment("POST",id,body,"fixture-other",PAYMENT_TOKEN).statusCode()).isEqualTo(404);
        var first=data(payment("POST",id,body,"fixture-user",PAYMENT_TOKEN),200);
        assertThat(first.path("orderStatus").asText()).isEqualTo("PAYMENT_PROCESSING");
        assertThat(first.path("nextAction").asText()).isEqualTo("CONTINUE_SAME_ATTEMPT");
        assertThat(data(payment("GET",id,null,null,PAYMENT_TOKEN),200)).isEqualTo(first);
        assertThat(payment("GET",id,null,"fixture-user",null).statusCode()).isEqualTo(401);
        var browser=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+orderPort+"/internal/v1/orders/"+id+"/payment-attempts"))
                .header("X-Payment-Service-Token",PAYMENT_TOKEN).header("Origin","https://example.com").GET().build();
        assertThat(HTTP.send(browser,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        expirePayment(id); assertThat(stock()).isEqualTo(10);
    }
    @Test @Order(14) void paymentValidatesAmountStateDeadlineAndRequiredFields() throws Exception {
        UUID id=create(quote(),UUID.randomUUID().toString()); var body=paymentBody(id,UUID.randomUUID());
        assertThat(payment("POST",id,body,"fixture-user",PAYMENT_TOKEN).body()).contains("PAYMENT_NOT_READY");
        settle(id,"PENDING_PAYMENT");
        var wrong=new HashMap<>(body); wrong.put("amount",1);
        assertThat(payment("POST",id,wrong,"fixture-user",PAYMENT_TOKEN).body()).contains("PAYMENT_AMOUNT_MISMATCH");
        wrong=new HashMap<>(body); wrong.put("currency","USD");
        assertThat(payment("POST",id,wrong,"fixture-user",PAYMENT_TOKEN).statusCode()).isEqualTo(409);
        wrong=new HashMap<>(body); wrong.remove("amount");
        assertThat(payment("POST",id,wrong,"fixture-user",PAYMENT_TOKEN).statusCode()).isEqualTo(400);
        orderDb.update("UPDATE customer_order SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",id.toString());
        assertThat(payment("POST",id,body,"fixture-user",PAYMENT_TOKEN).body()).contains("PAYMENT_WINDOW_EXPIRED");
        assertThat(orderDb.queryForObject("SELECT COUNT(*) FROM order_payment_attempt WHERE order_id=?",Integer.class,id.toString())).isZero();
        order.getBean(OrderService.class).expireBatch(); settle(id,"EXPIRED");
    }
    @Test @Order(15) void paymentReplayPreservesIdentityDeadlineAndGlobalUniqueness() throws Exception {
        UUID id=readyOrder(),paymentId=UUID.randomUUID(); var body=paymentBody(id,paymentId);
        var first=data(payment("POST",id,body,"fixture-user",PAYMENT_TOKEN),200);
        assertThat(data(payment("POST",id,body,"fixture-user",PAYMENT_TOKEN),200)).isEqualTo(first);
        assertThat(payment("POST",id,paymentBody(id,UUID.randomUUID()),"fixture-user",PAYMENT_TOKEN).body()).contains("PAYMENT_ALREADY_STARTED");
        assertThat(request("POST","/api/v1/me/orders/"+id+"/cancellations",null,"fixture-user",null).statusCode()).isEqualTo(409);
        UUID second=readyOrder();
        assertThat(payment("POST",second,paymentBody(second,paymentId),"fixture-user",PAYMENT_TOKEN).statusCode()).isEqualTo(409);
        assertThat(state(second)).isEqualTo("PENDING_PAYMENT");
        assertThat(orderDb.queryForObject("SELECT COUNT(*) FROM order_payment_attempt WHERE payment_id=?",Integer.class,paymentId.toString())).isEqualTo(1);
        data(request("POST","/api/v1/me/orders/"+second+"/cancellations",null,"fixture-user",null),202); settle(second,"CANCELED");
        expirePayment(id); assertThat(stock()).isEqualTo(10);
    }
    @Test @Order(16) void cancellationAndPaymentClaimHaveOnlyOneWinner() throws Exception {
        UUID id=readyOrder(); var body=paymentBody(id,UUID.randomUUID());
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var claim=pool.submit(()->{start.await();return payment("POST",id,body,"fixture-user",PAYMENT_TOKEN);});
            var cancel=pool.submit(()->{start.await();return request("POST","/api/v1/me/orders/"+id+"/cancellations",null,"fixture-user",null);});
            start.countDown(); int a=claim.get(20,TimeUnit.SECONDS).statusCode(),b=cancel.get(20,TimeUnit.SECONDS).statusCode();
            if(a==200) { assertThat(b).isEqualTo(409); assertThat(state(id)).isEqualTo("PAYMENT_PROCESSING"); expirePayment(id); }
            else { assertThat(a).isEqualTo(409); assertThat(b).isEqualTo(202); settle(id,"CANCELED"); }
        }
        assertThat(stock()).isEqualTo(10);
    }
    @Test @Order(17) void concurrentClaimsSerializeAndReplayWithoutCreatingAnotherAttempt() throws Exception {
        for(boolean same:List.of(true,false)) {
            UUID id=readyOrder(),paymentId=UUID.randomUUID(); var start=new java.util.concurrent.CountDownLatch(1);
            try(var pool=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var a=pool.submit(()->{start.await();return payment("POST",id,paymentBody(id,paymentId),"fixture-user",PAYMENT_TOKEN);});
                var b=pool.submit(()->{start.await();return payment("POST",id,paymentBody(id,same?paymentId:UUID.randomUUID()),"fixture-user",PAYMENT_TOKEN);});
                start.countDown(); var statuses=List.of(a.get(20,TimeUnit.SECONDS).statusCode(),b.get(20,TimeUnit.SECONDS).statusCode());
                assertThat(statuses).containsExactlyInAnyOrder(200,same?200:409);
            }
            assertThat(orderDb.queryForObject("SELECT COUNT(*) FROM order_payment_attempt WHERE order_id=?",Integer.class,id.toString())).isEqualTo(1);
            expirePayment(id);
        }
        assertThat(stock()).isEqualTo(10);
    }
    @Test @Order(18) void expiryPreservesUnknownPaymentAndReplayCannotRenewAuthorization() throws Exception {
        UUID id=readyOrder(); var body=paymentBody(id,UUID.randomUUID());
        var first=data(payment("POST",id,body,"fixture-user",PAYMENT_TOKEN),200);
        orderDb.update("UPDATE customer_order SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",id.toString());
        assertThat(data(payment("GET",id,null,null,PAYMENT_TOKEN),200).path("nextAction").asText()).isEqualTo("RECONCILE_ONLY");
        expirePayment(id); order.getBean(OrderService.class).expireBatch();
        var replay=data(payment("POST",id,body,"fixture-user",PAYMENT_TOKEN),200);
        assertThat(replay.path("nextAction").asText()).isEqualTo("RECONCILE_ONLY");
        assertThat(replay.path("expiresAt")).isEqualTo(first.path("expiresAt"));
        assertThat(replay.path("orderStatus").asText()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(orderDb.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,id.toString())).isEqualTo(2);
        assertThat(request("POST","/api/v1/me/orders/"+id+"/cancellations",null,"fixture-user",null).statusCode()).isEqualTo(409);
        assertThat(stock()).isEqualTo(10);
    }
    @Test @Order(19) void catalogExpiryDuringPaymentDoesNotDeclarePaymentFailure() throws Exception {
        UUID id=readyOrder(); data(payment("POST",id,paymentBody(id,UUID.randomUUID()),"fixture-user",PAYMENT_TOKEN),200);
        catalogDb.update("UPDATE inventory_reservation SET expires_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE order_id=?",id.toString());
        catalog.getBean(com.pueblo.catalog.inventory.InventoryService.class).expireBatch();
        settle(id,"RECONCILIATION_REQUIRED");
        assertThat(stock()).isEqualTo(10);
        assertThat(data(payment("GET",id,null,null,PAYMENT_TOKEN),200).path("nextAction").asText()).isEqualTo("RECONCILE_ONLY");
    }
    @Test @Order(20) void paymentAttemptSurvivesOrderServiceRestart() throws Exception {
        UUID id=readyOrder(); var body=paymentBody(id,UUID.randomUUID());
        var first=data(payment("POST",id,body,"fixture-user",PAYMENT_TOKEN),200);
        order.close(); order=app("order",OrderApplication.class,catalogPort); orderPort=port(order); orderDb=order.getBean(JdbcTemplate.class);
        assertThat(data(payment("GET",id,null,null,PAYMENT_TOKEN),200)).isEqualTo(first);
        assertThat(data(payment("POST",id,body,"fixture-user",PAYMENT_TOKEN),200)).isEqualTo(first);
        expirePayment(id); assertThat(stock()).isEqualTo(10);
    }
}
