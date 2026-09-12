"""Tests for storage/models.py — Pydantic data models."""

from screenmind.storage.models import ActivityRecord, DevContext, ScreenshotEntry, DailySummary


def test_activity_record_defaults():
    record = ActivityRecord()
    assert record.app_name == "unknown"
    assert record.activity_category == "other"
    assert record.mood == "neutral"
    assert record.confidence == 0.5
    assert record.visible_text_snippets == []


def test_activity_record_custom():
    record = ActivityRecord(
        app_name="VS Code",
        activity_category="coding",
        activity_summary="Editing main.py",
        mood="productive",
        confidence=0.9,
    )
    assert record.app_name == "VS Code"
    assert record.activity_category == "coding"
    assert record.confidence == 0.9


def test_dev_context_defaults():
    ctx = DevContext()
    assert ctx.repo_name == ""
    assert ctx.branch == ""
    assert ctx.changed_files == []
    assert ctx.insertions == 0
    assert ctx.deletions == 0


def test_screenshot_entry():
    from datetime import datetime
    entry = ScreenshotEntry(
        timestamp=datetime.now(),
        screenshot_path="/tmp/test.jpg",
        window_title="VS Code - main.py",
        bookmarked=True,
    )
    assert entry.bookmarked is True
    assert entry.analyzed is False
    assert entry.analysis is None


def test_daily_summary():
    s = DailySummary(date="2026-05-16", summary="Productive day", total_activities=42)
    assert s.date == "2026-05-16"
    assert s.total_activities == 42
