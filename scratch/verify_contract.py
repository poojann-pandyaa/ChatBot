import requests
import subprocess
import time
import uuid

BASE = "http://localhost:8080"
USER_ID = "default_user"
HEADERS = {"X-User-Id": USER_ID}

def pg(sql):
    out = subprocess.check_output(
        f'docker exec postgres-dev psql -U postgres -d chatbot_db -c "{sql}"',
        shell=True
    ).decode()
    return out

def wait_for_gateway():
    print("Waiting for app-gateway...")
    for _ in range(30):
        try:
            r = requests.get(f"{BASE}/health")
            if r.status_code == 200:
                print("✅ Gateway healthy\n")
                return
        except:
            pass
        time.sleep(2)
    raise RuntimeError("Gateway never became healthy")

def test_create_and_list():
    print("=== TEST 1: Create 2 conversations + verify list ===")
    conv1 = str(uuid.uuid4())
    conv2 = str(uuid.uuid4())

    for conv_id, prompt in [(conv1, "What is LoRA?"), (conv2, "Explain transformers")]:
        r = requests.post(f"{BASE}/api/chat", json={
            "prompt": prompt, "conversation_id": conv_id,
            "user_id": USER_ID, "debug": False, "stream": False
        })
        print(f"  Chat {conv_id[:8]}...: {r.status_code}")
    time.sleep(3)

    r = requests.get(f"{BASE}/api/conversations", headers=HEADERS)
    data = r.json()
    ids_returned = [c["id"] for c in data]
    assert conv1 in ids_returned, f"conv1 missing from list: {data}"
    assert conv2 in ids_returned, f"conv2 missing from list: {data}"
    print(f"  ✅ GET /api/conversations returned {len(data)} conversations\n")
    return conv1, conv2

def test_delete(conv_id):
    print(f"=== TEST 2: Delete conversation {conv_id[:8]}... ===")
    r = requests.delete(f"{BASE}/api/conversation/{conv_id}")
    assert r.status_code == 204, f"Expected 204, got {r.status_code}"
    print(f"  DELETE returned 204")

    # Check it's gone from API
    r2 = requests.get(f"{BASE}/api/conversations", headers=HEADERS)
    ids = [c["id"] for c in r2.json()]
    assert conv_id not in ids, f"Deleted conv still appears in list!"
    print(f"  ✅ Deleted conv no longer in list")

    # Check it's gone from Postgres
    pg_out = pg(f"SELECT COUNT(*) FROM shard_0.conversations WHERE id='{conv_id}';")
    assert "0" in pg_out, f"Still in Postgres! {pg_out}"
    pg_msg = pg(f"SELECT COUNT(*) FROM shard_0.messages WHERE conversation_id='{conv_id}';")
    assert "0" in pg_msg, f"Messages still in Postgres! {pg_msg}"
    print(f"  ✅ Deleted from Postgres (conversations + messages)\n")

def test_rename(conv_id):
    print(f"=== TEST 3: Rename conversation {conv_id[:8]}... ===")
    new_name = "My Renamed Conversation"
    r = requests.patch(f"{BASE}/api/conversation/{conv_id}/rename",
                       json={"name": new_name},
                       headers={"Content-Type": "application/json", "X-User-Id": USER_ID})
    assert r.status_code == 200, f"Expected 200, got {r.status_code}: {r.text}"
    data = r.json()
    assert data["name"] == new_name, f"Name mismatch: {data}"
    print(f"  PATCH returned 200, name={data['name']}")

    # Verify in Postgres
    pg_out = pg(f"SELECT title FROM shard_0.conversations WHERE id='{conv_id}';")
    assert new_name in pg_out, f"Title not updated in Postgres: {pg_out}"
    print(f"  ✅ Title persisted in Postgres\n")

def test_clear_all():
    print("=== TEST 4: Clear all conversations ===")
    # Create a fresh one
    conv_id = str(uuid.uuid4())
    requests.post(f"{BASE}/api/chat", json={
        "prompt": "test clear", "conversation_id": conv_id,
        "user_id": USER_ID, "debug": False, "stream": False
    })
    time.sleep(2)

    r = requests.delete(f"{BASE}/api/conversations", headers=HEADERS)
    assert r.status_code == 204, f"Expected 204, got {r.status_code}"

    r2 = requests.get(f"{BASE}/api/conversations", headers=HEADERS)
    remaining = r2.json()
    assert len(remaining) == 0, f"Expected empty list, got: {remaining}"
    print(f"  ✅ DELETE /api/conversations cleared all — list is empty\n")

    pg_out = pg(f"SELECT COUNT(*) FROM shard_0.conversations WHERE user_id='{USER_ID}';")
    assert " 0 " in pg_out, f"Postgres still has rows: {pg_out}"
    print(f"  ✅ Postgres conversations table is empty for user\n")

if __name__ == "__main__":
    wait_for_gateway()
    conv1, conv2 = test_create_and_list()
    test_delete(conv1)
    test_rename(conv2)
    test_clear_all()
    print("🎉 All verification tests passed!")
