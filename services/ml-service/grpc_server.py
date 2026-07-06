"""
gRPC server facade for ml-service.

Wraps the three existing FastAPI endpoint functions:
  - classify_endpoint  → MlService.Classify RPC
  - embed_endpoint     → MlService.Embed RPC
  - rerank_endpoint    → MlService.Rerank RPC

The inference code (load_models, classifier_pipeline, embed_model,
reranker_model) is NOT touched — this file only wraps it.

Run alongside the FastAPI app (started from app.py as a background thread).
"""

import logging
import concurrent.futures
from typing import Any

import grpc

# Generated stubs (generated via grpcio-tools from proto/ml_service.proto)
import ml_service_pb2
import ml_service_pb2_grpc

logger = logging.getLogger(__name__)


class MlServiceServicer(ml_service_pb2_grpc.MlServiceServicer):
    """gRPC facade: delegates every RPC to the existing endpoint functions."""

    def __init__(self, classify_fn, embed_fn, rerank_fn):
        """
        Args:
            classify_fn: reference to app.classify_endpoint
            embed_fn:    reference to app.embed_endpoint
            rerank_fn:   reference to app.rerank_endpoint
        """
        self._classify = classify_fn
        self._embed = embed_fn
        self._rerank = rerank_fn

    # ── Classify ────────────────────────────────────────────────────────────

    def Classify(self, request, context):
        """Classify RPC — calls classify_endpoint directly."""
        logger.info("gRPC Classify called for query: %s", request.query[:80])
        try:
            from app import ClassifyRequest as PyClassifyRequest
            py_req = PyClassifyRequest(query=request.query)
            result = self._classify(py_req)
            # result is a dict (FastAPI returns dict for ClassifyResponse)
            if not isinstance(result, dict):
                result = result.dict()
            return ml_service_pb2.ClassifyResponse(
                intent=result.get("intent", "factual"),
                reasoning_type=result.get("reasoning_type", "commonsense"),
                entities=result.get("entities", []),
                scope=result.get("scope", "single_topic"),
                ambiguity=result.get("ambiguity", "low"),
                sub_questions=result.get("sub_questions", [request.query]),
            )
        except Exception as e:
            logger.error("gRPC Classify error: %s", e)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return ml_service_pb2.ClassifyResponse()

    # ── Embed ────────────────────────────────────────────────────────────────

    def Embed(self, request, context):
        """Embed RPC — calls embed_endpoint directly."""
        logger.info("gRPC Embed called for text[:80]: %s", request.text[:80])
        try:
            from app import EmbedRequest as PyEmbedRequest
            py_req = PyEmbedRequest(text=request.text)
            result = self._embed(py_req)
            if not isinstance(result, dict):
                result = result.dict()
            return ml_service_pb2.EmbedResponse(
                embedding=result.get("embedding", [])
            )
        except Exception as e:
            logger.error("gRPC Embed error: %s", e)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return ml_service_pb2.EmbedResponse()

    # ── Rerank ───────────────────────────────────────────────────────────────

    def Rerank(self, request, context):
        """Rerank RPC — calls rerank_endpoint directly."""
        logger.info("gRPC Rerank called for query: %s, %d docs",
                    request.query[:80], len(request.documents))
        try:
            from app import RerankRequest as PyRerankRequest
            py_req = PyRerankRequest(query=request.query, documents=list(request.documents))
            result = self._rerank(py_req)
            if not isinstance(result, dict):
                result = result.dict()
            return ml_service_pb2.RerankResponse(
                scores=result.get("scores", [])
            )
        except Exception as e:
            logger.error("gRPC Rerank error: %s", e)
            context.set_code(grpc.StatusCode.INTERNAL)
            context.set_details(str(e))
            return ml_service_pb2.RerankResponse()


def serve(classify_fn, embed_fn, rerank_fn, port: int = 50051):
    """
    Start the gRPC server on the given port.
    This function blocks the calling thread until the server is stopped.
    Call it from a daemon thread in app.py.
    """
    server = grpc.server(concurrent.futures.ThreadPoolExecutor(max_workers=4))
    servicer = MlServiceServicer(classify_fn, embed_fn, rerank_fn)
    ml_service_pb2_grpc.add_MlServiceServicer_to_server(servicer, server)
    server.add_insecure_port(f"[::]:{port}")
    server.start()
    logger.info("gRPC server started on port %d", port)
    server.wait_for_termination()
