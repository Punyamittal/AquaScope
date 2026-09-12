"""
AquaScope Integration Router for ScreenMind.
Receives physical acoustic scan events and leak detection data from AquaScope (iQOO 15)
and indexes them into ScreenMind's unified episodic timeline and SQLite FTS5 store.
"""

import datetime
import json
import logging
from typing import Any, Dict, List, Optional
from pydantic import BaseModel, Field
from fastapi import APIRouter, HTTPException, Query

from screenmind.api.dependencies import db, embedder

logger = logging.getLogger("screenmind.api.routes.aquascope")

router = APIRouter(prefix="/api/aquascope", tags=["aquascope"])


class AcousticSyncPayload(BaseModel):
    id: Optional[str] = None
    timestamp: Optional[str] = None
    timestamp_ms: Optional[int] = None
    location_id: Optional[str] = "unknown_location"
    location_label: Optional[str] = "Physical Wall / Pipe"
    object_id: Optional[str] = "pipe"
    object_label: Optional[str] = "Water Pipe"
    event_type: Optional[str] = "ACOUSTIC_SCAN"
    anomaly_score: Optional[float] = 0.0
    confidence: Optional[float] = 1.0
    baseline_id: Optional[str] = None
    features: Optional[Dict[str, float]] = Field(default_factory=dict)
    summary: Optional[str] = ""
    evidence_state: Optional[str] = "OBSERVED"
    evidence_notes: Optional[List[str]] = Field(default_factory=list)
    unknown_notes: Optional[List[str]] = Field(default_factory=list)
    source: Optional[str] = "AQUASCOPE"


@router.get("/status")
async def get_aquascope_status():
    """Health check and sync stats for the AquaScope integration."""
    conn = db._get_conn()
    count_row = conn.execute(
        "SELECT COUNT(*) FROM activities WHERE category = 'physical_sensor' OR app_name LIKE '%AquaScope%'"
    ).fetchone()
    total_physical_events = count_row[0] if count_row else 0

    return {
        "status": "online",
        "service": "AquaScope x ScreenMind Bridge",
        "version": "1.0.0",
        "synced_physical_events": total_physical_events,
        "supported_features": ["acoustic_chirp", "deconvolution_fft", "anomaly_scoring", "episodic_sync"],
    }


@router.get("/events")
async def get_aquascope_events(
    limit: int = Query(default=20, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
):
    """Retrieve synced physical acoustic events from AquaScope."""
    conn = db._get_conn()
    rows = conn.execute(
        """
        SELECT id, timestamp, app_name, category, summary, details, visible_text
        FROM activities
        WHERE category = 'physical_sensor' OR app_name LIKE '%AquaScope%'
        ORDER BY timestamp DESC
        LIMIT ? OFFSET ?
        """,
        (limit, offset),
    ).fetchall()

    events = []
    for r in rows:
        d = dict(r)
        events.append(d)

    return {"count": len(events), "events": events}


@router.post("/event")
async def receive_aquascope_event(payload: AcousticSyncPayload):
    """
    Ingest a physical acoustic event from AquaScope into ScreenMind's memory.
    The event is written to SQLite activities with category='physical_sensor'
    and automatically indexed in FTS5 for hybrid search and Gemma 4 RAG.
    """
    conn = db._get_conn()

    # Determine timestamp (ISO format)
    if payload.timestamp:
        iso_ts = payload.timestamp
    elif payload.timestamp_ms:
        iso_ts = datetime.datetime.fromtimestamp(
            payload.timestamp_ms / 1000.0, tz=datetime.timezone.utc
        ).strftime("%Y-%m-%d %H:%M:%S")
    else:
        iso_ts = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d %H:%M:%S")

    # Build readable summary if empty
    summary = payload.summary
    if not summary:
        leak_indicator = "ANOMALY DETECTED" if (payload.anomaly_score or 0) > 25.0 else "NORMAL"
        summary = (
            f"AquaScope Acoustic Scan at {payload.location_label} ({payload.object_label}): "
            f"{leak_indicator} (Anomaly Score: {payload.anomaly_score or 0.0:.1f}%)"
        )

    # Build detailed context
    details_lines = [
        f"Location: {payload.location_label} (ID: {payload.location_id})",
        f"Object: {payload.object_label} (ID: {payload.object_id})",
        f"Event Type: {payload.event_type}",
        f"Anomaly Score: {payload.anomaly_score or 0.0:.2f}%",
        f"Evidence State: {payload.evidence_state}",
        f"Sensor Source: {payload.source} (iQOO 15 Ultrasound Chirp / KissFFT DSP)",
    ]
    if payload.baseline_id:
        details_lines.append(f"Baseline ID: {payload.baseline_id}")
    if payload.evidence_notes:
        details_lines.append("Evidence Notes: " + "; ".join(payload.evidence_notes))
    if payload.features:
        feat_str = ", ".join(f"{k}={v:.2f}" for k, v in payload.features.items())
        details_lines.append(f"Acoustic Features: {feat_str}")

    details = "\n".join(details_lines)
    app_name = f"AquaScope ({payload.location_label})"
    category = "physical_sensor"
    scene_desc = f"Acoustic pipe diagnostic scan at {payload.location_label}. Anomaly score {payload.anomaly_score or 0.0:.1f}%."

    # Compute embedding if available
    embedding_bytes = None
    if embedder:
        try:
            emb = embedder.embed_activity(
                summary=summary,
                details=details,
                visible_text=[payload.location_label, payload.object_label, payload.event_type or ""],
                app_name=app_name,
                category=category,
                scene_description=scene_desc,
            )
            if emb:
                embedding_bytes = db._encode_embedding(emb)
        except Exception as e:
            logger.warning(f"Failed to embed AquaScope physical event: {e}")

    try:
        cursor = conn.execute(
            """
            INSERT INTO activities (
                timestamp, screenshot_path, window_title, detected_app,
                bookmarked, app_name, category, summary, details,
                visible_text, mood, confidence, embedding, scene_description,
                analyzed, analysis_method, status
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                iso_ts,
                "",  # No screenshot path for acoustic probe
                f"AquaScope Scan - {payload.location_label}",
                "AquaScope",
                1 if (payload.anomaly_score or 0) > 30.0 else 0,
                app_name,
                category,
                summary,
                details,
                json.dumps([payload.location_label, payload.object_label, payload.event_type or ""]),
                "alert" if (payload.anomaly_score or 0) > 30.0 else "neutral",
                payload.confidence or 1.0,
                embedding_bytes,
                scene_desc,
                1,
                "aquascope_acoustic_probe",
                "ok",
            ),
        )
        conn.commit()
        inserted_id = cursor.lastrowid
        logger.info(f"Ingested AquaScope physical event #{inserted_id} ({summary})")
        return {
            "success": True,
            "activity_id": inserted_id,
            "summary": summary,
            "anomaly_score": payload.anomaly_score,
            "timestamp": iso_ts,
        }
    except Exception as e:
        conn.rollback()
        logger.error(f"Failed to save AquaScope event: {e}")
        raise HTTPException(status_code=500, detail=f"Failed to insert physical event: {e}")
