#!/bin/bash
set -e

DOCKER_REGISTRY="${DOCKER_REGISTRY:-poojan}"
IMAGE_TAG="${IMAGE_TAG:-latest}"

echo "Configuring docker environment to point to Minikube..."
eval $(minikube docker-env)

echo "Preparing ML Service HuggingFace cache..."
mkdir -p services/ml-service/hf_cache
if [ -d services/rag-engine/hf_cache ]; then
  cp -R services/rag-engine/hf_cache/. services/ml-service/hf_cache/
  echo "Copied HuggingFace cache from services/rag-engine/hf_cache."
else
  echo "No services/rag-engine/hf_cache directory found; continuing with empty services/ml-service/hf_cache."
fi

echo "Building ML Service image..."
docker build -t "${DOCKER_REGISTRY}/ml-service:${IMAGE_TAG}" services/ml-service/

echo "Cleaning ML Service build cache..."
rm -rf services/ml-service/hf_cache

echo "Building RAG Engine image..."
docker build -f services/rag-engine/Dockerfile -t "${DOCKER_REGISTRY}/rag-engine:${IMAGE_TAG}" .

echo "Building App Gateway image..."
docker build -f services/app-gateway/Dockerfile -t "${DOCKER_REGISTRY}/app-gateway:${IMAGE_TAG}" .

echo "Building Frontend image..."
docker build -t "${DOCKER_REGISTRY}/frontend:${IMAGE_TAG}" services/frontend/

echo "All images built successfully in Minikube!"
