import requests
import time

def login():
    res = requests.post("http://localhost:8081/api/auth/login", json={"user_id": "alice", "password": "alice123"}).json()
    return res["token"]

token = login()
conv_id = "test-conv-002"

# 1. Kill ml-service
import subprocess
print("Killing ml-service-dev container...")
subprocess.run(["docker", "kill", "ml-service-dev"])

# Wait for it to die
time.sleep(2)

print("Sending Q1 to trigger fallback...")
headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
payload = {"prompt": "what is rust programming", "conversation_id": conv_id, "userId": "alice", "stream": False, "debug": True}
q1 = requests.post("http://localhost:8081/api/chat", headers=headers, json=payload).json()
print("Q1 Status (should be zero-vector fallback, generating a response or fallback response):")
if "trace" in q1:
    print("Quality Score:", q1["trace"].get("router_decisions", {}).get("quality_score"))
else:
    print(q1)

# Restart ml-service
print("Restarting ml-service-dev...")
subprocess.run(["docker", "start", "ml-service-dev"])

# Wait for it to be fully healthy
for i in range(30):
    try:
        res = requests.get("http://localhost:8001/health")
        if res.status_code == 200:
            print("ML service healthy!")
            break
    except:
        pass
    time.sleep(1)

print("Sending Q2 which would match zero-vector if cached (e.g. 'what is c++ programming')...")
payload2 = {"prompt": "what is c++ programming", "conversation_id": "test-conv-003", "userId": "alice", "stream": False, "debug": True}
q2 = requests.post("http://localhost:8081/api/chat", headers=headers, json=payload2).json()
if "trace" in q2:
    cache_hit = q2["trace"].get("router_decisions", {}).get("cache_hit")
    print("Q2 Cache Hit (should be False):", cache_hit)
else:
    print("No trace in Q2", q2)
