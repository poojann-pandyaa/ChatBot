import os
import argparse
from pathlib import Path


def register_model(gguf_path, model_name="gemma2-reasoning", run_name="gguf-export-run"):
    print("=== MLflow Model Registration ===")
    print(f"Model GGUF Path: {gguf_path}")
    print(f"Model Name:      {model_name}")

    gguf_path = Path(gguf_path)
    if not gguf_path.exists():
        raise FileNotFoundError(f"GGUF model not found at {gguf_path}")

    # Read MLflow configurations
    mlflow_tracking_uri = os.getenv("MLFLOW_TRACKING_URI", "http://localhost:5000")
    print(f"MLflow Tracking URI: {mlflow_tracking_uri}")

    try:
        import mlflow
        mlflow.set_tracking_uri(mlflow_tracking_uri)
        mlflow.set_experiment("reasoning-rag-llmops")

        with mlflow.start_run(run_name=run_name) as run:
            print(f"Started MLflow run: {run.info.run_id}")
            
            # Log params
            mlflow.log_param("quantization", "Q4_K_M")
            mlflow.log_param("base_model", "google/gemma-2-2b-it")
            mlflow.log_param("artifact_size_bytes", gguf_path.stat().st_size)
            
            # Log GGUF artifact
            print("Uploading GGUF model file as artifact to MLflow...")
            mlflow.log_artifact(str(gguf_path), artifact_path="model_files")
            
            # Register in model registry
            model_uri = f"runs:/{run.info.run_id}/model_files"
            print(f"Registering model version under name: {model_name}...")
            try:
                mlflow.register_model(model_uri, model_name)
                print("Model registration complete.")
            except Exception as e:
                print(f"Could not register model in registry (check if MLflow is configured with database backend): {e}")

    except ImportError:
        print("MLflow library not installed. Skipping MLflow logging.")
        print(f"[MOCK] Registered {gguf_path.name} to local registry registry_meta.json")
        # Write local JSON file to simulate model registration
        registry_meta = {
            "model_name": model_name,
            "version": 1,
            "gguf_path": str(gguf_path.absolute()),
            "status": "Production"
        }
        with open(gguf_path.parent / "registry_meta.json", "w") as f:
            import json
            json.dump(registry_meta, f, indent=2)

    print("=== Registration Process Complete ===")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Register GGUF model artifact in MLflow")
    parser.add_argument("--path", default="outputs/gguf_export/gemma-2-2b-it-reasoning-q4_k_m.gguf", help="Path to GGUF file")
    parser.add_argument("--name", default="gemma2-reasoning", help="Registered model name")
    parser.add_argument("--run", default="gguf-export-run", help="MLflow run name")
    args = parser.parse_args()

    try:
        register_model(args.path, args.name, args.run)
    except Exception as e:
        print(f"Error: {e}")
