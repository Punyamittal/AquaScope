#!/usr/bin/env python3
"""
AquaScope coding helper via Experiential Labs (OpenAI-compatible).

Usage (PowerShell):
  $env:EXPLABS_API_KEY = "xpl_..."
  python tools/explabs_chat.py "Explain AudioEngine playAndRecord briefly"

Or copy .env.example -> .env and load it yourself.

Models (exact slugs):
  gpt-6-astra          — requires purchased credits
  claude-fable-5.1     — requires purchased credits
  gpt-5.6-luna         — free-tier (default until you buy credits)
  deepseek-v4-flash, deepseek-v4.1-flash, qwen3.8-27b — also free-tier

Docs: https://platform.experientiallabs.ai/docs/reference
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENV_FILE = ROOT / ".env"


def load_dotenv(path: Path) -> None:
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def chat(prompt: str) -> dict:
    load_dotenv(ENV_FILE)
    key = os.environ.get("EXPLABS_API_KEY", "").strip()
    if not key or key.startswith("xpl_your_"):
        raise SystemExit(
            "Set EXPLABS_API_KEY in .env or the environment.\n"
            "Create/manage keys: https://platform.experientiallabs.ai/api-keys"
        )

    base = os.environ.get("EXPLABS_BASE_URL", "https://api.experientiallabs.ai/v1").rstrip("/")
    model = os.environ.get("EXPLABS_MODEL", "gpt-5.6-luna")

    body = {
        "model": model,
        "messages": [
            {
                "role": "system",
                "content": (
                    "You are a coding assistant for AquaScope, an Android Kotlin app that "
                    "uses speaker+mic contact chirps for moisture anomaly detection on iQOO 15. "
                    "Be concise and concrete about DSP, AudioTrack/AudioRecord, and Kotlin."
                ),
            },
            {"role": "user", "content": prompt},
        ],
        "max_tokens": 1024,
    }

    req = urllib.request.Request(
        f"{base}/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=180) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", errors="replace")
        raise SystemExit(f"HTTP {e.code}: {detail}") from e


def main() -> None:
    prompt = " ".join(sys.argv[1:]).strip() or "Summarize the AquaScope DSP pipeline in 5 bullets."
    data = chat(prompt)
    msg = data["choices"][0]["message"]["content"]
    usage = data.get("usage", {})
    print(msg)
    print()
    print(f"[model={os.environ.get('EXPLABS_MODEL', 'gpt-5.6-luna')} usage={usage}]")


if __name__ == "__main__":
    main()
