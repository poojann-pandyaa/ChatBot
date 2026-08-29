import requests
import json

def get_scores(query):
    # Call embed
    emb_res = requests.post("http://localhost:8001/embed", json={"text": query, "return_bytes": False})
    if emb_res.status_code != 200:
        print("Embed failed", emb_res.text)
        return
    embedding = emb_res.json()["embedding"]
    
    # Query Qdrant
    qdrant_url = "http://localhost:6333/collections/stackexchange_chunks/points/search"
    q_payload = {
        "vector": embedding,
        "limit": 5,
        "with_payload": True
    }
    q_res = requests.post(qdrant_url, json=q_payload).json()
    if "result" not in q_res:
        print("Qdrant failed", q_res)
        return
    
    docs = [hit["payload"]["chunk_text"] for hit in q_res["result"]]
    print(f"--- Qdrant docs for '{query}' ---")
    for i, d in enumerate(docs):
        print(f"{i}: {d[:100]}...")
        
    # Call Rerank
    rr_res = requests.post("http://localhost:8001/rerank", json={"query": query, "documents": docs})
    if rr_res.status_code != 200:
        print("Rerank failed", rr_res.text)
        return
    print("Rerank Scores:", rr_res.json()["scores"])
    
get_scores("what is difference between java and pythin")
print("\n")
get_scores("Write OOps concepts on java")
