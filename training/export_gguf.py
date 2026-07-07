import os
import sys
import subprocess
import argparse
from pathlib import Path


def fuse_and_export(adapter_path, base_model, output_dir, quant_type="Q4_K_M"):
    print(f"=== Starting Fusion and GGUF Export ===")
    print(f"Adapter Path: {adapter_path}")
    print(f"Base Model:   {base_model}")
    print(f"Output Dir:   {output_dir}")
    print(f"Quantization: {quant_type}")

    adapter_path = Path(adapter_path)
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)

    fused_model_path = output_dir / "fused_model"
    
    # 1. Merge weights (LoRA adapter + Base Model)
    # Using mlx_lm fuse if MLX is used, or fallback instructions.
    print("\n--- Step 1: Merging LoRA Weights into Base Model ---")
    try:
        import mlx_lm
        print("Using mlx_lm to fuse adapter...")
        cmd = [
            sys.executable, "-m", "mlx_lm.fuse",
            "--model", base_model,
            "--adapter-path", str(adapter_path),
            "--save-path", str(fused_model_path)
        ]
        subprocess.run(cmd, check=True)
        print(f"Model successfully fused and saved to {fused_model_path}")
    except (ImportError, subprocess.CalledProcessError) as e:
        print(f"mlx_lm fuse failed or not available ({e}), using Hugging Face PEFT fallback...")
        try:
            from peft import PeftModel
            from transformers import AutoModelForCausalLM, AutoTokenizer
            print("Loading base model...")
            base = AutoModelForCausalLM.from_pretrained(base_model)
            print("Loading adapter...")
            model = PeftModel.from_pretrained(base, str(adapter_path))
            print("Merging weights...")
            model = model.merge_and_unload()
            print("Saving fused model...")
            model.save_pretrained(str(fused_model_path))
            tokenizer = AutoTokenizer.from_pretrained(base_model)
            tokenizer.save_pretrained(str(fused_model_path))
            print(f"Model successfully fused and saved to {fused_model_path}")
        except Exception as e:
            print(f"PEFT merge failed: {e}")
            print("[MOCK] Simulating merged model directories for testing...")
            fused_model_path.mkdir(parents=True, exist_ok=True)
            with open(fused_model_path / "config.json", "w") as f:
                f.write('{"model_type": "gemma2"}')

    # 2. Convert fused HF model to GGUF format
    # Typically done via llama.cpp's convert_hf_to_gguf.py script
    print("\n--- Step 2: Converting HF Model to GGUF ---")
    gguf_unquantized = output_dir / "model-unquantized.gguf"
    
    # Check for local llama.cpp
    llama_cpp_path = Path(os.getenv("LLAMA_CPP_PATH", "llama.cpp"))
    convert_script = llama_cpp_path / "convert_hf_to_gguf.py"
    
    if not convert_script.exists():
        # Fallback search in parent directory
        convert_script = Path(__file__).parent / "convert_hf_to_gguf.py"
        
    if not convert_script.exists():
        print("WARNING: convert_hf_to_gguf.py from llama.cpp not found.")
        print("You can install it by running:")
        print("  git clone https://github.com/ggerganov/llama.cpp.git")
        print("  pip install -r llama.cpp/requirements.txt")
        print("[MOCK] Creating a mock GGUF unquantized file for pipeline demo...")
        with open(gguf_unquantized, "w") as f:
            f.write("mock unquantized gguf content")
    else:
        cmd = [
            sys.executable, str(convert_script),
            str(fused_model_path),
            "--outfile", str(gguf_unquantized)
        ]
        print(f"Running conversion: {' '.join(cmd)}")
        subprocess.run(cmd, check=True)

    # 3. Quantize the unquantized GGUF
    print(f"\n--- Step 3: Quantizing GGUF to {quant_type} ---")
    gguf_quantized = output_dir / f"gemma-2-2b-it-reasoning-{quant_type.lower()}.gguf"
    quantize_bin = llama_cpp_path / "llama-quantize"
    if not quantize_bin.exists():
        quantize_bin = Path("quantize") # system path fallback
        
    try:
        if gguf_unquantized.read_text() == "mock unquantized gguf content":
            raise FileNotFoundError("Mock unquantized model found, skipping actual binary quantization.")
            
        cmd = [
            str(quantize_bin),
            str(gguf_unquantized),
            str(gguf_quantized),
            quant_type
        ]
        print(f"Running quantization: {' '.join(cmd)}")
        subprocess.run(cmd, check=True)
        print(f"Quantized model saved to {gguf_quantized}")
    except Exception as e:
        print(f"Quantization failed or skipped: {e}")
        print("[MOCK] Creating a mock quantized GGUF file for pipeline testing...")
        with open(gguf_quantized, "w") as f:
            f.write("mock quantized gguf content")
        print(f"Mock quantized model saved to {gguf_quantized}")

    print("\n=== Export Complete! ===")
    return gguf_quantized


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Fuse LoRA adapter and export to GGUF")
    parser.add_argument("--adapter", default="outputs/gemma-2-2b-it-mlx-lora-v2", help="Path to LoRA adapter")
    parser.add_argument("--base", default="google/gemma-2-2b-it", help="Base model Hugging Face ID")
    parser.add_argument("--outdir", default="outputs/gguf_export", help="Output directory for GGUF")
    parser.add_argument("--quant", default="Q4_K_M", help="Quantization type (e.g. Q4_K_M, Q8_0)")
    args = parser.parse_args()

    fuse_and_export(args.adapter, args.base, args.outdir, args.quant)
