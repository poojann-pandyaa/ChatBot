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
    
    response = asyncio.run(health(request))
    assert response.status_code == 503
    data = json.loads(response.body)
    assert data["status"] == "unhealthy"
    assert data["models_loaded"] is True
    assert data["grpc_running"] is False
