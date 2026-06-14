import os
import json
import hashlib
import asyncio
import numpy as np
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, List, Dict, Any
from fastapi.responses import StreamingResponse
import redis.asyncio as redis
from redis.commands.search.field import VectorField, TextField
from redis.commands.search.indexDefinition import IndexDefinition, IndexType
from redis.commands.search.query import Query

from src.reasoning.classifier import QueryClassifier
from src.reasoning.engine import ReasoningEngine
from src.generation.trace import ReasoningTrace
from src.reasoning.router import RouterAgent

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
    try:
        res = await router.route(
            prompt=request.prompt,
            history=request.history,
            stream=request.stream,
            include_trace=request.include_trace,
            metrics_callback={}
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
        print(f"Error in reasoning-chat endpoint: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health():
    return {"status": "healthy"}


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
