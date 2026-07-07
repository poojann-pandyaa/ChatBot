import os
import sys
import time
import pytest
import grpc
from unittest.mock import MagicMock

# Add current directory to path so generated stubs import correctly
sys.path.append(os.path.dirname(__file__))

import ml_service_pb2
import ml_service_pb2_grpc
import grpc_server

# Create mocks for the FastAPI endpoints
mock_classify = MagicMock(return_value={
    "intent": "conceptual",
    "reasoning_type": "adaptive",
    "entities": ["LoRA"],
    "scope": "multi_topic",
    "ambiguity": "medium",
    "sub_questions": ["What is LoRA?", "How to implement it?"]
})

mock_embed = MagicMock(return_value={
    "embedding": [0.1, 0.2, 0.3]
})

mock_rerank = MagicMock(return_value={
    "scores": [0.85, 0.45]
})

@pytest.fixture(scope="module")
def live_grpc_server():
    """Starts a live gRPC server on a dynamic port in a background thread."""
    import threading
    server = grpc.server(concurrent.futures.ThreadPoolExecutor(max_workers=2))
    servicer = grpc_server.MlServiceServicer(mock_classify, mock_embed, mock_rerank)
    ml_service_pb2_grpc.add_MlServiceServicer_to_server(servicer, server)
    port = server.add_insecure_port("127.0.0.1:0") # Bind to a free dynamic port
    server.start()
    
    # Yield the server target address to the tests
    yield f"127.0.0.1:{port}"
    
    server.stop(0)

import concurrent.futures

def test_live_grpc_classify(live_grpc_server):
    """Verifies Classify RPC over a real TCP channel against the live server."""
    with grpc.insecure_channel(live_grpc_server) as channel:
        stub = ml_service_pb2_grpc.MlServiceStub(channel)
        req = ml_service_pb2.ClassifyRequest(query="What is LoRA?")
        resp = stub.Classify(req)
        
        assert resp.intent == "conceptual"
        assert resp.reasoning_type == "adaptive"
        assert resp.entities == ["LoRA"]
        assert resp.scope == "multi_topic"
        assert resp.sub_questions == ["What is LoRA?", "How to implement it?"]

def test_live_grpc_embed(live_grpc_server):
    """Verifies Embed RPC over a real TCP channel against the live server."""
    with grpc.insecure_channel(live_grpc_server) as channel:
        stub = ml_service_pb2_grpc.MlServiceStub(channel)
        req = ml_service_pb2.EmbedRequest(text="Hello world")
        resp = stub.Embed(req)
        
        assert list(resp.embedding) == pytest.approx([0.1, 0.2, 0.3])

def test_live_grpc_rerank(live_grpc_server):
    """Verifies Rerank RPC over a real TCP channel against the live server."""
    with grpc.insecure_channel(live_grpc_server) as channel:
        stub = ml_service_pb2_grpc.MlServiceStub(channel)
        req = ml_service_pb2.RerankRequest(query="Query", documents=["doc1", "doc2"])
        resp = stub.Rerank(req)
        
        assert list(resp.scores) == pytest.approx([0.85, 0.45])
