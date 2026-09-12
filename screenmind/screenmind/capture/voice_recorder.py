"""
Voice Recorder — Captures mic audio + screenshot simultaneously.
Used for voice memo feature: press hotkey → speak → release → transcribe.
"""

import logging
import io
import threading
import time
import wave
from datetime import datetime
from pathlib import Path
from typing import Optional, Tuple

import numpy as np

from screenmind.config import settings
from screenmind.capture.screen import ScreenCapture

logger = logging.getLogger("screenmind.capture.voice_recorder")


SAMPLE_RATE = 16000  # Gemma 4 expects 16kHz audio
MAX_DURATION = 60  # Max recording seconds (safety cap)


class VoiceRecorder:
    """Hold-to-record voice memo with simultaneous screenshot capture."""

    def __init__(self):
        self._recording = False
        self._audio_data = []
        self._screenshot_path: Optional[Path] = None
        self._start_time: Optional[float] = None

    @property
    def is_recording(self) -> bool:
        return self._recording

    def start(self):
        """Start recording mic + capture screenshot immediately."""
        if self._recording:
            return

        import sounddevice as sd
        self._recording = True
        self._audio_data = []
        self._start_time = time.time()

        # Capture screenshot at the moment user presses hotkey
        self._screenshot_path = self._capture_screenshot()

        # Start mic recording in background
        self._stream = sd.InputStream(
            samplerate=SAMPLE_RATE,
            channels=1,
            dtype="float32",
            callback=self._audio_callback,
        )
        self._stream.start()
        logger.info("Recording started...")

    def stop(self) -> Optional[Tuple[bytes, Optional[Path], Path]]:
        """
        Stop recording and return (wav_bytes, screenshot_path, wav_path).
        Returns None if recording was too short or failed.
        """
        if not self._recording:
            return None

        self._recording = False
        self._stream.stop()
        self._stream.close()

        duration = time.time() - self._start_time
        logger.info(f"Recording stopped ({duration:.1f}s)")

        # Too short — probably accidental
        if duration < 0.5:
            logger.warning("Too short, discarding")
            return None

        # Convert to WAV bytes
        if not self._audio_data:
            return None

        audio = np.concatenate(self._audio_data)
        wav_bytes = self._to_wav(audio)

        # Save WAV file
        wav_path = self._save_wav(wav_bytes)

        return wav_bytes, self._screenshot_path, wav_path

    def _audio_callback(self, indata, frames, time_info, status):
        """Called by sounddevice for each audio chunk."""
        if self._recording:
            self._audio_data.append(indata.copy())
            # Safety cap
            if time.time() - self._start_time > MAX_DURATION:
                self._recording = False

    def _to_wav(self, audio: np.ndarray) -> bytes:
        """Convert float32 numpy array to WAV bytes."""
        # Convert float32 [-1, 1] to int16
        audio_int16 = (audio * 32767).astype(np.int16)

        buf = io.BytesIO()
        with wave.open(buf, "wb") as wf:
            wf.setnchannels(1)
            wf.setsampwidth(2)  # 16-bit
            wf.setframerate(SAMPLE_RATE)
            wf.writeframes(audio_int16.tobytes())

        return buf.getvalue()

    def _save_wav(self, wav_bytes: bytes) -> Path:
        """Save WAV to the memos directory."""
        now = datetime.now()
        memo_dir = settings.data_path / "memos" / now.strftime("%Y-%m-%d")
        memo_dir.mkdir(parents=True, exist_ok=True)

        filename = f"memo_{now.strftime('%H-%M-%S')}.wav"
        filepath = memo_dir / filename
        filepath.write_bytes(wav_bytes)
        return filepath

    def _capture_screenshot(self) -> Optional[Path]:
        """Capture and save a screenshot for the memo.

        Creates a fresh ScreenCapture instance each time because mss uses
        GDI handles on Windows which have thread affinity — the handle must
        be created on the same thread that uses it. Since this is called from
        the hotkey listener thread (not the main thread), we can't reuse an
        instance created in __init__.
        """
        try:
            capture = ScreenCapture()
            result = capture.capture()
            if result:
                return result[0]  # filepath
        except Exception as e:
            logger.warning(f"Screenshot capture failed: {e}")
        return None
