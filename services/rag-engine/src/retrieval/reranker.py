import os
import httpx
import asyncio

class ContextReRanker:
    def __init__(self, ml_service_url: str = None):
        self.ml_service_url = ml_service_url or os.getenv("ML_SERVICE_URL", "http://localhost:8000")
        self.client = httpx.AsyncClient(base_url=self.ml_service_url, timeout=30.0)
        print(f"ContextReRanker initialized. ML Service URL: {self.ml_service_url}")
        
    async def rerank(self, query, candidates, top_k=5):
        """
        candidates: List containing dicts with structure:
            {'chunk_id': int, 'score': float, 'metadata': dict}
        """
        if not candidates:
            return []
            
        # Prepare document texts for the cross-encoder endpoint
        documents = [cand['metadata']['chunk_text'] for cand in candidates]
        
        try:
            response = await self.client.post("/rerank", json={"query": query, "documents": documents})
            if response.status_code == 200:
                scores = response.json()["scores"]
            else:
                raise Exception(f"ML Service /rerank returned {response.status_code}: {response.text}")
        except Exception as e:
            print(f"Reranking failed, using base scores: {e}")
            scores = [cand.get('score', 0.0) for cand in candidates]
        
        scored_candidates = []
        for i, cand in enumerate(candidates):
            meta = cand['metadata']
            base_score = float(scores[i])
            
            # Incorporate Stack Exchange preference signals as per requirements
            score_signal = 0.1 * min(meta.get("score", 0) / 100.0, 1.0)
            accepted_signal = 0.15 if meta.get("is_accepted", False) else 0.0
            
            final_score = base_score + score_signal + accepted_signal
            
            scored_candidates.append({
                'chunk_id': cand['chunk_id'],
                'metadata': meta,
                'base_ce_score': base_score,
                'final_score': final_score
            })
            
        # Sort desc by final_score
        ranked = sorted(scored_candidates, key=lambda x: x['final_score'], reverse=True)
        return ranked[:top_k]

    async def close(self):
        await self.client.aclose()

if __name__ == "__main__":
    # Test stub
    pass
