-- V1: Formalise the conversations table that was previously created by Hibernate auto-ddl.
-- Flyway will skip this if the table already exists (idempotent via IF NOT EXISTS).

CREATE TABLE IF NOT EXISTS conversations (
    id         VARCHAR(255) PRIMARY KEY,
    user_id    VARCHAR(255) NOT NULL DEFAULT 'default_user',
    title      VARCHAR(500),
    created_at TIMESTAMP    NOT NULL DEFAULT now()
);
