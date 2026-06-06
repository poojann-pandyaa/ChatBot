import os
import json
import argparse
import asyncio
import httpx
from tqdm import tqdm
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct
from elasticsearch import Elasticsearch, helpers

MIN_SCORE = 3
MAX_ANSWERS = 3
MAX_CHUNK_LEN = 1024
COLLECTION_NAME = "stackexchange_chunks"
INDEX_NAME = "stackexchange_chunks"

async def embed_batch_api(ml_url, texts, concurrency_limit=10):
    """
    Call ML Service /embed endpoint concurrently to generate embeddings.
    """
    semaphore = asyncio.Semaphore(concurrency_limit)
    
    async def embed_single(client, text):
        async with semaphore:
            for attempt in range(3):
                try:
                    response = await client.post("/embed", json={"text": text}, timeout=10.0)
                    if response.status_code == 200:
                        return response.json()["embedding"]
                    else:
                        raise Exception(f"ML Service /embed returned {response.status_code}: {response.text}")
                except Exception as e:
                    if attempt == 2:
                        raise e
                    await asyncio.sleep(1.0)
            return None

    async with httpx.AsyncClient(base_url=ml_url, timeout=30.0) as client:
        tasks = [embed_single(client, text) for text in texts]
        embeddings = await asyncio.gather(*tasks)
        return embeddings

async def run_ingestion_async(data_path, qdrant_url, es_url, ml_url, limit, batch_size):
    print(f"Reading & chunking dataset from {data_path} (limit={limit})...")
    if not os.path.exists(data_path):
        alt_path = os.path.join(os.path.dirname(__file__), "../../", data_path)
        if os.path.exists(alt_path):
            data_path = alt_path
        else:
            raise FileNotFoundError(f"Dataset not found at {data_path} or {alt_path}")

    chunks = []
    metadata = []

    with open(data_path, "r", encoding="utf-8") as f:
        for line in f:
            if limit and len(chunks) >= limit:
                break
                
            record = json.loads(line)
            title = record.get("title", "")[:200]
            domain = record.get("domain", "").lower().strip()
            q_id = record.get("question_id", "")
            reasoning_category = record.get("reasoning_category", "Procedural")
            answers = record.get("answers", [])

            good = [
                a for a in answers
                if a.get("is_accepted") or a.get("score", 0) >= MIN_SCORE
            ]
            if not good:
                good = sorted(answers, key=lambda a: a.get("score", 0), reverse=True)[:1]

            good = sorted(
                good,
                key=lambda a: (a.get("is_accepted", False), a.get("score", 0)),
                reverse=True,
            )[:MAX_ANSWERS]

            for ans in good:
                if limit and len(chunks) >= limit:
                    break
                    
                body = ans.get("body_clean", "")[:MAX_CHUNK_LEN]
                chunk_text = f"Q: {title}\nA: {body}"[:MAX_CHUNK_LEN]
                chunks.append(chunk_text)
                metadata.append({
                    "chunk_id": len(chunks) - 1,
                    "question_id": q_id,
                    "score": ans.get("score", ans.get("pm_score", 0)),
                    "is_accepted": ans.get("is_accepted", False),
                    "domain": domain,
                    "reasoning_category": reasoning_category,
                    "chunk_text": chunk_text,
                })

    total = len(chunks)
    print(f"Total chunks chunked: {total}")
    if total == 0:
        print("No chunks. Exiting.")
        return

    # Initialize Clients
    print(f"Connecting to Qdrant at {qdrant_url}...")
    qdrant_client = QdrantClient(url=qdrant_url)
    
    print(f"Connecting to Elasticsearch at {es_url}...")
    es_client = Elasticsearch(es_url)
    if not es_client.ping():
        raise ConnectionError(f"Could not connect to Elasticsearch at {es_url}")

    # Recreate Qdrant Collection
    print(f"Recreating Qdrant collection: {COLLECTION_NAME}...")
    qdrant_client.recreate_collection(
        collection_name=COLLECTION_NAME,
        vectors_config=VectorParams(size=768, distance=Distance.COSINE),
    )

    # Recreate Elasticsearch Index
    if es_client.indices.exists(index=INDEX_NAME):
        print(f"Deleting existing Elasticsearch index: {INDEX_NAME}...")
        es_client.indices.delete(index=INDEX_NAME)

    print(f"Creating Elasticsearch index: {INDEX_NAME}...")
    mapping = {
        "mappings": {
            "properties": {
                "chunk_id": {"type": "integer"},
                "question_id": {"type": "keyword"},
                "score": {"type": "integer"},
                "is_accepted": {"type": "boolean"},
                "domain": {"type": "keyword"},
                "reasoning_category": {"type": "keyword"},
                "chunk_text": {
                    "type": "text",
                    "similarity": "BM25"
                }
            }
        }
    }
    es_client.indices.create(index=INDEX_NAME, body=mapping)

    # Batch Indexing
    print(f"Embedding and uploading {total} chunks in batches of {batch_size}...")
    for start in tqdm(range(0, total, batch_size), desc="Ingesting"):
        end = min(start + batch_size, total)
        batch_texts = chunks[start:end]
        batch_meta = metadata[start:end]
        
        # 1. Get embeddings from ML Service
        vecs = await embed_batch_api(ml_url, batch_texts)
        
        # 2. Upload to Qdrant
        points = []
        for idx, (meta, vec) in enumerate(zip(batch_meta, vecs)):
            if vec is None:
                continue
            points.append(
                PointStruct(
                    id=meta["chunk_id"],
                    vector=vec,
                    payload=meta
                )
            )
        if points:
            qdrant_client.upsert(
                collection_name=COLLECTION_NAME,
                wait=True,
                points=points
            )
            
        # 3. Upload to Elasticsearch
        es_actions = []
        for meta in batch_meta:
            es_actions.append({
                "_index": INDEX_NAME,
                "_id": meta["chunk_id"],
                "_source": meta
            })
        if es_actions:
            helpers.bulk(es_client, es_actions)

    print("Ingestion, Qdrant indexing, and Elasticsearch indexing complete.")

def main():
    parser = argparse.ArgumentParser(description="Ingest StackExchange data to Qdrant & Elasticsearch")
    parser.add_argument("--limit", type=int, default=1000, help="Limit number of chunks to ingest (0 or None for all)")
    parser.add_argument("--data", default="data/processed_dataset.jsonl", help="Dataset path")
    parser.add_argument("--qdrant-url", default=os.getenv("QDRANT_URL", "http://localhost:6333"), help="Qdrant url")
    parser.add_argument("--es-url", default=os.getenv("ELASTICSEARCH_URL", "http://localhost:9200"), help="Elasticsearch url")
    parser.add_argument("--ml-url", default=os.getenv("ML_SERVICE_URL", "http://localhost:8000"), help="ML Service url")
    parser.add_argument("--batch-size", type=int, default=32, help="Batch size for embeddings/indexing")
    args = parser.parse_args()

    limit = None if args.limit <= 0 else args.limit
    
    asyncio.run(
        run_ingestion_async(
            data_path=args.data,
            qdrant_url=args.qdrant_url,
            es_url=args.es_url,
            ml_url=args.ml_url,
            limit=limit,
            batch_size=args.batch_size
        )
    )

if __name__ == "__main__":
    main()
