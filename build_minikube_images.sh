#!/bin/bash
set -e
echo "Configuring docker environment to point to Minikube..."
eval $(minikube docker-env)

echo "Building ML Service image..."
docker build -t poojannpandyaa/ml-service:latest services/ml-service/

echo "Building RAG Engine image..."
docker build -f services/rag-engine/Dockerfile -t poojannpandyaa/rag-engine:latest .

echo "Building App Gateway image..."
docker build -f services/app-gateway/Dockerfile -t poojannpandyaa/app-gateway:latest .

echo "Building Frontend image..."
docker build -t poojannpandyaa/frontend:latest services/frontend/

echo "All images built successfully in Minikube!"
