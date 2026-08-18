import asyncio
import sys
sys.path.append('.')
from app import health
from unittest.mock import MagicMock
import json

def test_health_endpoint_grpc_failed():
    request = MagicMock()
    request.app.state.models_loaded = True
    request.app.state.grpc_running = False
    
    response = asyncio.run(health(request))
    print("STATUS:", response.status_code)
    print("BODY:", json.loads(response.body))

test_health_endpoint_grpc_failed()
