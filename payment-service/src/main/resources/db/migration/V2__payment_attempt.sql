CREATE TABLE payment_attempt (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    payment_key VARCHAR(200) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    amount BIGINT NOT NULL CHECK (amount > 0),
    status VARCHAR(32) NOT NULL DEFAULT 'PREPARING',
    version BIGINT NOT NULL DEFAULT 0,
    checks INT NOT NULL DEFAULT 0,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    lease_until TIMESTAMP(6) NULL,
    next_check_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CHECK (status IN ('PREPARING','PROCESSING','UNKNOWN','SUCCEEDED','FAILED','REVIEW_REQUIRED')),
    INDEX idx_payment_recovery(status,next_check_at,lease_until),
    INDEX idx_payment_user(user_id,created_at)
) ENGINE=InnoDB;
