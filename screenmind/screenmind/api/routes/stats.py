"""Stats routes — analytics, heatmap, disk usage, storage estimate."""

from datetime import date, timedelta
from typing import Optional

from fastapi import APIRouter, Query

from screenmind.api.dependencies import db

router = APIRouter(prefix="/api", tags=["stats"])


@router.get("/stats")
async def get_stats(
    date_from: Optional[str] = Query(default=None),
    date_to: Optional[str] = Query(default=None),
):
    """Get aggregated statistics."""
    today = str(date.today())
    df = date_from or today
    dt = date_to or today
    stats = db.get_stats(df, dt)
    return stats


@router.get("/stats/heatmap")
async def get_heatmap(
    date_from: Optional[str] = Query(default=None),
    date_to: Optional[str] = Query(default=None),
):
    today = str(date.today())
    df = date_from or str(date.today() - timedelta(days=7))
    dt = date_to or today
    return db.get_hourly_heatmap(df, dt)


@router.get("/disk")
async def get_disk_usage():
    return db.get_disk_usage()


@router.get("/storage-estimate")
async def storage_estimate():
    """Get storage usage estimates for different retention periods."""
    return db.get_storage_estimate()
