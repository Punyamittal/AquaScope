"""
Indic TTS route — server-side Hindi/Hinglish speech synthesis for the AquaScope
Android app, backed by AI4Bharat indic-parler-tts (engine/tts_engine.py).
Disabled by default (settings.tts_enabled) since the model is heavy and can
compete with llama-server for GPU/CPU.
"""

import asyncio
import io
import logging

from fastapi import APIRouter, HTTPException
from pydantic import BaseModel
from starlette.responses import StreamingResponse

from screenmind.config import settings
from screenmind.api import dependencies as deps

logger = logging.getLogger("screenmind.api.routes.tts")

router = APIRouter(prefix="/api/tts", tags=["tts"])

DEFAULT_DESCRIPTIONS = {
    "hi": "A calm, clear female Hindi voice speaks at a natural pace in a quiet room.",
    "hinglish": (
        "A calm, clear female Hindi voice speaks at a natural pace in a quiet room, "
        "mixing English words naturally like everyday conversational Hinglish."
    ),
}


class SpeakRequest(BaseModel):
    text: str
    language: str = "hi"  # "hi" | "hinglish" — anything else falls back to the "hi" voice
    voice_description: str | None = None


@router.post("/speak")
async def speak(payload: SpeakRequest):
    if not settings.tts_enabled:
        raise HTTPException(status_code=503, detail="Server TTS disabled (tts_enabled=False)")

    text = payload.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Empty text")

    engine = deps.tts_engine
    if engine is None or not engine.is_available:
        raise HTTPException(status_code=503, detail="indic-parler-tts unavailable on this server")

    description = payload.voice_description or DEFAULT_DESCRIPTIONS.get(
        payload.language, DEFAULT_DESCRIPTIONS["hi"]
    )

    try:
        wav_bytes = await asyncio.get_event_loop().run_in_executor(
            None, lambda: engine.synthesize(text[:2000], description)
        )
    except Exception as e:
        logger.error(f"TTS synthesis failed: {e}")
        raise HTTPException(status_code=500, detail=f"TTS synthesis failed: {e}")

    return StreamingResponse(io.BytesIO(wav_bytes), media_type="audio/wav")
