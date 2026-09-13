"""Test the /api/tts/speak endpoint using FastAPI TestClient (no real model load)."""
import pytest
from unittest.mock import MagicMock, patch

from screenmind.config import settings


@pytest.fixture
def client():
    """Create a test client with mocked dependencies, TTS disabled (default)."""
    from screenmind.api.server import create_app
    from screenmind.storage.database import Database
    from fastapi.testclient import TestClient

    db = MagicMock(spec=Database)
    app = create_app(database=db, capture_worker=MagicMock(), analysis_worker=MagicMock(), audio_worker=MagicMock(), embedder=False, tts_engine=False)

    with patch("screenmind.api.server.settings") as mock_settings:
        mock_settings.dashboard_pin_hash = None
        yield TestClient(app)


def test_speak_returns_503_when_tts_disabled(client):
    """Default config has tts_enabled=False — server TTS should refuse cleanly."""
    r = client.post("/api/tts/speak", json={"text": "Aapka pipe theek hai", "language": "hinglish"})
    assert r.status_code == 503


def test_speak_returns_400_on_empty_text(client):
    original = settings.tts_enabled
    settings.tts_enabled = True
    try:
        r = client.post("/api/tts/speak", json={"text": "   ", "language": "hi"})
        assert r.status_code == 400
    finally:
        settings.tts_enabled = original


def test_speak_returns_wav_with_mocked_engine(client):
    """With tts_enabled=True and a mocked engine, synthesis should return audio/wav bytes."""
    import screenmind.api.dependencies as deps

    fake_engine = MagicMock()
    fake_engine.is_available = True
    fake_engine.synthesize.return_value = b"RIFF....WAVEfmt "  # stand-in bytes, shape only

    original_enabled = settings.tts_enabled
    original_engine = deps.tts_engine
    settings.tts_enabled = True
    deps.tts_engine = fake_engine
    try:
        r = client.post("/api/tts/speak", json={"text": "Aapka pipe theek hai", "language": "hinglish"})
        assert r.status_code == 200
        assert r.headers["content-type"] == "audio/wav"
        assert r.content == b"RIFF....WAVEfmt "
        fake_engine.synthesize.assert_called_once()
    finally:
        settings.tts_enabled = original_enabled
        deps.tts_engine = original_engine


def test_speak_returns_503_when_engine_unavailable(client):
    fake_engine = MagicMock()
    fake_engine.is_available = False

    import screenmind.api.dependencies as deps

    original_enabled = settings.tts_enabled
    original_engine = deps.tts_engine
    settings.tts_enabled = True
    deps.tts_engine = fake_engine
    try:
        r = client.post("/api/tts/speak", json={"text": "hello", "language": "hi"})
        assert r.status_code == 503
    finally:
        settings.tts_enabled = original_enabled
        deps.tts_engine = original_engine
