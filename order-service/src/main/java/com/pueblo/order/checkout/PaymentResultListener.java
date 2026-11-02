package com.pueblo.order.checkout;

import com.fasterxml.jackson.databind.*;
import com.pueblo.messaging.*;
import java.util.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="pueblo.orders.payment-results.enabled",havingValue="true")
public final class PaymentResultListener {
    private final ObjectMapper mapper;
    private final HmacMessageSignature signature;
    private final OrderService service;
    public PaymentResultListener(ObjectMapper mapper,OrderService service,@Value("${pueblo.orders.payment-results.secret:}") String secret) {
        this.mapper=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.service=service;signature=new HmacMessageSignature(secret);
    }
    @KafkaListener(id="order-payment-results",topics="pueblo.payment.results.v1",groupId="order-payment-v1")
    public void receive(ConsumerRecord<String,String> record) {
        signature.verify(record.value(),record.headers(),"pueblo-payment-result-signature");
        EventEnvelope e;OrderService.PaymentResult p;
        try {
            e=mapper.readValue(record.value(),EventEnvelope.class);p=mapper.treeToValue(e.payload(),OrderService.PaymentResult.class);
            String expected=p.refundId()!=null?(p.status().equals("SUCCEEDED")?"RefundSucceeded":"RefundUncertain"):
                    switch(p.status()) {case "SUCCEEDED"->"PaymentSucceeded";case "FAILED"->"PaymentFailed";default->"PaymentUncertain";};
            if(!"payment-service".equals(e.producer())||e.schemaVersion()!=1||p.paymentId()==null||p.orderId()==null||p.amount()<=0
                    ||!"KRW".equals(p.currency())||p.version()<1||p.version()!=e.aggregateVersion()
                    ||!e.aggregateId().equals(record.key())||!e.aggregateId().equals(p.orderId().toString())||!e.eventType().equals(expected)
                    ||!e.correlationId().equals(p.orderId().toString())
                    ||!(p.refundId()==null?p.paymentId():p.refundId()).toString().equals(e.causationId())
                    ||!Set.of("SUCCEEDED","FAILED","UNKNOWN","REVIEW_REQUIRED").contains(p.status())
                    ||(p.refundId()!=null&&!Set.of("SUCCEEDED","REVIEW_REQUIRED").contains(p.status()))) throw new IllegalArgumentException();
        } catch(Exception ex) {throw new IllegalArgumentException("Invalid authenticated payment result");}
        service.paymentResult(e,p);
    }
}
