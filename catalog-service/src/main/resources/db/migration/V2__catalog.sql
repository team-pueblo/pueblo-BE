CREATE TABLE catalog_brand (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    slug VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    korean_name VARCHAR(160) NOT NULL DEFAULT '',
    logo_url VARCHAR(2048) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    display_order INT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
CREATE TABLE catalog_category (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    slug VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    name VARCHAR(160) NOT NULL,
    display_order INT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
CREATE TABLE catalog_product (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    brand_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    category_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    name VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    images JSON NOT NULL,
    policy JSON NOT NULL,
    list_price BIGINT NOT NULL,
    sale_price BIGINT NOT NULL,
    currency CHAR(3) NOT NULL DEFAULT 'KRW',
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    new_until DATETIME(6) NULL,
    source VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_url VARCHAR(2048) NULL,
    imported_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    UNIQUE KEY uq_product_source (source, external_id),
    INDEX ix_product_public (status, created_at, id),
    INDEX ix_product_brand (brand_id, status, created_at, id),
    INDEX ix_product_category (category_id, status, created_at, id),
    INDEX ix_product_price (status, sale_price, id),
    FOREIGN KEY (brand_id) REFERENCES catalog_brand(id),
    FOREIGN KEY (category_id) REFERENCES catalog_category(id),
    CONSTRAINT ck_product_price CHECK (list_price >= 0 AND sale_price >= 0 AND sale_price <= list_price),
    CONSTRAINT ck_product_currency CHECK (currency = 'KRW'),
    CONSTRAINT ck_product_status CHECK (status IN ('DRAFT','PUBLISHED','DISCONTINUED','DELETED'))
) ENGINE=InnoDB;
CREATE TABLE catalog_product_collection (
    product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    collection VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    PRIMARY KEY(product_id, collection),
    FOREIGN KEY(product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_collection CHECK (collection IN ('men','women','lifestyle'))
) ENGINE=InnoDB;
CREATE TABLE catalog_sku (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    product_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    external_id VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    options JSON NOT NULL,
    option_key VARCHAR(512) NOT NULL,
    price_override BIGINT NULL,
    stock INT NOT NULL DEFAULT 0,
    purchase_limit INT NOT NULL DEFAULT 9,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE KEY uq_sku_external(product_id, external_id),
    UNIQUE KEY uq_sku_options(product_id, option_key),
    FOREIGN KEY(product_id) REFERENCES catalog_product(id),
    CONSTRAINT ck_sku_stock CHECK(stock >= 0),
    CONSTRAINT ck_sku_price CHECK(price_override IS NULL OR price_override >= 0),
    CONSTRAINT ck_sku_limit CHECK(purchase_limit BETWEEN 1 AND 9)
) ENGINE=InnoDB;
