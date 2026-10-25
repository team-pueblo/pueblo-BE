package com.pueblo.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;

public final class KafkaEventPublisher implements EventPublisher {
    private final KafkaTemplate<Object, Object> kafka;
    private final ObjectMapper mapper;
    private final long timeoutMs;

    public KafkaEventPublisher(KafkaTemplate<Object, Object> kafka, ObjectMapper mapper, long timeoutMs) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.timeoutMs = timeoutMs;
    }

    @Override
    public void publish(OutboxRecord record) throws Exception {
        kafka.send(record.topic(), record.messageKey(), mapper.writeValueAsString(record.event()))
                .get(timeoutMs, TimeUnit.MILLISECONDS);
    }
}
