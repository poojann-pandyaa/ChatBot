import requests
import json

def get_scores(query):
    # Call embed
    emb_res = requests.post("http://localhost:8001/embed", json={"text": query, "return_bytes": False})
    embedding = emb_res.json()["embedding"]
    
    # Query Qdrant
    qdrant_url = "http://localhost:6333/collections/stackexchange_chunks/points/search"
    q_payload = {
        "vector": embedding,
        "limit": 5,
        "with_payload": True
    }
    q_res = requests.post(qdrant_url, json=q_payload).json()
    
    docs = [hit["payload"]["chunk_text"] for hit in q_res["result"]]
        
    # Call Rerank
    rr_res = requests.post("http://localhost:8001/rerank", json={"query": query, "documents": docs})
    print("Rerank Scores:", rr_res.json()["scores"])
    
get_scores("What is the difference between VC++ and C++?")
