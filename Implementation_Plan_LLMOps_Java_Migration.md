# Implementation Plan: Polyglot Microservices Migration — LLMOps Chatbot Platform

**Project:** Migration of `app-gateway` and `rag-engine` from Python/FastAPI to Java/Spring Boot, with architectural hardening for a microservices-based portfolio project.
**Prepared by:** [RESPONSIBLE PERSON]
**Date:** [ESTIMATED TIME]
**Audience:** Project owner / self (solo developer project), for interview-readiness tracking.

---

## 1. Project Initiation

### 1.1 Purpose & Objectives
- Convert the existing all-Python RAG chatbot platform into a polyglot microservices architecture: Java/Spring Boot for orchestration services, Python retained only for ML inference.
- Close identified architectural gaps (no relational store, hardcoded secrets, no resilience patterns, thin test coverage).
- Produce a project that is simple to defend verbally in SDE/AI Engineer interviews, prioritizing architectural soundness over framework count.

### 1.2 Scope

**In scope:**
- Rewrite `app-gateway` in Spring Boot (WebFlux).
- Rewrite `rag-engine` in Spring Boot (WebFlux), stateless.
- Add PostgreSQL (app-gateway only) for durable conversation metadata.
- Add resilience4j (retry + circuit breaker) on all inter-service calls.
- Migrate secrets management to native Kubernetes Secrets.
- Add JUnit 5 + Mockito + Testcontainers test suites.
- Update Kubernetes manifests and CI pipeline for the new services.

**Out of scope (explicitly deferred, documented as "next evolution" talking points only):**
- Kafka / full event-streaming backbone
- Outbox pattern implementation
- Service mesh (Istio/Linkerd), mTLS
- HashiCorp Vault / Spring Cloud Vault
- gRPC migration of internal APIs
- CQRS, database sharding, read replicas
- Rewriting `ml-service` (remains Python/FastAPI, untouched)
- Rewriting `frontend` (remains Next.js, untouched)

### 1.3 Stakeholders
| Stakeholder | Role | Interest |
|---|---|---|
| [RESPONSIBLE PERSON] | Project owner / sole developer | Deliver an interview-ready, architecturally sound portfolio project |
| [RESPONSIBLE PERSON] | Interviewer (external, non-team) | Evaluates system design reasoning and code ownership in verbal discussion |

### 1.4 Success Criteria
- Both Spring Boot services build, run, and pass their test suites locally and in a K8s cluster.
- Every architectural claim made about the project (database-per-service, resilience patterns, caching strategy) has a real, working implementation behind it.
- No unresolved documentation/code mismatches (e.g., no claimed-but-unimplemented capabilities).
- Full local demo path works end-to-end: `frontend → app-gateway → rag-engine → ml-service`.

### 1.5 High-Level Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Scope creep from adding "one more framework" | High | High | Stack is frozen per prior architecture decision; no additions without explicit re-scoping |
| RediSearch KNN not natively supported in Spring Data Redis | Medium | Medium | Use `ReactiveRedisTemplate.execute()` for raw `FT.SEARCH` command (pre-identified workaround) |
| Time overrun given solo development | High | Medium | Phase-gated delivery; Phase 1 (`app-gateway`) must be fully functional before Phase 2 starts |
| Over-engineering reduces defensibility in interviews | Medium | High | Every addition must map to a locked stack decision; unscoped patterns are "know but don't build" only |

---

## 2. Project Planning

### 2.1 Work Breakdown Structure (Phases)
1. Phase 0 — Environment & Project Setup
2. Phase 1 — `app-gateway` Service (Spring Boot)
3. Phase 2 — `rag-engine` Service (Spring Boot, stateless)
4. Phase 3 — Resilience Layer
5. Phase 4 — Testing
6. Phase 5 — Kubernetes & Secrets Configuration
7. Phase 6 — CI Pipeline Update
8. Phase 7 — Integration, Demo Readiness & Closure

### 2.2 Dependency Overview
- Phase 1 must complete before Phase 2 begins (rag-engine's WebClient integration pattern is validated first in app-gateway).
- Phase 3 depends on Phase 1 and Phase 2 having working WebClient calls to wrap.
- Phase 4 runs partially in parallel with Phases 1–3 (unit tests written alongside code) but integration/Testcontainers tests require Phases 1–2 complete.
- Phase 5 depends on Phases 1–2 producing containerizable services.
- Phase 6 depends on Phase 5 manifests being finalized.
- Phase 7 depends on all prior phases.

### 2.3 Resource Plan
| Resource | Allocation |
|---|---|
| [RESPONSIBLE PERSON] | Sole developer across all phases |
| Local Docker/Kubernetes environment (e.g., Minikube/Kind) | Required from Phase 0 onward |
| Existing Python `ml-service`, `frontend` | Reused unmodified |

### 2.4 Tooling & Environment Requirements
- Java 21 (or team-standard LTS), Maven
- Spring Boot 3.x (WebFlux, Data JPA, Data Redis Reactive)
- PostgreSQL (local/dev container)
- Redis (local/dev container)
- Docker, Kubernetes (Minikube/Kind for local testing)
- Jenkins (existing instance, reused)

---

## 3. Project Execution

### Phase 0 — Environment & Project Setup

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Create Maven multi-module project skeleton for `app-gateway` and `rag-engine`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None |
| [TASK NAME: Add core dependencies — `spring-boot-starter-webflux`, `spring-boot-starter-data-redis-reactive`, `spring-boot-starter-data-jpa`, `postgresql`, `resilience4j-spring-boot3`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Maven skeleton created |
| [TASK NAME: Stand up local dev Postgres + Redis containers via docker-compose] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None |
| [TASK NAME: Confirm existing `ml-service` and `frontend` run unmodified against new compose file] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Docker-compose updated |
| [TASK NAME: Document frozen tech stack decisions in `docs/architecture.md` to prevent scope creep] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None |

**Phase 0 Exit Criteria:** Empty Spring Boot projects build and run; local Postgres/Redis reachable; existing Python/Next.js services still function.

---

### Phase 1 — `app-gateway` Service (Spring Boot)

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Define `Conversation` JPA entity (id, created_at, title)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement `ConversationRepository` (Spring Data JPA)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Entity defined |
| [TASK NAME: Configure `ReactiveRedisTemplate` for chat history (rpush/lrange equivalents)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement `ChatController` — `POST /api/chat`, `GET /api/history/{id}`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Repository + Redis config ready |
| [TASK NAME: Implement `WebClient` bean for calling `rag-engine` `/v1/reasoning-chat`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Controller skeleton exists |
| [TASK NAME: Implement streaming response path (`Flux<String>`) for chat endpoint] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | WebClient bean implemented |
| [TASK NAME: Implement `/health` endpoint reporting Redis and Postgres connectivity status] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Redis + JPA configured |
| [TASK NAME: Write application config (`application.yml`) with externalized env-based values] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Controller and clients implemented |

**Phase 1 Exit Criteria:** `app-gateway` runs standalone, persists conversations to Postgres, reads/writes chat history to Redis, and successfully proxies a mock/stub request to a placeholder `rag-engine` endpoint.

---

### Phase 2 — `rag-engine` Service (Spring Boot, Stateless)

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Port `RouterService` — reasoning-type routing, follow-up detection, cache-check orchestration] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 1 complete |
| [TASK NAME: Port `QualityGateService` — reranker score thresholding and query refinement logic] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | RouterService skeleton exists |
| [TASK NAME: Port Reciprocal Rank Fusion (RRF) scoring logic for hybrid retrieval] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None (independent utility logic) |
| [TASK NAME: Implement `QdrantClient` (WebClient wrapper for dense vector search)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement `ElasticsearchClient` (WebClient wrapper for sparse/BM25 search)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement `MlServiceClient` (WebClient wrapper for classify/embed/rerank calls)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement `OllamaClient` (WebClient wrapper for generation, streaming and non-streaming)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 0 complete |
| [TASK NAME: Implement semantic cache read/write via `ReactiveRedisTemplate.execute()` raw `FT.SEARCH`/`HSET` commands] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Redis config ready |
| [TASK NAME: Implement `ReasoningController` — `POST /v1/reasoning-chat` (streaming and non-streaming)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All clients and services implemented |
| [TASK NAME: Wire `app-gateway`'s `WebClient` to the real `rag-engine` endpoint (replace Phase 1 stub)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | ReasoningController functional |

**Phase 2 Exit Criteria:** `rag-engine` is fully stateless (no DB), executes the complete classify → retrieve → quality-gate → generate pipeline, and returns valid responses to `app-gateway` end-to-end.

---

### Phase 3 — Resilience Layer

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Add `@Retry` annotations to all WebClient calls in `app-gateway` and `rag-engine`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phases 1–2 complete |
| [TASK NAME: Add `@CircuitBreaker` annotations with fallback methods for each downstream client] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Retry annotations added |
| [TASK NAME: Configure retry count, backoff intervals, and circuit-breaker thresholds in `application.yml`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Annotations added |
| [TASK NAME: Implement fallback responses (degraded-mode behavior) for `ml-service` and Ollama outages] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Circuit breakers configured |
| [TASK NAME: Manually verify circuit-breaker state transitions (closed → open → half-open) via forced service downtime] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Fallbacks implemented |

**Phase 3 Exit Criteria:** Simulated downstream failures (e.g., stopping `ml-service`) result in graceful degradation rather than cascading failures or hung requests.

---

### Phase 4 — Testing

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Write JUnit 5 + Mockito unit tests for `RouterService`, `QualityGateService`, RRF scoring logic] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 2 complete |
| [TASK NAME: Write unit tests for `ChatController` and `ConversationRepository` (mocked dependencies)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 1 complete |
| [TASK NAME: Configure Testcontainers module for Postgres integration tests] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 1 complete |
| [TASK NAME: Configure Testcontainers module for Redis integration tests] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 1–2 complete |
| [TASK NAME: Write integration tests validating conversation persistence end-to-end against real Postgres container] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Postgres Testcontainers configured |
| [TASK NAME: Write integration tests validating semantic cache hit/miss behavior against real Redis container] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Redis Testcontainers configured |
| [TASK NAME: Configure `pytest-cov`-equivalent (JaCoCo) coverage reporting and set minimum threshold] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Unit test suite established |

**Phase 4 Exit Criteria:** Both services have unit test coverage on core business logic plus at least one real (non-mocked) integration test per external dependency (Postgres, Redis).

---

### Phase 5 — Kubernetes & Secrets Configuration

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Write Dockerfiles for `app-gateway` and `rag-engine`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phases 1–2 complete |
| [TASK NAME: Create Kubernetes `Secret` manifests for Postgres credentials, Redis password, service URLs] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Dockerfiles complete |
| [TASK NAME: Create Kubernetes `ConfigMap` manifests for non-sensitive configuration (thresholds, timeouts)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Dockerfiles complete |
| [TASK NAME: Write Deployment manifests for both services referencing `envFrom: secretRef` / `configMapRef`] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Secrets/ConfigMaps created |
| [TASK NAME: Write Service manifests exposing both services within the cluster] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Deployments created |
| [TASK NAME: Deploy full stack to local Minikube/Kind cluster and verify pod health] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All manifests complete |

**Phase 5 Exit Criteria:** Full stack (`frontend`, `app-gateway`, `rag-engine`, `ml-service`, Postgres, Redis, Qdrant, Elasticsearch, Ollama) deploys and runs successfully in a local Kubernetes cluster using only native Secrets/ConfigMaps for configuration.

---

### Phase 6 — CI Pipeline Update

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Add Maven build + test stages for `app-gateway` and `rag-engine` to Jenkinsfile] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 4 complete |
| [TASK NAME: Add Docker build stages for both new services] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 5 Dockerfiles complete |
| [TASK NAME: Add Trivy filesystem/image scan stages for new services (extend existing pattern)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Docker build stages added |
| [TASK NAME: Update sanity-check stage to target new Spring Boot `/health` endpoints] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 5 deployment verified |

**Phase 6 Exit Criteria:** Jenkins pipeline builds, tests, scans, and validates both new services using the same conventions as the existing pipeline.

---

### Phase 7 — Integration, Demo Readiness & Closure

| Task | Responsible | Estimated Time | Dependencies |
|---|---|---|---|
| [TASK NAME: Run full end-to-end manual test: frontend query → app-gateway → rag-engine → ml-service → Ollama → response] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Phase 5 complete |
| [TASK NAME: Update `docs/architecture.md` to accurately reflect implemented capabilities only (remove any unimplemented claims)] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All phases complete |
| [TASK NAME: Prepare verbal walkthrough notes: database-per-service rationale, resilience4j rationale, K8s Secrets rationale] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | Documentation updated |
| [TASK NAME: Prepare "next evolution" talking points list (Kafka, outbox, service mesh, CQRS) for interview follow-ups] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | None |
| [TASK NAME: Final CV/resume line update reflecting actual implemented stack] | [RESPONSIBLE PERSON] | [ESTIMATED TIME] | All phases complete |

**Phase 7 Exit Criteria:** Project fully demoable end-to-end; documentation matches implementation exactly; interview talking points rehearsed.

---

## 4. Monitoring & Control

### 4.1 Progress Tracking
| Mechanism | Description |
|---|---|
| Phase gate reviews | Each phase's exit criteria must be explicitly verified before starting the next phase |
| Scope-freeze check | Any proposed new framework/tool is checked against Section 1.2 "Out of scope" list before consideration |
| Weekly self-review | [RESPONSIBLE PERSON] reviews completed vs. planned tasks against this document |

### 4.2 Change Control
- Any change to the locked tech stack (Section 1.2) requires explicit re-justification against the "simplicity + defensibility" principle established during architecture planning, not just "would be nice to have."
- New tasks discovered mid-phase are logged against the relevant phase table using the same placeholder format, not silently absorbed.

### 4.3 Quality Control
| Check | Applies To |
|---|---|
| Every architectural claim has working code behind it | All phases |
| No documentation describes unimplemented functionality | Phase 7 (final audit) |
| Test suite includes at least one real (non-mocked) integration test per external dependency | Phase 4 |
| Circuit breaker/retry behavior manually verified under simulated failure | Phase 3 |

### 4.4 Risk Monitoring
Revisit the risk table in Section 1.5 at the start of each phase; update likelihood/impact and mitigation status as the project progresses.

---

## 5. Project Closure

### 5.1 Closure Checklist
| Item | Status |
|---|---|
| [TASK NAME: All phase exit criteria met] | [ ] |
| [TASK NAME: Full end-to-end demo runs without manual intervention] | [ ] |
| [TASK NAME: Documentation matches implemented functionality with no gaps] | [ ] |
| [TASK NAME: Test suite passes in CI, including Testcontainers-based integration tests] | [ ] |
| [TASK NAME: Interview talking points document finalized and rehearsed] | [ ] |
| [TASK NAME: CV/resume updated to reflect final architecture] | [ ] |

### 5.2 Lessons Learned Template
| Category | Observation |
|---|---|
| What went well | [TASK NAME] |
| What was harder than expected | [TASK NAME] |
| What would be scoped differently next time | [TASK NAME] |

### 5.3 Final Deliverables
- `app-gateway` (Spring Boot service, source + tests + Dockerfile)
- `rag-engine` (Spring Boot service, source + tests + Dockerfile)
- Kubernetes manifests (Secrets, ConfigMaps, Deployments, Services)
- Updated Jenkins pipeline
- Updated architecture documentation
- Interview talking-points reference sheet

### 5.4 Sign-off
| Role | Name | Date |
|---|---|---|
| Project Owner | [RESPONSIBLE PERSON] | [ESTIMATED TIME] |

---

## 6. Appendix A: Deferred Scope Register (Not To Be Implemented)

These items are intentionally **excluded from the build** per the locked architecture decision (Section 1.2). They are documented here formally — not as pending tasks, but as a reference register — so each one has a rehearsed, precise explanation ready if raised in an interview, and so none of them are accidentally reopened mid-project.

| Item | What It Is | Why Deferred | Talking Point If Asked |
|---|---|---|---|
| **Kafka / full event-streaming backbone** | A distributed, ordered event log allowing multiple independent consumers to react to the same event (e.g., "chat completed") | Adds a new piece of infrastructure (broker, topics, consumer groups) with no consumer currently justified by project scale; the synchronous call chain is simple enough for a chatbot request/response flow | "At this scale, a synchronous call chain is appropriate and easier to reason about. I'd introduce Kafka if multiple independent services needed to react to the same event — e.g., analytics, billing, and caching all reacting to one 'chat completed' event without coupling to each other." |
| **Outbox pattern implementation** | Writing an event to an `outbox` table in the same DB transaction as the main write, then relaying it via a poller, to guarantee the DB write and event publish stay consistent | Only necessary once there's an actual second system to notify (e.g., Kafka); with no event bus in scope, there's nothing for the outbox to guarantee consistency with yet | "This solves the dual-write problem — keeping a DB write and an event publish consistent. It becomes necessary the moment I introduce an event bus; without one, it has no job to do yet." |
| **gRPC migration of internal APIs** | Replacing REST/JSON calls between `app-gateway`, `rag-engine`, and `ml-service` with binary Protobuf-based RPC and schema-enforced contracts | A genuine refactor of every inter-service contract; REST/WebClient is sufficient for this project's traffic volume and keeps the services simpler to demo and explain | "REST is appropriate for this project's scale and keeps the contract easy to inspect and debug. gRPC would be justified at higher internal traffic volume or where strict schema enforcement and streaming performance matter more than debuggability." |
| **CQRS** | Separating the write path (saving conversations) from the read path (fetching history), potentially via different models or stores optimized for each | The current read/write volume doesn't justify separate models; Postgres alone handles both sides fine at this scale | "CQRS pays off when read and write load patterns diverge significantly — e.g., read-heavy history queries at scale. Right now a single Postgres table serves both paths without contention." |
| **Database sharding** | Splitting a table (e.g., `conversations`) across multiple database instances by a shard key (e.g., hashed `user_id`) to distribute load | Single-instance Postgres has no capacity problem at this project's data volume; sharding adds operational complexity with no corresponding load to justify it | "Sharding solves a capacity/throughput problem I don't have yet. I'd shard by `user_id` hash if a single Postgres instance became a bottleneck." |
| **Read replicas** | Additional read-only copies of the primary database, used to offload read queries from the primary via leader-follower replication | No read load in this project comes close to justifying offloading reads from a single Postgres instance | "Read replicas reduce load on the primary for read-heavy workloads. I'd add one if conversation-history reads started competing with write throughput on the primary." |
| **Rewriting `ml-service`** | Porting the Python FastAPI ML inference service (classifier, embedder, reranker — all PyTorch-based) to Java | Java's model-serving ecosystem (DJL, ONNX Runtime for Java) is far less mature than Python's; there is no benefit to porting working PyTorch inference code to another language | "I deliberately kept `ml-service` in Python — it directly loads PyTorch models, and Python's ML ecosystem is the right tool for that job. My Java services call it over HTTP, which is exactly the polyglot-microservices pattern real teams use: right language per service, not language purity for its own sake." |
| **Rewriting `frontend`** | Porting the existing Next.js chat UI to another framework | No architectural or interview-relevance benefit; the frontend is not the focus of this migration and already functions correctly | "The frontend was out of scope for this migration — the goal was hardening the backend orchestration layer and its architecture, not the UI." |

**Register maintenance rule:** if a future need genuinely arises for any of the above (e.g., real read-load contention on Postgres), it should be re-scoped through Section 4.2 (Change Control) rather than added informally.
