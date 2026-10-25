package com.pueblo.messaging;

import java.util.Objects;

public record OutboxRecord(String topic, String messageKey, EventEnvelope event) {
    public OutboxRecord {
        Objects.requireNonNull(event, "event");
        if (topic == null || !topic.matches("[a-zA-Z0-9._-]{1,249}")
                || topic.equals(".") || topic.equals("..")) {
            throw new IllegalArgumentException("Invalid Kafka topic");
        }
        if (messageKey == null || messageKey.isBlank() || messageKey.length() > 128) {
            throw new IllegalArgumentException("Invalid Kafka message key");
        }
    }
}
