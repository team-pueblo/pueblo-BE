package com.pueblo.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(OutboxDispatcher.class);
    private final JdbcOutbox outbox;
    private final EventPublisher publisher;
    private final MessagingProperties properties;

    public OutboxDispatcher(JdbcOutbox outbox, EventPublisher publisher, MessagingProperties properties) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.properties = properties;
    }

    public void poll() {
        for (int i = 0; i < properties.maxPerPoll() && !Thread.currentThread().isInterrupted(); i++) {
            if (!dispatchOne()) {
                return;
            }
        }
    }

    /** Returns false only when no eligible row exists. A send never holds a DB transaction. */
    public boolean dispatchOne() {
        var candidate = outbox.claimNext();
        if (candidate.isEmpty()) {
            return false;
        }
        OutboxLease lease = candidate.get();
        if (lease.attempts() > properties.maxAttempts()) {
            outbox.markFailedAttempt(lease);
            log.error("Outbox exhausted after abandoned claim eventId={}", lease.record().event().eventId());
            return true;
        }
        try {
            publisher.publish(lease.record());
        } catch (Exception ex) {
            outbox.markFailedAttempt(lease);
            // Do not log payloads, exception messages, or external credentials.
            log.warn("Outbox send failed eventId={} attempt={} category={}",
                    lease.record().event().eventId(), lease.attempts(), ex.getClass().getSimpleName());
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
        // A DB failure here intentionally leaves PROCESSING; the lease will expire and replay.
        if (!outbox.markPublished(lease)) {
            log.warn("Outbox completion ignored for stale claim eventId={}", lease.record().event().eventId());
        }
        return true;
    }
}
