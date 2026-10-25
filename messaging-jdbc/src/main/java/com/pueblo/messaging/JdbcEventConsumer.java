package com.pueblo.messaging;

import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Invoke synchronously inside a record listener; return only after DB commit. */
public final class JdbcEventConsumer {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcEventConsumer(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public boolean consume(String consumerName, UUID eventId, Runnable businessAction) {
        if (consumerName == null || !consumerName.matches("[a-zA-Z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("Invalid consumer name");
        }
        java.util.Objects.requireNonNull(eventId, "eventId");
        java.util.Objects.requireNonNull(businessAction, "businessAction");
        return Boolean.TRUE.equals(transaction.execute(status -> {
            try {
                jdbc.update("INSERT INTO consumed_event (consumer_name, event_id) VALUES (?, ?)",
                        consumerName, eventId.toString());
            } catch (DuplicateKeyException duplicate) {
                return false;
            }
            // Failure rolls back both this action and the deduplication record.
            businessAction.run();
            return true;
        }));
    }
}
