import os
import json
import argparse
from tqdm import tqdm
from elasticsearch import Elasticsearch, helpers

MIN_SCORE = 3
MAX_ANSWERS = 3
MAX_CHUNK_LEN = 1024
INDEX_NAME = "stackexchange_chunks"


def run_ingestion(
    data_path="data/processed_dataset.jsonl",
    es_url=os.getenv("ELASTICSEARCH_URL", "http://localhost:9200"),
    limit=None,
):
    print(f"Connecting to Elasticsearch at {es_url}...")
    es = Elasticsearch(es_url)

    # Test connection
    if not es.ping():
        raise ConnectionError(f"Could not connect to Elasticsearch at {es_url}")

    # -- Chunking --
    chunks = []
    metadata = []

    print(f"Reading & chunking dataset from {data_path} (limit={limit})...")
    if not os.path.exists(data_path):
        alt_path = os.path.join(os.path.dirname(__file__), "../../", data_path)
        if os.path.exists(alt_path):
            data_path = alt_path
        else:
            raise FileNotFoundError(f"Dataset not found at {data_path} or {alt_path}")

    with open(data_path, "r", encoding="utf-8") as f:
        for line in tqdm(f, desc="Chunking"):
            if limit and len(chunks) >= limit:
                break
                
            record = json.loads(line)
            title = record.get("title", "")[:200]
            domain = record.get("domain", "")
            q_id = record.get("question_id", "")
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
                    "chunk_text": chunk_text,
                })

    total = len(chunks)
    print(f"Total chunks chunked: {total}")
    if total == 0:
        print("No chunks. Exiting.")
        return

    # Delete index if exists, then recreate
    if es.indices.exists(index=INDEX_NAME):
        print(f"Deleting existing Elasticsearch index: {INDEX_NAME}...")
        es.indices.delete(index=INDEX_NAME)

    print(f"Creating Elasticsearch index: {INDEX_NAME}...")
    # Map BM25 explicitly
    mapping = {
        "mappings": {
            "properties": {
                "chunk_id": {"type": "integer"},
                "question_id": {"type": "keyword"},
                "score": {"type": "integer"},
                "is_accepted": {"type": "boolean"},
                "domain": {"type": "keyword"},
                "chunk_text": {
                    "type": "text",
                    "similarity": "BM25"  # Use BM25 similarity
                }
            }
        }
    }
    es.indices.create(index=INDEX_NAME, body=mapping)

    print("Uploading chunks to Elasticsearch...")
    actions = []
    for meta in tqdm(metadata, desc="Indexing"):
        actions.append({
            "_index": INDEX_NAME,
            "_id": meta["chunk_id"],
            "_source": meta
        })
        
        # Flush every 1000 items
        if len(actions) >= 1000:
            helpers.bulk(es, actions)
            actions = []
            
    if actions:
        helpers.bulk(es, actions)

    print("Elasticsearch indexing complete.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Ingest StackExchange data to Elasticsearch")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of chunks to ingest")
    parser.add_argument("--data", default="data/processed_dataset.jsonl", help="Dataset path")
    parser.add_argument("--url", default="http://localhost:9200", help="Elasticsearch url")
    args = parser.parse_args()
    
    run_ingestion(data_path=args.data, es_url=args.url, limit=args.limit)
