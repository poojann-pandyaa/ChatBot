import pytest
from unittest.mock import MagicMock
import sys
import os

# Add current directory to path so generated stubs import correctly
sys.path.append(os.path.dirname(__file__))

import ml_service_pb2
from grpc_server import MlServiceServicer


def test_classify_facade():
    # Mock the existing fastapi classify_endpoint return value
    mock_classify = MagicMock(return_value={
        "intent": "conceptual",
        "reasoning_type": "adaptive",
        "entities": ["LoRA"],
        "scope": "multi_topic",
        "ambiguity": "medium",
        "sub_questions": ["What is LoRA?", "How to implement it?"]
    })

    servicer = MlServiceServicer(mock_classify, MagicMock(), MagicMock())
    request = ml_service_pb2.ClassifyRequest(query="What is LoRA and how to implement it?")
    context = MagicMock()

    resp = servicer.Classify(request, context)

    assert resp.intent == "conceptual"
    assert resp.reasoning_type == "adaptive"
    assert resp.entities == ["LoRA"]
    assert resp.scope == "multi_topic"
    assert resp.sub_questions == ["What is LoRA?", "How to implement it?"]
    mock_classify.assert_called_once()


def test_embed_facade():
    mock_embed = MagicMock(return_value={
        "embedding": [0.1, 0.2, 0.3]
    })

    servicer = MlServiceServicer(MagicMock(), mock_embed, MagicMock())
    request = ml_service_pb2.EmbedRequest(text="Hello world")
    context = MagicMock()

    resp = servicer.Embed(request, context)

    assert list(resp.embedding) == pytest.approx([0.1, 0.2, 0.3])
    mock_embed.assert_called_once()


def test_rerank_facade():
    mock_rerank = MagicMock(return_value={
        "scores": [0.85, 0.45]
    })

    servicer = MlServiceServicer(MagicMock(), MagicMock(), mock_rerank)
    request = ml_service_pb2.RerankRequest(query="Query", documents=["doc1", "doc2"])
    context = MagicMock()

    resp = servicer.Rerank(request, context)

    assert list(resp.scores) == pytest.approx([0.85, 0.45])
    mock_rerank.assert_called_once()
