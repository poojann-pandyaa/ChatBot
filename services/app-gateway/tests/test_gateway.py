import pytest
from fastapi.testclient import TestClient
from unittest.mock import patch, MagicMock, AsyncMock

# Patch config load and redis startup to avoid real network calls
with patch("config.Config.load_from_vault"):
    from app import app

client = TestClient(app)


def test_health_endpoint():
    with patch("app.redis_client") as mock_redis:
        # Test healthy status
        mock_redis.ping = AsyncMock(return_value=True)
        response = client.get("/health")
        assert response.status_code == 200
        assert response.json()["status"] == "healthy"
        assert response.json()["redis_connected"] is True


def test_metrics_endpoint():
    response = client.get("/metrics")
    assert response.status_code == 200
    assert "gateway_requests_total" in response.text


@pytest.mark.asyncio
async def test_chat_endpoint():
    mock_rag_response = {
        "answer": "This is the RAG answer",
        "reasoning_type": "commonsense",
        "sources": [
            {
                "chunk_id": 1,
                "score": 0.9,
                "question_id": "q1",
                "is_accepted": True,
                "domain": "ubuntu",
                "chunk_text": "Ubuntu config text"
            }
        ]
    }

    # Setup mock clients
    with patch("app.redis_client") as mock_redis, \
         patch("httpx.AsyncClient.post") as mock_post:
        
        mock_redis.rpush = AsyncMock()
        
        # Mock RAG Engine response
        mock_http_response = MagicMock()
        mock_http_response.status_code = 200
        mock_http_response.json = MagicMock(return_value=mock_rag_response)
        mock_post.return_value = mock_http_response

        # Request body
        payload = {
            "prompt": "Hello platform",
            "conversation_id": "session-123",
            "debug": False
        }

        # Use test client to call the gateway endpoint
        response = client.post("/api/chat", json=payload)
        
        assert response.status_code == 200
        data = response.json()
        assert data["answer"] == "This is the RAG answer"
        assert data["reasoning_type"] == "commonsense"
        
        # Verify Redis push log is called for prompt and answer
        assert mock_redis.rpush.call_count == 2
        mock_redis.rpush.assert_any_call("chat:session-123", "user:Hello platform")
        mock_redis.rpush.assert_any_call("chat:session-123", "assistant:This is the RAG answer")


@pytest.mark.asyncio
async def test_history_endpoint():
    with patch("app.redis_client") as mock_redis:
        # Redis return format
        mock_redis.lrange = AsyncMock(return_value=[
            "user:How do I exit vim?",
            "assistant:Press Esc, then type :wq and press Enter."
        ])

        response = client.get("/api/history/session-123")
        
        assert response.status_code == 200
        data = response.json()
        assert data["conversation_id"] == "session-123"
        assert len(data["messages"]) == 2
        assert data["messages"][0]["role"] == "user"
        assert data["messages"][0]["content"] == "How do I exit vim?"
        assert data["messages"][1]["role"] == "assistant"
        assert data["messages"][1]["content"] == "Press Esc, then type :wq and press Enter."
        mock_redis.lrange.assert_called_once_with("chat:session-123", 0, -1)
