-- Each service owns these tables in its own database.
-- Domain changes and insertion into outbox_event must share a local transaction.
CREATE TABLE outbox_event (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    schema_version INT NOT NULL,
    topic VARCHAR(249) NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    causation_id VARCHAR(128) NULL,
    request_id VARCHAR(128) NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_owner VARCHAR(128) NULL,
    lease_until DATETIME(6) NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (event_id),
    INDEX ix_outbox_dispatch (status, next_attempt_at, created_at),
    INDEX ix_outbox_lease (status, lease_until),
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING', 'PROCESSING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT ck_outbox_attempts CHECK (attempts >= 0)
) ENGINE=InnoDB;

-- Insert together with business effects, then acknowledge the Kafka record.
CREATE TABLE consumed_event (
    consumer_name VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (consumer_name, event_id)
) ENGINE=InnoDB;
