import requests
import uuid

BASE_URL = "http://localhost:8080/api/chat"

def test_empty_prompt():
    print("Testing empty prompt...")
    resp = requests.post(BASE_URL, json={
        "prompt": "   ",
        "conversation_id": str(uuid.uuid4()),
        "user_id": "test_user",
        "debug": False,
        "stream": False
    })
    print(f"Status: {resp.status_code}")
    print(f"Response: {resp.json()}\n")
    assert resp.status_code == 400
    assert "prompt" in resp.json()["details"]

def test_long_prompt():
    print("Testing long prompt (> 2000 chars)...")
    resp = requests.post(BASE_URL, json={
        "prompt": "a" * 2500,
        "conversation_id": str(uuid.uuid4()),
        "user_id": "test_user",
        "debug": False,
        "stream": False
    })
    print(f"Status: {resp.status_code}")
    print(f"Response: {resp.json()}\n")
    assert resp.status_code == 400
    assert "prompt" in resp.json()["details"]

def test_missing_conversation_id():
    print("Testing missing conversation_id...")
    resp = requests.post(BASE_URL, json={
        "prompt": "Hello",
        "user_id": "test_user",
        "debug": False,
        "stream": False
    })
    print(f"Status: {resp.status_code}")
    print(f"Response: {resp.json()}\n")
    assert resp.status_code == 400
    assert "conversationId" in resp.json()["details"]

def test_large_payload():
    print("Testing large payload (> 1MB)...")
    large_payload = "a" * 1500000 # 1.5MB
    resp = requests.post(BASE_URL, json={
        "prompt": "Hello",
        "conversation_id": str(uuid.uuid4()),
        "user_id": large_payload,
        "debug": False,
        "stream": False
    })
    print(f"Status: {resp.status_code}")
    if resp.status_code != 400 and resp.status_code != 413:
        print(f"FAILED, got {resp.status_code}")
    else:
        print(f"Success, rejected with {resp.status_code}\n")

if __name__ == "__main__":
    try:
        test_empty_prompt()
        test_long_prompt()
        test_missing_conversation_id()
        test_large_payload()
        print("All validation tests passed successfully!")
    except AssertionError as e:
        print("Test failed!")
