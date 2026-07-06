# ChatBot MLOps - Local Deep-Dive Notes

This file is a local replacement for the removed stale repository documentation. It reflects the current source tree and is intended for local reference only.

## Current System Shape

The project is a local LLM/RAG platform with these major components:

- `services/frontend`: Next.js chatbot UI adapted from Chatbot UI.
- `services/app-gateway`: FastAPI gateway that stores chat history in Redis and proxies requests to the RAG engine.
- `services/rag-engine`: FastAPI orchestration service for routing, retrieval, reranking, generation, semantic cache, and trace output.
- `services/ml-service`: FastAPI ML service that hosts the classifier, embedding model, and cross-encoder reranker.
- `training/ingestion`: scripts that prepare and index StackExchange-style records into Qdrant and Elasticsearch.
- `ansible`: Kubernetes deployment templates and playbooks for Minikube.
- `ci/Jenkinsfile`: Jenkins pipeline for tests, image builds, scans, registry push, deployment, ingestion, and sanity verification.
- `docker-compose.dev.yml`: local Docker Compose stack for development.

The runtime is best understood as four working planes.

## Plane 1: Client, Gateway, and Routing Plane

### Components

- Frontend: `services/frontend`
- Next.js API route: `services/frontend/pages/api/chat.ts`
- App Gateway: `services/app-gateway/app.py`
- Gateway config: `services/app-gateway/config.py`

### Actual Request Flow

1. Browser submits a message to the Next.js UI.
2. The frontend API route reads the latest message from the request body.
3. `pages/api/chat.ts` forwards only the latest message to the gateway:
   - URL: `APP_GATEWAY_URL` or `http://app-gateway:8080`
   - Endpoint: `POST /api/chat`
   - Payload includes `prompt`, `conversation_id`, `debug: true`, and `stream: true`.
4. The App Gateway loads the last 10 Redis messages for that conversation before saving the new user message.
5. The gateway forwards the request to `RAG_ENGINE_URL/v1/reasoning-chat`.
6. For streaming requests, the gateway streams NDJSON chunks from the RAG engine back to the frontend and reconstructs token chunks so it can save the assistant response to Redis after streaming ends.
7. For non-streaming requests, the gateway returns the RAG engine JSON response and saves the assistant response to Redis.

### Implemented Endpoints

- `POST /api/chat`: main gateway endpoint.
- `GET /api/history/{conversation_id}`: returns Redis-backed conversation history if Redis is connected.
- `GET /health`: returns gateway health plus Redis connection status.

### Important Observations

- The gateway is mostly stateless, but chat continuity depends on Redis.
- If Redis connection fails, the gateway continues in stateless mode instead of failing startup.
- The gateway reads Vault config at import time through `config.py`, but falls back to environment variables if Vault fails.
- The frontend still contains many inherited Chatbot UI components, but the active chat API is wired to the local gateway, not OpenAI.
- `pages/api/models.ts` returns one UI model entry named `Gemma 2B (Local)`.

## Plane 2: State, Cache, and Secret Plane

### Components

- Redis for chat history: used by `services/app-gateway/app.py`.
- Redis Stack / RediSearch semantic cache: used by `services/rag-engine/app.py` and `src/reasoning/router.py`.
- Vault dev server: configured in Compose and Ansible; read by the app gateway config.

### Redis Usage

The gateway stores conversation history in Redis lists:

- Key format: `chat:{conversation_id}`
- User messages are appended as `user:{prompt}`.
- Assistant messages are appended as `assistant:{answer}`.
- The gateway includes the last 10 messages as history when calling the RAG engine.

The RAG engine uses Redis as a semantic cache:

- Index name: `idx:semantic_cache`
- Key prefix: `cache:`
- Embedding field: 768-dimensional `FLOAT32` HNSW vector.
- Cache lookup: KNN 1 vector search.
- Cache threshold:
  - `0.05` for commonsense or unknown.
  - `0.08` for adaptive or strategic.
- Cache TTL: 86400 seconds.

### Vault Usage

The gateway `Config` class attempts to read these values from Vault:

- `RAG_ENGINE_URL`
- `REDIS_HOST`
- `REDIS_PORT`
- `REDIS_PASSWORD`

The Ansible playbook provisions those values into `secret/{{ vault_secret_path }}`.

### Important Mismatches

- `docker-compose.dev.yml` uses `redis:7-alpine`, but the RAG engine semantic cache requires RediSearch commands through `redis_client.ft(...)`. The Kubernetes template uses `redis/redis-stack-server:latest`, which matches the semantic cache requirement better.
- The RAG engine Kubernetes template includes Vault environment variables, but the RAG engine code does not currently read Vault through `hvac`.
- Vault is a dev-mode service with a static root token. This is acceptable for a local Minikube demo, not production secret management.

## Plane 3: Reasoning, Retrieval, and Generation Plane

### Components

- API entrypoint: `services/rag-engine/app.py`
- Router: `services/rag-engine/src/reasoning/router.py`
- Reasoning paths: `services/rag-engine/src/reasoning/engine.py`
- Classifier HTTP client: `services/rag-engine/src/reasoning/classifier.py`
- Retrieval: `services/rag-engine/src/retrieval/hybrid_search.py`
- Reranking client: `services/rag-engine/src/retrieval/reranker.py`
- Generator: `services/rag-engine/src/generation/generator.py`
- Trace model: `services/rag-engine/src/generation/trace.py`

### Actual RAG Pipeline

1. `POST /v1/reasoning-chat` receives `prompt`, optional `history`, `include_trace`, and `stream`.
2. `RouterAgent` creates a `ReasoningTrace`.
3. The follow-up detector checks whether the prompt depends on chat history.
4. Follow-up prompts are rewritten through Ollama before retrieval.
5. The rewritten query is embedded by calling `ml-service /embed`.
6. The semantic cache is checked in Redis before classification and retrieval.
7. The query is classified by calling `ml-service /classify`.
8. The reasoning engine chooses one path:
   - `commonsense`: retrieve main query, rerank top 5, generate.
   - `adaptive`: retrieve sub-questions concurrently, rerank each, deduplicate, generate.
   - `strategic`: retrieve main query plus sub-questions, rerank sub-question results, deduplicate, generate.
9. A quality gate evaluates the reranked candidates.
10. If quality is low, the router refines the query, retries retrieval, merges candidates, and reranks again.
11. The final prompt is built from the top retrieved chunks, history, sub-questions, and reasoning-specific instructions.
12. Generation is delegated to Ollama `/api/generate`.
13. The response and sources are cached asynchronously in Redis.

### Retrieval Details

Hybrid retrieval performs:

- Query embedding via `ml-service /embed`.
- Dense search against Qdrant collection `stackexchange_chunks`.
- Sparse search against Elasticsearch index `stackexchange_chunks`.
- Reciprocal Rank Fusion with score `1 / (60 + rank + 1)`.

Sparse search currently uses a basic Elasticsearch `match` query on `chunk_text`.

### Generation Details

The generator calls Ollama with:

- Default model: `gemma2:2b`
- Temperature: `0.2`
- Top-p: `0.9`
- Context window option: `4096`
- Max prediction option: `2048`

If Ollama is unavailable, the generator returns mock output for development and integration testing.

### Important Observations

- The RAG engine does not load classifier, embedding, or reranker models locally. It calls `ml-service` for those operations.
- The old documentation's "self-consistency decoding" claim is effectively disabled because strategic generation uses `n=1`.
- The RAG engine exposes `/health`, but no `/metrics` endpoint exists in the current code.
- Streaming responses are NDJSON with `trace`, `token`, and `error` event types.

## Plane 4: ML Compute, Data, and Indexing Plane

### Components

- ML service: `services/ml-service/app.py`
- Qdrant: dense vector store.
- Elasticsearch: sparse BM25 text index.
- Ollama: local LLM inference service.
- Ingestion script: `training/ingestion/run_ingestion.py`

### ML Service Responsibilities

The ML service loads three model families during startup:

- Classifier: `google/flan-t5-base`
- Embedder: `BAAI/bge-base-en-v1.5`
- Reranker: `cross-encoder/ms-marco-MiniLM-L-6-v2`

It exposes:

- `POST /classify`
- `POST /embed`
- `POST /rerank`
- `GET /health`

The classifier includes heuristic fallback logic for strategic and adaptive queries. The embedder returns normalized 768-dimensional vectors. The reranker scores query/document pairs and returns numeric cross-encoder scores.

### Ingestion Flow

`training/ingestion/run_ingestion.py`:

1. Reads `data/processed_dataset.jsonl`.
2. Filters answers using accepted-answer status or score threshold.
3. Builds chunks with question title and answer body.
4. Recreates Qdrant collection `stackexchange_chunks` with 768-dimensional cosine vectors.
5. Recreates Elasticsearch index `stackexchange_chunks` with BM25 text mapping for `chunk_text`.
6. Calls `ml-service /embed` concurrently with `asyncio.Semaphore(10)`.
7. Uploads vectors to Qdrant and metadata documents to Elasticsearch in batches.

### Data/Inference Services

Kubernetes templates define:

- Qdrant with persistent volume storage.
- Elasticsearch with persistent volume storage and single-node settings.
- Ollama with persistent model storage and resource limits.
- ML service with offline Hugging Face environment variables.

### Important Mismatches

- `ci/Jenkinsfile` builds only `rag-engine`, `app-gateway`, and `frontend`. It does not build or push `ml-service`.
- `ansible/templates/ml-service.yaml.j2` hardcodes `poojan/ml-service:latest` instead of using `{{ docker_registry }}/ml-service:{{ image_tag }}`.
- The Ansible rollout restart loop restarts `rag-engine`, `app-gateway`, and `frontend`, but not `ml-service`.
- The Docker Compose Ollama image and Kubernetes Ollama image versions differ.
- Docker Compose exposes frontend on host port `3000`; Kubernetes exposes it as NodePort `30080`.

## CI/CD and Deployment Plane

This is not one of the four runtime planes, but it controls how the system is built and deployed.

### Jenkins Pipeline

`ci/Jenkinsfile` performs:

1. Git checkout.
2. Python virtual environment creation.
3. Python dependency installation.
4. RAG engine tests.
5. App gateway tests.
6. Trivy filesystem scan against `services/`.
7. Parallel Docker builds for RAG engine, app gateway, and frontend.
8. Parallel Trivy image scans.
9. Docker registry push.
10. Ansible deployment.
11. Port-forwarded ingestion run with `--limit 1000`.
12. Port-forwarded gateway sanity check.

### Ansible Deployment

`ansible/playbooks/deploy.yml` calls the `k8s_deploy` role. The role:

1. Renders all Jinja2 Kubernetes templates into `ansible/playbooks/build`.
2. Applies the namespace.
3. Deploys Vault, Redis, Qdrant, Elasticsearch, and Ollama.
4. Waits for Vault readiness.
5. Provisions gateway/Redis connection data into Vault.
6. Deploys ML service, RAG engine, app gateway, and frontend.
7. Restarts selected application deployments.
8. Waits for application rollout readiness.

### Key Deployment Risk

The deployment and CI path should be updated before relying on this as a complete reproducible pipeline. The current strongest gap is `ml-service`: it is essential at runtime, but the Jenkins build/push/restart path does not manage it consistently.

## Source-Accurate Architecture Summary

Browser/UI -> Next.js API route -> App Gateway -> RAG Engine -> ML Service, Redis, Qdrant, Elasticsearch, Ollama.

Redis has two separate responsibilities:

- Gateway chat history.
- RAG semantic response cache.

Vault currently has one effective code consumer:

- App Gateway config.

The RAG engine orchestrates reasoning, but the heavy local ML models live in `ml-service`, while final text generation lives in Ollama.

## Recommended Cleanup Tasks

1. Decide whether Docker Compose should use `redis/redis-stack-server` so semantic cache works locally.
2. Add ML service image build, scan, push, and rollout restart steps to Jenkins.
3. Parameterize the ML service image in the Ansible template.
4. Either remove unused Vault environment variables from RAG engine or implement Vault loading there.
5. Add real `/metrics` endpoints only if Prometheus metrics are actually required.
6. Consider adding a tracked, source-accurate README later after the implementation gaps above are resolved.
