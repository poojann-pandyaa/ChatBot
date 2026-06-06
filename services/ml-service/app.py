import os
import re
import torch
import torch.nn.functional as F
import numpy as np
from typing import Optional, List, Dict, Any
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from transformers import AutoModelForSeq2SeqLM, AutoTokenizer, AutoModel, pipeline
from sentence_transformers import CrossEncoder

app = FastAPI(title="Reasoning RAG ML Service", version="1.0.0")

# Prompt and helper constants for Classifier
CLASSIFIER_PROMPT = """Classify the query into one reasoning type: commonsense, adaptive, or strategic.

commonsense = simple factual question with one direct answer (how to do X, what is X)
adaptive    = multi-part question: explains a concept AND asks when/how to use it
strategic   = direct comparison between two or more options (X vs Y, which is better)

Examples:
Query: How do I reverse a list in Python?
Intent: procedural
Reasoning Type: commonsense
Scope: single_topic
Sub-questions: How do I reverse a list in Python?

Query: What does git stash do?
Intent: factual
Reasoning Type: commonsense
Scope: single_topic
Sub-questions: What does git stash do?

Query: How do I read a file line by line in Python?
Intent: procedural
Reasoning Type: commonsense
Scope: single_topic
Sub-questions: How do I read a file line by line in Python?

Query: What is async/await and when should I use it?
Intent: conceptual
Reasoning Type: adaptive
Scope: multi_topic
Sub-questions: What is async/await in Python?, How does the event loop work with async/await?, When should you use async/await vs threading?

Query: What is LoRA and how do I implement it?
Intent: conceptual
Reasoning Type: adaptive
Scope: multi_topic
Sub-questions: What is LoRA fine-tuning?, How does LoRA reduce trainable parameters?, How do I implement LoRA with a transformer model?

Query: Explain the difference between list and tuple and which is faster
Intent: conceptual
Reasoning Type: adaptive
Scope: multi_topic
Sub-questions: What is the difference between list and tuple in Python?, Which is faster for instantiation and element access?, When should you use a tuple instead of a list?

Query: TCP vs UDP which should I use?
Intent: comparative
Reasoning Type: strategic
Scope: multi_topic
Sub-questions: What are the differences between TCP and UDP?, What are the tradeoffs of each?, When should you choose TCP vs UDP?

Query: SQL vs NoSQL for a high traffic web app
Intent: comparative
Reasoning Type: strategic
Scope: multi_topic
Sub-questions: What are the differences between SQL and NoSQL?, How does each perform under high traffic?, Which should you choose based on use case?

Query: multiprocessing vs multithreading in Python
Intent: comparative
Reasoning Type: strategic
Scope: multi_topic
Sub-questions: What is the difference between multiprocessing and multithreading?, What are the tradeoffs of each?, When should you use multiprocessing vs multithreading?

Now classify this query. Return ONLY the format shown, nothing else.

Query: {query}
Intent: <factual|procedural|comparative|conceptual|opinion|debugging>
Reasoning Type: <commonsense|adaptive|strategic>
Scope: <single_topic|multi_topic>
Sub-questions: <1-3 focused sub-questions separated by commas>"""

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
    query: str

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

print(f"ML Service starting up. Using device: {device}")

# Global model pointers
classifier_pipeline = None
embed_tokenizer = None
embed_model = None
reranker_model = None

@app.on_event("startup")
def load_models():
    global classifier_pipeline, embed_tokenizer, embed_model, reranker_model
    
    # 1. Load Classifier (Flan-T5)
    print("Loading google/flan-t5-base...")
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
    print("Loading BAAI/bge-base-en-v1.5...")
    emb_model_name = "BAAI/bge-base-en-v1.5"
    embed_tokenizer = AutoTokenizer.from_pretrained(emb_model_name)
    embed_model = AutoModel.from_pretrained(
        emb_model_name, low_cpu_mem_usage=True, torch_dtype=torch.float32
    ).to(device)
    embed_model.eval()
    
    # 3. Load Reranker (CrossEncoder)
    print("Loading cross-encoder/ms-marco-MiniLM-L-6-v2...")
    rerank_model_name = "cross-encoder/ms-marco-MiniLM-L-6-v2"
    reranker_model = CrossEncoder(rerank_model_name, device=device)
    print("All ML models loaded successfully.")


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


@app.post("/classify", response_model=ClassifyResponse)
def classify_endpoint(request: ClassifyRequest):
    try:
        prompt = CLASSIFIER_PROMPT.format(query=request.query)
        outputs = classifier_pipeline(prompt)
        response = outputs[0]["generated_text"].strip()

        parsed = {
            "intent": "factual",
            "reasoning_type": "commonsense",
            "entities": [],
            "scope": "single_topic",
            "ambiguity": "low",
            "sub_questions": [request.query],
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

        keyword_type = _keyword_fallback(request.query)
        if keyword_type and parsed["reasoning_type"] == "commonsense":
            parsed["reasoning_type"] = keyword_type
            parsed["scope"] = "multi_topic"
            if len(parsed["sub_questions"]) == 1:
                parsed["sub_questions"] = _generate_fallback_subquestions(
                    request.query, keyword_type
                )

        return parsed
    except Exception as e:
        print(f"Classification endpoint failed: {e}")
        keyword_type = _keyword_fallback(request.query) or "commonsense"
        return {
            "intent": "factual",
            "reasoning_type": keyword_type,
            "entities": [],
            "scope": "multi_topic" if keyword_type != "commonsense" else "single_topic",
            "ambiguity": "low",
            "sub_questions": [request.query],
        }


@app.post("/embed", response_model=EmbedResponse)
def embed_endpoint(request: EmbedRequest):
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
        print(f"Embed endpoint failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/rerank", response_model=RerankResponse)
def rerank_endpoint(request: RerankRequest):
    try:
        if not request.documents:
            return {"scores": []}
        pairs = [(request.query, doc) for doc in request.documents]
        scores = reranker_model.predict(pairs)
        return {"scores": [float(score) for score in scores]}
    except Exception as e:
        print(f"Rerank endpoint failed: {e}")
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/health")
def health():
    return {"status": "healthy"}
