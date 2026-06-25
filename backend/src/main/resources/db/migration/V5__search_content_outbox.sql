-- V5__search_content_outbox.sql — reliable content events and search snapshot support

ALTER TABLE content_post
    ADD COLUMN source_version BIGINT NOT NULL DEFAULT 0 AFTER updated_at;

CREATE TABLE content_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    aggregate_type VARCHAR(32) NOT NULL,
    aggregate_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload_version INT NOT NULL DEFAULT 1,
    payload_json JSON NOT NULL,
    source_version BIGINT NOT NULL,
    occurred_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME,
    UNIQUE KEY uk_content_outbox_event_id (event_id),
    KEY idx_content_outbox_unpublished (published_at, id),
    KEY idx_content_outbox_aggregate (aggregate_type, aggregate_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE counter_snapshot_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_id BIGINT NOT NULL,
    payload_json JSON NOT NULL,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at DATETIME,
    UNIQUE KEY uk_counter_snapshot_event_id (event_id),
    KEY idx_counter_snapshot_unpublished (published_at, id),
    KEY idx_counter_snapshot_entity (entity_type, entity_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
