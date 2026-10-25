package com.pueblo.payment.checkout;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pueblo.messaging.*;
import com.pueblo.payment.gateway.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
@Configuration
@EnableScheduling
@ConditionalOnProperty(name="pueblo.payments.enabled",havingValue="true")
public class PaymentConfiguration {
    @Bean JsonHttp paymentHttp(ObjectMapper mapper) { return new JsonHttp(mapper); }
    @Bean ServiceClients paymentServiceClients(JsonHttp http,@Value("${pueblo.payments.member-url}") String member,@Value("${pueblo.payments.order-url}") String order,@Value("${pueblo.payments.order-token}") String token) { return new ServiceClients(http,member,order,token); }
    @Bean TossClient tossClient(JsonHttp http,@Value("${pueblo.payments.toss-url:https://api.tosspayments.com}") String url,@Value("${pueblo.payments.toss-secret}") String key,@Value("${pueblo.payments.mock:false}") boolean mock) { return new TossClient(http,url,key,mock); }
    @Bean PaymentService paymentService(JdbcTemplate jdbc,PlatformTransactionManager manager,ServiceClients clients,TossClient toss,JdbcOutbox outbox,ObjectMapper mapper) { return new PaymentService(jdbc,manager,clients,toss,outbox,mapper); }
    @Bean KafkaRecordCustomizer paymentResultSigner(@Value("${pueblo.payments.event-secret}") String secret) {
        var signature=new HmacMessageSignature(secret);
        return (source,message)->{ if(source.topic().equals(PaymentService.RESULTS_TOPIC)) message.headers().add("pueblo-payment-result-signature",signature.sign((String)message.value())); };
    }
    @Bean @ConditionalOnProperty(name="pueblo.payments.reconciliation-enabled",havingValue="true",matchIfMissing=true)
    Reconciler paymentReconciler(PaymentService service) { return new Reconciler(service); }
    static final class Reconciler {
        private final PaymentService service;
        Reconciler(PaymentService service) { this.service=service; }
        @Scheduled(fixedDelayString="${pueblo.payments.reconciliation-ms:5000}") public void tick() { service.reconcileBatch(); }
    }
}
