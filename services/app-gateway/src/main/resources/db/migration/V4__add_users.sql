-- V4: Add users table for authentication and token budget

CREATE TABLE users (
    id            VARCHAR(255) PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT now()
);

