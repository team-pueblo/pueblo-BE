package com.pueblo.payment.checkout;

import com.fasterxml.jackson.databind.*;
import com.pueblo.messaging.*;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="pueblo.payments.refunds-enabled",havingValue="true")
public final class RefundListener {
    private final ObjectMapper mapper;
    private final HmacMessageSignature signature;
    private final RefundService service;
    public RefundListener(ObjectMapper mapper,RefundService service,@Value("${pueblo.payments.event-secret}") String secret) {
        this.mapper=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.service=service;signature=new HmacMessageSignature(secret);
    }
    @KafkaListener(id="payment-refunds",topics="pueblo.payment.refunds.v1",groupId="payment-refunds-v1")
    public void receive(ConsumerRecord<String,String> record) {
        signature.verify(record.value(),record.headers(),"pueblo-payment-refund-signature");
        RefundService.Request p;
        try {
            var e=mapper.readValue(record.value(),EventEnvelope.class);p=mapper.treeToValue(e.payload(),RefundService.Request.class);
            if(!"order-service".equals(e.producer())||!"RefundRequested".equals(e.eventType())||e.schemaVersion()!=1||e.aggregateVersion()!=1
                    ||p.refundId()==null||p.paymentId()==null||p.orderId()==null||p.amount()<=0||!"KRW".equals(p.currency())
                    ||!e.eventId().equals(p.refundId())||!p.paymentId().toString().equals(e.causationId())
                    ||!e.aggregateId().equals(record.key())||!e.aggregateId().equals(p.orderId().toString())) throw new IllegalArgumentException();
        } catch(Exception ex) {throw new IllegalArgumentException("Invalid authenticated refund command");}
        service.accept(p);
    }
}
