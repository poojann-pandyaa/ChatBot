# Implementation Plan: Full-Scope Microservices Architecture — LLMOps Chatbot Platform
### (Re-analyzed against actual `ChatBot-main` source, July 2026)

**Project:** Migration of `app-gateway` and `rag-engine` to Java/Spring Boot, with a full distributed-systems pattern set: gRPC internal APIs, Kafka event backbone, Outbox pattern, CQRS, database sharding, and read replicas.
**Prepared by:** [RESPONSIBLE PERSON]
**Date:** [ESTIMATED TIME]
**Audience:** Project owner / self (solo developer project), for interview-readiness tracking.

---

## 0. Source Codebase Findings (baseline for this plan)

Before planning changes, the actual repository was re-verified directly. These facts drive every phase below — the plan targets real code, not an assumed architecture.

| Component | Confirmed as-is | Implication for this plan |
|---|---|---|
| `app-gateway` (`services/app-gateway/app.py`, 193 lines) | FastAPI, no persistence beyond Redis (`redis.asyncio`). Chat history stored as `chat:{conversation_id}` Redis lists via `rpush`/`lrange`. Calls `rag-engine` via raw `httpx` (streaming via NDJSON `client.stream(...)`, non-streaming via plain POST). No relational store, no auth, no rate limiting. | This is the exact service being replaced with Spring Boot + Postgres + gRPC. The NDJSON streaming logic must be preserved as gRPC server-streaming, not lost in translation. |
| `ml-service` (`services/ml-service/app.py`, 327 lines) | Three **synchronous** (`def`, not `async def`) FastAPI endpoints: `POST /classify` (query → intent/reasoning_type/entities/scope/ambiguity/sub_questions), `POST /embed` (text → embedding vector), `POST /rerank` (query + documents → scores). Loads PyTorch models directly (classifier, embedder, CrossEncoder) at startup. | These three exact request/response shapes become the `.proto` contract for the gRPC facade — no redesign, a direct 1:1 mapping. |
| `docker-compose.dev.yml` | `ml-service` is **absent** from local dev compose today. | Phase 0 must fix this as a prerequisite — you can't build against a service that doesn't run locally. |
| `config.py` | `VAULT_TOKEN` defaults to Vault's dev root token, hardcoded. | Out of scope for this plan (Kubernetes Secrets already chosen over Vault per prior architecture decision) — noted here only so it isn't mistaken for something this plan fixes. |
| Redis usage | Used for two different things today: (1) raw chat history lists in `app-gateway`, (2) semantic cache via RediSearch KNN in `rag-engine`. | Both remain Redis; CQRS's read-model (Phase 6) and the chat-history store are logically distinct even though both may live in Redis. |

---

## 1. Project Initiation

### 1.1 Purpose & Objectives
- Convert the existing all-Python RAG chatbot platform into a polyglot microservices architecture: Java/Spring Boot for orchestration, Python retained only for ML inference.
- Implement a complete distributed-systems pattern set — internal gRPC contracts, a Kafka event backbone, transactionally-safe event publishing via the Outbox pattern, read/write separation via CQRS, and horizontal data scaling via sharding and read replicas.
- Ground every pattern in the actual existing code paths identified in Section 0, not a hypothetical rebuild.

### 1.2 Scope

**In scope — full build:**
- Rewrite `app-gateway` in Spring Boot (WebFlux), replacing its `httpx`/Redis-only logic.
- Rewrite `rag-engine` in Spring Boot (WebFlux), preserving its existing orchestration logic (router, quality gate, RRF fusion).
- PostgreSQL for durable conversation data (`app-gateway` only), extended with sharding and a read replica.
- Redis retained for chat-session history and semantic cache (both already present in the source).
- resilience4j (retry + circuit breaker) on all inter-service calls.
- **gRPC** for internal contracts, replacing the existing `httpx` calls: `app-gateway` ↔ `rag-engine` ↔ `ml-service`.
- **Kafka** event-streaming backbone for post-chat side effects (cache update, analytics).
- **Outbox pattern** guaranteeing consistent DB write + event publish for conversation saves.
- **CQRS** separating conversation write path (command) from read path (query) — replacing today's single `GET /api/history/{conversation_id}` reading directly from Redis.
- **Database sharding** of the new `conversations` table by hashed `user_id`.
- **Read replica** for Postgres, serving the CQRS query side.
- Kubernetes Secrets for configuration (fixes the hardcoded Vault token noted in Section 0, as a side effect of moving to K8s-native config).
- JUnit 5 + Mockito + Testcontainers test suites.
- Updated Kubernetes manifests and CI pipeline (existing Jenkinsfile extended, not replaced).

**Explicitly NOT rewritten (boundary, not a gap):**
- `ml-service` — remains Python/FastAPI. Its PyTorch model logic (`load_models()`, classifier/embedder/CrossEncoder inference in `classify_endpoint`/`embed_endpoint`/`rerank_endpoint`) is untouched. It gains a **thin gRPC server facade** that wraps these three existing functions directly — no reimplementation, no new inference logic.
- `frontend` — remains Next.js, untouched. Continues calling `app-gateway` over REST; gRPC is internal-only.

**Still out of scope (deferred, not built — see Appendix A):**
- Service mesh (Istio/Linkerd), mTLS
- HashiCorp Vault / Spring Cloud Vault (Kubernetes Secrets used instead; also resolves the hardcoded-token finding from Section 0)

### 1.3 Stakeholders
| Stakeholder | Role | Interest |
|---|---|---|
| [RESPONSIBLE PERSON] | Project owner / sole developer | Deliver an architecturally deep, fully defensible portfolio project |
| [RESPONSIBLE PERSON] | Interviewer (external, non-team) | Evaluates system design depth and code ownership in verbal discussion |

### 1.4 Success Criteria
- All listed patterns (gRPC, Kafka, Outbox, CQRS, sharding, read replica) are implemented with working code, not just described.
- The specific request/response contracts already defined in `ml-service` (Section 0) are preserved exactly through the gRPC migration — no silent behavior change.
- Full stack runs end-to-end locally and in Kubernetes, including `ml-service` in local dev (fixing the Section 0 compose gap).
- Documentation matches implementation exactly — no claimed-but-unimplemented capability.
- Every pattern has a rehearsed, specific verbal explanation of what problem it solves in *this* system.

### 1.5 High-Level Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Scope is large for a solo developer | High | High | Strict phase-gating; no phase starts until the prior phase's exit criteria are met |
| Breadth without depth — patterns implemented shallowly | High | High | Each pattern-introducing phase includes an explicit "verify by breaking it" task |
| gRPC facade on `ml-service` diverges from its existing Pydantic schemas over time | Medium | Medium | `.proto` messages are defined directly from the exact fields in `ClassifyResponse`/`EmbedResponse`/`RerankResponse` (Section 0), reviewed side-by-side with `app.py` before coding starts |
| Kafka + Outbox + CQRS interdependency creates a fragile build order | Medium | High | Sequenced deliberately (see Section 2.2): Kafka before Outbox, Outbox before CQRS |
| `ml-service` currently missing from local dev compose (Section 0) blocks early integration testing | High | Medium | Fixed explicitly as a Phase 0 task, before any other work begins |
| NDJSON streaming behavior (today's `stream_generator()` in `app-gateway`) is lost or subtly altered when moved to gRPC server-streaming | Medium | High | Phase 3 includes an explicit side-by-side output comparison test between the old NDJSON stream and the new gRPC stream |

---

## 2. Project Planning

### 2.1 Work Breakdown Structure (Phases)
1. Phase 0 — Environment & Project Setup (incl. fixing the missing `ml-service` compose entry)
2. Phase 1 — `app-gateway` Core Service
3. Phase 2 — `rag-engine` Core Service (Orchestration)
4. Phase 3 — gRPC Migration of Internal APIs
5. Phase 4 — Kafka Event-Streaming Backbone
6. Phase 5 — Outbox Pattern
7. Phase 6 — CQRS Implementation
8. Phase 7 — Database Sharding & Read Replicas
9. Phase 8 — Resilience Layer
10. Phase 9 — Testing
11. Phase 10 — Kubernetes & Secrets Configuration
12. Phase 11 — CI Pipeline Update
13. Phase 12 — Integration, Demo Readiness & Closure

### 2.2 Dependency Overview (critical path)
```
Phase 0 (setup, incl. ml-service compose fix)
   │
Phase 1 (app-gateway) ──► Phase 2 (rag-engine)
                              │
                              ▼
                    Phase 3 (gRPC — .proto contracts built directly
                    from ml-service's existing Classify/Embed/Rerank
                    request/response models)
                              │
                              ▼
                    Phase 4 (Kafka backbone — chat-completed events,
                    replacing nothing in ml-service, additive to app-gateway)
                              │
                              ▼
                    Phase 5 (Outbox — publishes INTO Kafka from Phase 4,
                    wraps the new Postgres conversation-save transaction)
                              │
                              ▼
                    Phase 6 (CQRS — read model replaces today's direct
                    Redis-lrange-based GET /api/history/{id})
                              │
                              ▼
                    Phase 7 (Sharding + read replica — CQRS write side
                    now shard-routed, query side reads from replica)
                              │
                              ▼
                    Phase 8 (Resilience — wraps gRPC + Kafka error handling)
                              │
                              ▼
              Phase 9 (Testing) ──► Phase 10 (K8s) ──► Phase 11 (CI) ──► Phase 12 (Closure)
```
**Note:** Phases 3–7 must run in this exact order — each depends on infrastructure the previous phase introduced.

### 2.3 Resource Plan
| Resource | Allocation |
|---|---|
| [RESPONSIBLE PERSON] | Sole developer across all phases |
| Local Kubernetes environment (Minikube/Kind) | Required from Phase 0 onward |
| Kafka (Redpanda recommended — Kafka-API-compatible, no Zookeeper/JVM overhead) | Required from Phase 4 onward |
| 2x PostgreSQL instances (primary + read replica) | Required from Phase 7 onward |
| Existing Python `ml-service`, `frontend` | Reused; `ml-service` gains a gRPC facade only, and is added to local dev compose (Section 0 gap fix) |

### 2.4 Tooling & Environment Requirements
- Java 21, Maven
- Spring Boot 3.x (WebFlux, Data JPA, Data Redis Reactive, Spring Kafka, gRPC Spring Boot starter)
- `protoc` + `protobuf-maven-plugin`
- Kafka (Redpanda for local dev)
- PostgreSQL (primary + streaming replica)
- Redis
- `grpcio`, `grpcio-tools` (Python) for the `ml-service` gRPC facade
- Docker, Kubernetes (Minikube/Kind)
- Jenkins (existing instance and Jenkinsfile, extended)

---

## 3. Project Execution

### Phase 0 — Environment & Project Setup

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Add `ml-service` to `docker-compose.dev.yml` (currently missing — confirmed in Section 0)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None |
| [TASK NAME: Create Maven multi-module project skeleton for `app-gateway` and `rag-engine`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None |
| [TASK NAME: Add core Spring Boot dependencies (WebFlux, Data JPA, Data Redis Reactive, resilience4j)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Maven skeleton created |
| [TASK NAME: Add `protobuf-maven-plugin` and gRPC starter dependencies to both modules] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Maven skeleton created |
| [TASK NAME: Confirm full existing stack (Qdrant, Elasticsearch, Redis, Ollama, now-fixed ml-service) runs together via updated compose file] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Compose fix applied |
| [TASK NAME: Document frozen full-scope architecture in `docs/architecture.md`, correcting the existing Prometheus claim identified in the prior audit] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None |

**Phase 0 Exit Criteria:** Full existing stack, including `ml-service`, runs together locally for the first time. Empty Spring Boot projects build.

---

### Phase 1 — `app-gateway` Core Service

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Define `Conversation` JPA entity (id, user_id, created_at, title) — net-new; today's `app-gateway` has no relational entity at all] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement `ConversationRepository` (Spring Data JPA)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Entity defined |
| [TASK NAME: Configure `ReactiveRedisTemplate`, replicating the exact `chat:{conversation_id}` list key pattern and `rpush`/`lrange` semantics from the current `app.py`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement `ChatController` — `POST /api/chat`, `GET /api/history/{id}`, matching the current `UserChatRequest` shape (prompt, conversation_id, debug, stream)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Repository + Redis config ready |
| [TASK NAME: Implement placeholder `WebClient` call to `rag-engine` (temporary REST bridge, replaced by gRPC in Phase 3)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Controller skeleton exists |
| [TASK NAME: Port `/health` endpoint, extending today's `redis_connected` check to also report Postgres connectivity] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Redis + JPA configured |

**Phase 1 Exit Criteria:** `app-gateway` reproduces all current functionality (chat, history, health) plus new Postgres-backed conversation persistence, callable from the existing `frontend` unmodified.

---

### Phase 2 — `rag-engine` Core Service (Orchestration)

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Port `RouterService` from `router.py` — reasoning-type routing, follow-up detection, cache-check orchestration] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 1 complete |
| [TASK NAME: Port `QualityGateService` from `quality_gate.py` — reranker score thresholding, retry/refinement logic] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | RouterService skeleton exists |
| [TASK NAME: Port Reciprocal Rank Fusion scoring from `hybrid_search.py`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None (independent utility) |
| [TASK NAME: Implement `QdrantClient`, `ElasticsearchClient` (WebClient wrappers — external, non-gRPC dependencies, unchanged protocol)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement `OllamaClient`, and explicitly fix the current zero-vector embedding fallback (`hybrid_search.py`) to fail loudly/skip dense search instead of silently querying a zero vector] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement semantic cache read/write via `ReactiveRedisTemplate.execute()` raw RediSearch commands, matching the existing KNN cache logic] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Redis config ready |
| [TASK NAME: Implement `ReasoningController` — REST endpoint (`/v1/reasoning-chat`), temporary until Phase 3] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All clients/services implemented |

**Phase 2 Exit Criteria:** `rag-engine` executes classify → retrieve → quality-gate → generate end-to-end over REST, called successfully by `app-gateway`, with the known zero-vector fallback bug fixed.

---

### Phase 3 — gRPC Migration of Internal APIs

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Define `.proto` contract for `app-gateway` ↔ `rag-engine` (`ReasoningChatRequest`/`Response`; map today's NDJSON `{"type": "token", "data": ...}` stream chunks to a server-streaming RPC message)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 2 complete |
| [TASK NAME: Define `.proto` contract for `rag-engine` ↔ `ml-service`, built field-for-field from the existing `ClassifyRequest/Response`, `EmbedRequest/Response`, `RerankRequest/Response` Pydantic models in `ml-service/app.py`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 2 complete |
| [TASK NAME: Generate Java gRPC stubs for both Spring Boot services] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Proto contracts defined |
| [TASK NAME: Implement gRPC server in `rag-engine`, replacing `ReasoningController`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Stubs generated |
| [TASK NAME: Implement gRPC client in `app-gateway`, replacing the Phase 1 WebClient stub] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | rag-engine gRPC server functional |
| [TASK NAME: Add a minimal Python gRPC server facade to `ml-service`, wrapping `classify_endpoint`/`embed_endpoint`/`rerank_endpoint` directly — no changes to `load_models()` or inference code; existing FastAPI REST app left running unmodified alongside it] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Python `.proto` stubs generated via `grpcio-tools` |
| [TASK NAME: Implement gRPC client in `rag-engine` calling `ml-service`'s new gRPC facade, replacing the WebClient calls to `/classify`, `/embed`, `/rerank`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | ml-service gRPC facade running |
| [TASK NAME: Side-by-side comparison test: old NDJSON stream output vs. new gRPC server-streaming output, for the same prompt, confirming identical token sequence and ordering] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All gRPC clients/servers wired |

**Phase 3 Exit Criteria:** All internal traffic flows over gRPC. `ml-service`'s inference code is byte-for-byte unmodified; only a gRPC entrypoint was added. Streaming behavior is verified equivalent to the original NDJSON implementation.

---

### Phase 4 — Kafka Event-Streaming Backbone

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Stand up Redpanda/Kafka in `docker-compose.dev.yml`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Define topic schema: `chat-completed`, `cache-update-requested`, `usage-recorded`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Kafka running |
| [TASK NAME: Define Protobuf schemas for each event payload (reusing message style from Phase 3's `.proto` work)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Topics defined |
| [TASK NAME: Add Spring Kafka dependency and producer configuration to `app-gateway`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 3 complete |
| [TASK NAME: Implement `ChatEventProducer` — publishes `chat-completed` after the assistant's full answer is assembled (the same point where today's code currently does `redis_client.rpush(..., f"assistant:{answer}")`)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Kafka + schemas ready |
| [TASK NAME: Implement `CacheUpdateConsumer` — listens to `chat-completed`, updates Redis semantic cache] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Producer implemented |
| [TASK NAME: Implement `UsageAnalyticsConsumer` — listens to `chat-completed`, records a usage counter independently] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Producer implemented |
| [TASK NAME: Verify killing one consumer doesn't block the other or fail the original chat request] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Both consumers implemented |

**Phase 4 Exit Criteria:** `app-gateway` publishes `chat-completed` after each response; two independent consumers react without coupling to each other or the request path.

---

### Phase 5 — Outbox Pattern

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Define `OutboxEvent` JPA entity (id, aggregate_id, event_type, payload, created_at, published boolean)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 4 complete |
| [TASK NAME: Wrap the `Conversation` write and `OutboxEvent` insert in a single `@Transactional` method] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | OutboxEvent entity defined |
| [TASK NAME: Implement `OutboxPoller` (`@Scheduled`) — reads unpublished rows, publishes to Kafka, marks published] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Transactional write implemented |
| [TASK NAME: Replace Phase 4's direct producer call with the Outbox-based flow] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Poller implemented |
| [TASK NAME: Verify: kill `app-gateway` immediately after DB commit but before Kafka publish — confirm poller delivers the event on restart] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Full outbox flow wired |

**Phase 5 Exit Criteria:** Conversation writes and their Kafka events are transactionally consistent, proven via the forced-crash test.

---

### Phase 6 — CQRS Implementation

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Design command side — `ConversationCommandService` (writes via Phase 1/5's transactional + outbox flow)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 5 complete |
| [TASK NAME: Design query side — `ConversationQueryService` reading from a denormalized read model] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Command side defined |
| [TASK NAME: Implement `ConversationReadModelConsumer` — Kafka consumer updating a read-optimized store from `chat-completed` events] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Kafka backbone available |
| [TASK NAME: Replace today's direct Redis-`lrange`-based `GET /api/history/{id}` with a read exclusively from the CQRS read model] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Read model consumer implemented |
| [TASK NAME: Verify eventual consistency — document the propagation delay between write and read-model visibility] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Read/write paths both functional |

**Phase 6 Exit Criteria:** Reads and writes for conversation data go through fully separate paths; the read side is populated asynchronously via Kafka, never read directly from the write-side table.

---

### Phase 7 — Database Sharding & Read Replicas

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Stand up a second Postgres instance as a streaming replica of the primary] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Configure `AbstractRoutingDataSource` — CQRS query-side reads to the replica, command-side writes to the primary] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Replica running, Phase 6 complete |
| [TASK NAME: Design shard key strategy — hash(`user_id`) modulo 2, defining two logical shard schemas on the primary] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Routing datasource configured |
| [TASK NAME: Implement `ShardRouter` utility] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Shard key strategy defined |
| [TASK NAME: Update `ConversationCommandService` to write through `ShardRouter`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | ShardRouter implemented |
| [TASK NAME: Verify writes for different `user_id` hashes land in correct shards, and reads are served from the replica] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All routing implemented |

**Phase 7 Exit Criteria:** Writes are shard-routed by `user_id` hash; reads are served from a Postgres read replica — both verified by direct inspection.

---

### Phase 8 — Resilience Layer

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Add resilience4j `@Retry`/`@CircuitBreaker` to all gRPC client calls] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 3 complete |
| [TASK NAME: Configure Kafka producer idempotence and bounded retries] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 4 complete |
| [TASK NAME: Configure Kafka consumer dead-letter topic for repeatedly failing messages] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Consumers implemented |
| [TASK NAME: Implement fallback methods for `ml-service`/Ollama outages, replacing today's hardcoded `[MOCK GENERATION]` placeholder in `generator.py` with an explicit, flagged degraded-mode response] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Circuit breakers configured |
| [TASK NAME: Verify circuit-breaker transitions and dead-letter behavior via forced outages] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All resilience config complete |

**Phase 8 Exit Criteria:** Forced outages of `ml-service`, `rag-engine`, and Kafka each degrade gracefully or park safely — never silent data loss, and never a hidden mock response masquerading as a real one.

---

### Phase 9 — Testing

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: JUnit 5 + Mockito unit tests — `RouterService`, `QualityGateService`, RRF scoring, `ShardRouter`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phases 2, 7 complete |
| [TASK NAME: Testcontainers — Postgres primary + replica routing tests] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 7 complete |
| [TASK NAME: Testcontainers — Redis (semantic cache, CQRS read model, chat-history list parity with original format)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 6 complete |
| [TASK NAME: Testcontainers — Kafka (produce `chat-completed`, verify both consumers)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 4 complete |
| [TASK NAME: Integration test proving Outbox consistency under simulated crash] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 5 complete |
| [TASK NAME: gRPC contract tests — verify Java and Python stubs agree, and that `ml-service`'s gRPC responses match its existing REST responses for identical input (regression check against the original FastAPI behavior)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 3 complete |
| [TASK NAME: Add unit tests for `ml-service` endpoints — none exist in the current codebase per the prior audit] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 3 complete |
| [TASK NAME: Configure JaCoCo coverage reporting with a minimum threshold] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Unit test suite established |

**Phase 9 Exit Criteria:** Each pattern has at least one real integration test; `ml-service`'s previously untested endpoints now have coverage; gRPC responses are regression-checked against original REST behavior.

---

### Phase 10 — Kubernetes & Secrets Configuration

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Write Dockerfiles for `app-gateway`, `rag-engine`, and updated `ml-service` (with gRPC facade)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 8 complete |
| [TASK NAME: Deploy Kafka (Redpanda/Strimzi) into the local Kubernetes cluster] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Dockerfiles complete |
| [TASK NAME: Deploy Postgres primary + replica into the cluster] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Dockerfiles complete |
| [TASK NAME: Create Kubernetes `Secret` manifests (DB credentials, Redis password, Kafka bootstrap config) — replacing the hardcoded `VAULT_TOKEN` pattern found in the original `config.py`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Postgres/Kafka deployed |
| [TASK NAME: Create `ConfigMap` manifests for non-sensitive config] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Secrets created |
| [TASK NAME: Write Deployment/Service manifests for all services] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Config manifests complete |
| [TASK NAME: Also add `ml-service` to the Ansible/K8s deploy role explicitly — confirmed absent from the Jenkins build/push stages in the original pipeline] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Dockerfiles complete |
| [TASK NAME: Deploy full stack to local Minikube/Kind and verify pod health end-to-end] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All manifests complete |

**Phase 10 Exit Criteria:** Full stack, including Kafka, both Postgres instances, and `ml-service`, runs in a local Kubernetes cluster using only native Secrets/ConfigMaps.

---

### Phase 11 — CI Pipeline Update

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Add Maven build/test stages for `app-gateway`, `rag-engine`, including proto-generation] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 9 complete |
| [TASK NAME: Add Docker build/push stages for all services, including `ml-service` — closing the gap where it was previously unbuilt in Jenkins] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 10 Dockerfiles complete |
| [TASK NAME: Extend existing Trivy scan stages to cover new images] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Docker stages added |
| [TASK NAME: Update sanity-check stage to exercise a full chat request through gRPC → Kafka → CQRS read path] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 10 deployment verified |

**Phase 11 Exit Criteria:** Jenkins pipeline builds, tests, scans, and validates the entire full-scope stack, including `ml-service` for the first time.

---

### Phase 12 — Integration, Demo Readiness & Closure

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Full end-to-end manual test: frontend → app-gateway (gRPC) → rag-engine (gRPC) → ml-service (gRPC facade) → Ollama → response → Kafka event → CQRS read model updated] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 10 complete |
| [TASK NAME: Update `docs/architecture.md` with a diagram of the full event/data flow, correcting the previously inaccurate Prometheus claim] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All phases complete |
| [TASK NAME: Prepare verbal walkthrough notes per pattern: problem solved + how it was verified] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Documentation updated |
| [TASK NAME: Prepare Appendix A talking points for interview follow-ups] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None |
| [TASK NAME: Final CV/resume line update reflecting the full implemented stack] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All phases complete |

**Phase 12 Exit Criteria:** Project fully demoable end-to-end; documentation matches implementation exactly; every pattern has a rehearsed, verification-backed explanation.

---

## 4. Monitoring & Control

### 4.1 Progress Tracking
| Mechanism | Description |
|---|---|
| Phase gate reviews | Each phase's exit criteria verified before the next phase starts |
| "Verify by breaking it" discipline | Phases 3–8 each include a forced-failure test; not complete until it passes |
| Scope-freeze check | Appendix A items are not added without a new explicit decision |
| Weekly self-review | [RESPONSIBLE PERSON] reviews completed vs. planned tasks |

### 4.2 Change Control
- Any change to locked scope (Section 1.2) requires explicit re-justification.
- New tasks discovered mid-phase are logged against the relevant phase table using the same placeholder format.

### 4.3 Quality Control
| Check | Applies To |
|---|---|
| Every architectural claim has working code and a passing forced-failure test | Phases 3–8 |
| No documentation describes unimplemented functionality | Phase 12 (final audit) |
| `ml-service` and `frontend` core logic confirmed unmodified (diff against original repo) | Phase 12 |
| gRPC responses regression-tested against original REST behavior | Phase 9 |
| Test suite includes a real integration test per pattern | Phase 9 |

### 4.4 Risk Monitoring
Revisit Section 1.5 at the start of each phase. If time runs short, prefer finishing fewer patterns completely (with passing forced-failure tests) over having all patterns half-implemented.

---

## 5. Project Closure

### 5.1 Closure Checklist
| Item | Status |
|---|---|
| [TASK NAME: All phase exit criteria met, including forced-failure verifications] | [ ] |
| [TASK NAME: Full end-to-end demo runs without manual intervention] | [ ] |
| [TASK NAME: `ml-service` and `frontend` confirmed unmodified apart from the gRPC facade addition] | [ ] |
| [TASK NAME: Documentation matches implemented functionality with no gaps] | [ ] |
| [TASK NAME: Test suite passes in CI, including all pattern-specific integration tests] | [ ] |
| [TASK NAME: Interview talking points finalized and rehearsed for every implemented pattern] | [ ] |
| [TASK NAME: CV/resume updated to reflect final architecture] | [ ] |

### 5.2 Lessons Learned Template
| Category | Observation |
|---|---|
| What went well | [TASK NAME] |
| What was harder than expected | [TASK NAME] |
| What would be scoped differently next time | [TASK NAME] |

### 5.3 Final Deliverables
- `app-gateway` (Spring Boot, gRPC client + server, Kafka producer, Outbox, CQRS command/query services, shard-aware routing)
- `rag-engine` (Spring Boot, gRPC client + server, stateless orchestration, zero-vector fallback fixed)
- `ml-service` (Python FastAPI, unmodified inference logic, plus new gRPC facade, now included in local dev compose and CI)
- `.proto` contract definitions (shared source of truth, derived directly from `ml-service`'s existing Pydantic models)
- Kubernetes manifests (Secrets, ConfigMaps, Deployments, Services, Kafka, dual Postgres)
- Updated Jenkins pipeline (now building `ml-service` for the first time)
- Updated architecture documentation with accurate capability claims and a full event/data-flow diagram
- Interview talking-points reference sheet

### 5.4 Sign-off
| Role | Name | Date |
|---|---|---|
| Project Owner | [RESPONSIBLE PERSON] | [ESTIMATED TIME] |

---

## 6. Appendix A: Remaining Deferred Scope Register (Not To Be Implemented)

| Item | What It Is | Why Deferred | Talking Point If Asked |
|---|---|---|---|
| **Service mesh (Istio/Linkerd), mTLS** | Sidecar-proxy traffic management, encryption, observability without app code changes | Equivalent concerns already handled in application code (resilience4j, gRPC contracts); a mesh is the next infrastructural step once service count grows | "I'd introduce a mesh once per-service resilience code became repetitive to maintain across more services." |
| **HashiCorp Vault / Spring Cloud Vault** | Centralized secrets management with dynamic credentials and audit logging | Kubernetes Secrets are the deliberate choice at this scale, and directly replace the hardcoded dev root token found in the original `config.py` | "Kubernetes Secrets closed the specific hardcoded-token issue found in the original codebase. I'd move to Vault once I needed rotation, dynamic credentials, or a full audit trail." |

**Register maintenance rule:** any future need for the above should be re-scoped through Section 4.2 (Change Control), not added informally.
