import os
import time
import json
import hashlib
import asyncio
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, List, Dict, Any
from fastapi.responses import Response, StreamingResponse
import redis.asyncio as redis
from redis.commands.search.field import VectorField, TextField
from redis.commands.search.indexDefinition import IndexDefinition, IndexType
from redis.commands.search.query import Query

from src.reasoning.classifier import QueryClassifier
from src.reasoning.engine import ReasoningEngine
from src.generation.trace import ReasoningTrace
from src.reasoning.router import RouterAgent

# Prometheus client imports
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST

app = FastAPI(title="Reasoning RAG Engine API", version="1.0.0")

# Initialize models
classifier = QueryClassifier()
engine = ReasoningEngine()

# Redis Semantic Cache initialization
redis_host = os.getenv("REDIS_HOST", "localhost")
redis_port = int(os.getenv("REDIS_PORT", 6379))
redis_password = os.getenv("REDIS_PASSWORD", "")

redis_client = redis.Redis(
    host=redis_host,
    port=redis_port,
    password=redis_password,
    decode_responses=True
)

redis_raw_client = redis.Redis(
    host=redis_host,
    port=redis_port,
    password=redis_password,
    decode_responses=False
)

@app.on_event("startup")
async def startup_event():
    print("Initialising Redis Semantic Cache Index...")
    try:
        await redis_client.ft("idx:semantic_cache").info()
        print("Semantic cache index already exists.")
    except Exception as e:
        print("Semantic cache index not found, creating it...")
        try:
            await redis_client.ft("idx:semantic_cache").create_index(
                fields=[
                    VectorField("embedding", "HNSW", {
                        "TYPE": "FLOAT32",
                        "DIM": 768,
                        "DISTANCE_METRIC": "COSINE"
                    }),
                    TextField("query"),
                    TextField("answer"),
                    TextField("reasoning_type"),
                    TextField("sources")
                ],
                definition=IndexDefinition(prefix=["cache:"], index_type=IndexType.HASH)
            )
            print("Semantic cache index created successfully.")
        except Exception as idx_err:
            print(f"Failed to create Redis Search index: {idx_err}")

async def save_to_cache(query: str, vector: list, answer: str, reasoning_type: str, sources: list):
    try:
        q_hash = hashlib.sha256(query.encode("utf-8")).hexdigest()
        key = f"cache:{q_hash}"
        embedding_bytes = np.array(vector, dtype=np.float32).tobytes()
        
        await redis_raw_client.hset(
            key,
            mapping={
                "embedding": embedding_bytes,
                "query": query.encode("utf-8"),
                "answer": answer.encode("utf-8"),
                "reasoning_type": reasoning_type.encode("utf-8"),
                "sources": json.dumps(sources).encode("utf-8")
            }
        )
        # Set TTL to 24 hours
        await redis_raw_client.expire(key, 86400)
        print(f"Saved query to semantic cache: {key}")
    except Exception as e:
        print(f"Failed to save to semantic cache: {e}")

# Prometheus Metrics
LATENCY_HISTOGRAM = Histogram(
    "rag_request_latency_seconds",
    "Time spent processing reasoning request",
    ["reasoning_type"]
)
CLASSIFY_LATENCY = Histogram(
    "rag_classification_latency_seconds",
    "Time spent in query classification"
)
RETRIEVAL_LATENCY = Histogram(
    "rag_retrieval_latency_seconds",
    "Time spent performing hybrid search and reranking"
)
GENERATION_LATENCY = Histogram(
    "rag_generation_latency_seconds",
    "Time spent in Ollama generation"
)
REQUEST_COUNTER = Counter(
    "rag_requests_total",
    "Total count of reasoning requests",
    ["reasoning_type", "status"]
)

# Router Agent metrics
FOLLOWUP_COUNTER = Counter(
    "rag_followup_detections_total",
    "Count of queries detected as follow-ups",
    ["detected"]  # "true" or "false"
)
RETRIEVAL_RETRY = Counter(
    "rag_retrieval_retries_total",
    "Count of retrieval quality gate retries",
    ["reason"]  # "low_relevance" or "no_results"
)
QUALITY_GATE_SCORE = Histogram(
    "rag_quality_gate_score",
    "Average reranker score at quality gate evaluation"
)
ROUTER_PATH = Counter(
    "rag_router_path_total",
    "Count of queries per router path",
    ["path"]  # "cache_hit", "simple_rag", "multi_step_rag", "retry_rag"
)


class ChatRequest(BaseModel):
    prompt: str
    history: Optional[List[Dict[str, str]]] = []
    include_trace: bool = False
    stream: bool = False


class SourceMetadata(BaseModel):
    chunk_id: int
    score: float
    question_id: str
    is_accepted: bool
    domain: str
    chunk_text: str


class ChatResponse(BaseModel):
    answer: str
    reasoning_type: str
    sources: List[SourceMetadata]
    trace: Optional[Dict[str, Any]] = None
def safe_decode(val) -> str:
    if val is None:
        return ""
    if isinstance(val, bytes):
        return val.decode("utf-8")
    return str(val)


router = RouterAgent(classifier, engine, redis_raw_client, safe_decode)


@app.post("/v1/reasoning-chat", response_model=ChatResponse)
async def reasoning_chat(request: ChatRequest):
    metrics_callback = {
        "latency_histogram": LATENCY_HISTOGRAM,
        "classify_latency": CLASSIFY_LATENCY,
        "retrieval_latency": RETRIEVAL_LATENCY,
        "generation_latency": GENERATION_LATENCY,
        "request_counter": REQUEST_COUNTER,
        "followup_counter": FOLLOWUP_COUNTER,
        "retry_counter": RETRIEVAL_RETRY,
        "quality_score_histogram": QUALITY_GATE_SCORE,
        "path_counter": ROUTER_PATH,
    }
    
    try:
        res = await router.route(
            prompt=request.prompt,
            history=request.history,
            stream=request.stream,
            include_trace=request.include_trace,
            metrics_callback=metrics_callback
        )
        
        if request.stream:
            return StreamingResponse(res["streaming_response"], media_type="application/x-ndjson")
            
        formatted_sources = [
            SourceMetadata(
                chunk_id=s.get("chunk_id"),
                score=s.get("score"),
                question_id=s.get("question_id"),
                is_accepted=s.get("is_accepted"),
                domain=s.get("domain"),
                chunk_text=s.get("chunk_text")
            )
            for s in res["sources"]
        ]
        
        return ChatResponse(
            answer=res["answer"],
            reasoning_type=res["reasoning_type"],
            sources=formatted_sources,
            trace=res["trace"]
        )
    except Exception as e:
        REQUEST_COUNTER.labels(reasoning_type="unknown", status="error").inc()
        print(f"Error in reasoning-chat endpoint: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health():
    return {"status": "healthy"}


@app.get("/metrics")
def metrics():
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.on_event("shutdown")
async def shutdown_event():
    print("Shutting down APIs and closing database client connections...")
    await engine.close()
    await classifier.close()
    await redis_client.aclose()
    await redis_raw_client.aclose()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
