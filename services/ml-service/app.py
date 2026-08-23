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
import asyncio
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
TOPIC_OVERLOAD_THRESHOLD = 3

import json

config_path = "/configs/classifier_rules.json"
if not os.path.exists(config_path):
    config_path = os.path.join(os.path.dirname(__file__), "../../configs/classifier_rules.json")

with open(config_path, "r") as f:
    rules = json.load(f)

STRATEGIC_VS_PATTERNS = rules["strategic_vs_patterns"]
STRATEGIC_NOUN_PAIRS = [tuple(pair) for pair in rules["strategic_noun_pairs"]]
ADAPTIVE_EXPLAIN_SIGNALS = rules["adaptive_explain_signals"]
ADAPTIVE_USAGE_SIGNALS = rules["adaptive_usage_signals"]

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
    return_bytes: bool = False

class EmbedResponse(BaseModel):
    embedding: Optional[List[float]] = None
    embedding_bytes: Optional[bytes] = None

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
    
    def init_models():
        try:
            _load_models(app_state=app.state)
            app.state.models_loaded = True
        except Exception as e:
            log.error("Model loading failed: %s", e, exc_info=True)
            global _models_load_error
            _models_load_error = str(e)
            app.state.load_error = str(e)
            # Do NOT re-raise — keep the process alive so /health can report the failure
            # to orchestrators (k8s readiness probe, docker-compose healthcheck).

    threading.Thread(target=init_models, daemon=True, name="model-loader").start()
    yield
    if getattr(app.state, "grpc_server", None):
        await asyncio.to_thread(app.state.grpc_server.stop(grace=15).wait)
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


def _split_topics(query: str) -> list[str]:
    """Split a query into distinct topic segments for topic-overload detection.

    Two branches:
    - If the query contains "?", split on "?" (question-delimited).
    - Otherwise, split on numbered-list markers (``\\d+[.)]\\s*``) or semicolons.

    Each segment is stripped of leading/trailing " ,.-" and kept only if it
    contains >= 2 words.

    Known limitations (see test_split_topics.py for details):
    - Decimal numbers (e.g. "3.2") can trigger false splits in the non-"?" branch
      because ``\\d+[.]\\s*`` matches "3." with zero trailing whitespace.
    - A single sentence containing a rhetorical "?" in quoted speech will produce
      two segments instead of one (case j in tests).
    - One-word topics ("Cats? Dogs?") are silently dropped by the >= 2-word filter
      even when they represent real distinct questions (case k in tests).
    """
    if "?" in query:
        # Fix j: only split on ? followed by whitespace or end-of-string so that
        # rhetorical ? inside quoted speech (e.g. 'He said "why?" and left.') does
        # not produce a spurious segment.
        segments = re.split(r'\?(?=\s|$)', query)
        min_words = 1  # Fix k: each surviving segment is a genuine question topic
    else:
        # Fix i: (?!\d) negative lookahead prevents splitting on decimal points like
        # "3.2" — the regex now only matches list markers (digit + dot + space),
        # not mid-number dots.
        segments = re.split(r'\d+\.(?!\d)\s*|\d+\)\s*|;', query)
        min_words = 2  # non-? branch still filters stray/artifact segments

    result = []
    for seg in segments:
        cleaned = seg.strip(" ,.-")
        if len(cleaned.split()) >= min_words:
            result.append(cleaned)
    return result


def _estimate_ambiguity(query: str) -> str:
    vague_markers = ["it", "this", "that", "thing", "stuff", "somehow"]
    word_count = len(query.split())
    has_vague = any(re.search(rf"\b{m}\b", query.lower()) for m in vague_markers)
    # Only flag as high ambiguity if the query is very short AND contains vague
    # pronouns/markers. Short-but-specific queries like "Explain socket programming"
    # should proceed to retrieval, not be blocked for clarification.
    if has_vague and word_count < 5:
        return "high"
    if word_count < 3:
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
        parse_failed = not any(k in response.lower() for k in ("intent:", "reasoning type:", "scope:"))
        if parsed["intent"] == "factual" and parsed["reasoning_type"] == "commonsense" and parsed["scope"] == "single_topic":
            if parse_failed:
                log.warning("Classifier model output did not match expected format. Raw output: %r", response)

        keyword_type = _keyword_fallback(query_text)
        if keyword_type and parse_failed:
            parsed["reasoning_type"] = keyword_type
            parsed["scope"] = "multi_topic"
            if len(parsed["sub_questions"]) == 1:
                parsed["sub_questions"] = _generate_fallback_subquestions(
                    query_text, keyword_type
                )
            
            # Increment fallback counter for Task 3
            count = getattr(app.state, "fallback_count", 0)
            app.state.fallback_count = count + 1

        # Topic-overload detection: if the query bundles too many unrelated questions,
        # short-circuit with a scope=topic_overload so the Java router requests clarification.
        topics = _split_topics(query_text)
        if len(topics) > TOPIC_OVERLOAD_THRESHOLD:
            parsed["scope"] = "topic_overload"
            parsed["sub_questions"] = topics
            log.info("Topic overload detected (%d topics): %r", len(topics), query_text)

        return parsed
    except Exception as e:
        log.error("Classification endpoint failed: %s", e, exc_info=True)
        keyword_type = _keyword_fallback(query_text) or "commonsense"
        topics = _split_topics(query_text)
        return {
            "intent": "factual",
            "reasoning_type": keyword_type,
            "entities": _extract_entities_heuristic(query_text),
            "scope": "topic_overload" if len(topics) > TOPIC_OVERLOAD_THRESHOLD else ("multi_topic" if keyword_type != "commonsense" else "single_topic"),
            "ambiguity": _estimate_ambiguity(query_text),
            "sub_questions": topics if len(topics) > TOPIC_OVERLOAD_THRESHOLD else [query_text],
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
        vector = emb.cpu().squeeze(0).numpy().astype("float32")
        if request.return_bytes:
            return {"embedding_bytes": vector.tobytes()}
        return {"embedding": vector.tolist()}
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

    HTTP 200 → Both models are loaded and gRPC server is running (Data plane is up).
    HTTP 503 → Models not loaded OR gRPC server is down (prevents split-brain routing).
    """
    loaded = getattr(http_request.app.state, "models_loaded", False)
    grpc_ok = getattr(http_request.app.state, "grpc_running", False)
    healthy = loaded and grpc_ok
    body = {
        "status": "healthy" if healthy else "unhealthy",
        "models_loaded": loaded,
        "grpc_running": grpc_ok,
        "fallback_count": getattr(http_request.app.state, "fallback_count", 0)
    }
    if not loaded:
        body["error"] = getattr(http_request.app.state, "load_error", None) or "models not loaded"
    if not grpc_ok:
        body["grpc_error"] = getattr(http_request.app.state, "grpc_error", None) or "gRPC not started"
    return JSONResponse(status_code=200 if healthy else 503, content=body)
