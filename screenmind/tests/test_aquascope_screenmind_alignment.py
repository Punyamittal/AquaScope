"""
Test suite validating the seamless integration and alignment
between AquaScope and ScreenMind.
"""

import pytest


@pytest.mark.asyncio
async def test_aquascope_status(client):
    res = await client.get("/api/aquascope/status")
    assert res.status_code == 200
    data = res.json()
    assert data["status"] == "online"
    assert "AquaScope" in data["service"]
    assert "synced_physical_events" in data


@pytest.mark.asyncio
async def test_aquascope_event_ingestion_and_timeline(client, db):
    # 1. Post a physical acoustic event from AquaScope
    payload = {
        "id": "scan_test_101",
        "location_id": "loc_master_bath",
        "location_label": "Master Bathroom Wall",
        "object_id": "hot_water_pipe",
        "object_label": "Copper Hot Water Pipe",
        "event_type": "POSSIBLE_LEAK",
        "anomaly_score": 42.5,
        "confidence": 0.94,
        "summary": "AquaScope Acoustic Scan: 42.5% deviation detected on Master Bathroom Wall",
        "evidence_notes": ["Log-mel peak at 2.4 kHz", "Decay time +38ms above baseline"],
        "source": "AQUASCOPE (iQOO 15)",
    }
    post_res = await client.post("/api/aquascope/event", json=payload)
    assert post_res.status_code == 200
    post_data = post_res.json()
    assert post_data["success"] is True
    assert post_data["anomaly_score"] == 42.5

    # 2. Verify it is reflected in get_aquascope_events
    events_res = await client.get("/api/aquascope/events")
    assert events_res.status_code == 200
    events_data = events_res.json()
    assert events_data["count"] >= 1
    matched = [e for e in events_data["events"] if "Master Bathroom Wall" in e["summary"]]
    assert len(matched) >= 1

    # 3. Verify timeline returns both 'activities' and 'items' schemas
    timeline_res = await client.get("/api/timeline?limit=10")
    assert timeline_res.status_code == 200
    timeline_data = timeline_res.json()
    assert "activities" in timeline_data
    assert "items" in timeline_data
    assert len(timeline_data["activities"]) == len(timeline_data["items"])
    assert any("AquaScope" in a["app_name"] for a in timeline_data["activities"])

    # 4. Verify hybrid search finds the acoustic event via FTS5 even without embedder
    search_res = await client.get("/api/search?q=Bathroom")
    assert search_res.status_code == 200
    search_data = search_res.json()
    assert "results" in search_data
    assert any("Master Bathroom" in r["summary"] or "Bathroom" in r["details"] for r in search_data["results"])


@pytest.mark.asyncio
async def test_chat_supports_message_param_and_non_streaming_json(client):
    # Test chat endpoint with message param and stream=False
    chat_res = await client.post("/api/chat", json={"message": "hello", "stream": False})
    assert chat_res.status_code == 200
    data = chat_res.json()
    assert "answer" in data
    assert "mode" in data
