package com.pueblo.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;

public final class KafkaEventPublisher implements EventPublisher {
    private final KafkaTemplate<Object, Object> kafka;
    private final ObjectMapper mapper;
    private final long timeoutMs;
    private final java.util.List<KafkaRecordCustomizer> customizers;

    public KafkaEventPublisher(KafkaTemplate<Object, Object> kafka, ObjectMapper mapper, long timeoutMs) {
        this(kafka, mapper, timeoutMs, java.util.List.of());
    }

    public KafkaEventPublisher(KafkaTemplate<Object, Object> kafka, ObjectMapper mapper, long timeoutMs,
            java.util.List<KafkaRecordCustomizer> customizers) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.timeoutMs = timeoutMs;
        this.customizers = java.util.List.copyOf(customizers);
    }

    @Override
    public void publish(OutboxRecord record) throws Exception {
        var message = new org.apache.kafka.clients.producer.ProducerRecord<Object, Object>(
                record.topic(), record.messageKey(), mapper.writeValueAsString(record.event()));
        customizers.forEach(customizer -> customizer.customize(record, message));
        kafka.send(message).get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
