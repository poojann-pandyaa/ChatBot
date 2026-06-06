import pytest
from fastapi.testclient import TestClient
from unittest.mock import MagicMock, patch, AsyncMock

# Patch the imports/initializations inside app.py to avoid downloading models
with patch("transformers.pipeline"), \
     patch("transformers.AutoTokenizer.from_pretrained"), \
     patch("transformers.AutoModel.from_pretrained"), \
     patch("sentence_transformers.CrossEncoder"):
    from app import app

client = TestClient(app)


def test_health_endpoint():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json() == {"status": "healthy"}


def test_metrics_endpoint():
    response = client.get("/metrics")
    assert response.status_code == 200
    assert "rag_requests_total" in response.text


@patch("app.classifier.classify")
@patch("app.engine.execute")
@patch("app.engine.generator.generate")
def test_reasoning_chat_endpoint(mock_generate, mock_execute, mock_classify):
    # Setup mock classifier response
    mock_classify.return_value = {
        "intent": "procedural",
        "reasoning_type": "commonsense",
        "scope": "single_topic",
        "ambiguity": "low",
        "sub_questions": ["How do I reverse a list in Python?"]
    }

    # Setup mock engine execution trace output
    mock_trace = MagicMock()
    mock_trace.query = "How do I reverse a list in Python?"
    mock_trace.classification = mock_classify.return_value
    mock_trace.reranked_final = [
        {
            "chunk_id": 42,
            "final_score": 0.95,
            "metadata": {
                "question_id": "q123",
                "is_accepted": True,
                "domain": "stackoverflow",
                "chunk_text": "To reverse a list, use list.reverse() or my_list[::-1]."
            }
        }
    ]
    mock_trace.final_answer = "You can reverse a list using list.reverse() or slicing."
    mock_trace.to_dict.return_value = {"trace_key": "trace_val"}

    # Mock async execute method
    async def mock_async_execute(trace, *args, **kwargs):
        return mock_trace
    mock_execute.side_effect = mock_async_execute

    # Mock async generate method
    async def mock_async_generate(trace, *args, **kwargs):
        return mock_trace
    mock_generate.side_effect = mock_async_generate

    # Make request
    response = client.post(
        "/v1/reasoning-chat",
        json={"prompt": "How do I reverse a list?", "include_trace": True}
    )

    assert response.status_code == 200
    data = response.json()
    assert data["answer"] == "You can reverse a list using list.reverse() or slicing."
    assert data["reasoning_type"] == "commonsense"
    assert len(data["sources"]) == 1
    assert data["sources"][0]["chunk_id"] == 42
    assert data["sources"][0]["domain"] == "stackoverflow"
    assert data["trace"] == {"trace_key": "trace_val"}
