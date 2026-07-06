# Plane 1 and Plane 2 Change List

This file lists the implementation gaps found in Plane 1 and Plane 2, with multiple fix options for each problem. It is based on the current source tree, not the removed stale documentation.

## Scope

Plane 1 means the client, frontend API route, and app gateway routing path:

- `services/frontend`
- `services/frontend/pages/api/chat.ts`
- `services/frontend/pages/index.tsx`
- `services/app-gateway/app.py`
- `services/app-gateway/config.py`
- `ansible/templates/frontend.yaml.j2`
- `ansible/templates/app-gateway.yaml.j2`

Plane 2 means state, cache, and secrets:

- Redis chat history in `services/app-gateway/app.py`
- Redis semantic cache in `services/rag-engine/app.py` and `services/rag-engine/src/reasoning/router.py`
- Vault config loading in `services/app-gateway/config.py`
- Redis and Vault deployment templates in `ansible/templates/redis.yaml.j2` and `ansible/templates/vault.yaml.j2`
- Local equivalents in `docker-compose.dev.yml`

## Priority Model

- P0: fix before claiming reliability or security in a demo/interview.
- P1: important hardening or correctness fix.
- P2: useful cleanup that can wait until the core behavior is stable.

## Plane 1: Client, Gateway, and Routing Plane

### P0-1: Gateway Health Check Masks Redis Failure

Current behavior:

- `/health` returns `{"status": "healthy", "redis_connected": false}` when Redis is unavailable.
- Kubernetes readiness and liveness probes use `/health`.
- That means a gateway pod can be marked ready even though conversation state is unavailable.

Impact:

- Follow-up questions degrade silently because history is missing.
- Users may think chat continuity exists when the gateway is running in stateless mode.
- CI or deployment checks can pass while the state plane is broken.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Keep `/health` always 200, add `/ready` that returns non-200 when Redis is unavailable, and point readinessProbe to `/ready`. | Clean separation between process liveness and dependency readiness. | Requires code, tests, and template update. | Yes |
| B | Make `/health` fail when Redis is unavailable. | Simple. | Liveness may restart the pod repeatedly during Redis outages. | No |
| C | Keep current behavior but expose status in UI. | Avoids pod churn. | Still routes traffic to a degraded gateway. | Only as an extra |

Implementation files:

- `services/app-gateway/app.py`
- `services/app-gateway/tests/test_gateway.py`
- `ansible/templates/app-gateway.yaml.j2`

### P0-2: No NetworkPolicy Isolation

Current behavior:

- No `NetworkPolicy` manifests exist.
- Frontend is exposed through NodePort.
- Internal services rely mostly on service type and convention, not explicit traffic policy.

Impact:

- Any compromised pod in the namespace can potentially attempt connections to Redis, Vault, app gateway, RAG engine, and data services.
- Plane 2 credential weaknesses become more severe because network reachability is broad.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Add default deny ingress/egress, then allow only required flows. | Strongest boundary and best architecture story. | Requires careful testing with DNS and service calls. | Yes |
| B | Add ingress-only policies for Redis, Vault, RAG, and gateway. | Lower risk of breaking egress. | Less complete isolation. | Acceptable first step |
| C | Defer policies because this is local Minikube. | Fastest. | Leaves a visible security gap. | No |

Minimum allowed flows:

- Browser or NodePort -> frontend.
- Frontend -> app-gateway.
- App-gateway -> Redis, Vault, RAG engine.
- RAG engine -> Redis, Qdrant, Elasticsearch, ML service, Ollama.
- Ansible/Jenkins operational access remains outside in-cluster NetworkPolicy unless represented by pods.

Implementation files:

- Add `ansible/templates/network-policy.yaml.j2`
- Include it in `ansible/playbooks/roles/k8s_deploy/tasks/main.yml`

### P0-3: Gateway Metrics Are Tested But Not Implemented

Current behavior:

- `services/app-gateway/tests/test_gateway.py` expects `/metrics` and `gateway_requests_total`.
- `services/app-gateway/app.py` does not define `/metrics`.
- The old docs claimed metrics exist, but source code does not match that claim.

Impact:

- Tests should fail when run as written.
- Observability claims are not defensible.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Implement Prometheus metrics in gateway. | Aligns tests, docs, and deploy claims. | Adds dependency and instrumentation work. | Yes |
| B | Delete the metrics test and remove metrics claims. | Honest and low effort. | Loses observability feature. | Acceptable if speed matters |
| C | Add a placeholder `/metrics`. | Makes tests pass cheaply. | Misleading unless real counters/histograms are added. | No |

Implementation files:

- `services/app-gateway/app.py`
- `services/app-gateway/requirements.txt`
- `services/app-gateway/tests/test_gateway.py`

Suggested metrics:

- `gateway_requests_total`
- `gateway_request_latency_seconds`
- `gateway_rag_errors_total`
- `gateway_redis_errors_total`
- `gateway_stream_errors_total`

### P1-4: Streaming Error Format Is Inconsistent

Current behavior:

- Frontend expects NDJSON events with `type: trace`, `type: token`, or `type: error`.
- Gateway streams upstream bytes directly.
- If RAG returns non-200 during streaming, gateway yields plain text: `Error from RAG Engine: ...`
- If the gateway stream itself fails, it yields plain text: `[Gateway Stream Error: ...]`.

Impact:

- Frontend JSON parsing can fail on non-NDJSON errors.
- Users can see partial or malformed responses.
- Error handling is harder to test.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Standardize all streaming responses as NDJSON events. | Clean client contract. | Requires gateway tests for streaming. | Yes |
| B | Use Server-Sent Events instead of NDJSON. | Browser-native event stream semantics. | Larger frontend/gateway rewrite. | Later |
| C | Keep plain text errors and make frontend tolerate them. | Small frontend-only fix. | Keeps backend contract messy. | No |

Implementation files:

- `services/app-gateway/app.py`
- `services/frontend/pages/index.tsx`
- Add gateway streaming tests.

### P1-5: Gateway Does Not Enforce Request Limits or Rate Limits

Current behavior:

- Pydantic validates request shape but not prompt length or conversation ID format.
- No rate limiting exists at the gateway.
- Frontend has a model max length check, but direct gateway/API calls bypass it.

Impact:

- Oversized prompts can stress RAG, Redis, and Ollama.
- Abusive repeated requests can overwhelm a local deployment.
- Arbitrary conversation IDs become Redis key suffixes.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Add Pydantic field constraints and Redis-backed rate limiting. | Strong practical protection. | Needs careful behavior during Redis outage. | Yes |
| B | Add only request size and ID validation. | Low effort, immediate protection. | No abuse throttling. | Good first step |
| C | Rely only on frontend validation. | No backend complexity. | Insufficient. | No |

Implementation files:

- `services/app-gateway/app.py`
- `services/app-gateway/tests/test_gateway.py`

Suggested constraints:

- Prompt max length aligned with frontend model max length.
- `conversation_id` restricted to UUID-like or safe slug characters.
- Request body size cap at proxy/gateway layer if an ingress is later added.

### P1-6: Gateway and Frontend Do Not Share a Durable Conversation Contract

Current behavior:

- Frontend stores conversations in localStorage.
- Gateway stores server-side history in Redis.
- Frontend sends full message history to `pages/api/chat.ts`, but that API route forwards only the latest message to the gateway.
- Gateway reconstructs history from Redis.

Impact:

- Browser local history and Redis history can diverge.
- Clearing frontend chat does not clear Redis history.
- If Redis is unavailable, follow-up behavior changes even though the frontend still has local history.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Make gateway the source of truth and add delete/reset history endpoints. | Clean state ownership. | Requires UI changes. | Yes |
| B | Forward recent frontend messages to gateway and stop relying on Redis for prompt context. | Better behavior during Redis outage. | Redis history becomes less authoritative. | Possible |
| C | Keep dual state but document it. | No code change. | Divergence remains. | No |

Implementation files:

- `services/frontend/pages/api/chat.ts`
- `services/frontend/pages/index.tsx`
- `services/app-gateway/app.py`

### P1-7: No Gateway Request Correlation ID

Current behavior:

- Logs use `print`.
- No request ID is created or propagated from frontend -> gateway -> RAG.

Impact:

- Debugging streaming failures or latency across services is difficult.
- CI sanity failures are harder to trace.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Generate or accept `X-Request-ID` in frontend API route and gateway, pass it to RAG. | Good low-cost traceability. | Requires minor cross-service changes. | Yes |
| B | Add structured logging only in gateway. | Improves one service. | No cross-service correlation. | Partial |
| C | Keep prints. | No effort. | Weak operational story. | No |

Implementation files:

- `services/frontend/pages/api/chat.ts`
- `services/app-gateway/app.py`
- Optional: `services/rag-engine/app.py`

### P2-8: Frontend Docker Uses Node 19 While Jenkins Uses Node 20

Current behavior:

- `services/frontend/Dockerfile` uses `node:19-alpine`.
- Jenkins declares `nodejs 'node20'`.

Impact:

- Build/test behavior can differ between CI and container image.
- Node 19 is not a conservative runtime target.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Move Dockerfile to `node:20-alpine`. | Aligns runtime with CI. | Rebuild required. | Yes |
| B | Move Jenkins to Node 19. | Aligns versions. | Bad direction. | No |
| C | Use an `.nvmrc` or Volta pin plus Docker update. | Best developer consistency. | Slight extra config. | Good |

Implementation files:

- `services/frontend/Dockerfile`
- Optional `.nvmrc`

## Plane 2: State, Cache, and Secret Plane

### P0-1: Redis Has No Persistence

Current behavior:

- Kubernetes Redis template has no PVC.
- Redis command does not enable AOF.
- Docker Compose Redis also has no volume and no AOF.
- Redis stores both chat history and semantic cache.

Impact:

- Pod/container restart loses chat history.
- Pod/container restart loses semantic cache.
- This directly weakens claims about resilient state.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Add Redis PVC and enable AOF with `--appendonly yes`. | Low effort and preserves data across restarts. | Local disk can grow. | Yes |
| B | Use RDB snapshots only. | Less write overhead. | More data loss window. | No for chat history |
| C | Treat Redis as disposable cache and move chat history elsewhere. | Cleaner cache semantics. | Requires new datastore. | Later |

Implementation files:

- `ansible/templates/redis.yaml.j2`
- `docker-compose.dev.yml`

### P0-2: Docker Compose Redis Cannot Support Semantic Cache

Current behavior:

- Compose uses `redis:7-alpine`.
- RAG engine startup calls RediSearch commands through `redis_client.ft(...)`.
- Kubernetes uses `redis/redis-stack-server:latest`, which includes RediSearch.

Impact:

- Local Compose behavior differs from Kubernetes.
- Semantic cache index creation fails in Compose unless Redis Stack is used.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Change Compose Redis to `redis/redis-stack-server` and load RediSearch consistently. | Aligns local and K8s. | Slightly heavier image. | Yes |
| B | Disable semantic cache in Compose with a feature flag. | Keeps Compose light. | Local behavior diverges. | No |
| C | Split gateway Redis and semantic Redis, using Redis Stack only for semantic cache. | Cleaner separation. | More services. | Later |

Implementation files:

- `docker-compose.dev.yml`
- Optional: `services/rag-engine/app.py` feature flag for semantic cache.

### P0-3: RAG Engine Receives Redis Password as Plaintext Environment Variable

Current behavior:

- App Gateway can read Redis password from Vault.
- RAG Engine reads `REDIS_PASSWORD` directly from environment.
- `ansible/templates/rag-engine.yaml.j2` injects `{{ redis_password }}` directly.
- RAG Engine template also includes Vault environment variables, but the code does not read Vault.

Impact:

- The "secrets through Vault" model is inconsistent.
- One service bypasses the stated secret-management boundary.
- A pod spec read reveals the Redis credential.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Add shared Vault config loader to RAG Engine and remove plaintext `REDIS_PASSWORD` from its template. | Makes architecture consistent. | More code and startup dependency. | Yes |
| B | Use Kubernetes Secret env vars for both gateway and RAG instead of Vault. | Simpler and idiomatic K8s. | Weakens Vault story. | Acceptable if Vault is overkill |
| C | Keep plaintext env vars and update docs. | Honest but weaker security. | Does not fix exposure. | No |

Implementation files:

- `services/rag-engine/app.py`
- Add or share config module.
- `ansible/templates/rag-engine.yaml.j2`
- `ansible/playbooks/roles/k8s_deploy/tasks/main.yml`

### P0-4: Vault Runs in Dev Mode With Static Root Token

Current behavior:

- Vault template sets `VAULT_DEV_ROOT_TOKEN_ID` from `vault_root_token`.
- Default token is `root-token`.
- Ansible uses that token to write secrets.
- Apps receive the same broad token.

Impact:

- Any leaked token has broad Vault authority.
- This is acceptable for local dev only, not a credible secure deployment model.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Keep dev mode but create a read-only policy/token for app services. | Improves least privilege while staying local-friendly. | Still dev mode. | Good first step |
| B | Enable Kubernetes auth method and bind service accounts to read-only policy. | Best security model for K8s. | More setup complexity. | Best target |
| C | Remove Vault and use Kubernetes Secrets. | Simpler. | Loses Vault learning objective. | Only if simplifying |

Implementation files:

- `ansible/templates/vault.yaml.j2`
- `ansible/playbooks/roles/k8s_deploy/tasks/main.yml`
- App deployment templates.

### P0-5: Redis Uses One Shared Password and No ACL Separation

Current behavior:

- Gateway and RAG Engine use the same Redis host, port, and password.
- Gateway uses `chat:*` keys.
- RAG Engine uses `cache:*` keys and `idx:semantic_cache`.
- No Redis ACL users are configured.

Impact:

- A compromised gateway can access semantic cache keys.
- A compromised RAG engine can access chat history.
- Access does not match natural key ownership boundaries.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Configure Redis ACL users: gateway user for `chat:*`, RAG user for `cache:*` and RediSearch commands. | Strong separation within one Redis. | ACL syntax and command permissions need testing. | Yes |
| B | Split Redis into `redis-session` and `redis-cache`. | Operationally simple isolation. | More pods/services/storage. | Strong alternative |
| C | Keep shared Redis/password. | Simple. | Weak boundary. | No |

Implementation files:

- `ansible/templates/redis.yaml.j2`
- `docker-compose.dev.yml`
- `services/app-gateway/config.py`
- `services/rag-engine/app.py`

### P1-6: Vault Contains Non-Secret Config Alongside Secret Data

Current behavior:

- Vault stores `RAG_ENGINE_URL`, `REDIS_HOST`, `REDIS_PORT`, and `REDIS_PASSWORD`.
- Only `REDIS_PASSWORD` is truly secret.

Impact:

- The boundary between config and secret is blurred.
- Rotating or auditing secrets becomes noisier.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Move URLs/ports to ConfigMap or plain env vars; keep only passwords/tokens in Vault. | Clean config model. | Requires template updates. | Yes |
| B | Keep all runtime config in Vault. | Centralized config. | Overstates what is secret. | Acceptable locally |
| C | Move everything to Kubernetes Secret. | Simple. | Less explicit config separation. | No |

Implementation files:

- `ansible/playbooks/roles/k8s_deploy/tasks/main.yml`
- `ansible/templates/app-gateway.yaml.j2`
- Optional ConfigMap template.

### P1-7: Vault Fallback Behavior Can Hide Misconfiguration

Current behavior:

- Gateway tries Vault, then falls back to environment variables.
- If Vault is down or secrets are stale, the gateway may still boot using defaults.

Impact:

- Production-like deployments can run with unintended credentials.
- A rotated password can break later in a confusing way.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Add `REQUIRE_VAULT=true` mode that fails startup if Vault cannot be read. | Strong deployment correctness. | Less convenient locally. | Yes |
| B | Keep fallback in dev only via `APP_ENV=dev`. | Balances local convenience and correctness. | More config. | Good |
| C | Keep current fallback. | Convenient. | Silent drift. | No |

Implementation files:

- `services/app-gateway/config.py`
- `services/app-gateway/tests/test_gateway.py`
- Deployment templates.

### P1-8: Redis Session History Has No TTL or Retention Policy

Current behavior:

- Gateway appends chat messages to `chat:{conversation_id}`.
- No expiry or max list length is applied.
- Semantic cache has TTL, but chat history does not.

Impact:

- Redis memory can grow indefinitely.
- Old chat data remains longer than needed.
- Persistence, once added, makes retention more important.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Apply `LTRIM` to cap messages and `EXPIRE` to set retention after each write. | Simple, practical. | Old conversations expire. | Yes |
| B | Store only last N messages permanently. | Predictable memory. | No long-term history. | Good |
| C | Move history to a database and keep Redis as cache. | Best long-term model. | Bigger change. | Later |

Implementation files:

- `services/app-gateway/app.py`
- `services/app-gateway/tests/test_gateway.py`

Suggested defaults:

- Keep last 50 messages per conversation.
- Expire conversation history after 7 or 30 days for local/demo use.

### P1-9: No Redis Resource Policy for Cache Growth

Current behavior:

- Semantic cache entries expire after 86400 seconds.
- Redis has no `maxmemory` or eviction policy in templates.
- Session history has no TTL today.

Impact:

- Redis memory behavior is not bounded.
- Adding persistence without memory policy can create disk/memory pressure.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Set `maxmemory` and an eviction policy appropriate for cache keys. | Predictable memory use. | Shared Redis makes policy harder. | Good with split Redis |
| B | Split Redis and apply cache eviction only to semantic cache Redis. | Cleanest. | More infrastructure. | Best target |
| C | Rely on Kubernetes memory limit. | Simple. | Can cause Redis eviction/crash behavior at the pod level. | No |

Implementation files:

- `ansible/templates/redis.yaml.j2`
- `docker-compose.dev.yml`

### P1-10: Vault Has No Persistence

Current behavior:

- Vault runs in dev mode.
- Ansible reprovisions secrets after deploying Vault.
- There is no Vault storage PVC.

Impact:

- Vault data is lost on restart.
- This is mostly masked because Ansible writes the expected secret again.
- It is still not a production-like secret store.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Keep dev Vault and document it as ephemeral. | Honest and simple. | Not production-like. | Good for local |
| B | Add file storage with PVC and non-dev initialization flow. | More realistic. | Much more operational work. | Later |
| C | Replace Vault with Kubernetes Secrets. | Removes fake production complexity. | Loses Vault integration. | Only if simplifying |

Implementation files:

- `ansible/templates/vault.yaml.j2`
- `ansible/playbooks/roles/k8s_deploy/tasks/main.yml`

### P2-11: Internal Traffic Is Plain HTTP

Current behavior:

- Gateway, RAG, ML service, Vault, Ollama, Qdrant, and Elasticsearch communicate over plain HTTP inside the cluster.

Impact:

- Acceptable for local Minikube.
- Not acceptable for a real multi-tenant cluster without compensating controls.

Options:

| Option | Change | Pros | Cons | Recommended |
| --- | --- | --- | --- | --- |
| A | Keep HTTP locally and state the boundary clearly. | Pragmatic. | No transport security. | Yes for now |
| B | Add service mesh mTLS. | Strongest. | Heavy for this project. | Later |
| C | Add app-level TLS manually between services. | More secure. | Certificate management complexity. | No |

## Recommended Implementation Order

### Week 1: High-Signal Fixes

1. Add gateway `/ready` and update readinessProbe.
2. Implement real gateway `/metrics` or remove the metrics tests and claims.
3. Add Redis PVC and AOF.
4. Change Docker Compose Redis to Redis Stack.
5. Standardize gateway streaming errors as NDJSON.

### Week 2: Security Boundary Fixes

1. Add NetworkPolicy templates.
2. Remove plaintext Redis password from RAG Engine by using Vault or Kubernetes Secret.
3. Add read-only Vault app token or Kubernetes auth.
4. Add Redis ACLs or split session/cache Redis.

### Week 3: State Ownership and Operational Polish

1. Add chat history TTL and `LTRIM`.
2. Add request limits and conversation ID validation.
3. Add request IDs and structured logs.
4. Align frontend Docker Node version with CI.

## Best Minimal Roadmap

If the goal is to make Plane 1 and Plane 2 credible quickly without overbuilding:

1. Gateway readiness must fail when Redis is unavailable.
2. Redis must persist data with PVC plus AOF.
3. Docker Compose must use Redis Stack to match Kubernetes semantic cache behavior.
4. RAG Engine must stop receiving the Redis password as plaintext env var.
5. Add NetworkPolicy for frontend -> gateway, gateway -> state/RAG, and RAG -> state/data/ML.
6. Either implement `/metrics` properly or remove metrics tests and documentation claims.

These six changes close the largest correctness, reliability, and security contradictions in the first two planes.
