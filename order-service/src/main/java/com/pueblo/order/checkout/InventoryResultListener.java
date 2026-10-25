package com.pueblo.order.checkout;

import com.fasterxml.jackson.databind.*;
import com.pueblo.messaging.EventEnvelope;
import com.pueblo.messaging.HmacMessageSignature;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="pueblo.orders.enabled",havingValue="true")
public final class InventoryResultListener {
    private final ObjectMapper mapper;
    private final OrderService service;
    private final HmacMessageSignature signature;
    public InventoryResultListener(ObjectMapper mapper,OrderService service,@Value("${pueblo.orders.inventory-secret:}") String secret) {
        this.mapper=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        this.service=service; signature=new HmacMessageSignature(secret);
    }
    @KafkaListener(id="order-inventory-results",topics=OrderService.RESULTS_TOPIC,groupId="order-inventory-v1")
    public void receive(ConsumerRecord<String,String> record) {
        signature.verify(record.value(),record.headers(),"pueblo-inventory-result-signature");
        EventEnvelope envelope;
        OrderModels.Result result;
        try {
            envelope=mapper.readValue(record.value(),EventEnvelope.class);
            result=mapper.treeToValue(envelope.payload(),OrderModels.Result.class);
            if(!envelope.producer().equals("catalog-service") || envelope.schemaVersion()!=1 || result.commandId()==null
                    || result.orderId()==null || result.reservationId()==null || result.generation()<1 || result.version()<1
                    || !envelope.aggregateId().equals(record.key()) || !record.key().equals(result.orderId().toString())
                    || !result.commandId().toString().equals(envelope.causationId()) || envelope.aggregateVersion()!=result.version()
                    || !envelope.eventType().equals(result.eventType()) || result.state()==null
                    || !java.util.Set.of("InventoryReserved","InventoryReleased","InventoryExpired","InventoryCommandRejected","InventoryCommitted","InventoryRestored").contains(result.eventType()))
                throw new IllegalArgumentException();
        } catch(Exception ex) { throw new IllegalArgumentException("Invalid authenticated inventory result"); }
        service.result(envelope,result);
    }
}
