"""One-off verification: load ai4bharat/indic-parler-tts and synthesize a clip."""
import sys
import time

import soundfile as sf
import torch
from parler_tts import ParlerTTSForConditionalGeneration
from transformers import AutoTokenizer

MODEL = "ai4bharat/indic-parler-tts"
OUT = "tools/tts_verify_output.wav"

t0 = time.time()
device = "cuda" if torch.cuda.is_available() else "cpu"
print(f"Loading {MODEL} on {device} ...", flush=True)
model = ParlerTTSForConditionalGeneration.from_pretrained(MODEL).to(device)
tokenizer = AutoTokenizer.from_pretrained(MODEL)
desc_tokenizer = AutoTokenizer.from_pretrained(model.config.text_encoder._name_or_path)
print(f"Loaded in {time.time() - t0:.1f}s", flush=True)

description = "A female speaker with a clear voice delivers a slightly expressive and animated speech at a moderate speed."
prompt = "नमस्ते! AquaScope में आपका स्वागत है।"

t0 = time.time()
desc_ids = desc_tokenizer(description, return_tensors="pt").to(device)
prompt_ids = tokenizer(prompt, return_tensors="pt").to(device)
audio = model.generate(
    input_ids=desc_ids.input_ids,
    attention_mask=desc_ids.attention_mask,
    prompt_input_ids=prompt_ids.input_ids,
    prompt_attention_mask=prompt_ids.attention_mask,
)
arr = audio.cpu().numpy().squeeze()
sf.write(OUT, arr, model.config.sampling_rate)
print(f"Synthesized {len(arr) / model.config.sampling_rate:.2f}s of audio in {time.time() - t0:.1f}s -> {OUT}", flush=True)
print("TTS_VERIFY_OK", flush=True)
