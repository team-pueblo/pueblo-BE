CREATE TABLE inventory_order (
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    generation INT NOT NULL DEFAULT 0,
    reservation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    version BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB;

CREATE TABLE inventory_reservation (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    order_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    items_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    reserved_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    refund_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uq_reservation_generation (order_id, generation),
    INDEX ix_inventory_expiry (status, expires_at, id),
    FOREIGN KEY (order_id) REFERENCES inventory_order(order_id),
    CHECK (generation > 0),
    CHECK (status IN ('RESERVED','COMMITTED','RELEASED','EXPIRED','REJECTED','RESTORED'))
) ENGINE=InnoDB;

CREATE TABLE inventory_reservation_item (
    reservation_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    sku_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    quantity INT NOT NULL,
    PRIMARY KEY(reservation_id, sku_id),
    FOREIGN KEY(reservation_id) REFERENCES inventory_reservation(id),
    FOREIGN KEY(sku_id) REFERENCES catalog_sku(id),
    CHECK(quantity BETWEEN 1 AND 9)
) ENGINE=InnoDB;

CREATE TABLE inventory_command (
    command_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    result JSON NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB;
