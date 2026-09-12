"""Tests for integrations/obsidian.py — file I/O with tmp_path."""
from screenmind.integrations.obsidian import export_summary


class TestExportSummary:
    def test_successful_export(self, tmp_path):
        result = export_summary(
            vault_path=str(tmp_path),
            date_str="2026-05-20",
            summary="Productive day focused on coding.",
            activity_count=42,
        )
        assert result is True

        filepath = tmp_path / "ScreenMind" / "2026-05-20.md"
        assert filepath.exists()

        content = filepath.read_text(encoding="utf-8")
        assert "2026-05-20" in content
        assert "Productive day" in content
        assert "42 screen activities" in content

    def test_with_standup(self, tmp_path):
        export_summary(
            vault_path=str(tmp_path),
            date_str="2026-05-20",
            summary="A summary",
            standup="Worked on auth module",
        )
        content = (tmp_path / "ScreenMind" / "2026-05-20.md").read_text(encoding="utf-8")
        assert "Standup Notes" in content
        assert "auth module" in content

    def test_invalid_vault_path(self):
        result = export_summary(
            vault_path="/nonexistent/vault/path",
            date_str="2026-05-20",
            summary="Test",
        )
        assert result is False

    def test_empty_vault_path(self):
        result = export_summary(vault_path="", date_str="2026-05-20", summary="Test")
        assert result is False

    def test_obsidian_tags_present(self, tmp_path):
        export_summary(str(tmp_path), "2026-05-20", "Summary")
        content = (tmp_path / "ScreenMind" / "2026-05-20.md").read_text(encoding="utf-8")
        assert "#screenmind" in content
        assert "#daily-summary" in content

    def test_overwrites_existing(self, tmp_path):
        export_summary(str(tmp_path), "2026-05-20", "Version 1")
        export_summary(str(tmp_path), "2026-05-20", "Version 2")
        content = (tmp_path / "ScreenMind" / "2026-05-20.md").read_text(encoding="utf-8")
        assert "Version 2" in content
