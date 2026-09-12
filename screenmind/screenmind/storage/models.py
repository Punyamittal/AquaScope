"""
Pydantic Models for ScreenMind
Defines the data structures used across the application.
"""

from datetime import datetime
from pathlib import Path
from typing import List, Optional

from pydantic import BaseModel, Field


class ActivityRecord(BaseModel):
    """
    Structured output from Gemma 4 screenshot analysis.
    This is what the model returns after analyzing a screenshot.
    """

    app_name: str = Field(
        default="unknown",
        description="Primary application visible (e.g., VS Code, Chrome, Slack)",
    )
    activity_category: str = Field(
        default="other",
        description="One of: coding, browsing, communication, writing, design, media, terminal, meeting, idle, other",
    )
    activity_summary: str = Field(
        default="",
        description="One sentence describing what the user is doing",
    )
    detailed_context: str = Field(
        default="",
        description="2-3 sentences with specific details",
    )
    visible_text_snippets: List[str] = Field(
        default_factory=list,
        description="Key text visible on screen, max 5 items",
    )
    mood: str = Field(
        default="neutral",
        description="productive, distracted, collaborative, learning, or neutral",
    )
    confidence: float = Field(
        default=0.5,
        description="Model confidence 0.0 to 1.0",
        ge=0.0,
        le=1.0,
    )
    scene_description: str = Field(
        default="",
        description="Rich visual narration of the screenshot: layout, conversations, people, notifications, actionable items",
    )


class DevContext(BaseModel):
    """Git context for coding activities."""

    repo_name: str = ""
    branch: str = ""
    last_commit: str = ""
    changed_files: List[str] = Field(default_factory=list)
    insertions: int = 0
    deletions: int = 0


class ScreenshotEntry(BaseModel):
    """
    Complete entry for a single captured & analyzed screenshot.
    Combines capture metadata, Gemma 4 analysis, and optional dev context.
    """

    id: Optional[int] = None
    # TODO: migrate to timezone-aware UTC timestamps (datetime.now(timezone.utc)).
    # All 25+ call sites currently use naive local time. Switching partially would
    # create mixed timezones in the DB. Requires a full migration + display updates.
    timestamp: datetime
    screenshot_path: str
    window_title: Optional[str] = None
    detected_app_name: Optional[str] = None  # From OS-level window detection
    bookmarked: bool = False

    # Gemma 4 analysis results
    analysis: Optional[ActivityRecord] = None

    # Developer context (populated for coding activities)
    dev_context: Optional[DevContext] = None

    # Embedding vector (stored as list of floats for JSON serialization)
    embedding: Optional[List[float]] = None

    # Processing status
    analyzed: bool = False
    analysis_error: Optional[str] = None


class DailySummary(BaseModel):
    """AI-generated daily activity summary."""

    id: Optional[int] = None
    date: str  # YYYY-MM-DD
    summary: str = ""
    total_activities: int = 0
    category_breakdown: dict = Field(default_factory=dict)
    top_repos: List[str] = Field(default_factory=list)
    productive_hours: float = 0.0


class StandupNotes(BaseModel):
    """Auto-generated standup meeting notes."""

    date: str
    yesterday: List[str] = Field(default_factory=list)
    today: List[str] = Field(default_factory=list)
    blockers: List[str] = Field(default_factory=list)
    raw_text: str = ""
