"""
Download the default Gemma 4 local AI model for ScreenMind.
"""
import sys
from pathlib import Path
from huggingface_hub import hf_hub_download

def main():
    base_dir = Path.home() / ".screenmind" / "models" / "gemma-4-e2b"
    variant_dir = base_dir / "Q4_0"
    variant_dir.mkdir(parents=True, exist_ok=True)
    base_dir.mkdir(parents=True, exist_ok=True)

    repo_id = "ggml-org/gemma-4-E2B-it-GGUF"
    model_file = "gemma-4-E2B-it-Q4_0.gguf"
    mmproj_file = "mmproj-gemma-4-E2B-it-Q8_0.gguf"

    print(f"1/2: Downloading {model_file} to {variant_dir}...")
    hf_hub_download(
        repo_id=repo_id,
        filename=model_file,
        local_dir=str(variant_dir)
    )
    print(f"✓ Model {model_file} downloaded successfully!")

    print(f"2/2: Downloading {mmproj_file} to {base_dir}...")
    hf_hub_download(
        repo_id=repo_id,
        filename=mmproj_file,
        local_dir=str(base_dir)
    )
    print(f"✓ Multimodal projector {mmproj_file} downloaded successfully!")

    print("\nAll local model files downloaded successfully!")

if __name__ == "__main__":
    main()
