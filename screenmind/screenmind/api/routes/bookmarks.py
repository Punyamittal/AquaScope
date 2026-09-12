"""Bookmark routes — toggle and list bookmarks."""

from fastapi import APIRouter, Query

from screenmind.api.dependencies import db

router = APIRouter(prefix="/api", tags=["bookmarks"])


@router.put("/activities/{activity_id}/bookmark")
async def toggle_bookmark(activity_id: int):
    """Toggle bookmark status."""
    new_state = db.toggle_bookmark(activity_id)
    return {"bookmarked": new_state, "activity_id": activity_id}


@router.get("/bookmarks")
async def get_bookmarks(limit: int = Query(default=50, ge=1, le=200)):
    bookmarks = db.get_bookmarks(limit=limit)
    for b in bookmarks:
        if b.get("screenshot_path"):
            b["screenshot_url"] = f"/api/screenshot/{b['id']}"
    return {"bookmarks": bookmarks}
