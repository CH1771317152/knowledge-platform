-- V2__relation.sql — relation module: following (source of truth), follower projection, outbox, consumed-event dedup

-- ============================================================
-- relation module
-- ============================================================

CREATE TABLE relation_following (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    followed_at DATETIME,
    canceled_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relation_following_pair (follower_id, following_id),
    KEY idx_relation_following_list (follower_id, status, followed_at, id),
    KEY idx_relation_following_reverse (following_id, status, followed_at, id),
    CONSTRAINT fk_relation_following_follower FOREIGN KEY (follower_id) REFERENCES user_account (id),
    CONSTRAINT fk_relation_following_following FOREIGN KEY (following_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE relation_follower (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    follower_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    followed_at DATETIME,
    canceled_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relation_follower_pair (user_id, follower_id),
    KEY idx_relation_follower_list (user_id, status, followed_at, id),
    KEY idx_relation_follower_reverse (follower_id, status, followed_at, id),
    CONSTRAINT fk_relation_follower_user FOREIGN KEY (user_id) REFERENCES user_account (id),
    CONSTRAINT fk_relation_follower_follower FOREIGN KEY (follower_id) REFERENCES user_account (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE relation_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    follower_id BIGINT NOT NULL,
    following_id BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relation_outbox_event_id (event_id),
    KEY idx_relation_outbox_created_at (created_at),
    KEY idx_relation_outbox_aggregate (aggregate_type, aggregate_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE relation_consumed_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_relation_consumed_event (event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
