import os
import time
import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, List, Dict, Any
from redis import asyncio as aioredis

from config import config

# Prometheus client imports
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
from fastapi.responses import Response

app = FastAPI(title="App Gateway API", version="1.0.0")

# Redis connection
redis_client = None

# Prometheus Metrics
LATENCY_HISTOGRAM = Histogram(
    "gateway_request_latency_seconds",
    "Time spent processing gateway request"
)
REQUEST_COUNTER = Counter(
    "gateway_requests_total",
    "Total count of gateway requests",
    ["status"]
)


class UserChatRequest(BaseModel):
    prompt: str
    conversation_id: str
    debug: bool = False


@app.on_event("startup")
async def startup_event():
    global redis_client
    print(f"Connecting to Redis at {config.REDIS_HOST}:{config.REDIS_PORT}...")
    try:
        redis_client = aioredis.Redis(
            host=config.REDIS_HOST,
            port=config.REDIS_PORT,
            password=config.REDIS_PASSWORD if config.REDIS_PASSWORD else None,
            decode_responses=True
        )
        await redis_client.ping()
        print("Successfully connected to Redis.")
    except Exception as e:
        print(f"Redis connection failed: {e}. Gateway will run in stateless mode.")


@app.post("/api/chat")
async def chat(request: UserChatRequest):
    start_time = time.time()
    try:
        # 1. Log to Redis history if Redis is available
        if redis_client:
            await redis_client.rpush(f"chat:{request.conversation_id}", f"user:{request.prompt}")
            
        # 2. Query stateless RAG engine
        async with httpx.AsyncClient() as client:
            rag_response = await client.post(
                f"{config.RAG_ENGINE_URL}/v1/reasoning-chat",
                json={
                    "prompt": request.prompt,
                    "include_trace": request.debug
                },
                timeout=120.0,
            )
            
        if rag_response.status_code != 200:
            raise HTTPException(
                status_code=rag_response.status_code, 
                detail=f"RAG Engine returned error: {rag_response.text}"
            )
            
        result = rag_response.json()
        answer = result.get("answer", "")
        
        # 3. Save assistant response to Redis if available
        if redis_client:
            await redis_client.rpush(f"chat:{request.conversation_id}", f"assistant:{answer}")
            
        LATENCY_HISTOGRAM.observe(time.time() - start_time)
        REQUEST_COUNTER.labels(status="success").inc()
        
        return result
        
    except Exception as e:
        REQUEST_COUNTER.labels(status="error").inc()
        print(f"Error in App Gateway: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/history/{conversation_id}")
async def get_history(conversation_id: str):
    if not redis_client:
        return {"conversation_id": conversation_id, "messages": []}
        
    try:
        messages = await redis_client.lrange(f"chat:{conversation_id}", 0, -1)
        formatted_messages = []
        for msg in messages:
            role, _, content = msg.partition(":")
            formatted_messages.append({"role": role, "content": content})
        return {
            "conversation_id": conversation_id,
            "messages": formatted_messages
        }
    except Exception as e:
        print(f"Failed to fetch history from Redis: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health():
    redis_ok = False
    if redis_client:
        try:
            await redis_client.ping()
            redis_ok = True
        except Exception:
            pass
            
    return {
        "status": "healthy",
        "redis_connected": redis_ok
    }


@app.get("/metrics")
def metrics():
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.on_event("shutdown")
async def shutdown_event():
    if redis_client:
        print("Closing Redis client connection...")
        await redis_client.close()
