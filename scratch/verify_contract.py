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

def chat(conv_id, prompt, retries=3):
    for attempt in range(retries):
        r = requests.post(f"{BASE}/api/chat", json={
            "prompt": prompt, "conversation_id": conv_id,
            "user_id": USER_ID, "debug": False, "stream": False
        }, timeout=60)
        if r.status_code == 200:
            return r
        print(f"  ⚠️  Chat attempt {attempt+1} got {r.status_code}, retrying in 5s...")
        time.sleep(5)
    raise AssertionError(f"Chat failed after {retries} attempts")

def wait_for_gateway():
    print("Waiting for app-gateway...")
    for _ in range(30):
        try:
            r = requests.get(f"{BASE}/health", timeout=5)
            if r.status_code == 200:
                print("✅ Gateway healthy\n")
                return
        except:
            pass
        time.sleep(2)
    raise RuntimeError("Gateway never became healthy")

# ─── TEST 1: Create + verify list ────────────────────────────────────────────
def test_create_and_list():
    print("=== TEST 1: Create conversation + verify it appears in list ===")
    conv_id = str(uuid.uuid4())
    r = chat(conv_id, "What is LoRA?")
    print(f"  Chat {conv_id[:8]}...: {r.status_code}")
    time.sleep(3)

    resp = requests.get(f"{BASE}/api/conversations", headers=HEADERS)
    ids = [c["id"] for c in resp.json()]
    assert conv_id in ids, f"conv missing from list: {ids[:5]}"
    print(f"  ✅ GET /api/conversations returned {len(ids)} convs, new conv present\n")
    return conv_id

# ─── TEST 2: Delete ───────────────────────────────────────────────────────────
def test_delete(conv_id):
    print(f"=== TEST 2: Delete conversation {conv_id[:8]}... ===")
    r = requests.delete(f"{BASE}/api/conversation/{conv_id}")
    assert r.status_code == 204, f"Expected 204, got {r.status_code}"
    print(f"  DELETE returned 204")

    ids = [c["id"] for c in requests.get(f"{BASE}/api/conversations", headers=HEADERS).json()]
    assert conv_id not in ids, "Deleted conv still in list!"
    print(f"  ✅ Deleted conv no longer in list")

    pg_out = pg(f"SELECT COUNT(*) FROM shard_0.conversations WHERE id='{conv_id}';")
    pg1_out = pg(f"SELECT COUNT(*) FROM shard_1.conversations WHERE id='{conv_id}';")
    total = int(pg_out.split('\n')[2].strip()) + int(pg1_out.split('\n')[2].strip())
    assert total == 0, f"Still in Postgres! shard_0={pg_out} shard_1={pg1_out}"
    print(f"  ✅ Deleted from Postgres across all shards\n")

# ─── TEST 3: Rename ───────────────────────────────────────────────────────────
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

    # Verify in Postgres (check both shards)
    for shard in ["shard_0", "shard_1"]:
        pg_out = pg(f"SELECT title FROM {shard}.conversations WHERE id='{conv_id}';")
        if "(1 row)" in pg_out:
            assert new_name in pg_out, f"Title not updated in {shard}: {pg_out}"
            print(f"  ✅ Title persisted in {shard}\n")
            return
    raise AssertionError(f"Conversation {conv_id} not found in any shard after rename!")

# ─── TEST 4: Clear all ────────────────────────────────────────────────────────
def test_clear_all():
    print("=== TEST 4: Clear all conversations ===")
    conv_id = str(uuid.uuid4())
    chat(conv_id, "Attention mechanism test")
    time.sleep(3)

    r = requests.delete(f"{BASE}/api/conversations", headers=HEADERS)
    assert r.status_code == 204, f"Expected 204, got {r.status_code}"

    remaining = requests.get(f"{BASE}/api/conversations", headers=HEADERS).json()
    assert len(remaining) == 0, f"Expected empty list, got: {remaining}"
    print(f"  ✅ DELETE /api/conversations cleared all — list is empty")

    for shard in ["shard_0", "shard_1"]:
        pg_out = pg(f"SELECT COUNT(*) FROM {shard}.conversations WHERE user_id='{USER_ID}';")
        count = int(pg_out.split('\n')[2].strip())
        assert count == 0, f"{shard} still has {count} rows"
    print(f"  ✅ Postgres empty for user across all shards\n")

# ─── Main ─────────────────────────────────────────────────────────────────────
if __name__ == "__main__":
    wait_for_gateway()

    # Test 1: Create + list
    conv1 = test_create_and_list()

    # Test 2: Delete conv1
    test_delete(conv1)

    # Test 3: Create a fresh conv then rename it
    conv_for_rename = str(uuid.uuid4())
    chat(conv_for_rename, "What is attention mechanism?")
    time.sleep(3)
    test_rename(conv_for_rename)

    # Test 4: Clear everything
    test_clear_all()

    print("🎉 All verification tests passed!")
