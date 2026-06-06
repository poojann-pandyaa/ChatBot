import json
import time
import asyncio
import numpy as np
from typing import Dict, Any, List, Optional
from pydantic import BaseModel

from src.reasoning.followup_detector import FollowupDetector
from src.reasoning.quality_gate import RetrievalQualityGate
from src.generation.trace import ReasoningTrace

class RouterAgent:
    def __init__(self, classifier, engine, redis_raw_client, safe_decode_func):
        self.classifier = classifier
        self.engine = engine
        self.redis_raw_client = redis_raw_client
        self.safe_decode = safe_decode_func
        self.followup_detector = FollowupDetector()
        self.quality_gate = RetrievalQualityGate()

    async def _embed_query(self, query: str) -> list:
        return await self.engine.retriever._embed(query)

    async def _check_cache(self, q_vector: list, query: str, reasoning_type: str = "commonsense") -> Optional[dict]:
        try:
            query_vector = np.array(q_vector, dtype=np.float32).tobytes()
            # RediSearch KNN search
            from redis.commands.search.query import Query
            q = Query("*=>[KNN 1 @embedding $vec_param AS score]").sort_by("score").dialect(2)
            res = await self.redis_raw_client.ft("idx:semantic_cache").search(q, query_params={"vec_param": query_vector})
            
            if res.docs:
                doc = res.docs[0]
                score = float(doc.score)
                # Adaptive threshold: code/commonsense queries require tighter bounds (0.05)
                # Concept/strategic/adaptive can tolerate slightly looser bounds (0.08)
                threshold = 0.05 if reasoning_type in ("commonsense", "unknown") else 0.08
                if score <= threshold:
                    print(f"[RouterAgent] Semantic Cache HIT! Score: {score} (threshold: {threshold})")
                    return {
                        "answer": self.safe_decode(getattr(doc, "answer")),
                        "reasoning_type": self.safe_decode(getattr(doc, "reasoning_type")),
                        "sources": json.loads(self.safe_decode(getattr(doc, "sources"))),
                        "score": score
                    }
        except Exception as cache_err:
            print(f"[RouterAgent] Semantic cache lookup failed: {cache_err}")
        return None

    def _deduplicate(self, candidates):
        seen = set()
        deduped = []
        for cand in candidates:
            if cand['chunk_id'] not in seen:
                seen.add(cand['chunk_id'])
                deduped.append(cand)
        return deduped

    async def route(
        self,
        prompt: str,
        history: list,
        stream: bool,
        include_trace: bool,
        metrics_callback: Dict[str, Any]
    ) -> Dict[str, Any]:
        """
        Routes the request through the Router Agent pipeline.
        
        metrics_callback includes reference to latency histograms, counters, etc.
        """
        start_time = time.time()
        
        # Initialize Trace
        trace = ReasoningTrace(prompt)
        trace.history = history
        trace.router_decisions["original_query"] = prompt
        
        # 1. Follow-up detection
        is_followup = self.followup_detector.is_followup(prompt, history)
        trace.router_decisions["is_followup"] = is_followup
        
        if metrics_callback.get("followup_counter"):
            metrics_callback["followup_counter"].labels(detected=str(is_followup).lower()).inc()
            
        rewritten_prompt = prompt
        if is_followup:
            t0 = time.time()
            rewritten_prompt = await self.engine.generator.rewrite_query(prompt, history)
            trace.router_decisions["query_rewritten"] = True
            trace.router_decisions["rewritten_query"] = rewritten_prompt
            print(f"[RouterAgent] Followup detected. Rewrote query in {time.time() - t0:.2f}s")
        else:
            print("[RouterAgent] Standalone query. Skipping query rewriting.")
            
        trace.query = rewritten_prompt
        
        # 2. Embedding for Cache & Retrieval
        t_embed_start = time.time()
        q_vector = await self._embed_query(rewritten_prompt)
        q_vector_list = q_vector.tolist() if hasattr(q_vector, "tolist") else list(q_vector)
        
        # 3. Cache check
        cache_hit = await self._check_cache(q_vector_list, rewritten_prompt)
        if cache_hit:
            trace.router_decisions["cache_hit"] = True
            trace.router_decisions["path_taken"] = "cache_hit"
            
            # Record Latency
            cached_rt = cache_hit["reasoning_type"]
            if metrics_callback.get("latency_histogram"):
                metrics_callback["latency_histogram"].labels(reasoning_type=cached_rt).observe(time.time() - start_time)
            if metrics_callback.get("request_counter"):
                metrics_callback["request_counter"].labels(reasoning_type=cached_rt, status="success").inc()
            if metrics_callback.get("path_counter"):
                metrics_callback["path_counter"].labels(path="cache_hit").inc()
                
            return {
                "answer": cache_hit["answer"],
                "reasoning_type": cached_rt,
                "sources": cache_hit["sources"],
                "trace": trace.to_dict() if include_trace else None,
                "cached": True
            }
            
        # 4. Classification (intent and reasoning type)
        t_classify_start = time.time()
        classification = await self.classifier.classify(rewritten_prompt)
        if metrics_callback.get("classify_latency"):
            metrics_callback["classify_latency"].observe(time.time() - t_classify_start)
            
        reasoning_type = classification.get("reasoning_type", "commonsense")
        trace.classification = classification
        
        # 5. First Retrieval Attempt
        t_retrieval_start = time.time()
        trace = await self.engine.execute(trace, run_generator=False)
        
        # 6. Quality Gate Evaluation
        passed, avg_score, threshold = self.quality_gate.evaluate(trace.reranked_final, reasoning_type)
        trace.router_decisions["quality_score"] = avg_score
        
        if metrics_callback.get("quality_score_histogram"):
            metrics_callback["quality_score_histogram"].observe(avg_score)
            
        if not passed:
            print(f"[RouterAgent] Quality gate FAILED (Score: {avg_score:.3f} < {threshold}). Retrying with refinement.")
            trace.router_decisions["retrieval_retried"] = True
            trace.router_decisions["retry_reason"] = "low_relevance"
            trace.router_decisions["path_taken"] = "retry_rag"
            
            if metrics_callback.get("retry_counter"):
                metrics_callback["retry_counter"].labels(reason="low_relevance").inc()
                
            # Refine query
            refined_query = self.quality_gate.refine_query(rewritten_prompt, classification)
            trace.router_decisions["refined_query"] = refined_query
            print(f"[RouterAgent] Refined query: '{refined_query}'")
            
            # Execute secondary retrieval
            retry_trace = ReasoningTrace(refined_query)
            retry_trace.classification = classification
            retry_trace = await self.engine.execute(retry_trace, run_generator=False)
            
            # Merge candidates from both runs and rerank using cross-encoder
            combined = self._deduplicate(trace.reranked_final + retry_trace.reranked_final)
            
            # We need to construct candidate structure for the reranker
            candidates_to_rerank = [{"chunk_id": c["chunk_id"], "metadata": c["metadata"]} for c in combined]
            
            # Rerank with the original query to ensure relevance to user request
            reranked_combined = await self.engine.reranker.rerank(rewritten_prompt, candidates_to_rerank, top_k=5)
            trace.reranked_final = reranked_combined
        else:
            trace.router_decisions["path_taken"] = "simple_rag" if reasoning_type == "commonsense" else "multi_step_rag"
            print(f"[RouterAgent] Quality gate PASSED (Score: {avg_score:.3f} >= {threshold}).")

        # Record retrieval/rerank latency
        if metrics_callback.get("retrieval_latency"):
            metrics_callback["retrieval_latency"].observe(time.time() - t_retrieval_start)
            
        if metrics_callback.get("path_counter"):
            metrics_callback["path_counter"].labels(path=trace.router_decisions["path_taken"]).inc()
            
        # Format sources
        sources = []
        for c in trace.reranked_final:
            meta = c["metadata"]
            sources.append({
                "chunk_id": c["chunk_id"],
                "score": c.get("final_score", c.get("score", 0.0)),
                "question_id": str(meta.get("question_id", "")),
                "is_accepted": meta.get("is_accepted", False),
                "domain": meta.get("domain", ""),
                "chunk_text": meta.get("chunk_text", "")
            })

        # Save to cache helper (asynchronous task)
        async def background_cache(full_ans):
            try:
                # We save to cache using rewritten_prompt
                import hashlib
                q_hash = hashlib.sha256(rewritten_prompt.encode("utf-8")).hexdigest()
                key = f"cache:{q_hash}"
                
                # Retrieve raw client hset
                embedding_bytes = np.array(q_vector_list, dtype=np.float32).tobytes()
                await self.redis_raw_client.hset(
                    key,
                    mapping={
                        "embedding": embedding_bytes,
                        "query": rewritten_prompt.encode("utf-8"),
                        "answer": full_ans.encode("utf-8"),
                        "reasoning_type": reasoning_type.encode("utf-8"),
                        "sources": json.dumps(sources).encode("utf-8")
                    }
                )
                await self.redis_raw_client.expire(key, 86400)
                print(f"[RouterAgent] Saved to semantic cache: {key}")
            except Exception as e:
                print(f"[RouterAgent] Async cache save failed: {e}")

        # 7. Final response generation
        if stream:
            async def stream_generator():
                t_gen_start = time.time()
                try:
                    trace_payload = {
                        "reasoning_type": reasoning_type,
                        "sub_questions": classification.get("sub_questions", []),
                        "sources": sources,
                        "router_decisions": trace.router_decisions
                    }
                    yield (json.dumps({"type": "trace", "data": trace_payload}) + "\n")
                    
                    full_answer = ""
                    async for token in self.engine.generator.generate_stream(trace):
                        full_answer += token
                        yield (json.dumps({"type": "token", "data": token}) + "\n")
                        
                    if metrics_callback.get("generation_latency"):
                        metrics_callback["generation_latency"].observe(time.time() - t_gen_start)
                    if metrics_callback.get("latency_histogram"):
                        metrics_callback["latency_histogram"].labels(reasoning_type=reasoning_type).observe(time.time() - start_time)
                    if metrics_callback.get("request_counter"):
                        metrics_callback["request_counter"].labels(reasoning_type=reasoning_type, status="success").inc()
                        
                    # Cache in background
                    asyncio.create_task(background_cache(full_answer))
                except Exception as stream_err:
                    print(f"[RouterAgent] Error in stream generation: {stream_err}")
                    if metrics_callback.get("request_counter"):
                        metrics_callback["request_counter"].labels(reasoning_type=reasoning_type, status="error").inc()
                    yield (json.dumps({"type": "error", "data": str(stream_err)}) + "\n")
            
            return {
                "streaming_response": stream_generator()
            }
            
        else:
            t_gen_start = time.time()
            trace = await self.engine.generator.generate(trace)
            if metrics_callback.get("generation_latency"):
                metrics_callback["generation_latency"].observe(time.time() - t_gen_start)
            if metrics_callback.get("latency_histogram"):
                metrics_callback["latency_histogram"].labels(reasoning_type=reasoning_type).observe(time.time() - start_time)
            if metrics_callback.get("request_counter"):
                metrics_callback["request_counter"].labels(reasoning_type=reasoning_type, status="success").inc()
                
            # Cache in background
            asyncio.create_task(background_cache(trace.final_answer))
            
            return {
                "answer": trace.final_answer,
                "reasoning_type": reasoning_type,
                "sources": sources,
                "trace": trace.to_dict() if include_trace else None,
                "cached": False
            }
