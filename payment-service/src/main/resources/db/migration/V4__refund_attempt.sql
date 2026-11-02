CREATE TABLE refund_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    amount BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    version BIGINT NOT NULL DEFAULT 0,
    checks INT NOT NULL DEFAULT 0,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    lease_until TIMESTAMP(6) NULL,
    next_check_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (payment_id) REFERENCES payment_attempt(id),
    CHECK (status IN ('PENDING','PROCESSING','UNKNOWN','SUCCEEDED','REVIEW_REQUIRED')),
    CHECK (amount > 0), INDEX idx_refund_recovery(status,next_check_at)
) ENGINE=InnoDB;
