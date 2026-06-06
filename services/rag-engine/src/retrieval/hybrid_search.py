import os
import asyncio
import numpy as np
import httpx
from qdrant_client import AsyncQdrantClient
from elasticsearch import AsyncElasticsearch


class HybridRetriever:
    def __init__(
        self,
        qdrant_url=os.getenv("QDRANT_URL", "http://localhost:6333"),
        es_url=os.getenv("ELASTICSEARCH_URL", "http://localhost:9200"),
        ml_service_url=os.getenv("ML_SERVICE_URL", "http://localhost:8000"),
        collection_name="stackexchange_chunks",
        es_index="stackexchange_chunks",
    ):
        print(f"Initializing HybridRetriever...")
        print(f"Qdrant URL: {qdrant_url}")
        print(f"Elasticsearch URL: {es_url}")
        print(f"ML Service URL: {ml_service_url}")
        
        self.qdrant_client = AsyncQdrantClient(url=qdrant_url)
        self.es_client = AsyncElasticsearch(es_url)
        self.ml_service_url = ml_service_url
        self.http_client = httpx.AsyncClient(base_url=self.ml_service_url, timeout=30.0)
        
        self.qdrant_collection = collection_name
        self.es_index = es_index

    async def _embed(self, text: str) -> np.ndarray:
        try:
            response = await self.http_client.post("/embed", json={"text": text})
            if response.status_code == 200:
                vector = response.json()["embedding"]
                return np.array(vector, dtype="float32")
            else:
                raise Exception(f"ML Service /embed returned {response.status_code}: {response.text}")
        except Exception as e:
            print(f"Embedding failed: {e}")
            return np.zeros(768, dtype="float32")

    async def _dense_search(self, q_embedding: np.ndarray, top_k: int):
        try:
            res = await self.qdrant_client.search(
                collection_name=self.qdrant_collection,
                query_vector=q_embedding.tolist(),
                limit=top_k,
            )
            return [
                {
                    "chunk_id": int(hit.id),
                    "payload": hit.payload
                }
                for hit in res
            ]
        except Exception as e:
            print(f"Dense search failed: {e}")
            return []

    async def _sparse_search(self, query: str, top_k: int):
        try:
            body = {
                "size": top_k,
                "query": {
                    "match": {
                        "chunk_text": query
                    }
                }
            }
            res = await self.es_client.search(
                index=self.es_index,
                body=body
            )
            hits = res["hits"]["hits"]
            return [
                {
                    "chunk_id": int(hit["_id"]),
                    "payload": hit["_source"]
                }
                for hit in hits
            ]
        except Exception as e:
            print(f"Sparse search failed: {e}")
            return []

    async def hybrid_retrieve(self, query: str, top_k: int = 20):
        # 1. Embed query
        q_embedding = await self._embed(query)

        # 2. Parallel Dense & Sparse search calls
        dense_task = self._dense_search(q_embedding, top_k)
        sparse_task = self._sparse_search(query, top_k)
        
        dense_results, sparse_results = await asyncio.gather(dense_task, sparse_task)

        # 3. Reciprocal Rank Fusion (RRF)
        rrf_scores = {}
        chunk_payloads = {}

        for rank, hit in enumerate(dense_results):
            cid = hit["chunk_id"]
            chunk_payloads[cid] = hit["payload"]
            rrf_scores[cid] = rrf_scores.get(cid, 0) + 1 / (60 + rank + 1)

        for rank, hit in enumerate(sparse_results):
            cid = hit["chunk_id"]
            chunk_payloads[cid] = hit["payload"]
            rrf_scores[cid] = rrf_scores.get(cid, 0) + 1 / (60 + rank + 1)

        # 4. Sort by score
        fused = sorted(rrf_scores.items(), key=lambda x: x[1], reverse=True)

        results = []
        for chunk_id, score in fused[:top_k]:
            results.append({
                "chunk_id": chunk_id,
                "score": score,
                "metadata": chunk_payloads[chunk_id]
            })
        return results

    async def close(self):
        await self.es_client.close()
        await self.http_client.aclose()


if __name__ == "__main__":
    # Test harness
    async def test():
        retriever = HybridRetriever()
        res = await retriever.hybrid_retrieve("How to reverse a list in Python?")
        for r in res[:3]:
            print(r)
        await retriever.close()
    
    asyncio.run(test())
