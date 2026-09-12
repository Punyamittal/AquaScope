"""Rewind routes — timelapse day replay."""

from fastapi import APIRouter, Query

from screenmind.api.dependencies import db

router = APIRouter(prefix="/api", tags=["rewind"])


@router.get("/rewind")
async def get_rewind(
    date: str = Query(default=None),
):
    """Get timelapse frames for day rewind."""
    target = date or str(__import__("datetime").date.today())
    frames = db.get_rewind_data(target)
    for f in frames:
        if f.get("screenshot_path"):
            f["screenshot_url"] = f"/api/screenshot/{f['id']}"
    return {"date": target, "frames": frames}
