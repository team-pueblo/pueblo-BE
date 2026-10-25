package com.pueblo.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses only the owning service's datasource and transaction manager. */
public final class JdbcOutbox {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String producer;
    private final MessagingProperties properties;
    private final TransactionTemplate mandatory;
    private final TransactionTemplate independent;

    public JdbcOutbox(JdbcTemplate jdbc, ObjectMapper mapper, String producer,
            PlatformTransactionManager transactionManager, MessagingProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.producer = producer;
        this.properties = properties;
        this.mandatory = new TransactionTemplate(transactionManager);
        mandatory.setPropagationBehavior(TransactionDefinition.PROPAGATION_MANDATORY);
        this.independent = new TransactionTemplate(transactionManager);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        independent.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Must be called inside the same transaction as the business mutation. */
    public void append(OutboxRecord record) {
        if (!producer.equals(record.event().producer())) {
            throw new IllegalArgumentException("Producer does not match the owning service");
        }
        mandatory.executeWithoutResult(status -> {
            EventEnvelope event = record.event();
            jdbc.update("""
                    INSERT INTO outbox_event
                    (event_id, aggregate_id, aggregate_version, event_type, schema_version,
                     topic, message_key, correlation_id, causation_id, request_id, payload, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, event.eventId().toString(), event.aggregateId(), event.aggregateVersion(),
                    event.eventType(), event.schemaVersion(), record.topic(), record.messageKey(),
                    event.correlationId(), event.causationId(), event.requestId(),
                    event.payload().toString(), Timestamp.from(event.occurredAt()));
        });
    }

    /** Claim one record at a time so a slow send cannot consume later records' leases. */
    public Optional<OutboxLease> claimNext() {
        return independent.execute(status -> {
            List<OutboxLease> candidates = jdbc.query("""
                    SELECT * FROM outbox_event
                    WHERE (status = 'PENDING' AND next_attempt_at <= UTC_TIMESTAMP(6))
                       OR (status = 'PROCESSING' AND lease_until <= UTC_TIMESTAMP(6))
                    ORDER BY created_at, event_id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (rs, row) -> new OutboxLease(read(rs), UUID.randomUUID().toString(),
                            rs.getInt("attempts") + 1));
            if (candidates.isEmpty()) {
                return Optional.empty();
            }
            OutboxLease lease = candidates.getFirst();
            jdbc.update("""
                    UPDATE outbox_event SET status = 'PROCESSING', attempts = attempts + 1,
                    lease_owner = ?, lease_until = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6))
                    WHERE event_id = ?
                    """, lease.owner(), properties.leaseMs() * 1000, lease.record().event().eventId().toString());
            return Optional.of(lease);
        });
    }

    public boolean markPublished(OutboxLease lease) {
        return Boolean.TRUE.equals(independent.execute(status -> jdbc.update("""
                UPDATE outbox_event SET status = 'PUBLISHED', published_at = UTC_TIMESTAMP(6),
                lease_owner = NULL, lease_until = NULL
                WHERE event_id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, lease.record().event().eventId().toString(), lease.owner()) == 1));
    }

    public boolean markFailedAttempt(OutboxLease lease) {
        boolean exhausted = lease.attempts() >= properties.maxAttempts();
        return Boolean.TRUE.equals(independent.execute(status -> jdbc.update("""
                UPDATE outbox_event SET status = ?, lease_owner = NULL, lease_until = NULL,
                next_attempt_at = TIMESTAMPADD(MICROSECOND, ?, UTC_TIMESTAMP(6))
                WHERE event_id = ? AND status = 'PROCESSING' AND lease_owner = ?
                """, exhausted ? "FAILED" : "PENDING", properties.retryDelayMs(lease.attempts()) * 1000,
                lease.record().event().eventId().toString(), lease.owner()) == 1));
    }

    private OutboxRecord read(ResultSet rs) throws SQLException {
        try {
            return new OutboxRecord(rs.getString("topic"), rs.getString("message_key"),
                    new EventEnvelope(UUID.fromString(rs.getString("event_id")), rs.getString("event_type"),
                            rs.getInt("schema_version"), producer, rs.getString("aggregate_id"),
                            rs.getLong("aggregate_version"), rs.getTimestamp("occurred_at", utcCalendar()).toInstant(),
                            rs.getString("correlation_id"), rs.getString("causation_id"), rs.getString("request_id"),
                            mapper.readTree(rs.getString("payload"))));
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new SQLException("Invalid stored outbox envelope", ex);
        }
    }

    private java.util.Calendar utcCalendar() {
        return java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
    }
}
