# Architecture Decision Record: Read Replicas Eventual Consistency & Replica Lag Handling

## Context

The platform scales PostgreSQL horizontally by sharding data across multiple databases (per-user partitioning) and routing database calls dynamically via Spring's `AbstractRoutingDataSource`. Each shard contains a primary write endpoint and a replica read endpoint (e.g. `shard-0-write-pool` and `shard-0-read-pool`).

For reads, the platform utilizes CQRS (Command Query Responsibility Segregation). Writes are persisted in the PostgreSQL primary shard, and a `chat-completed` event is emitted. An asynchronous Kafka consumer (`ConversationReadModelConsumer`) consumes this event and updates the denormalized read model stored in a fast Redis cache. The query side (`ConversationQueryService`) retrieves conversation history exclusively from this Redis read model to minimize primary database load.

## Problem Statement

If a read query is executed immediately after a write (read-your-writes), there is a chance the write has not yet replicated to the read replicas (replica lag) or the Kafka consumer has not yet updated the Redis read model. If not handled, this would lead to inconsistent history views for the user.

---

## Architectural Decision

We explicitly choose to accept **eventual consistency** for read operations across replicas and the cache, governed by the following design justifications and mitigations:

### 1. CQRS Redis Read Model (Primary Read Path)
* **Design Decision:** The main UI history reads bypass PostgreSQL entirely and read directly from Redis.
* **Lag Mitigation:** 
  * The frontend client renders the newly generated assistant tokens in real-time in the UI during the chat session. This ensures that the user has an immediate, local copy of the conversation state without needing to query the backend database or cache.
  * Redis updates occur via a high-priority, low-latency Kafka topic (`chat-completed`). Under normal operating conditions, Kafka event propagation and Redis cache update latency are $<50\text{ ms}$, which is virtually unnoticeable to the end-user.

### 2. Eventual Consistency Acceptance on Database Replicas
* **Design Decision:** In scenarios where direct database reads are added in the future (e.g., fallback paths if Redis goes offline), we accept eventual consistency from read replicas without implementing complex synchronized locking or routing back to the primary DB.
* **Justification:**
  * **Availability over Consistency:** In accordance with the CAP theorem, we prioritize High Availability and Low Latency (AP) for chat reads. Forcing reads to route to the primary database for a time-window after a write adds state management complexity (e.g., tracking write timestamps in the gateway) and increases load on the primary DB, negating the scaling benefit of read replicas.
  * **User Experience (UX) Tolerance:** Chat history reads are not highly time-sensitive financial or transactional records. An occasional delay of a few hundred milliseconds in history synchronization across other sessions (e.g., dual-login screens) is a standard trade-off in modern enterprise chat architectures.

### 3. Kafka Idempotence Protection
* **Design Decision:** To prevent issues where duplicate or delayed events cause incorrect history updates, the `ConversationReadModelConsumer` uses idempotency checks (verifying the message fingerprint in Redis) before saving, ensuring the denormalized view remains consistent regardless of partition replication speeds.
