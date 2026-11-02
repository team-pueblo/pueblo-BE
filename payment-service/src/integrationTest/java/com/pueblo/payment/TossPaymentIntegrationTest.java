package com.pueblo.payment;

import com.fasterxml.jackson.databind.*;
import com.pueblo.payment.checkout.PaymentService;
import com.pueblo.messaging.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TossPaymentIntegrationTest {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4.7").withDatabaseName("payment_test")
            .withUrlParam("connectionTimeZone","UTC").withUrlParam("forceConnectionTimeZoneToSession","true");
    static final String MACHINE="payment-order-integration-credential-only",SECRET="test_sk_fixture_not_a_real_key",EVENT="payment-event-integration-credential-only";
    static final UUID USER=UUID.randomUUID(),OTHER=UUID.randomUUID();
    static final ObjectMapper JSON=new ObjectMapper();
    static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    static final Map<String,JsonNode> prepared=new ConcurrentHashMap<>(),provider=new ConcurrentHashMap<>();
    static final AtomicInteger confirmations=new AtomicInteger(),queries=new AtomicInteger(),refunds=new AtomicInteger();
    static volatile String mode="success";
    static volatile boolean correctAuth,correctIdempotency;
    static HttpServer remote; static ExecutorService remoteThreads;
    static ConfigurableApplicationContext app; static JdbcTemplate db; static int port;
    @BeforeAll static void start() throws Exception {
        remote=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        remoteThreads=Executors.newVirtualThreadPerTaskExecutor(); remote.setExecutor(remoteThreads);
        remote.createContext("/",TossPaymentIntegrationTest::handle);remote.start();
        launch();
    }
    static void launch() {
        String url="http://127.0.0.1:"+remote.getAddress().getPort();
        app=new SpringApplicationBuilder(PaymentApplication.class).run("--server.port=0","--spring.datasource.url="+MYSQL.getJdbcUrl(),
                "--spring.datasource.username="+MYSQL.getUsername(),"--spring.datasource.password="+MYSQL.getPassword(),
                "--spring.kafka.bootstrap-servers=127.0.0.1:1","--pueblo.messaging.polling-enabled=false",
                "--pueblo.payments.enabled=true","--pueblo.payments.reconciliation-enabled=false","--pueblo.payments.member-url="+url,
                "--pueblo.payments.order-url="+url,"--pueblo.payments.order-token="+MACHINE,"--pueblo.payments.toss-url="+url,
                "--pueblo.payments.toss-secret="+SECRET,"--pueblo.payments.mock=true","--pueblo.payments.event-secret="+EVENT,
                "--pueblo.docs.enabled=true","--springdoc.api-docs.enabled=true","--springdoc.swagger-ui.enabled=false");
        port=((ServletWebServerApplicationContext)app).getWebServer().getPort();db=app.getBean(JdbcTemplate.class);
    }
    static void handle(HttpExchange e) throws java.io.IOException {
        String path=e.getRequestURI().getPath(),auth=e.getRequestHeaders().getFirst("Authorization");
        if(path.equals("/api/v1/me")) { reply(e,"Bearer user".equals(auth)?200:"Bearer other".equals(auth)?200:401,Map.of("data",Map.of("id","Bearer other".equals(auth)?OTHER:USER)));return; }
        if(path.startsWith("/api/v1/me/orders/")) {
            reply(e,"Bearer user".equals(auth)?200:404,Map.of("data",Map.of("totalAmount",10000,"currency","KRW")));return;
        }
        if(path.startsWith("/internal/")) {
            if(!MACHINE.equals(e.getRequestHeaders().getFirst("X-Payment-Service-Token"))) { reply(e,401,Map.of());return; }
            String id=path.split("/")[4];
            if(e.getRequestMethod().equals("POST")) {
                JsonNode input=JSON.readTree(e.getRequestBody());
                var data=JSON.createObjectNode().put("orderId",id).put("paymentId",input.path("paymentId").asText()).put("amount",10000).put("currency","KRW")
                        .put("nextAction",mode.equals("expired")?"RECONCILE_ONLY":"CONTINUE_SAME_ATTEMPT").put("expiresAt",java.time.Instant.now().plusSeconds(600).toString());
                prepared.putIfAbsent(id,data);
                if(mode.equals("order-lost")) { e.close();return; }
            }
            reply(e,prepared.containsKey(id)?200:404,Map.of("data",prepared.getOrDefault(id,JSON.createObjectNode())));return;
        }
        if(path.equals("/v1/payments/confirm")) {
            confirmations.incrementAndGet(); JsonNode input=JSON.readTree(e.getRequestBody());String id=input.path("orderId").asText();
            correctAuth=("Basic "+Base64.getEncoder().encodeToString((SECRET+":").getBytes(StandardCharsets.UTF_8))).equals(auth);
            correctIdempotency=prepared.get(id).path("paymentId").asText().equals(e.getRequestHeaders().getFirst("Idempotency-Key"));
            var result=JSON.createObjectNode().put("orderId",id).put("paymentKey",input.path("paymentKey").asText()).put("currency","KRW").put("totalAmount",10000).put("balanceAmount",10000).put("status","DONE");
            if(mode.equals("mismatch")) result.put("totalAmount",1);
            if(mode.equals("declined")) result.put("status","ABORTED");
            if(mode.equals("pending")) result.put("status","IN_PROGRESS");
            provider.put(id,result);
            if(mode.equals("lost")) { e.close();return; }
            if(mode.equals("error")) { reply(e,500,Map.of("message","private upstream error"));return; }
            reply(e,200,result);return;
        }
        if(path.startsWith("/v1/payments/orders/")) {reply(e,404,Map.of("code","NOT_FOUND_MERCHANT"));return;}
        if(path.startsWith("/v1/payments/")) {
            String key=path.substring("/v1/payments/".length()).replace("/cancel","");
            var found=provider.values().stream().filter(p->key.equals(p.path("paymentKey").asText())).findFirst();
            if(path.endsWith("/cancel")) {
                refunds.incrementAndGet();
                if(found.isPresent()) {
                    var p=(com.fasterxml.jackson.databind.node.ObjectNode)found.get();p.put("status","CANCELED").put("balanceAmount",0);
                    p.putArray("cancels").addObject().put("cancelStatus","DONE").put("cancelAmount",10000);
                    if(mode.equals("refund-lost")) {e.close();return;}
                }
            } else queries.incrementAndGet();
            reply(e,found.isPresent()?200:404,found.orElse(JSON.createObjectNode()));return;
        }
        reply(e,404,Map.of());
    }
    static void reply(HttpExchange e,int status,Object body) throws java.io.IOException {
        byte[] bytes=JSON.writeValueAsBytes(body);e.getResponseHeaders().add("Content-Type","application/json");e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
    }
    @AfterAll static void stop() { if(app!=null)app.close();if(remote!=null)remote.stop(0);if(remoteThreads!=null)remoteThreads.close(); }
    @BeforeEach void reset() { mode="success";confirmations.set(0);queries.set(0);refunds.set(0); }
    HttpResponse<String> call(String method,String path,Object body,String user) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(25)).header("Content-Type","application/json");
        if(user!=null)b.header("Authorization","Bearer "+user);
        return HTTP.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
    }
    Map<String,Object> body(UUID id) { return Map.of("orderId",id,"paymentKey","fixture_"+id,"amount",10000); }
    HttpResponse<String> confirm(UUID id) throws Exception { return call("POST","/api/v1/payments/confirm",body(id),"user"); }
    JsonNode data(HttpResponse<String> r,int status) throws Exception { assertThat(r.statusCode()).as(r.body()).isEqualTo(status);return JSON.readTree(r.body()).path("data"); }
    String state(UUID id) { return db.queryForObject("SELECT status FROM payment_attempt WHERE order_id=?",String.class,id.toString()); }
    void recover(UUID id) {
        db.update("UPDATE payment_attempt SET next_check_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND,lease_until=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE order_id=?",id.toString());
        app.getBean(PaymentService.class).reconcileBatch();
    }
    @Test @Order(1) void confirmsMatchingAmountAndUsesSecretAndPersistentIdempotency() throws Exception {
        UUID id=UUID.randomUUID();var result=data(confirm(id),200);
        assertThat(result.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(correctAuth).isTrue();assertThat(correctIdempotency).isTrue();
        assertThat(data(confirm(id),200)).isEqualTo(result);assertThat(confirmations.get()).isEqualTo(1);
        String payload=db.queryForObject("SELECT payload FROM outbox_event WHERE aggregate_id=?",String.class,id.toString());
        assertThat(payload).contains("SUCCEEDED").doesNotContain("paymentKey",SECRET);
        assertThat(data(call("GET","/api/v1/me/payments/orders/"+id,null,"user"),200)).isEqualTo(result);
    }
    @Test @Order(2) void rejectsUnauthenticatedOtherUserAndForgedAmountsBeforePg() throws Exception {
        UUID id=UUID.randomUUID();
        assertThat(call("POST","/api/v1/payments/confirm",body(id),null).statusCode()).isEqualTo(401);
        assertThat(call("POST","/api/v1/payments/confirm",body(id),"other").statusCode()).isEqualTo(404);
        var bad=new HashMap<>(body(id));bad.put("amount",1);
        assertThat(call("POST","/api/v1/payments/confirm",bad,"user").statusCode()).isEqualTo(409);
        bad.remove("amount");assertThat(call("POST","/api/v1/payments/confirm",bad,"user").statusCode()).isEqualTo(400);
        assertThat(confirmations.get()).isZero();
        data(confirm(id),200);
        assertThat(call("GET","/api/v1/me/payments/orders/"+id,null,"other").statusCode()).isEqualTo(404);
        bad=new HashMap<>(body(id));bad.put("paymentKey","different");
        assertThat(call("POST","/api/v1/payments/confirm",bad,"user").statusCode()).isEqualTo(409);
        assertThat(confirmations.get()).isEqualTo(1);
    }
    @Test @Order(3) void concurrentRetriesSendOnlyOneApproval() throws Exception {
        UUID id=UUID.randomUUID();var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->{gate.await();return confirm(id);});var b=pool.submit(()->{gate.await();return confirm(id);});gate.countDown();
            assertThat(a.get(30,TimeUnit.SECONDS).statusCode()).isIn(200,202);assertThat(b.get(30,TimeUnit.SECONDS).statusCode()).isIn(200,202);
        }
        assertThat(state(id)).isEqualTo("SUCCEEDED");assertThat(confirmations.get()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,id.toString())).isEqualTo(1);
    }
    @Test @Order(4) void lostApprovalResponseIsQueriedWithoutSecondCharge() throws Exception {
        mode="lost";UUID id=UUID.randomUUID();data(confirm(id),202);assertThat(state(id)).isEqualTo("UNKNOWN");
        recover(id);assertThat(state(id)).isEqualTo("SUCCEEDED");assertThat(confirmations.get()).isEqualTo(1);assertThat(queries.get()).isPositive();
        recover(id);assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=?",Integer.class,id.toString())).isEqualTo(2);
    }
    @Test @Order(5) void providerErrorsAndMismatchesAreNotApprovedOrExposed() throws Exception {
        mode="error";UUID id=UUID.randomUUID();var r=confirm(id);data(r,202);assertThat(r.body()).doesNotContain("private upstream error",SECRET);
        recover(id);assertThat(state(id)).isEqualTo("SUCCEEDED");
        mode="mismatch";UUID bad=UUID.randomUUID();assertThat(data(confirm(bad),200).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
    }
    @Test @Order(6) void expiredOrderNeverTriggersNewApproval() throws Exception {
        mode="expired";UUID id=UUID.randomUUID();data(confirm(id),202);assertThat(confirmations.get()).isZero();
        db.update("UPDATE payment_attempt SET checks=19 WHERE order_id=?",id.toString());recover(id);
        assertThat(state(id)).isEqualTo("REVIEW_REQUIRED");assertThat(confirmations.get()).isZero();
    }
    @Test @Order(7) void restartRecoversPersistedUnknownAttempt() throws Exception {
        mode="lost";UUID id=UUID.randomUUID();data(confirm(id),202);app.close();launch();recover(id);
        assertThat(state(id)).isEqualTo("SUCCEEDED");assertThat(confirmations.get()).isEqualTo(1);
    }
    @Test @Order(8) void lostOrderAcceptanceNeverBlindlyConfirms() throws Exception {
        mode="order-lost";UUID id=UUID.randomUUID();assertThat(confirm(id).statusCode()).isEqualTo(503);assertThat(confirmations.get()).isZero();
        recover(id);assertThat(state(id)).isEqualTo("UNKNOWN");assertThat(confirmations.get()).isZero();
        db.update("UPDATE payment_attempt SET checks=19 WHERE order_id=?",id.toString());recover(id);assertThat(state(id)).isEqualTo("REVIEW_REQUIRED");
    }
    @Test @Order(9) void terminalFailureRequiresMatchingProviderObject() throws Exception {
        mode="declined";UUID id=UUID.randomUUID();assertThat(data(confirm(id),200).path("status").asText()).isEqualTo("FAILED");
        recover(id);assertThat(confirmations.get()).isEqualTo(1);
    }
    @Test @Order(10) void resultAndOutboxRollbackTogether() throws Exception {
        UUID id=UUID.randomUUID();mode="lost";data(confirm(id),202);
        var outbox=org.mockito.Mockito.mock(JdbcOutbox.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("injected failure")).when(outbox).append(org.mockito.ArgumentMatchers.any());
        var service=new PaymentService(db,app.getBean(org.springframework.transaction.PlatformTransactionManager.class),app.getBean(com.pueblo.payment.gateway.ServiceClients.class),app.getBean(com.pueblo.payment.gateway.TossClient.class),outbox,app.getBean(ObjectMapper.class));
        db.update("UPDATE payment_attempt SET next_check_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE order_id=?",id.toString());
        assertThatThrownBy(service::reconcileBatch).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo("UNKNOWN");recover(id);assertThat(state(id)).isEqualTo("SUCCEEDED");
    }

    @Test @Order(11) void swaggerRequiresBearerAndTransportSignsResultWithoutCredentials() throws Exception {
        var spec=JSON.readTree(call("GET","/v3/api-docs",null,null).body());
        assertThat(spec.path("paths").path("/api/v1/payments/confirm").path("post").path("security").toString()).contains("bearerAuth");
        var event=new EventEnvelope(UUID.randomUUID(),"PaymentSucceeded",1,"payment-service",UUID.randomUUID().toString(),1,java.time.Instant.now(),UUID.randomUUID().toString(),null,null,JSON.createObjectNode().put("status","SUCCEEDED"));
        var record=new OutboxRecord(PaymentService.RESULTS_TOPIC,event.aggregateId(),event);
        String json=app.getBean(ObjectMapper.class).writeValueAsString(event);
        var kafka=new org.apache.kafka.clients.producer.ProducerRecord<Object,Object>(record.topic(),record.messageKey(),json);
        app.getBean(KafkaRecordCustomizer.class).customize(record,kafka);
        new HmacMessageSignature(EVENT).verify(json,kafka.headers(),"pueblo-payment-result-signature");
        assertThat(kafka.headers().lastHeader("Authorization")).isNull();
    }
    @Test @Order(12) void ownerRecheckResolvesReviewAndAuditsWithoutApproval() throws Exception {
        UUID id=UUID.randomUUID();mode="lost";data(confirm(id),202);
        db.update("UPDATE payment_attempt SET status='REVIEW_REQUIRED',checks=20,next_check_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE order_id=?",id.toString());
        String path="/api/v1/me/payments/orders/"+id+"/rechecks";
        assertThat(call("POST",path,null,null).statusCode()).isEqualTo(401);
        assertThat(call("POST",path,null,"other").statusCode()).isEqualTo(404);
        assertThat(data(call("POST",path,null,"user"),200).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(confirmations.get()).isEqualTo(1);assertThat(queries.get()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM payment_recheck_audit WHERE resulting_status='SUCCEEDED'",Integer.class)).isEqualTo(1);
        call("POST",path,null,"user");assertThat(queries.get()).isEqualTo(1);
    }
    @Test @Order(13) void reviewStaysUncertainAndRechecksAreThrottled() throws Exception {
        UUID id=UUID.randomUUID();mode="lost";data(confirm(id),202);
        ((com.fasterxml.jackson.databind.node.ObjectNode)provider.get(id.toString())).put("status","IN_PROGRESS");
        db.update("UPDATE payment_attempt SET status='REVIEW_REQUIRED',checks=20,next_check_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE order_id=?",id.toString());
        String path="/api/v1/me/payments/orders/"+id+"/rechecks";
        assertThat(data(call("POST",path,null,"user"),200).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
        call("POST",path,null,"user");assertThat(queries.get()).isEqualTo(1);assertThat(confirmations.get()).isEqualTo(1);
        provider.remove(id.toString());
        db.update("UPDATE payment_attempt SET next_check_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE order_id=?",id.toString());
        assertThat(data(call("POST",path,null,"user"),200).path("status").asText()).isEqualTo("REVIEW_REQUIRED");
    }
    @Test @Order(14) void fullRefundResponseLossRecoversWithoutSecondCancel() throws Exception {
        UUID id=UUID.randomUUID();var payment=data(confirm(id),200);
        var service=app.getBean(com.pueblo.payment.checkout.RefundService.class);
        UUID refund=UUID.randomUUID();var request=new com.pueblo.payment.checkout.RefundService.Request(refund,UUID.fromString(payment.path("paymentId").asText()),id,10000,"KRW");
        service.accept(request);service.accept(request);mode="refund-lost";service.recoverBatch();
        assertThat(db.queryForObject("SELECT status FROM refund_attempt WHERE id=?",String.class,refund.toString())).isEqualTo("UNKNOWN");
        app.close();launch();
        db.update("UPDATE refund_attempt SET next_check_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",refund.toString());
        app.getBean(com.pueblo.payment.checkout.RefundService.class).recoverBatch();
        assertThat(db.queryForObject("SELECT status FROM refund_attempt WHERE id=?",String.class,refund.toString())).isEqualTo("SUCCEEDED");
        assertThat(refunds.get()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='RefundSucceeded'",Integer.class,id.toString())).isEqualTo(1);
    }

    @Test @Order(15) void refundCommandAuthenticationAndConcurrentWorkersAreIdempotent() throws Exception {
        UUID id=UUID.randomUUID();var payment=data(confirm(id),200);UUID refund=UUID.randomUUID();
        var request=new com.pueblo.payment.checkout.RefundService.Request(refund,UUID.fromString(payment.path("paymentId").asText()),id,10000,"KRW");
        var service=app.getBean(com.pueblo.payment.checkout.RefundService.class);
        var eventMapper=app.getBean(ObjectMapper.class);
        var listener=new com.pueblo.payment.checkout.RefundListener(eventMapper,service,EVENT);
        var event=new EventEnvelope(refund,"RefundRequested",1,"order-service",id.toString(),1,java.time.Instant.now(),id.toString(),request.paymentId().toString(),null,JSON.valueToTree(request));
        String text=eventMapper.writeValueAsString(event);
        var record=new org.apache.kafka.clients.consumer.ConsumerRecord<String,String>("pueblo.payment.refunds.v1",0,0,id.toString(),text);
        assertThatThrownBy(()->listener.receive(record)).isInstanceOf(IllegalArgumentException.class);
        record.headers().add("pueblo-payment-refund-signature",new HmacMessageSignature(EVENT).sign(text));
        listener.receive(record);listener.receive(record);
        assertThatThrownBy(()->service.accept(new com.pueblo.payment.checkout.RefundService.Request(UUID.randomUUID(),request.paymentId(),id,10000,"KRW"))).isInstanceOf(IllegalArgumentException.class);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var start=new CountDownLatch(1);
            var a=pool.submit(()->{start.await();return service.recoverBatch();});
            var b=pool.submit(()->{start.await();return service.recoverBatch();});start.countDown();a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);
        }
        assertThat(refunds.get()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT status FROM refund_attempt WHERE id=?",String.class,refund.toString())).isEqualTo("SUCCEEDED");
    }
    @Test @Order(16) void refundMismatchAndLookup404NeverBecomeSuccess() throws Exception {
        UUID id=UUID.randomUUID();var payment=data(confirm(id),200);UUID refund=UUID.randomUUID();
        var service=app.getBean(com.pueblo.payment.checkout.RefundService.class);
        service.accept(new com.pueblo.payment.checkout.RefundService.Request(refund,UUID.fromString(payment.path("paymentId").asText()),id,10000,"KRW"));
        provider.remove(id.toString());service.recoverBatch();
        assertThat(db.queryForObject("SELECT status FROM refund_attempt WHERE id=?",String.class,refund.toString())).isEqualTo("UNKNOWN");
        db.update("UPDATE refund_attempt SET checks=19,next_check_at=UTC_TIMESTAMP()-INTERVAL 1 SECOND WHERE id=?",refund.toString());service.recoverBatch();
        assertThat(db.queryForObject("SELECT status FROM refund_attempt WHERE id=?",String.class,refund.toString())).isEqualTo("REVIEW_REQUIRED");
        assertThat(refunds.get()).isEqualTo(1);
        UUID other=UUID.randomUUID();var next=data(confirm(other),200);UUID another=UUID.randomUUID();
        service.accept(new com.pueblo.payment.checkout.RefundService.Request(another,UUID.fromString(next.path("paymentId").asText()),other,10000,"KRW"));
        ((com.fasterxml.jackson.databind.node.ObjectNode)provider.get(other.toString())).put("totalAmount",1);
        service.recoverBatch();assertThat(db.queryForObject("SELECT status FROM refund_attempt WHERE id=?",String.class,another.toString())).isEqualTo("REVIEW_REQUIRED");
    }

}
