import os
# Set before any torch/transformers import so MPS unsupported ops transparently
# fall back to CPU instead of raising NotImplementedError (e.g. aten::isin).
os.environ.setdefault("PYTORCH_ENABLE_MPS_FALLBACK", "1")
import re
import logging
import threading
import torch
import torch.nn.functional as F
import numpy as np
from contextlib import asynccontextmanager
from typing import Optional, List, Dict, Any
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, AutoModel, pipeline
from sentence_transformers import CrossEncoder

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s — %(message)s")
log = logging.getLogger("ml-service")

# Prompt and helper constants for Classifier
CLASSIFIER_PROMPT = """Classify the query below. Follow the exact format shown in the examples.

Query: What is the difference between a process and a thread?
Intent: conceptual
Reasoning Type: adaptive
Scope: single_topic
Sub-questions: What is a process?, What is a thread?, How do they differ?

Query: SQL vs NoSQL, which should I use for a high-write logging system?
Intent: comparative
Reasoning Type: strategic
Scope: multi_topic
Sub-questions: What are SQL's write characteristics?, What are NoSQL's write characteristics?, Which fits high-write logging?

Query: How do I fix a NullPointerException in Java?
Intent: debugging
Reasoning Type: commonsense
Scope: single_topic
Sub-questions: How do I fix a NullPointerException in Java?

Query: {query}
Intent:"""

VALID_REASONING_TYPES = {"commonsense", "adaptive", "strategic"}
VALID_INTENTS = {"factual", "procedural", "comparative", "conceptual", "opinion", "debugging"}

STRATEGIC_VS_PATTERNS = [
    " vs ",
    " versus ",
    " or ",
    "which is better",
    "which should i choose",
    "pros and cons of",
    "tradeoffs between",
    "compare and contrast",
]

STRATEGIC_NOUN_PAIRS = [
    ("tcp", "udp"),
    ("sql", "nosql"),
    ("multiprocessing", "multithreading"),
    ("process", "thread"),
    ("rest", "graphql"),
    ("docker", "kubernetes"),
    ("redis", "memcached"),
]

ADAPTIVE_EXPLAIN_SIGNALS = [
    "what is", "explain", "how does", "what are", "describe", "define",
    "difference between",
]
ADAPTIVE_USAGE_SIGNALS = [
    "when should", "when to use", "and when", "and how", "how to use",
    "and why", "should i use", "when do i", "which is faster", "which is better",
    "how do i implement", "how to implement",
]

# Pydantic models for request/response
class ClassifyRequest(BaseModel):
    query: Optional[str] = None
    question: Optional[str] = None

class ClassifyResponse(BaseModel):
    intent: str
    reasoning_type: str
    entities: List[str]
    scope: str
    ambiguity: str
    sub_questions: List[str]

class EmbedRequest(BaseModel):
    text: str

class EmbedResponse(BaseModel):
    embedding: List[float]

class RerankRequest(BaseModel):
    query: str
    documents: List[str]

class RerankResponse(BaseModel):
    scores: List[float]

# Device detection
device = "cuda" if torch.cuda.is_available() else ("mps" if torch.backends.mps.is_available() else "cpu")
pipe_device = device if device != "cpu" else -1

log.info("ML Service starting up. Using device: %s", device)

# Global model pointers
classifier_pipeline = None
embed_tokenizer = None
embed_model = None
reranker_model = None

# Global loading status — checked by endpoint guards so gRPC facade can also use them
_models_ready = False
_models_load_error = None


def _load_models(app_state=None):
    """Load all ML models synchronously. Called once at startup via the lifespan handler.

    ``app_state`` is an optional FastAPI ``app.state`` object passed from the lifespan
    handler so we can propagate gRPC startup status into /health.
    """
    global classifier_pipeline, embed_tokenizer, embed_model, reranker_model, _models_ready

    # 1. Load Classifier (Flan-T5)
    log.info("Loading google/flan-t5-base...")
    clf_model_name = "google/flan-t5-base"
    clf_model = AutoModelForSeq2SeqLM.from_pretrained(
        clf_model_name, low_cpu_mem_usage=True, torch_dtype=torch.float32
    )
    clf_tokenizer = AutoTokenizer.from_pretrained(clf_model_name)
    classifier_pipeline = pipeline(
        "text2text-generation",
        model=clf_model,
        tokenizer=clf_tokenizer,
        max_new_tokens=128,
        truncation=True,
        max_length=512,
        device=pipe_device,
    )

    # 2. Load Embedder (BGE)
    log.info("Loading BAAI/bge-base-en-v1.5...")
    emb_model_name = "BAAI/bge-base-en-v1.5"
    embed_tokenizer = AutoTokenizer.from_pretrained(emb_model_name)
    embed_model = AutoModel.from_pretrained(
        emb_model_name, low_cpu_mem_usage=True, torch_dtype=torch.float32
    ).to(device)
    embed_model.eval()

    # 3. Load Reranker (CrossEncoder)
    log.info("Loading cross-encoder/ms-marco-MiniLM-L-6-v2...")
    rerank_model_name = "cross-encoder/ms-marco-MiniLM-L-6-v2"
    reranker_model = CrossEncoder(rerank_model_name, device=device)
    log.info("All ML models loaded successfully.")
    _models_ready = True

    # Start gRPC server facade as a daemon thread (stops when FastAPI stops)
    grpc_port = int(os.environ.get("GRPC_PORT", "50051"))
    import grpc_server
    grpc_thread = threading.Thread(
        target=grpc_server.serve,
        args=(classify_endpoint, embed_endpoint, rerank_endpoint, grpc_port, app_state),
        daemon=True,
        name="grpc-server"
    )
    grpc_thread.start()
    log.info("gRPC facade started on port %s", grpc_port)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Modern FastAPI lifespan handler — replaces the deprecated @app.on_event pattern."""
    global _models_load_error
    app.state.models_loaded = False
    app.state.load_error = None
    app.state.grpc_running = False
    app.state.grpc_error = None
    try:
        _load_models(app_state=app.state)
        app.state.models_loaded = True
    except Exception as e:
        log.error("Model loading failed: %s", e, exc_info=True)
        _models_load_error = str(e)
        app.state.load_error = str(e)
        # Do NOT re-raise — keep the process alive so /health can report the failure
        # to orchestrators (k8s readiness probe, docker-compose healthcheck).
    yield
    log.info("ML Service shutting down.")


app = FastAPI(title="Reasoning RAG ML Service", version="1.0.0", lifespan=lifespan)

# Fallback helpers
def _keyword_fallback(query: str) -> Optional[str]:
    q = query.lower().strip()
    for a, b in STRATEGIC_NOUN_PAIRS:
        if a in q and b in q:
            return "strategic"
    for pattern in STRATEGIC_VS_PATTERNS:
        if pattern in q:
            if pattern == " or ":
                if re.search(r'\b\w+\s+or\s+\w+\b', q):
                    return "strategic"
            else:
                return "strategic"
    has_explain = any(sig in q for sig in ADAPTIVE_EXPLAIN_SIGNALS)
    has_usage = any(sig in q for sig in ADAPTIVE_USAGE_SIGNALS)
    if has_explain and has_usage:
        return "adaptive"
    return None

def _generate_fallback_subquestions(query: str, reasoning_type: str) -> list:
    q = query.strip().rstrip("?")
    if reasoning_type == "strategic":
        return [
            f"What are the key differences between the options in: {q}?",
            f"What are the tradeoffs for each option in: {q}?",
            f"What is the recommended choice and why for: {q}?",
        ]
    elif reasoning_type == "adaptive":
        return [
            f"What is the core concept in: {q}?",
            f"How does it work in practice: {q}?",
            f"When and why should you use it: {q}?",
        ]
    return [query]


def _extract_entities_heuristic(query: str) -> list[str]:
    # Capitalized words/phrases (proper nouns), excluding sentence-initial word
    words = query.split()
    entities = []
    for i, w in enumerate(words):
        clean = w.strip(".,?!:;\"'")
        if clean and clean[0].isupper() and i != 0:
            entities.append(clean)
    # Years / numbers
    entities += re.findall(r"\b\d{3,4}\b", query)
    # Dedup, preserve order
    seen = set()
    return [e for e in entities if not (e in seen or seen.add(e))]


def _estimate_ambiguity(query: str) -> str:
    vague_markers = ["it", "this", "that", "thing", "stuff", "somehow"]
    word_count = len(query.split())
    has_vague = any(re.search(rf"\b{m}\b", query.lower()) for m in vague_markers)
    if word_count < 5 or has_vague:
        return "high"
    if word_count < 10:
        return "medium"
    return "low"


@app.post("/classify", response_model=ClassifyResponse)
def classify_endpoint(request: ClassifyRequest):
    if not _models_ready:
        raise HTTPException(status_code=503, detail="Models not loaded")
    
    query_text = request.query or request.question or ""
    try:
        prompt = CLASSIFIER_PROMPT.format(query=query_text)
        outputs = classifier_pipeline(prompt)
        response = outputs[0]["generated_text"].strip()

        parsed = {
            "intent": "factual",
            "reasoning_type": "commonsense",
            "entities": [],
            "scope": "single_topic",
            "ambiguity": "low",
            "sub_questions": [query_text],
        }

        for line in response.split("\n"):
            line = line.strip()
            if not line:
                continue
            key, _, value = line.partition(":")
            key = key.strip().lower()
            value = value.strip().lower()

            if key == "intent" and value in VALID_INTENTS:
                parsed["intent"] = value
            elif key == "reasoning type":
                for rt in VALID_REASONING_TYPES:
                    if rt in value:
                        parsed["reasoning_type"] = rt
                        break
            elif key == "scope":
                parsed["scope"] = "multi_topic" if "multi" in value else "single_topic"
            elif key == "sub-questions":
                raw_sqs = line.split(":", 1)[1].strip()
                if raw_sqs:
                    sqs = [sq.strip() for sq in raw_sqs.split(",") if sq.strip()]
                    if sqs:
                        parsed["sub_questions"] = sqs

        # Heuristic enhancements (computed independently of model generation)
        parsed["entities"] = _extract_entities_heuristic(query_text)
        parsed["ambiguity"] = _estimate_ambiguity(query_text)

        # Log warning if the model output fails to match format
        if parsed["intent"] == "factual" and parsed["reasoning_type"] == "commonsense" and parsed["scope"] == "single_topic":
            if not any(k in response.lower() for k in ("intent:", "reasoning type:", "scope:")):
                log.warning("Classifier model output did not match expected format. Raw output: %r", response)

        keyword_type = _keyword_fallback(query_text)
        if keyword_type and parsed["reasoning_type"] == "commonsense":
            parsed["reasoning_type"] = keyword_type
            parsed["scope"] = "multi_topic"
            if len(parsed["sub_questions"]) == 1:
                parsed["sub_questions"] = _generate_fallback_subquestions(
                    query_text, keyword_type
                )

        return parsed
    except Exception as e:
        log.error("Classification endpoint failed: %s", e, exc_info=True)
        keyword_type = _keyword_fallback(query_text) or "commonsense"
        return {
            "intent": "factual",
            "reasoning_type": keyword_type,
            "entities": _extract_entities_heuristic(query_text),
            "scope": "multi_topic" if keyword_type != "commonsense" else "single_topic",
            "ambiguity": _estimate_ambiguity(query_text),
            "sub_questions": [query_text],
        }


@app.post("/embed", response_model=EmbedResponse)
def embed_endpoint(request: EmbedRequest):
    if not _models_ready:
        raise HTTPException(status_code=503, detail="Models not loaded")
    try:
        encoded = embed_tokenizer(
            [request.text],
            padding=True,
            truncation=True,
            max_length=256,
            return_tensors="pt",
        ).to(device)
        with torch.no_grad():
            output = embed_model(**encoded)
        emb = output.last_hidden_state[:, 0, :]       # CLS token
        emb = F.normalize(emb, p=2, dim=1)            # L2 normalise
        vector = emb.cpu().squeeze(0).numpy().astype("float32").tolist()
        return {"embedding": vector}
    except Exception as e:
        log.error("Embed endpoint failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/rerank", response_model=RerankResponse)
def rerank_endpoint(request: RerankRequest):
    if not _models_ready:
        raise HTTPException(status_code=503, detail="Models not loaded")
    try:
        if not request.documents:
            return {"scores": []}
        pairs = [(request.query, doc) for doc in request.documents]
        scores = reranker_model.predict(pairs)
        return {"scores": [float(score) for score in scores]}
    except Exception as e:
        log.error("Rerank endpoint failed: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
async def health(http_request: Request):
    """Returns service health including model-load status and gRPC facade status.

    HTTP 200 → models loaded (gRPC may still be down, check grpc_running field).
    HTTP 503 → models not loaded; service is starting or failed to start.
    """
    loaded = getattr(http_request.app.state, "models_loaded", False)
    grpc_ok = getattr(http_request.app.state, "grpc_running", False)
    body = {
        "status": "healthy" if loaded else "unhealthy",
        "grpc_running": grpc_ok,
    }
    if not loaded:
        body["error"] = getattr(http_request.app.state, "load_error", None) or "models not loaded"
    if not grpc_ok:
        body["grpc_error"] = getattr(http_request.app.state, "grpc_error", None) or "gRPC not started"
    return JSONResponse(status_code=200 if loaded else 503, content=body)
