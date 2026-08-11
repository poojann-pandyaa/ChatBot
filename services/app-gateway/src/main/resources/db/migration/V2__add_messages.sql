-- V2: Add the messages table — the missing piece for durable per-message storage.
-- Each user/assistant turn is stored as a separate row with full metadata.

CREATE TABLE messages (
    id              BIGSERIAL    PRIMARY KEY,
    conversation_id VARCHAR(255) NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20)  NOT NULL CHECK (role IN ('user', 'assistant')),
    content         TEXT         NOT NULL,
    reasoning_type  VARCHAR(50),
    created_at      TIMESTAMP    NOT NULL DEFAULT now()
);

-- Index for the most common query pattern: fetch all messages for a conversation in order
CREATE INDEX idx_messages_conversation_id_created_at
    ON messages(conversation_id, created_at ASC);
