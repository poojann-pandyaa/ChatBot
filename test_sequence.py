import requests, json

TOKEN = requests.post("http://localhost:8081/api/auth/login", json={"user_id": "alice", "password": "alice123"}).json()["token"]
headers = {"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"}
conv_id = "test-seq-4"

requests.post("http://localhost:8081/api/chat", json={"prompt": "what is TCP connection in CN", "conversation_id": conv_id, "userId": "alice", "stream": False}, headers=headers)
requests.post("http://localhost:8081/api/chat", json={"prompt": "How to configure database in spring", "conversation_id": conv_id, "userId": "alice", "stream": False}, headers=headers)
res3 = requests.post("http://localhost:8081/api/chat", json={"prompt": "\"How does garbage collection work in Java?\"", "conversation_id": conv_id, "userId": "alice", "stream": False}, headers=headers).json()

hist = requests.get(f"http://localhost:8081/api/chat/history/{conv_id}", headers=headers).json()
for msg in hist.get("messages", []):
    if msg.get("role") == "assistant" and msg.get("trace"):
        print("Trace for:", msg.get("content")[:30])
        print("Rewritten Query:", msg["trace"].get("router_decisions", {}).get("rewritten_query"))
