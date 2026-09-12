"""Voice Memos routes — list and serve voice memos."""

from pathlib import Path

from fastapi import APIRouter, HTTPException, Query
from fastapi.responses import FileResponse

from screenmind.api.dependencies import db

router = APIRouter(prefix="/api/memos", tags=["memos"])


@router.get("")
async def list_memos(
    date: str = Query(default=None),
    limit: int = Query(default=50, ge=1, le=200),
):
    """List voice memos, optionally filtered by date."""
    target_date = date or str(__import__("datetime").date.today())
    conn = db._get_conn()

    rows = conn.execute(
        """
        SELECT id, timestamp, screenshot_path, summary, app_name, bookmarked
        FROM activities
        WHERE DATE(timestamp) = ? AND (app_name = 'Voice Memo' OR detected_app = 'Voice Memo')
        ORDER BY timestamp DESC
        LIMIT ?
        """,
        (target_date, limit),
    ).fetchall()

    memos = []
    for row in rows:
        d = dict(row)
        d["screenshot_url"] = f"/api/screenshot/{d['id']}" if d.get("screenshot_path") else None
        # Check if WAV file exists in memos dir
        d["audio_url"] = f"/api/memos/{d['id']}/audio"
        memos.append(d)

    return {"memos": memos, "date": target_date}


@router.get("/{memo_id}/audio")
async def get_memo_audio(memo_id: int):
    """Serve the WAV audio file for a voice memo."""
    activity = db.get_activity_by_id(memo_id)
    if not activity or (activity.get("app_name") != "Voice Memo" and activity.get("detected_app") != "Voice Memo"):
        raise HTTPException(status_code=404, detail="Memo not found")

    # WAV path stored in details field
    wav_path = activity.get("details", "")
    if not wav_path or not Path(wav_path).exists():
        raise HTTPException(status_code=404, detail="Audio file not found")

    return FileResponse(wav_path, media_type="audio/wav")
