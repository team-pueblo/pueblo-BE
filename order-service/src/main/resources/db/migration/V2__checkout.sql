CREATE TABLE checkout_quote (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    items JSON NOT NULL, total_amount BIGINT NOT NULL, expires_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CHECK (total_amount >= 0), INDEX idx_quote_expiry (expires_at)
) ENGINE=InnoDB;
CREATE TABLE customer_order (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quote_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    items JSON NOT NULL, total_amount BIGINT NOT NULL, shipping JSON NOT NULL, consent JSON NOT NULL,
    status VARCHAR(40) NOT NULL, reason VARCHAR(80), cancellation_target VARCHAR(40),
    reservation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    reserve_command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    release_command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    release_requested BOOLEAN NOT NULL DEFAULT FALSE,
    inventory_version BIGINT NOT NULL DEFAULT 0,
    prepare_deadline TIMESTAMP(6) NOT NULL, reserved_at TIMESTAMP(6), expires_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    CHECK (total_amount >= 0),
    CHECK (status IN ('PREPARING','PENDING_PAYMENT','ABORTING','REJECTED','CANCELED','EXPIRED','RECONCILIATION_REQUIRED')),
    FOREIGN KEY (quote_id) REFERENCES checkout_quote(id),
    INDEX idx_order_owner (user_id,created_at,id),
    INDEX idx_order_prepare (status,prepare_deadline), INDEX idx_order_expiry (status,expires_at)
) ENGINE=InnoDB;
CREATE TABLE order_idempotency (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result JSON, created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY(user_id,request_key)
) ENGINE=InnoDB;
CREATE TABLE inventory_result_receipt (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
