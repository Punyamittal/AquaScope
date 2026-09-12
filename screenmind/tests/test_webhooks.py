"""Tests for integrations/webhooks.py — mock all HTTP, test logic."""
import json
import hashlib
import hmac
from unittest.mock import patch, MagicMock
from screenmind.integrations.webhooks import (
    fire,
    _parse_headers,
    _send,
    get_delivery_log,
    test_webhook as webhook_ping,
    _delivery_log,
    _log_lock,
)


# ── _parse_headers ──────────────────────────────────────────────────────

class TestParseHeaders:
    def test_empty_string(self):
        assert _parse_headers("") == {}

    def test_single_header(self):
        result = _parse_headers("Authorization: Bearer abc123")
        assert result == {"Authorization": "Bearer abc123"}

    def test_multiple_headers(self):
        result = _parse_headers("X-Custom: value1\nX-Other: value2")
        assert result["X-Custom"] == "value1"
        assert result["X-Other"] == "value2"

    def test_no_colon_skipped(self):
        result = _parse_headers("no-colon-here\nX-Valid: yes")
        assert "no-colon-here" not in result
        assert result["X-Valid"] == "yes"


# ── fire ────────────────────────────────────────────────────────────────

class TestFire:
    def test_empty_url_returns_false(self):
        assert fire("test", {}, "") is False

    def test_event_not_in_allowed_returns_false(self):
        assert fire("bookmark", {}, "http://example.com", enabled_events="daily_summary") is False

    def test_event_in_allowed_queues(self):
        with patch("screenmind.integrations.webhooks.threading.Thread") as mock_thread:
            mock_thread.return_value.start = MagicMock()
            result = fire("bookmark", {"id": 1}, "http://example.com", enabled_events="bookmark,daily_summary")
            assert result is True
            mock_thread.return_value.start.assert_called_once()

    def test_multiple_urls_spawn_multiple_threads(self):
        with patch("screenmind.integrations.webhooks.threading.Thread") as mock_thread:
            mock_thread.return_value.start = MagicMock()
            fire("test", {}, "http://a.com, http://b.com")
            assert mock_thread.call_count == 2


# ── _send ───────────────────────────────────────────────────────────────

class TestSend:
    @patch("screenmind.integrations.webhooks.urllib.request.urlopen")
    def test_success(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.status = 200
        mock_resp.__enter__ = MagicMock(return_value=mock_resp)
        mock_resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = mock_resp

        payload = {"event": "test", "data": {}}
        result = _send("http://example.com", payload, "", {}, "test")
        assert result is True

    @patch("screenmind.integrations.webhooks.urllib.request.urlopen")
    def test_hmac_signing(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.status = 200
        mock_resp.__enter__ = MagicMock(return_value=mock_resp)
        mock_resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = mock_resp

        payload = {"event": "test"}
        secret = "mysecret"
        _send("http://example.com", payload, secret, {}, "test")

        # Verify the request was made with HMAC header
        call_args = mock_urlopen.call_args
        req = call_args[0][0]
        assert "X-Screenmind-Signature" in req.headers or "X-screenmind-signature" in req.headers

    @patch("screenmind.integrations.webhooks.urllib.request.urlopen")
    def test_http_error(self, mock_urlopen):
        import urllib.error
        mock_urlopen.side_effect = urllib.error.HTTPError(
            "http://example.com", 500, "Server Error", {}, None
        )
        result = _send("http://example.com", {}, "", {}, "test")
        assert result is False

    @patch("screenmind.integrations.webhooks.urllib.request.urlopen")
    def test_url_error(self, mock_urlopen):
        import urllib.error
        mock_urlopen.side_effect = urllib.error.URLError("Connection refused")
        result = _send("http://example.com", {}, "", {}, "test")
        assert result is False


# ── test_webhook ────────────────────────────────────────────────────────

class TestWebhookPing:
    def test_empty_url(self):
        result = webhook_ping("")
        assert result["ok"] is False

    @patch("screenmind.integrations.webhooks.urllib.request.urlopen")
    def test_success(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.status = 200
        mock_resp.__enter__ = MagicMock(return_value=mock_resp)
        mock_resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = mock_resp

        result = webhook_ping("http://example.com")
        assert result["ok"] is True
        assert result["status"] == 200

    @patch("screenmind.integrations.webhooks.urllib.request.urlopen")
    def test_failure(self, mock_urlopen):
        mock_urlopen.side_effect = Exception("Connection refused")
        result = webhook_ping("http://example.com")
        assert result["ok"] is False


# ── get_delivery_log ────────────────────────────────────────────────────

def test_delivery_log_records():
    """Delivery log should contain entries after _send calls."""
    with _log_lock:
        _delivery_log.clear()

    from screenmind.integrations.webhooks import _log_delivery
    _log_delivery("http://test.com", "test", "ok", 200)

    log = get_delivery_log()
    assert len(log) >= 1
    assert log[0]["url"] == "http://test.com"
    assert log[0]["status"] == "ok"
