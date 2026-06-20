-- V1__init.sql — baseline schema for user / auth / content modules

-- ============================================================
-- user module
-- ============================================================

CREATE TABLE user_account (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    email VARCHAR(128) NOT NULL,
    phone VARCHAR(32),
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    role VARCHAR(32) NOT NULL DEFAULT 'USER',
    email_verified TINYINT(1) NOT NULL DEFAULT 0,
    phone_verified TINYINT(1) NOT NULL DEFAULT 0,
    last_login_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_account_username (username),
    UNIQUE KEY uk_user_account_email (email),
    UNIQUE KEY uk_user_account_phone (phone)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE user_profile (
    user_id BIGINT PRIMARY KEY,
    display_name VARCHAR(64) NOT NULL,
    avatar_url VARCHAR(512),
    bio VARCHAR(512),
    location VARCHAR(128),
    website VARCHAR(512),
    birthday DATE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_user_profile_account FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- content module
-- ============================================================

CREATE TABLE content_post (
    id BIGINT PRIMARY KEY,
    author_id BIGINT NOT NULL,
    client_request_id VARCHAR(128),
    title VARCHAR(200),
    summary VARCHAR(500),
    cover_object_key VARCHAR(512),
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    visibility VARCHAR(32) NOT NULL DEFAULT 'PRIVATE',
    publish_stage VARCHAR(32) NOT NULL DEFAULT 'DRAFT_CREATED',
    published_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_post_author_request (author_id, client_request_id),
    KEY idx_content_post_author_created (author_id, created_at),
    KEY idx_content_post_status_published (status, visibility, published_at),
    CONSTRAINT fk_content_post_author FOREIGN KEY (author_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_post_body (
    post_id BIGINT PRIMARY KEY,
    body_format VARCHAR(32) NOT NULL DEFAULT 'MARKDOWN',
    body_bucket VARCHAR(128),
    body_object_key VARCHAR(512),
    body_etag VARCHAR(255),
    body_sha256 CHAR(64),
    body_size_bytes BIGINT,
    body_version INT NOT NULL DEFAULT 1,
    upload_url_expires_at DATETIME,
    confirmed_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_content_post_body_post FOREIGN KEY (post_id) REFERENCES content_post (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_post_file (
    post_id BIGINT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    usage_type VARCHAR(32) NOT NULL,
    content_type VARCHAR(128),
    size_bytes BIGINT,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, object_key, usage_type),
    CONSTRAINT fk_content_post_file_post FOREIGN KEY (post_id) REFERENCES content_post (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(64) NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_content_tag_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE content_post_tag (
    post_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, tag_id),
    CONSTRAINT fk_content_post_tag_post FOREIGN KEY (post_id) REFERENCES content_post (id),
    CONSTRAINT fk_content_post_tag_tag FOREIGN KEY (tag_id) REFERENCES content_tag (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ============================================================
-- auth module
-- ============================================================

CREATE TABLE auth_refresh_token (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    token_jti VARCHAR(64) NOT NULL,
    device_id VARCHAR(128),
    user_agent VARCHAR(512),
    ip_address VARCHAR(64),
    expires_at DATETIME NOT NULL,
    revoked_at DATETIME,
    replaced_by_token_id BIGINT,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_auth_refresh_token_hash (token_hash),
    UNIQUE KEY uk_auth_refresh_token_jti (token_jti),
    KEY idx_auth_refresh_token_user_id (user_id),
    KEY idx_auth_refresh_token_expires_at (expires_at),
    CONSTRAINT fk_auth_refresh_token_user FOREIGN KEY (user_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE auth_verification_audit (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    channel VARCHAR(16) NOT NULL,
    target VARCHAR(255) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    request_ip VARCHAR(64),
    failure_reason VARCHAR(255),
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    consumed_at DATETIME,
    KEY idx_auth_verification_audit_target (target),
    KEY idx_auth_verification_audit_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
