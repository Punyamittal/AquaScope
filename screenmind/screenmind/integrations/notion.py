"""
Notion Integration
Auto-exports daily summaries to a Notion database.
Requires: pip install notion-client
"""

import logging
from datetime import datetime

logger = logging.getLogger("screenmind.integrations.notion")


def export_summary(token: str, database_id: str, date_str: str, summary: str, standup: str = "", activity_count: int = 0) -> bool:
    """
    Create a page in the user's Notion database with the daily summary.
    
    Args:
        token: Notion internal integration token.
        database_id: Target Notion database ID.
        date_str: Date in YYYY-MM-DD format.
        summary: The AI-generated daily summary text.
        standup: Optional standup notes text.
        activity_count: Number of activities captured that day.
    
    Returns:
        True if export succeeded, False otherwise.
    """
    try:
        from notion_client import Client
    except ImportError:
        logger.warning("notion-client not installed. Run: pip install notion-client")
        return False

    if not token or not database_id:
        logger.warning("Token or database ID not configured.")
        return False

    try:
        notion = Client(auth=token)

        # Build page content as Notion blocks
        children = [
            {
                "object": "block",
                "type": "heading_2",
                "heading_2": {"rich_text": [{"type": "text", "text": {"content": "📝 Daily Summary"}}]}
            },
        ]

        # Split summary into paragraphs (Notion blocks have a 2000 char limit)
        if summary:
            for para in summary.strip().split("\n\n"):
                if para.strip():
                    children.append({
                        "object": "block",
                        "type": "paragraph",
                        "paragraph": {"rich_text": [{"type": "text", "text": {"content": para.strip()[:2000]}}]}
                    })

        if standup:
            children.append({
                "object": "block",
                "type": "heading_2",
                "heading_2": {"rich_text": [{"type": "text", "text": {"content": "📋 Standup Notes"}}]}
            })
            for para in standup.strip().split("\n\n"):
                if para.strip():
                    children.append({
                        "object": "block",
                        "type": "paragraph",
                        "paragraph": {"rich_text": [{"type": "text", "text": {"content": para.strip()[:2000]}}]}
                    })

        # Create the page
        notion.pages.create(
            parent={"database_id": database_id},
            properties={
                "Name": {
                    "title": [{"text": {"content": f"ScreenMind — {date_str}"}}]
                },
                "Date": {
                    "date": {"start": date_str}
                },
            },
            children=children,
        )

        logger.info(f"Exported summary for {date_str}")
        return True

    except Exception as e:
        logger.error(f"Export failed: {e}")
        return False


def test_connection(token: str, database_id: str) -> dict:
    """Test Notion API connection. Returns status dict."""
    try:
        from notion_client import Client
    except ImportError:
        return {"ok": False, "error": "notion-client not installed. Run: pip install notion-client"}

    if not token:
        return {"ok": False, "error": "Token not provided"}
    if not database_id:
        return {"ok": False, "error": "Database ID not provided"}

    try:
        notion = Client(auth=token)
        db = notion.databases.retrieve(database_id=database_id)
        return {"ok": True, "database_title": db.get("title", [{}])[0].get("plain_text", "Unknown")}
    except Exception as e:
        return {"ok": False, "error": str(e)}
