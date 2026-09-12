"""Tests for engine/dev_context.py — mock git, test path extraction and coding detection."""
import pytest
from unittest.mock import patch, MagicMock, PropertyMock
from screenmind.engine.dev_context import DevContextDetector


# ── is_coding_activity ──────────────────────────────────────────────────

class TestIsCodingActivity:
    @patch("screenmind.engine.dev_context.settings")
    def test_category_coding(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        assert d.is_coding_activity(category="coding") is True

    @patch("screenmind.engine.dev_context.settings")
    def test_category_terminal(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        assert d.is_coding_activity(category="terminal") is True

    @patch("screenmind.engine.dev_context.settings")
    def test_category_browsing(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        assert d.is_coding_activity(category="browsing") is False

    @patch("screenmind.engine.dev_context.settings")
    def test_known_app_vscode(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        assert d.is_coding_activity(app_name="Code") is True

    @patch("screenmind.engine.dev_context.settings")
    def test_known_app_with_exe(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        assert d.is_coding_activity(app_name="Code.exe") is True

    @patch("screenmind.engine.dev_context.settings")
    def test_unknown_app(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        assert d.is_coding_activity(app_name="Spotify") is False

    @patch("screenmind.engine.dev_context.settings")
    def test_window_title_with_py_file(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        assert d.is_coding_activity(window_title="main.py - Visual Studio Code") is True

    @patch("screenmind.engine.dev_context.settings")
    def test_window_title_no_keywords(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        assert d.is_coding_activity(window_title="Google Chrome") is False

    @patch("screenmind.engine.dev_context.settings")
    def test_no_signals(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        assert d.is_coding_activity() is False


# ── _extract_paths_from_text ────────────────────────────────────────────

class TestExtractPaths:
    @patch("screenmind.engine.dev_context.settings")
    def test_windows_path(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        paths = d._extract_paths_from_text(r"C:\Users\dev\project\main.py")
        assert any("main.py" in p for p in paths)

    @patch("screenmind.engine.dev_context.settings")
    def test_unix_path(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        paths = d._extract_paths_from_text("/home/user/project/app.js")
        assert any("app.js" in p for p in paths)

    @patch("screenmind.engine.dev_context.settings")
    def test_tilde_path(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        paths = d._extract_paths_from_text("~/projects/screenmind/config.py")
        assert len(paths) >= 1

    @patch("screenmind.engine.dev_context.settings")
    def test_relative_path(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        paths = d._extract_paths_from_text("editing src/main.py now")
        assert any("src/main.py" in p for p in paths)

    @patch("screenmind.engine.dev_context.settings")
    def test_no_paths(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        paths = d._extract_paths_from_text("Just a regular sentence")
        # May find some false positives but should not crash
        assert isinstance(paths, list)


# ── get_context ─────────────────────────────────────────────────────────

class TestGetContext:
    @patch("screenmind.engine.dev_context.settings")
    def test_no_repo_found(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        # With no workspace dirs and no paths in title, should return None
        result = d.get_context(window_title="Google Chrome")
        assert result is None

    @patch("screenmind.engine.dev_context.settings")
    def test_gitpython_not_installed(self, mock_settings):
        mock_settings.workspace_dirs_list = []
        d = DevContextDetector()
        with patch.dict("sys.modules", {"git": None}):
            result = d.get_context()
            assert result is None
