import requests
import json
import time

def login():
    res = requests.post("http://localhost:8081/api/auth/login", json={"user_id": "alice", "password": "alice123"}).json()
    return res["token"]

def send_chat(token, conv_id, prompt):
    headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    payload = {"prompt": prompt, "conversation_id": conv_id, "userId": "alice", "stream": False, "debug": True}
    res = requests.post("http://localhost:8081/api/chat", headers=headers, json=payload)
    if res.status_code != 200:
        print("ERROR:", res.text)
        return None
    return res.json()

token = login()
conv_id = "test-conv-001"
print("Sending Q1: explain OOPS")
q1 = send_chat(token, conv_id, "explain OOPS")
print("Q1 Trace:", q1.get("trace", {}))

time.sleep(1)

print("\nSending Q2: how does garbage collection work in java")
q2 = send_chat(token, conv_id, "how does garbage collection work in java")
if q2:
    trace = q2.get("trace", {})
    decisions = trace.get("router_decisions", {})
    is_followup = decisions.get("is_followup")
    print("Q2 Is Followup:", is_followup)
    print("Q2 Cache Hit:", decisions.get("cache_hit"))
    print("Q2 Answer length:", len(q2.get("answer", "")))
