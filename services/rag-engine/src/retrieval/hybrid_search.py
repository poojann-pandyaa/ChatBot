import os
import asyncio
import numpy as np
from transformers import AutoTokenizer, AutoModel
import torch
import torch.nn.functional as F
from qdrant_client import AsyncQdrantClient
from elasticsearch import AsyncElasticsearch

EMBED_MODEL = "BAAI/bge-base-en-v1.5"


class HybridRetriever:
    def __init__(
        self,
        qdrant_url=os.getenv("QDRANT_URL", "http://localhost:6333"),
        es_url=os.getenv("ELASTICSEARCH_URL", "http://localhost:9200"),
        collection_name="stackexchange_chunks",
        es_index="stackexchange_chunks",
    ):
        print(f"Initializing HybridRetriever...")
        print(f"Qdrant URL: {qdrant_url}")
        print(f"Elasticsearch URL: {es_url}")
        
        self.qdrant_client = AsyncQdrantClient(url=qdrant_url)
        self.es_client = AsyncElasticsearch(es_url)
        
        self.qdrant_collection = collection_name
        self.es_index = es_index

        print("Loading local embedding model...")
        self.device = "cuda" if torch.cuda.is_available() else ("mps" if torch.backends.mps.is_available() else "cpu")
        self.tokenizer = AutoTokenizer.from_pretrained(EMBED_MODEL)
        self.model = AutoModel.from_pretrained(EMBED_MODEL).to(self.device)
        self.model.eval()
        print(f"Embedding model loaded on device: {self.device}")

    def _embed(self, text: str) -> np.ndarray:
        """Embed a single query string. Returns float32 array shape (768,)."""
        encoded = self.tokenizer(
            [text],
            padding=True,
            truncation=True,
            max_length=256,
            return_tensors="pt",
        ).to(self.device)
        with torch.no_grad():
            output = self.model(**encoded)
        emb = output.last_hidden_state[:, 0, :]       # CLS token
        emb = F.normalize(emb, p=2, dim=1)            # L2 normalise
        return emb.cpu().squeeze(0).numpy().astype("float32")

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
        q_embedding = self._embed(query)

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


if __name__ == "__main__":
    # Test harness
    async def test():
        retriever = HybridRetriever()
        res = await retriever.hybrid_retrieve("How to reverse a list in Python?")
        for r in res[:3]:
            print(r)
        await retriever.close()
    
    asyncio.run(test())
