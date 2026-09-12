"""Tests for screenmind_sdk.py — mock HTTP, test helpers and state."""
import json
from unittest.mock import patch, MagicMock
import screenmind.screenmind_sdk as sdk


# ── HTTP Helpers ────────────────────────────────────────────────────────

class TestGet:
    @patch("screenmind.screenmind_sdk.urllib.request.urlopen")
    def test_success(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps({"status": "ok"}).encode()
        mock_resp.__enter__ = MagicMock(return_value=mock_resp)
        mock_resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = mock_resp

        result = sdk._get("/api/status")
        assert result["status"] == "ok"

    @patch("screenmind.screenmind_sdk.urllib.request.urlopen")
    def test_url_error(self, mock_urlopen):
        import urllib.error
        mock_urlopen.side_effect = urllib.error.URLError("refused")
        result = sdk._get("/api/status")
        assert result == {}

    @patch("screenmind.screenmind_sdk.urllib.request.urlopen")
    def test_invalid_json(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.read.return_value = b"not json"
        mock_resp.__enter__ = MagicMock(return_value=mock_resp)
        mock_resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = mock_resp

        result = sdk._get("/api/status")
        assert result == {}


class TestPost:
    @patch("screenmind.screenmind_sdk.urllib.request.urlopen")
    def test_success(self, mock_urlopen):
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps({"captured": True}).encode()
        mock_resp.__enter__ = MagicMock(return_value=mock_resp)
        mock_resp.__exit__ = MagicMock(return_value=False)
        mock_urlopen.return_value = mock_resp

        result = sdk._post("/api/capture", {"force": True})
        assert result["captured"] is True

    @patch("screenmind.screenmind_sdk.urllib.request.urlopen")
    def test_post_error(self, mock_urlopen):
        import urllib.error
        mock_urlopen.side_effect = urllib.error.URLError("refused")
        result = sdk._post("/api/capture")
        assert result == {}


# ── Sanitize ────────────────────────────────────────────────────────────

class TestSanitize:
    def test_clean_name(self):
        assert sdk._sanitize_name("my-agent") == "my-agent"

    def test_path_traversal(self):
        result = sdk._sanitize_name("../../evil")
        assert ".." not in result

    def test_special_chars(self):
        result = sdk._sanitize_name("test@agent!#$")
        assert "@" not in result
        assert "!" not in result

    def test_empty_returns_unknown(self):
        assert sdk._sanitize_name("") == "unknown"


# ── Agent Context ───────────────────────────────────────────────────────

class TestAgentContext:
    def test_set_and_get(self):
        sdk._set_current_agent("test-plugin")
        assert sdk._get_current_agent() == "test-plugin"

    def test_resolve_agent_explicit(self):
        result = sdk._resolve_agent("explicit-name")
        assert result == "explicit-name"

    def test_resolve_agent_from_context(self):
        sdk._set_current_agent("context-agent")
        result = sdk._resolve_agent()
        assert result == "context-agent"


# ── State Persistence ───────────────────────────────────────────────────

class TestState:
    @patch("screenmind.screenmind_sdk.Path.home")
    def test_save_and_load(self, mock_home, tmp_path):
        mock_home.return_value = tmp_path
        sdk._set_current_agent("state-test")

        sdk.save_state("counter", 42, agent_name="state-test")
        value = sdk.load_state("counter", agent_name="state-test")
        assert value == 42

    @patch("screenmind.screenmind_sdk.Path.home")
    def test_load_default(self, mock_home, tmp_path):
        mock_home.return_value = tmp_path
        value = sdk.load_state("nonexistent", default="fallback", agent_name="test")
        assert value == "fallback"

    @patch("screenmind.screenmind_sdk.Path.home")
    def test_clear_state(self, mock_home, tmp_path):
        mock_home.return_value = tmp_path
        sdk.save_state("key", "value", agent_name="clear-test")
        sdk.clear_state(agent_name="clear-test")
        result = sdk.load_state("key", agent_name="clear-test")
        assert result is None


# ── Data Access Functions ───────────────────────────────────────────────

class TestDataAccess:
    @patch("screenmind.screenmind_sdk._get")
    def test_get_recent_activity(self, mock_get):
        mock_get.return_value = {"activities": [{"id": 1}]}
        result = sdk.get_recent_activity(minutes=30)
        assert len(result) == 1

    @patch("screenmind.screenmind_sdk._get")
    def test_search(self, mock_get):
        mock_get.return_value = {"results": [{"id": 1, "summary": "test"}]}
        result = sdk.search("test query")
        assert len(result) == 1

    @patch("screenmind.screenmind_sdk._get")
    def test_get_summary(self, mock_get):
        mock_get.return_value = {"summary": {"summary": "Productive day"}}
        result = sdk.get_summary("2026-05-20")
        assert "Productive" in result

    @patch("screenmind.screenmind_sdk._get")
    def test_get_summary_empty(self, mock_get):
        mock_get.return_value = {"summary": {}}
        result = sdk.get_summary()
        assert result == ""

    @patch("screenmind.screenmind_sdk._post")
    def test_capture_now(self, mock_post):
        mock_post.return_value = {"status": "captured"}
        result = sdk.capture_now()
        assert result["status"] == "captured"


# ── write_file ──────────────────────────────────────────────────────────

class TestWriteFile:
    @patch("screenmind.config.settings")
    def test_write_within_data_dir(self, mock_settings, tmp_path):
        mock_settings.data_path = tmp_path
        target = tmp_path / "output" / "test.txt"
        sdk.write_file(str(target), "hello world")
        assert target.read_text() == "hello world"

    @patch("screenmind.config.settings")
    def test_write_outside_data_dir_blocked(self, mock_settings, tmp_path):
        mock_settings.data_path = tmp_path / "safe"
        (tmp_path / "safe").mkdir()
        import pytest
        with pytest.raises(PermissionError):
            sdk.write_file(str(tmp_path / "evil.txt"), "hacked")
