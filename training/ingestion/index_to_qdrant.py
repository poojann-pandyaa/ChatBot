import os
import json
import torch
import numpy as np
from tqdm import tqdm
from qdrant_client import QdrantClient
from qdrant_client.models import Distance, VectorParams, PointStruct
from transformers import AutoTokenizer, AutoModel

EMBED_MODEL = "BAAI/bge-base-en-v1.5"
BATCH_SIZE = 32
MAX_CHUNK_LEN = 1024
MIN_SCORE = 3
MAX_ANSWERS = 3
COLLECTION_NAME = "stackexchange_chunks"


def embed_batch_torch(model, tokenizer, texts, device):
    encoded = tokenizer(
        texts,
        padding=True,
        truncation=True,
        max_length=256,
        return_tensors="pt",
    ).to(device)

    with torch.no_grad():
        output = model(**encoded)

    # CLS-token embedding (index 0) -- standard for BGE
    embeddings = output.last_hidden_state[:, 0, :]
    # L2 normalise for cosine similarity
    import torch.nn.functional as F
    embeddings = F.normalize(embeddings, p=2, dim=1)
    return embeddings.cpu().numpy().astype("float32")


def run_ingestion(
    data_path="data/processed_dataset.jsonl",
    qdrant_url=os.getenv("QDRANT_URL", "http://localhost:6333"),
):
    print(f"Connecting to Qdrant at {qdrant_url}...")
    client = QdrantClient(url=qdrant_url)

    device = "mps" if torch.backends.mps.is_available() else "cpu"
    if torch.cuda.is_available():
        device = "cuda"

    print(f"Loading embedding model {EMBED_MODEL} on {device}...")
    tokenizer = AutoTokenizer.from_pretrained(EMBED_MODEL)
    model = AutoModel.from_pretrained(EMBED_MODEL).to(device)
    model.eval()

    # -- Chunking --
    chunks = []
    metadata = []

    print(f"Reading & chunking dataset from {data_path}...")
    if not os.path.exists(data_path):
        # Fallback to local subdirectory if run from different folder
        alt_path = os.path.join(os.path.dirname(__file__), "../../", data_path)
        if os.path.exists(alt_path):
            data_path = alt_path
        else:
            raise FileNotFoundError(f"Dataset not found at {data_path} or {alt_path}")

    with open(data_path, "r", encoding="utf-8") as f:
        for line in tqdm(f, desc="Chunking"):
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
    print(f"Total chunks: {total}")
    if total == 0:
        print("No chunks. Exiting.")
        return

    # Recreate collection in Qdrant
    print(f"Recreating Qdrant collection: {COLLECTION_NAME}...")
    client.recreate_collection(
        collection_name=COLLECTION_NAME,
        vectors_config=VectorParams(size=768, distance=Distance.COSINE),
    )

    print(f"Embedding and uploading {total} chunks in batches...")
    for start in tqdm(range(0, total, BATCH_SIZE), desc="Ingesting"):
        end = min(start + BATCH_SIZE, total)
        batch_texts = chunks[start:end]
        batch_meta = metadata[start:end]
        
        vecs = embed_batch_torch(model, tokenizer, batch_texts, device)
        
        points = []
        for idx, (meta, vec) in enumerate(zip(batch_meta, vecs)):
            points.append(
                PointStruct(
                    id=meta["chunk_id"],
                    vector=vec.tolist(),
                    payload=meta
                )
            )
        
        client.upsert(
            collection_name=COLLECTION_NAME,
            wait=True,
            points=points
        )

    print("Qdrant indexing complete.")


if __name__ == "__main__":
    run_ingestion()
