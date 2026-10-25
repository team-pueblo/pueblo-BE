CREATE TABLE member_user (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    email VARCHAR(254) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_member_status CHECK (status IN ('PENDING', 'ACTIVE')),
    CONSTRAINT ck_member_role CHECK (role IN ('MEMBER', 'ADMIN'))
) ENGINE=InnoDB;
CREATE TABLE member_consent (
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    policy_type VARCHAR(16) NOT NULL,
    version VARCHAR(64) NOT NULL,
    agreed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id, policy_type, version),
    FOREIGN KEY (user_id) REFERENCES member_user(id)
) ENGINE=InnoDB;
CREATE TABLE auth_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    INDEX ix_auth_session_user (user_id, revoked_at),
    FOREIGN KEY (user_id) REFERENCES member_user(id)
) ENGINE=InnoDB;
CREATE TABLE auth_refresh_token (
    jti CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    token_hash BINARY(32) NOT NULL,
    used_at DATETIME(6) NULL,
    INDEX ix_refresh_session (session_id),
    FOREIGN KEY (session_id) REFERENCES auth_session(id)
) ENGINE=InnoDB;
CREATE TABLE auth_one_time_token (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    token_hash BINARY(32) NOT NULL UNIQUE,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    INDEX ix_one_time_user (user_id, purpose),
    FOREIGN KEY (user_id) REFERENCES member_user(id),
    CONSTRAINT ck_token_purpose CHECK (purpose IN ('VERIFY', 'RESET'))
) ENGINE=InnoDB;
CREATE TABLE auth_mail_job (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    token_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    encrypted_token BLOB NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL,
    lease_owner CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
    lease_until DATETIME(6) NULL,
    INDEX ix_mail_pending (status, next_attempt_at),
    FOREIGN KEY (token_id) REFERENCES auth_one_time_token(id),
    CONSTRAINT ck_mail_status CHECK (status IN ('PENDING', 'PROCESSING', 'SENT', 'SKIPPED', 'FAILED'))
) ENGINE=InnoDB;
CREATE TABLE auth_rate_limit (
    key_hash BINARY(32) PRIMARY KEY,
    window_start DATETIME(6) NOT NULL,
    attempts INT NOT NULL
) ENGINE=InnoDB;
