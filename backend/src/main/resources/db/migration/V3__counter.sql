-- V3__counter.sql — counter module: consumer idempotency dedup (counts/bitmap live in Redis)
CREATE TABLE counter_consumed_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(128) NOT NULL,
    consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_counter_consumed_event (event_id, consumer_group)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
