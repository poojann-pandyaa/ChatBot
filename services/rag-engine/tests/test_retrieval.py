import pytest
from unittest.mock import AsyncMock, MagicMock, patch
import numpy as np

# We patch torch and transformers during import/setup to avoid downloading huge models during tests
with patch("transformers.AutoTokenizer.from_pretrained"), \
     patch("transformers.AutoModel.from_pretrained"):
    from src.retrieval.hybrid_search import HybridRetriever


@pytest.fixture
def mock_retriever():
    with patch("transformers.AutoTokenizer.from_pretrained"), \
         patch("transformers.AutoModel.from_pretrained"):
        retriever = HybridRetriever(
            qdrant_url="http://mock-qdrant:6333",
            es_url="http://mock-es:9200"
        )
        # Mock the embedding method
        retriever._embed = MagicMock(return_value=np.zeros(768, dtype=np.float32))
        return retriever


@pytest.mark.asyncio
async def test_dense_search(mock_retriever):
    # Setup mock hits
    mock_hit = MagicMock()
    mock_hit.id = 42
    mock_hit.payload = {"chunk_text": "dense mock text"}
    
    mock_retriever.qdrant_client.search = AsyncMock(return_value=[mock_hit])
    
    results = await mock_retriever._dense_search(np.zeros(768), top_k=1)
    
    assert len(results) == 1
    assert results[0]["chunk_id"] == 42
    assert results[0]["payload"]["chunk_text"] == "dense mock text"
    mock_retriever.qdrant_client.search.assert_called_once()


@pytest.mark.asyncio
async def test_sparse_search(mock_retriever):
    # Setup mock Elasticsearch hits
    mock_es_response = {
        "hits": {
            "hits": [
                {
                    "_id": "99",
                    "_source": {"chunk_text": "sparse mock text"}
                }
            ]
        }
    }
    mock_retriever.es_client.search = AsyncMock(return_value=mock_es_response)
    
    results = await mock_retriever._sparse_search("query text", top_k=1)
    
    assert len(results) == 1
    assert results[0]["chunk_id"] == 99
    assert results[0]["payload"]["chunk_text"] == "sparse mock text"
    mock_retriever.es_client.search.assert_called_once()


@pytest.mark.asyncio
async def test_hybrid_retrieve(mock_retriever):
    # Setup both searches to return mock results
    mock_dense_results = [
        {"chunk_id": 1, "payload": {"chunk_text": "result 1"}}
    ]
    mock_sparse_results = [
        {"chunk_id": 1, "payload": {"chunk_text": "result 1"}},
        {"chunk_id": 2, "payload": {"chunk_text": "result 2"}}
    ]
    
    mock_retriever._dense_search = AsyncMock(return_value=mock_dense_results)
    mock_retriever._sparse_search = AsyncMock(return_value=mock_sparse_results)
    
    fused_results = await mock_retriever.hybrid_retrieve("search query", top_k=5)
    
    # Verify RRF fusion worked: chunk_id 1 is present in both, so it should be ranked first
    assert len(fused_results) == 2
    assert fused_results[0]["chunk_id"] == 1
    assert fused_results[0]["metadata"]["chunk_text"] == "result 1"
    assert fused_results[1]["chunk_id"] == 2
