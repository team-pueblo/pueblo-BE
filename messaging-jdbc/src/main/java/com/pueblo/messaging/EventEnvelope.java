package com.pueblo.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Transport contract. Payloads and business entities remain owned by each service. */
public record EventEnvelope(
        UUID eventId, String eventType, int schemaVersion, String producer,
        String aggregateId, long aggregateVersion, Instant occurredAt,
        String correlationId, String causationId, String requestId, JsonNode payload) {
    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        occurredAt = occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        Objects.requireNonNull(payload, "payload");
        requireText(eventType, "eventType");
        requireText(producer, "producer");
        requireText(aggregateId, "aggregateId");
        requireText(correlationId, "correlationId");
        if (schemaVersion < 1 || aggregateVersion < 0) {
            throw new IllegalArgumentException("Invalid event version");
        }
        // JsonNode is mutable; retain an independent snapshot of the business payload.
        payload = payload.deepCopy();
    }

    @Override
    public JsonNode payload() {
        return payload.deepCopy();
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(name + " must contain 1..128 characters");
        }
    }
}
