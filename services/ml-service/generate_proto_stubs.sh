#!/usr/bin/env bash
# generate_proto_stubs.sh
# Generates Python protobuf + gRPC stubs for ml-service from the shared proto/ directory.
# Run once before building the ml-service Docker image or starting locally.
#
# Prerequisites: pip install grpcio-tools protobuf
#
set -euo pipefail

PROTO_DIR="$(cd "$(dirname "$0")/../.." && pwd)/proto"
OUT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo "Generating Python stubs from: ${PROTO_DIR}"
echo "Output directory:             ${OUT_DIR}"

python3 -m grpc_tools.protoc \
    --proto_path="${PROTO_DIR}" \
    --python_out="${OUT_DIR}" \
    --grpc_python_out="${OUT_DIR}" \
    "${PROTO_DIR}/ml_service.proto"

echo "Done. Generated files:"
ls "${OUT_DIR}/ml_service_pb2.py" "${OUT_DIR}/ml_service_pb2_grpc.py"
