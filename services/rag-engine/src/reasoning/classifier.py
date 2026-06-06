import asyncio
import os
import re
from typing import Optional

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


import httpx

class QueryClassifier:
    def __init__(self, ml_service_url: str = None):
        self.ml_service_url = ml_service_url or os.getenv("ML_SERVICE_URL", "http://localhost:8000")
        self.client = httpx.AsyncClient(base_url=self.ml_service_url, timeout=30.0)
        print(f"QueryClassifier initialized. ML Service URL: {self.ml_service_url}")

    async def classify(self, query: str) -> dict:
        try:
            response = await self.client.post("/classify", json={"query": query})
            if response.status_code == 200:
                return response.json()
            else:
                print(f"ML Service returned status code {response.status_code}: {response.text}")
                raise Exception(f"ML Service error: {response.text}")
        except Exception as e:
            print(f"Classification failed: {e}")
            keyword_type = _keyword_fallback(query) or "commonsense"
            return {
                "intent": "factual",
                "reasoning_type": keyword_type,
                "entities": [],
                "scope": "multi_topic" if keyword_type != "commonsense" else "single_topic",
                "ambiguity": "low",
                "sub_questions": [query],
            }

    async def close(self):
        await self.client.aclose()

