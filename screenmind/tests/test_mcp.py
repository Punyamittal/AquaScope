"""Comprehensive tests for MCP server tools."""
import pytest
import json
from unittest.mock import patch, MagicMock
from pathlib import Path
from datetime import date, timedelta


@pytest.fixture(autouse=True)
def mock_mcp_deps():
    """Mock MCP server dependencies."""
    with patch("screenmind.mcp_server.db") as mock_db, \
         patch("screenmind.mcp_server.embedder") as mock_embedder:
        mock_db._get_conn.return_value = MagicMock()
        mock_db._decode_embedding.return_value = None
        mock_db.get_stats.return_value = {
            "total_activities": 42,
            "category_breakdown": {"coding": 20, "browsing": 15},
            "top_apps": {"VS Code": 20, "Chrome": 15},
            "top_repos": {"screenmind": 10},
            "meetings_count": 2,
            "meetings_minutes": 45,
        }
        mock_db.get_daily_summary.return_value = None
        mock_embedder.search.return_value = []
        yield mock_db, mock_embedder


class TestGetStats:
    """Tests for get_stats tool."""

    def test_returns_valid_json(self, mock_mcp_deps):
        from screenmind.mcp_server import get_stats
        result = get_stats()
        data = json.loads(result)
        assert "total_activities" in data
        assert data["total_activities"] == 42

    def test_calls_db_with_date_range(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        from screenmind.mcp_server import get_stats
        get_stats()
        mock_db.get_stats.assert_called_once()
        args = mock_db.get_stats.call_args
        assert "date_from" in args.kwargs or len(args.args) >= 2


class TestGetActivityByTime:
    """Tests for get_activity_by_time tool."""

    def test_invalid_date_returns_error(self, mock_mcp_deps):
        from screenmind.mcp_server import get_activity_by_time
        result = get_activity_by_time("yesterday")
        data = json.loads(result)
        assert "error" in data
        assert "YYYY-MM-DD" in data["error"]

    def test_invalid_date_formats(self, mock_mcp_deps):
        from screenmind.mcp_server import get_activity_by_time
        for bad_date in ["2026/05/20", "20-05-2026", "May 20", "last tuesday", ""]:
            data = json.loads(get_activity_by_time(bad_date))
            assert "error" in data

    def test_valid_date_no_error(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchall.return_value = []
        from screenmind.mcp_server import get_activity_by_time
        result = get_activity_by_time("2026-05-20")
        data = json.loads(result)
        assert "error" not in data

    def test_valid_date_with_hours(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchall.return_value = []
        from screenmind.mcp_server import get_activity_by_time
        result = get_activity_by_time("2026-05-20", start_hour=9, end_hour=17)
        data = json.loads(result)
        assert "error" not in data

    def test_invalid_hours(self, mock_mcp_deps):
        from screenmind.mcp_server import get_activity_by_time
        result = get_activity_by_time("2026-05-20", start_hour=25)
        data = json.loads(result)
        assert "error" in data


class TestGetScreenshot:
    """Tests for get_screenshot tool."""

    def test_nonexistent_activity(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchone.return_value = None
        from screenmind.mcp_server import get_screenshot
        result = get_screenshot(99999)
        data = json.loads(result)
        assert "error" in data

    def test_no_screenshot_path(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchone.return_value = {"screenshot_path": ""}
        from screenmind.mcp_server import get_screenshot
        result = get_screenshot(1)
        data = json.loads(result)
        assert "error" in data

    def test_path_traversal_blocked(self, mock_mcp_deps, tmp_path):
        """Paths outside screenshots_dir are rejected."""
        mock_db, _ = mock_mcp_deps
        evil_path = str(tmp_path / ".." / ".." / "etc" / "passwd")
        mock_db._get_conn.return_value.execute.return_value.fetchone.return_value = {"screenshot_path": evil_path}
        from screenmind.mcp_server import get_screenshot
        with patch("screenmind.mcp_server.settings") as mock_settings:
            mock_settings.screenshots_dir = tmp_path / "screenshots"
            result = get_screenshot(1)
        data = json.loads(result)
        assert "error" in data

    def test_valid_screenshot(self, mock_mcp_deps, tmp_path):
        """Valid screenshot returns path and size."""
        mock_db, _ = mock_mcp_deps
        ss_dir = tmp_path / "screenshots"
        ss_dir.mkdir()
        f = ss_dir / "test.jpg"
        f.write_bytes(b"\xff\xd8" + b"\x00" * 1000)

        mock_db._get_conn.return_value.execute.return_value.fetchone.return_value = {"screenshot_path": str(f)}
        from screenmind.mcp_server import get_screenshot
        with patch("screenmind.mcp_server.settings") as mock_settings:
            mock_settings.screenshots_dir = ss_dir
            result = get_screenshot(1)
        data = json.loads(result)
        assert "screenshot_path" in data
        assert "size_kb" in data
        assert data["size_kb"] > 0


class TestSearchAudio:
    """Tests for search_audio tool."""

    def test_no_results(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchall.return_value = []
        from screenmind.mcp_server import search_audio
        result = search_audio("budget meeting")
        data = json.loads(result)
        assert data["count"] == 0

    def test_with_results(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchall.return_value = [
            {"id": 1, "start_time": "2026-05-20T10:00:00", "end_time": "2026-05-20T10:30:00",
             "app_name": "Zoom", "duration_minutes": 30,
             "transcript": "We discussed the budget for Q3 and agreed on 50k",
             "summary": "Budget discussion"}
        ]
        from screenmind.mcp_server import search_audio
        result = search_audio("budget")
        data = json.loads(result)
        assert data["count"] == 1
        assert "budget" in data["results"][0]["transcript_snippet"].lower()

    def test_wildcard_chars_escaped(self, mock_mcp_deps):
        """% and _ in query don't act as SQL wildcards."""
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchall.return_value = []
        from screenmind.mcp_server import search_audio
        search_audio("100% done_task")
        # Verify the escaped query was passed
        call_args = mock_db._get_conn.return_value.execute.call_args
        query_param = call_args[0][1][0]  # first param
        assert "\\%" in query_param
        assert "\\_" in query_param


class TestGetDailySummary:
    """Tests for get_daily_summary tool."""

    def test_no_summary_available(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db.get_daily_summary.return_value = None
        from screenmind.mcp_server import get_daily_summary
        result = get_daily_summary("2026-05-20")
        data = json.loads(result)
        assert "message" in data
        assert "No summary" in data["message"]

    def test_defaults_to_today(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db.get_daily_summary.return_value = None
        from screenmind.mcp_server import get_daily_summary
        result = get_daily_summary()
        data = json.loads(result)
        assert data["date"] == date.today().isoformat()

    def test_returns_summary_when_available(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db.get_daily_summary.return_value = {
            "summary": "Productive day focused on coding",
            "standup": "Worked on auth module",
            "created_at": "2026-05-20T23:00:00",
        }
        from screenmind.mcp_server import get_daily_summary
        result = get_daily_summary("2026-05-20")
        data = json.loads(result)
        assert "Productive day" in data["summary"]


class TestGetRecentActivity:
    """Tests for get_recent_activity tool."""

    def test_empty_results(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchall.return_value = []
        from screenmind.mcp_server import get_recent_activity
        result = get_recent_activity()
        data = json.loads(result)
        assert data["count"] == 0

    def test_respects_count_limit(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchall.return_value = []
        from screenmind.mcp_server import get_recent_activity
        get_recent_activity(count=5)
        call_args = mock_db._get_conn.return_value.execute.call_args
        assert 5 in call_args[0][1]  # LIMIT param

    def test_caps_at_50(self, mock_mcp_deps):
        mock_db, _ = mock_mcp_deps
        mock_db._get_conn.return_value.execute.return_value.fetchall.return_value = []
        from screenmind.mcp_server import get_recent_activity
        get_recent_activity(count=999)
        call_args = mock_db._get_conn.return_value.execute.call_args
        assert 50 in call_args[0][1]  # capped to 50


class TestCaptureNow:
    """Tests for capture_now tool."""

    @patch("urllib.request.urlopen")
    @patch("urllib.request.Request")
    def test_success(self, mock_request, mock_urlopen, mock_mcp_deps):
        mock_urlopen.return_value.__enter__ = MagicMock()
        mock_urlopen.return_value.__exit__ = MagicMock(return_value=False)
        from screenmind.mcp_server import capture_now
        result = capture_now()
        data = json.loads(result)
        assert data["status"] == "captured"

    @patch("urllib.request.urlopen")
    def test_server_not_running(self, mock_urlopen, mock_mcp_deps):
        import urllib.error
        mock_urlopen.side_effect = urllib.error.URLError("refused")
        from screenmind.mcp_server import capture_now
        result = capture_now()
        data = json.loads(result)
        assert data["status"] == "error"
        assert "running" in data["message"]
