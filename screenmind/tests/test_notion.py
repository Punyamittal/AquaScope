"""Tests for integrations/notion.py — mock notion_client."""
from unittest.mock import patch, MagicMock
from screenmind.integrations.notion import export_summary, test_connection as notion_connection_check


# ── export_summary ──────────────────────────────────────────────────────

class TestExportSummary:
    def test_missing_token(self):
        with patch("screenmind.integrations.notion.Client", create=True):
            result = export_summary("", "db-id", "2026-05-20", "Summary")
            assert result is False

    def test_missing_database_id(self):
        with patch("screenmind.integrations.notion.Client", create=True):
            result = export_summary("token", "", "2026-05-20", "Summary")
            assert result is False

    @patch.dict("sys.modules", {"notion_client": MagicMock()})
    def test_successful_export(self):
        mock_client_cls = MagicMock()
        mock_client = MagicMock()
        mock_client_cls.return_value = mock_client

        with patch("screenmind.integrations.notion.Client", mock_client_cls, create=True):
            # Need to re-import to pick up the mock
            from importlib import reload
            import screenmind.integrations.notion as notion_mod
            reload(notion_mod)

            result = notion_mod.export_summary(
                token="test-token",
                database_id="test-db",
                date_str="2026-05-20",
                summary="Productive day",
                standup="Worked on tests",
                activity_count=10,
            )
            assert result is True

    def test_import_error(self):
        """When notion_client isn't installed, returns False."""
        with patch.dict("sys.modules", {"notion_client": None}):
            from importlib import reload
            import screenmind.integrations.notion as notion_mod
            reload(notion_mod)
            result = notion_mod.export_summary("token", "db", "2026-05-20", "Summary")
            assert result is False


# ── test_connection ─────────────────────────────────────────────────────

class TestNotionConnectionCheck:
    def test_missing_token(self):
        result = notion_connection_check("", "db-id")
        assert result["ok"] is False

    def test_missing_database_id(self):
        result = notion_connection_check("token", "")
        assert result["ok"] is False

    def test_import_error(self):
        with patch.dict("sys.modules", {"notion_client": None}):
            from importlib import reload
            import screenmind.integrations.notion as notion_mod
            reload(notion_mod)
            result = notion_mod.test_connection("token", "db-id")
            assert result["ok"] is False
