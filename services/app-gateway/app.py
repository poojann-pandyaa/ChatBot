import os
import time
import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, List, Dict, Any
from redis import asyncio as aioredis
from fastapi.responses import StreamingResponse

from config import config

app = FastAPI(title="App Gateway API", version="1.0.0")

# Redis connection
redis_client = None


class UserChatRequest(BaseModel):
    prompt: str
    conversation_id: str
    debug: bool = False
    stream: bool = True


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
        # 1. Fetch history from Redis BEFORE pushing the new prompt
        history_msgs = []
        if redis_client:
            try:
                raw_history = await redis_client.lrange(f"chat:{request.conversation_id}", 0, -1)
                for msg in raw_history[-10:]:  # Keep last 10 messages for context window
                    role, _, content = msg.partition(":")
                    history_msgs.append({"role": role, "content": content})
            except Exception as e:
                print(f"Error fetching history: {e}")

        # 2. Log user message to Redis history
        if redis_client:
            await redis_client.rpush(f"chat:{request.conversation_id}", f"user:{request.prompt}")

        # 3. Query RAG engine (Streaming vs Standard)
        if request.stream:
            async def stream_generator():
                full_answer_list = []
                try:
                    async with httpx.AsyncClient(timeout=None) as client:
                        async with client.stream(
                            "POST",
                            f"{config.RAG_ENGINE_URL}/v1/reasoning-chat",
                            json={
                                "prompt": request.prompt,
                                "history": history_msgs,
                                "include_trace": request.debug,
                                "stream": True
                            }
                        ) as stream_res:
                            if stream_res.status_code != 200:
                                error_text = await stream_res.aread()
                                yield f"Error from RAG Engine: {error_text.decode()}".encode()
                                return

                            import json
                            buffer = ""
                            async for chunk in stream_res.aiter_bytes():
                                yield chunk
                                chunk_str = chunk.decode(errors="ignore")
                                buffer += chunk_str
                                while "\n" in buffer:
                                    line, buffer = buffer.split("\n", 1)
                                    line = line.strip()
                                    if not line:
                                        continue
                                    try:
                                        parsed = json.loads(line)
                                        if parsed.get("type") == "token":
                                            full_answer_list.append(parsed.get("data", ""))
                                    except Exception as parse_err:
                                        print(f"Failed to parse stream line in gateway: {parse_err}")

                            # Handle remaining buffer
                            if buffer.strip():
                                try:
                                    parsed = json.loads(buffer.strip())
                                    if parsed.get("type") == "token":
                                        full_answer_list.append(parsed.get("data", ""))
                                except Exception as parse_err:
                                    print(f"Failed to parse leftover stream line in gateway: {parse_err}")

                    # Write full assistant answer to Redis history
                    full_answer = "".join(full_answer_list)
                    if redis_client:
                        await redis_client.rpush(f"chat:{request.conversation_id}", f"assistant:{full_answer}")

                except Exception as stream_err:
                    print(f"Streaming error in App Gateway: {stream_err}")
                    yield f"\n[Gateway Stream Error: {stream_err}]".encode()

            return StreamingResponse(stream_generator(), media_type="application/x-ndjson")

        # Non-streaming path
        async with httpx.AsyncClient() as client:
            rag_response = await client.post(
                f"{config.RAG_ENGINE_URL}/v1/reasoning-chat",
                json={
                    "prompt": request.prompt,
                    "history": history_msgs,
                    "include_trace": request.debug,
                    "stream": False
                },
                timeout=300.0,
            )

        if rag_response.status_code != 200:
            raise HTTPException(
                status_code=rag_response.status_code,
                detail=f"RAG Engine returned error: {rag_response.text}"
            )

        result = rag_response.json()
        answer = result.get("answer", "")

        # Save assistant response to Redis
        if redis_client:
            await redis_client.rpush(f"chat:{request.conversation_id}", f"assistant:{answer}")

        return result

    except Exception as e:
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


@app.on_event("shutdown")
async def shutdown_event():
    if redis_client:
        print("Closing Redis client connection...")
        await redis_client.close()
