package com.pueblo.catalog.inventory;

import com.pueblo.messaging.*;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="pueblo.inventory.enabled", havingValue="true")
public final class InventoryResultSigner implements KafkaRecordCustomizer {
    public static final String HEADER = "pueblo-inventory-result-signature";
    private final HmacMessageSignature signature;
    public InventoryResultSigner(@Value("${pueblo.inventory.command-secret:}") String secret) {
        signature = new HmacMessageSignature(secret);
    }
    @Override public void customize(OutboxRecord source, ProducerRecord<Object, Object> message) {
        if (source.topic().equals(InventoryService.EVENTS_TOPIC)) message.headers().add(HEADER, signature.sign((String) message.value()));
    }
}
