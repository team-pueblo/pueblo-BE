ALTER TABLE customer_order DROP CHECK chk_customer_order_status;
ALTER TABLE customer_order ADD CONSTRAINT chk_customer_order_status CHECK (status IN
('PREPARING','PENDING_PAYMENT','PAYMENT_PROCESSING','ABORTING','REJECTED','CANCELED','EXPIRED','RECONCILIATION_REQUIRED','PAYMENT_CONFIRMING','REACQUIRING','PAID','REFUND_PENDING','REFUNDED'));
ALTER TABLE customer_order ADD COLUMN generation INT NOT NULL DEFAULT 1,
    ADD COLUMN inventory_state VARCHAR(32),
    ADD COLUMN payment_version BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN payment_status VARCHAR(32),
    ADD COLUMN refund_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin,
    ADD COLUMN refund_version BIGINT NOT NULL DEFAULT 0;
CREATE TABLE payment_result_receipt (
    event_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
CREATE TABLE order_inventory_command (
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL
) ENGINE=InnoDB;
INSERT INTO order_inventory_command SELECT reserve_command_id,id FROM customer_order;
INSERT INTO order_inventory_command SELECT release_command_id,id FROM customer_order;
