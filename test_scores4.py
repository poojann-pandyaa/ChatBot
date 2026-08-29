import requests

def get_scores(query):
    emb_res = requests.post("http://localhost:8001/embed", json={"text": query, "return_bytes": False})
    embedding = emb_res.json()["embedding"]
    qdrant_url = "http://localhost:6333/collections/stackexchange_chunks/points/search"
    q_res = requests.post(qdrant_url, json={"vector": embedding, "limit": 5, "with_payload": True}).json()
    docs = [hit["payload"]["chunk_text"] for hit in q_res["result"]]
    rr_res = requests.post("http://localhost:8001/rerank", json={"query": query, "documents": docs})
    scores = rr_res.json()["scores"]
    print("Scores:", scores)
    
get_scores("How do I reverse a string in java?")
