import os
import time
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import Optional, List, Dict, Any

from src.reasoning.classifier import QueryClassifier
from src.reasoning.engine import ReasoningEngine
from src.generation.trace import ReasoningTrace

# Prometheus client imports
from prometheus_client import Counter, Histogram, generate_latest, CONTENT_TYPE_LATEST
from fastapi.responses import Response

app = FastAPI(title="Reasoning RAG Engine API", version="1.0.0")

# Initialize models
classifier = QueryClassifier()
engine = ReasoningEngine()

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


class ChatRequest(BaseModel):
    prompt: str
    include_trace: bool = False


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


@app.post("/v1/reasoning-chat", response_model=ChatResponse)
async def reasoning_chat(request: ChatRequest):
    start_time = time.time()
    reasoning_type = "unknown"
    
    try:
        # 1. Classification
        t0 = time.time()
        classification = classifier.classify(request.prompt)
        CLASSIFY_LATENCY.observe(time.time() - t0)
        
        reasoning_type = classification.get("reasoning_type", "commonsense")
        
        # 2. Setup Trace
        trace = ReasoningTrace(request.prompt)
        trace.classification = classification
        
        # 3. Execution
        t1 = time.time()
        trace = await engine.execute(trace)
        GENERATION_LATENCY.observe(time.time() - t1)
        
        # Calculate retrieval/rerank latency (total minus classify and generate)
        retrieval_time = (time.time() - start_time) - (time.time() - t1) - (t1 - t0)
        RETRIEVAL_LATENCY.observe(max(retrieval_time, 0.0))
        
        # Format sources response
        sources = []
        for c in trace.reranked_final:
            meta = c["metadata"]
            sources.append(
                SourceMetadata(
                    chunk_id=c["chunk_id"],
                    score=c.get("final_score", c.get("score", 0.0)),
                    question_id=meta.get("question_id", ""),
                    is_accepted=meta.get("is_accepted", False),
                    domain=meta.get("domain", ""),
                    chunk_text=meta.get("chunk_text", "")
                )
            )
            
        LATENCY_HISTOGRAM.labels(reasoning_type=reasoning_type).observe(time.time() - start_time)
        REQUEST_COUNTER.labels(reasoning_type=reasoning_type, status="success").inc()
        
        return ChatResponse(
            answer=trace.final_answer,
            reasoning_type=reasoning_type,
            sources=sources,
            trace=trace.to_dict() if request.include_trace else None
        )
        
    except Exception as e:
        REQUEST_COUNTER.labels(reasoning_type=reasoning_type, status="error").inc()
        print(f"Error in reasoning-chat endpoint: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health():
    # Basic health check endpoint for K8s liveness/readiness
    return {"status": "healthy"}


@app.get("/metrics")
def metrics():
    # Prometheus scrape endpoint
    return Response(content=generate_latest(), media_type=CONTENT_TYPE_LATEST)


@app.on_event("shutdown")
async def shutdown_event():
    print("Shutting down APIs and closing database client connections...")
    await engine.close()


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)

