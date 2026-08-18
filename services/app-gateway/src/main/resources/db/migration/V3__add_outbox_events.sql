-- V3: Add outbox_events table for transactional outbox pattern.

CREATE TABLE IF NOT EXISTS outbox_events (
    id           BIGSERIAL    PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type   VARCHAR(255) NOT NULL,
    payload      TEXT         NOT NULL,
    created_at   TIMESTAMP    NOT NULL,
    published    BOOLEAN      NOT NULL
);
