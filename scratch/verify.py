import requests
import subprocess
import time
import uuid

def main():
    print("Wait for app-gateway to be healthy...")
    while True:
        try:
            res = requests.get("http://localhost:8080/health")
            if res.status_code == 200:
                print("App Gateway is healthy!")
                break
        except requests.exceptions.ConnectionError:
            pass
        time.sleep(2)

    conv_id = str(uuid.uuid4())
    user_id = "test-user-1"
    
    print("\n1. Sending chat message...")
    res = requests.post("http://localhost:8080/api/chat", json={
        "prompt": "Hello, what is LoRA?",
        "conversation_id": conv_id,
        "user_id": user_id,
        "debug": False,
        "stream": False
    })
    print(f"Chat response: {res.status_code} - {res.text[:200]}")
    
    # Wait for async outbox consumer and command service
    time.sleep(3)
    
    print("\n2. Checking Postgres for 2 rows...")
    pg_cmd = f"docker exec postgres-dev psql -U postgres -d chatbot_db -c \"SELECT role, LEFT(content,40) FROM shard_0.messages WHERE conversation_id='{conv_id}';\""
    pg_out = subprocess.check_output(pg_cmd, shell=True).decode("utf-8")
    print(pg_out)
    if "user" in pg_out and "assistant" in pg_out:
        print("✅ Postgres has the 2 rows!")
    else:
        print("❌ Postgres missing rows!")

    print("\n3. Flushing Redis...")
    subprocess.check_call("docker exec redis-dev redis-cli FLUSHALL", shell=True)
    print("Redis flushed.")
    
    print("\n4. Calling /api/history to test fallback...")
    res = requests.get(f"http://localhost:8080/api/history/{conv_id}")
    history = res.json()
    print(f"History response: {history}")
    if len(history.get("messages", [])) == 2:
        print("✅ Rebuilt history from Postgres successfully!")
    else:
        print("❌ Failed to rebuild history!")

if __name__ == "__main__":
    main()
