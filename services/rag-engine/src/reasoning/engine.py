import sys
import os
import asyncio
from typing import Optional
sys.path.append(os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

from retrieval.hybrid_search import HybridRetriever
from retrieval.reranker import ContextReRanker
from generation.trace import ReasoningTrace
from generation.generator import FinalGenerator


class ReasoningEngine:
    def __init__(
        self,
        model_name: Optional[str] = None,
        ollama_url: Optional[str] = None,
    ):
        print("Initializing Reasoning Engine...")
        self.retriever = HybridRetriever()
        self.reranker = ContextReRanker()
        self.generator = FinalGenerator(
            model_name=model_name,
            ollama_url=ollama_url,
        )

    def deduplicate(self, candidates):
        seen = set()
        deduped = []
        for cand in candidates:
            if cand['chunk_id'] not in seen:
                seen.add(cand['chunk_id'])
                deduped.append(cand)
        return deduped

    async def commonsense_path(self, trace, run_generator=True):
        print("Executing Commonsense Path...")
        candidates = await self.retriever.hybrid_retrieve(trace.query, top_k=20)
        reranked = await self.reranker.rerank(trace.query, candidates, top_k=5)
        trace.retrieved_per_subquery["main"] = [r['chunk_id'] for r in reranked]
        trace.reranked_final = reranked
        if run_generator:
            return await self.generator.generate(trace)
        return trace

    async def adaptive_path(self, trace, run_generator=True):
        print("Executing Adaptive Path...")
        sub_questions = trace.classification.get("sub_questions", [])
        
        # Concurrently retrieve for all sub-questions
        tasks = [self.retriever.hybrid_retrieve(sq, top_k=10) for sq in sub_questions]
        results = await asyncio.gather(*tasks)
        
        # Concurrently rerank for all sub-questions
        rerank_tasks = [self.reranker.rerank(sq, cands, top_k=3) for sq, cands in zip(sub_questions, results)]
        reranked_results = await asyncio.gather(*rerank_tasks)
        
        all_candidates = []
        for sq, ranked in zip(sub_questions, reranked_results):
            trace.retrieved_per_subquery[sq] = [r['chunk_id'] for r in ranked]
            all_candidates.extend(ranked)
            
        trace.reranked_final = self.deduplicate(all_candidates)
        if run_generator:
            return await self.generator.generate(trace)
        return trace

    async def strategic_path(self, trace, run_generator=True):
        print("Executing Strategic Path...")
        sub_questions = trace.classification.get("sub_questions", [])
        
        # Concurrently retrieve for level-1 main query and all sub-questions/categories
        tasks = [self.retriever.hybrid_retrieve(trace.query, top_k=10)]
        for sq in sub_questions:
            tasks.append(self.retriever.hybrid_retrieve(sq, top_k=10))
            
        results = await asyncio.gather(*tasks)
        
        level1_candidates = results[0]
        all_candidates = list(level1_candidates)
        trace.retrieved_per_subquery["level1_main"] = [r['chunk_id'] for r in level1_candidates[:3]]
        
        # Concurrently rerank for all sub-questions
        rerank_tasks = [self.reranker.rerank(sq, cands, top_k=3) for sq, cands in zip(sub_questions, results[1:])]
        reranked_results = await asyncio.gather(*rerank_tasks)
        
        for sq, ranked in zip(sub_questions, reranked_results):
            trace.retrieved_per_subquery[sq] = [r['chunk_id'] for r in ranked]
            all_candidates.extend(ranked)
            
        trace.reranked_final = self.deduplicate(all_candidates)
        if run_generator:
            return await self.generator.generate(trace)
        return trace

    async def execute(self, trace, run_generator=True):
        r_type = trace.classification.get("reasoning_type", "commonsense")
        if trace.classification.get("ambiguity", "low") == "high":
            print("Note: High ambiguity detected.")
            
        if r_type == "commonsense":
            return await self.commonsense_path(trace, run_generator)
        elif r_type == "adaptive":
            return await self.adaptive_path(trace, run_generator)
        elif r_type == "strategic":
            return await self.strategic_path(trace, run_generator)
        else:
            return await self.commonsense_path(trace, run_generator)

    async def close(self):
        await self.retriever.close()
        await self.reranker.close()
        await self.generator.close()
