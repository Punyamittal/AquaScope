"""
Indic TTS Engine
Generates Hindi/Hinglish speech using AI4Bharat's indic-parler-tts
(Apache-2.0, HuggingFace `parler_tts` + `transformers`, PyTorch).

Heavy model — lazy-loaded on first use, never at server startup. May compete
for GPU with llama-server (engine/model_manager.py); falls back to CPU when
no CUDA device is available.
"""

import io
import logging
import wave
from typing import Optional

import numpy as np

logger = logging.getLogger("screenmind.engine.tts_engine")


class IndicParlerTtsEngine:
    """
    Wraps AI4Bharat indic-parler-tts for Hindi/Hinglish speech synthesis.
    Mirrors Embedder's lazy-load / is_available shape (engine/embedder.py),
    but explicitly picks a torch device since this model is far heavier.
    """

    def __init__(self, model_name: str = "ai4bharat/indic-parler-tts"):
        self._model_name = model_name
        self._model = None
        self._tokenizer = None
        self._description_tokenizer = None
        self._device = None
        self._initialized = False

    def _ensure_model(self):
        """Lazy-load the model, tokenizer, and description tokenizer on first use."""
        if self._initialized:
            return
        try:
            import torch
            from parler_tts import ParlerTTSForConditionalGeneration
            from transformers import AutoTokenizer

            self._device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            logger.info(
                f"Loading indic-parler-tts ({self._model_name}) on {self._device} "
                "(first-time download can be several GB)..."
            )
            self._model = ParlerTTSForConditionalGeneration.from_pretrained(
                self._model_name
            ).to(self._device)
            self._tokenizer = AutoTokenizer.from_pretrained(self._model_name)
            self._description_tokenizer = AutoTokenizer.from_pretrained(
                self._model.config.text_encoder._name_or_path
            )
            self._initialized = True
            logger.info("indic-parler-tts loaded.")
        except ImportError:
            logger.warning(
                "parler_tts / torch / transformers not installed. "
                "Install with the 'tts' extra to enable server-side Hindi/Hinglish voice."
            )
            raise
        except Exception as e:
            logger.error(f"Failed to load indic-parler-tts: {e}")
            raise

    def synthesize(self, text: str, description: str) -> bytes:
        """
        Synthesize [text] in the voice style described by [description].
        Returns mono PCM16 WAV bytes at the model's native sample rate.
        """
        self._ensure_model()
        import torch

        description_ids = self._description_tokenizer(
            description, return_tensors="pt"
        ).input_ids.to(self._device)
        prompt_ids = self._tokenizer(text, return_tensors="pt").input_ids.to(self._device)

        with torch.no_grad():
            generation = self._model.generate(
                input_ids=description_ids, prompt_input_ids=prompt_ids
            )

        audio = generation.cpu().numpy().squeeze()
        return self._to_wav_bytes(audio, self._model.config.sampling_rate)

    @staticmethod
    def _to_wav_bytes(audio_np: "np.ndarray", sample_rate: int) -> bytes:
        buf = io.BytesIO()
        audio_int16 = (audio_np * 32767).clip(-32768, 32767).astype(np.int16)
        with wave.open(buf, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)  # 16-bit
            wf.setframerate(sample_rate)
            wf.writeframes(audio_int16.tobytes())
        return buf.getvalue()

    @property
    def is_available(self) -> bool:
        """Check if the TTS model can be loaded (without paying synthesis cost)."""
        try:
            self._ensure_model()
            return True
        except Exception:
            return False
