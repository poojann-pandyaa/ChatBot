import requests
import json

res = requests.post("http://localhost:8005/embed", json={"text": "how does garbage collection work in java \n"})
vector = res.json()["embedding"]

res = requests.post("http://localhost:6333/collections/stackexchange_chunks/points/search", json={
    "vector": vector,
    "limit": 5,
    "with_payload": True
})
for p in res.json()["result"]:
    print(p["id"], p["payload"]["chunk_text"][:50])
