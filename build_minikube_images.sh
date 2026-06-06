#!/bin/bash
set -e
echo "Configuring docker environment to point to Minikube..."
eval $(minikube docker-env)

echo "Preparing ML Service HuggingFace cache..."
cp -r services/rag-engine/hf_cache services/ml-service/hf_cache

echo "Building ML Service image..."
docker build -t poojan/ml-service:latest services/ml-service/

echo "Cleaning ML Service build cache..."
rm -rf services/ml-service/hf_cache

echo "Building RAG Engine image..."
docker build -t poojan/rag-engine:latest services/rag-engine/

echo "Building App Gateway image..."
docker build -t poojan/app-gateway:latest services/app-gateway/

echo "Building Frontend image..."
docker build -t poojan/frontend:latest services/frontend/

echo "All images built successfully in Minikube!"
