import asyncio
import json
from unittest.mock import MagicMock
from fastapi import Request
from app import health

def test_health_endpoint_grpc_failed():
    """Asserts /health returns 503 when grpc_running is False even if models_loaded is True."""
    request = MagicMock(spec=Request)
    request.app.state.models_loaded = True
    request.app.state.grpc_running = False
    request.app.state.load_error = None
    request.app.state.grpc_error = None
    
    response = asyncio.run(health(request))
    assert response.status_code == 503
    data = json.loads(response.body)
    assert data["status"] == "unhealthy"
    assert data["models_loaded"] is True
    assert data["grpc_running"] is False
    assert data["grpc_error"] == "gRPC not started"

def test_health_endpoint_models_failed():
    """Asserts /health returns 503 when models_loaded is False."""
    request = MagicMock(spec=Request)
    request.app.state.models_loaded = False
    request.app.state.grpc_running = True
    request.app.state.load_error = "OOM killer"
    request.app.state.grpc_error = None

    response = asyncio.run(health(request))
    assert response.status_code == 503
    data = json.loads(response.body)
    assert data["status"] == "unhealthy"
    assert data["models_loaded"] is False
    assert data["grpc_running"] is True
    assert data["error"] == "OOM killer"

def test_health_endpoint_all_healthy():
    """Asserts /health returns 200 when both models and gRPC are up."""
    request = MagicMock(spec=Request)
    request.app.state.models_loaded = True
    request.app.state.grpc_running = True
    request.app.state.load_error = None
    request.app.state.grpc_error = None

    response = asyncio.run(health(request))
    assert response.status_code == 200
    data = json.loads(response.body)
    assert data["status"] == "healthy"
    assert data["models_loaded"] is True
    assert data["grpc_running"] is True
    assert "error" not in data
    assert "grpc_error" not in data
