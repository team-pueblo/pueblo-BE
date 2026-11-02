CREATE TABLE payment_recheck_audit (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    payment_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    previous_status VARCHAR(32) NOT NULL,
    resulting_status VARCHAR(32),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at TIMESTAMP(6) NULL,
    FOREIGN KEY (payment_id) REFERENCES payment_attempt(id)
) ENGINE=InnoDB;
