package com.pueblo.messaging;

import org.apache.kafka.clients.producer.ProducerRecord;

/** Transport-only hook; service-specific signing rules remain in the owning service. */
@FunctionalInterface
public interface KafkaRecordCustomizer {
    void customize(OutboxRecord source, ProducerRecord<Object, Object> message);
}
