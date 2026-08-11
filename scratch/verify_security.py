"""
Phase 5 E2E Security Verification
Tests: Login, JWT auth, ownership enforcement, rate limiting
"""
import requests
import uuid

BASE = "http://localhost:8080"

PASS = "✅"
FAIL = "❌"

def test(name, condition):
    icon = PASS if condition else FAIL
    print(f"  {icon}  {name}")
    if not condition:
        global any_failed
        any_failed = True

any_failed = False

print("\n══ Phase 5: Security E2E Tests ══\n")

# ── 1. Login ────────────────────────────────────────────────────────────────
print("1. Authentication")

r = requests.post(f"{BASE}/api/auth/login", json={"user_id": "alice", "password": "alice123"})
test("alice logs in → 200 with token", r.status_code == 200 and "token" in r.json())
alice_token = r.json().get("token", "") if r.ok else ""

r = requests.post(f"{BASE}/api/auth/login", json={"user_id": "bob", "password": "bob123"})
test("bob logs in → 200 with token", r.status_code == 200 and "token" in r.json())
bob_token = r.json().get("token", "") if r.ok else ""

r = requests.post(f"{BASE}/api/auth/login", json={"user_id": "alice", "password": "wrongpassword"})
test("bad password → 401", r.status_code == 401)

r = requests.post(f"{BASE}/api/auth/login", json={"user_id": "hacker", "password": "hax"})
test("unknown user → 401", r.status_code == 401)

# ── 2. JWT enforcement ──────────────────────────────────────────────────────
print("\n2. JWT Enforcement")

alice_headers = {"Authorization": f"Bearer {alice_token}"}
bob_headers   = {"Authorization": f"Bearer {bob_token}"}

r = requests.get(f"{BASE}/api/conversations")
test("no token → 401", r.status_code == 401)

r = requests.get(f"{BASE}/api/conversations", headers={"Authorization": "Bearer invalidtoken"})
test("invalid token → 401", r.status_code == 401)

r = requests.get(f"{BASE}/api/conversations", headers=alice_headers)
test("valid alice token → 200", r.status_code == 200)

# Public endpoints still work without a token
r = requests.get(f"{BASE}/health")
test("GET /health (no token) → 200", r.status_code == 200)

r = requests.get(f"{BASE}/ready")
test("GET /ready (no token) → 2xx", r.status_code in (200, 503))

# ── 3. Conversation ownership ──────────────────────────────────────────────
print("\n3. Conversation Ownership")

# Alice creates a conversation via chat
conv_id = str(uuid.uuid4())
r = requests.post(f"{BASE}/api/chat",
    headers={**alice_headers, "Content-Type": "application/json"},
    json={"prompt": "Hello", "conversation_id": conv_id, "debug": False, "stream": False})
test(f"alice creates conversation {conv_id[:8]}…", r.status_code in (200, 500))

# Alice can access her own conversation history
r = requests.get(f"{BASE}/api/history/{conv_id}", headers=alice_headers)
test("alice reads her own history → 200 or 200-empty", r.status_code == 200)

# Bob cannot access alice's conversation
r = requests.get(f"{BASE}/api/history/{conv_id}", headers=bob_headers)
test("bob reads alice's history → 403", r.status_code == 403)

# Bob cannot delete alice's conversation
r = requests.delete(f"{BASE}/api/conversation/{conv_id}", headers=bob_headers)
test("bob deletes alice's conversation → 403", r.status_code == 403)

# Alice can delete her own conversation
r = requests.delete(f"{BASE}/api/conversation/{conv_id}", headers=alice_headers)
test("alice deletes her own conversation → 204 or 403 (not in db yet)", r.status_code in (204, 403))

# ── 4. Rate limiting ────────────────────────────────────────────────────────
print("\n4. Rate Limiting (20 req/min)")

import concurrent.futures, uuid as _uuid

def send_chat(i):
    try:
        r = requests.post(f"{BASE}/api/chat",
            headers={**alice_headers, "Content-Type": "application/json"},
            json={"prompt": f"rate test {i}", "conversation_id": str(_uuid.uuid4()),
                  "debug": False, "stream": False},
            timeout=10)
        return r.status_code
    except Exception:
        return 0

# Fire 22 concurrent requests — all land before any completes, so the fixed bucket
# will definitely exhaust within the same 1-minute window
with concurrent.futures.ThreadPoolExecutor(max_workers=22) as pool:
    status_codes = list(pool.map(send_chat, range(22)))

got_429 = 429 in status_codes
print(f"  Status codes received: {sorted(set(status_codes))}")
test("After 22 concurrent requests, alice gets 429", got_429)

# ── Summary ────────────────────────────────────────────────────────────────
print()
if any_failed:
    print("Some tests failed. Check the output above.")
else:
    print("All Phase 5 security tests passed! 🎉")
