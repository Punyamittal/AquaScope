"""
Obsidian Integration
Auto-exports daily summaries to an Obsidian vault as markdown files.
"""

import logging
import os
from datetime import datetime
from pathlib import Path

logger = logging.getLogger("screenmind.integrations.obsidian")


def export_summary(vault_path: str, date_str: str, summary: str, standup: str = "", activity_count: int = 0) -> bool:
    """
    Write a daily summary to the Obsidian vault.
    
    Creates: {vault}/ScreenMind/{YYYY-MM-DD}.md
    
    Args:
        vault_path: Absolute path to the Obsidian vault root folder.
        date_str: Date in YYYY-MM-DD format.
        summary: The AI-generated daily summary text.
        standup: Optional standup notes text.
        activity_count: Number of activities captured that day.
    
    Returns:
        True if export succeeded, False otherwise.
    """
    if not vault_path or not Path(vault_path).is_dir():
        logger.warning(f"Vault path not found: {vault_path}")
        return False

    # Create ScreenMind subfolder in vault
    export_dir = Path(vault_path) / "ScreenMind"
    export_dir.mkdir(parents=True, exist_ok=True)

    filepath = export_dir / f"{date_str}.md"

    # Build markdown content with Obsidian-friendly formatting
    lines = [
        f"# 📋 ScreenMind — {date_str}",
        "",
        f"*Auto-generated on {datetime.now().strftime('%Y-%m-%d %H:%M')}*",
        f"*{activity_count} screen activities captured*",
        "",
        "---",
        "",
    ]

    if summary:
        lines += [
            "## 📝 Daily Summary",
            "",
            summary.strip(),
            "",
        ]

    if standup:
        lines += [
            "## 📋 Standup Notes",
            "",
            standup.strip(),
            "",
        ]

    # Obsidian tags and backlinks
    lines += [
        "---",
        "",
        f"#screenmind #daily-summary #{date_str[:7]}",
        f"[[{date_str} Daily Log]]",
        "",
    ]

    content = "\n".join(lines)

    try:
        filepath.write_text(content, encoding="utf-8")
        logger.info(f"Exported summary to {filepath}")
        return True
    except Exception as e:
        logger.error(f"Export failed: {e}")
        return False
