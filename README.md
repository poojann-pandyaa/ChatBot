# ChatBot MLOps

A production-grade AI chat system built on a **RAG (Retrieval-Augmented Generation)** pipeline with full MLOps infrastructure. Users ask questions and receive answers grounded in a Stack Overflow Q&A knowledge base, with streaming and multi-step reasoning support.

---

## Architecture

```
Browser (Next.js)
      │
      ▼
app-gateway  ──gRPC──▶  rag-engine  ──gRPC──▶  ml-service (Python)
(Spring WebFlux)         (Spring WebFlux)        (Flan-T5 + BGE + CrossEncoder)
      │                       │
      │                  Qdrant (vector)
      │                  Elasticsearch (BM25)
      │                  Ollama (generation)
      │
  Redis (cache + history)
  PostgreSQL (conversations + outbox)
  Kafka (async events via Outbox pattern)
```

### Services

| Service | Language | Role |
|---|---|---|
| `frontend` | Next.js / TypeScript | Chat UI with streaming NDJSON support |
| `app-gateway` | Java / Spring WebFlux | API gateway, Redis history, Outbox pattern |
| `rag-engine` | Java / Spring WebFlux | RAG pipeline orchestration, gRPC server |
| `ml-service` | Python / FastAPI + gRPC | Embedding (BGE), reranking (CrossEncoder), classification (Flan-T5) |

### RAG Pipeline

1. **Query rewrite** — follow-up questions are rewritten to standalone queries
2. **Semantic cache** — Redis vector search (KNN) checks for near-identical prior answers
3. **Classification** — Flan-T5 classifies reasoning type: `commonsense`, `adaptive`, or `strategic`
4. **Retrieval** — Hybrid dense (Qdrant) + sparse (Elasticsearch BM25) with Reciprocal Rank Fusion
5. **Reranking** — CrossEncoder reranks top candidates
6. **Quality gate** — low-relevance results trigger a retry with a refined query
7. **Generation** — Ollama (Gemma 2B) generates a grounded, cited answer
8. **Cache write** — result is saved to semantic cache for future reuse

---

## Quick Start

### Prerequisites
- Docker Desktop
- At least 8 GB RAM available to Docker

### Run

```bash
docker compose -f docker-compose.dev.yml up --build
```

Services start at:

| Service | URL |
|---|---|
| Frontend | http://localhost:3000 |
| App Gateway API | http://localhost:8080 |
| RAG Engine | http://localhost:8000 |

> **Note:** On first run, `ml-service` downloads ~1.5 GB of ML models. Allow 5–10 minutes for full readiness.

### Ingest Data

Before chatting, populate the vector and keyword indices:

```bash
cd training

# 1. Download & preprocess Stack Overflow dump
python ingestion/preprocess.py

# 2. Index into Qdrant (dense) and Elasticsearch (sparse)
python ingestion/run_ingestion.py
```

---

## API Reference

### `POST /api/chat`

```json
{
  "prompt": "What is the difference between list and tuple in Python?",
  "conversation_id": "session-abc",
  "userId": "user-123",
  "stream": true,
  "debug": false
}
```

**Non-streaming response (`stream: false`):**
```json
{
  "answer": "Lists are mutable, tuples are immutable...",
  "reasoning_type": "commonsense",
  "sources": [{ "chunk_id": 42, "score": 0.91, "domain": "python" }]
}
```

**Streaming response (`stream: true`):** NDJSON lines of:
```json
{"type": "trace", "data": { "reasoning_type": "commonsense", "sources": [...] }}
{"type": "token",  "data": "Lists"}
{"type": "token",  "data": " are"}
```

### `GET /api/history/{conversationId}`

Returns the full message history for a conversation from PostgreSQL.

### `GET /health` · `GET /ready`

Health and readiness probes (used by Docker and Kubernetes).

---

## Project Structure

```
.
├── docker-compose.dev.yml      # Full local dev stack
├── proto/                      # gRPC .proto definitions (shared)
├── services/
│   ├── app-gateway/            # Java Spring WebFlux gateway
│   ├── rag-engine/             # Java RAG pipeline + gRPC server
│   ├── ml-service/             # Python ML models gRPC server
│   └── frontend/               # Next.js chat UI
├── training/
│   ├── ingestion/              # Data preprocessing & indexing scripts
│   ├── train.py                # Fine-tuning script (LoRA)
│   ├── export_gguf.py          # Export model to GGUF for Ollama
│   └── register_model.py       # Register model to MLflow
├── configs/                    # Shared taxonomy config
├── ansible/                    # Infrastructure provisioning playbooks
└── ci/                         # Jenkinsfile CI/CD pipeline
```

---

## Kafka Topology

The platform uses Redpanda (Kafka-compatible) event-streaming backbone for asynchronous side-effects (semantic cache updates, usage counters, and read-model builders). 

The following topics are explicitly configured and managed:

| Topic Name | Partitions | Replica Factor | Retention Period | Consumer Groups | Purpose |
|---|---|---|---|---|---|
| `chat-completed` | 3 | 1 (Dev) | 7 days (`604800000` ms) | `cache-update-group`, `usage-analytics-group`, `read-model-group` | Main chat-completed event backbone. Decoupled from the request path. |
| `chat-completed.DLT` | 1 | 1 (Dev) | 14 days (`1209600000` ms) | `dlq-monitoring-group` | Dead Letter Queue (DLQ). Receives poison-pill messages after 3 failed retries. |

---

## Key Design Patterns

| Pattern | Where | Purpose |
|---|---|---|
| Transactional Outbox | `app-gateway` | Guarantees DB + Kafka consistency — never lose an event |
| Semantic Cache | `rag-engine` (Redis KNN) | Avoids redundant LLM calls for similar queries |
| Reciprocal Rank Fusion | `rag-engine` | Combines dense + sparse retrieval without score calibration |
| Circuit Breaker + Retry | All gRPC clients | Resilience4j — auto-fallback when upstream services are slow |
| CQRS | `app-gateway` | Redis read model (fast history) + PostgreSQL command store |
| Database Sharding | `app-gateway` | Per-user shard routing for horizontal PostgreSQL scaling |

---

## Fine-tuning

To fine-tune the generation model on your own data:

```bash
cd training

# Prepare fine-tuning dataset from Stack Overflow
python ingestion/prepare_finetune.py

# Train with LoRA (GPU) or MLX (Apple Silicon)
python train.py        # GPU
python train_mlx.py    # Apple Silicon

# Export for Ollama
python export_gguf.py

# Register to MLflow
python register_model.py
```

---

## CI/CD

Jenkins pipeline defined in [`ci/Jenkinsfile`](ci/Jenkinsfile):

1. Build & test all services
2. Build Docker images
3. Push to registry
4. Deploy to Kubernetes via Ansible

Teardown pipeline: [`ci/Jenkinsfile.destroy`](ci/Jenkinsfile.destroy)
