"""
Layout Analyzer — Gemma-Guided Screen Region Detection + OCR Text Organization

Two-step approach:
1. LAYOUT DETECTION: Gemma sees the screenshot → outputs visual regions as JSON
   (sidebar, chat_area, profile_panel, etc.) with adaptive coordinates
2. TEXT ORGANIZATION: OCR bounding boxes are classified into regions →
   organized text with section labels

Gemma only identifies VISUAL STRUCTURE — never reads text (which it's bad at).
OCR provides ACCURATE TEXT — never understands layout (which it's bad at).
Each tool does what it's best at.
"""

import logging

import io
import json
import re
import time
from typing import List, Dict, Optional, Tuple

from PIL import Image

logger = logging.getLogger("screenmind.engine.layout_analyzer")


# ── Layout Region Detection ──────────────────────────────────────────────────

LAYOUT_PROMPT = """Look at this screenshot. Identify ALL distinct VISUAL LAYOUT REGIONS with TIGHT boundaries.

Rules:
- Each region should TIGHTLY wrap only its content — do NOT make regions wider than the actual visual panel
- For apps with sidebars: the sidebar region should end exactly where the sidebar panel ends (NOT where the main content starts)
- Identify 3-5 regions for complex layouts (sidebar, content columns, panels, toolbars)

Return ONLY a JSON array. Each region:
- "name": descriptive label (e.g., "nav_sidebar", "chat_messages", "email_list", "sender_column", "profile_panel", "toolbar")
- "x_start": left edge as fraction 0.0-1.0 of screen width
- "x_end": right edge as fraction 0.0-1.0
- "y_start": top edge as fraction 0.0-1.0 of screen height  
- "y_end": bottom edge as fraction 0.0-1.0
- "content_type": one of: "navigation", "messages", "email_list", "user_profile", "toolbar", "code", "content"

Example for an email app: [{"name":"nav_sidebar","x_start":0.0,"x_end":0.12,"y_start":0.0,"y_end":1.0,"content_type":"navigation"},{"name":"email_list","x_start":0.12,"x_end":1.0,"y_start":0.08,"y_end":1.0,"content_type":"email_list"}]

Return ONLY the JSON array, nothing else."""


def detect_layout(image: Image.Image, ollama_client=None, model: str = "") -> List[Dict]:
    """
    Ask Gemma to identify visual layout regions from a screenshot.

    Args:
        image: PIL Image of the screenshot
        ollama_client: Deprecated, ignored. Kept for backward compat.
        model: Deprecated, ignored. Kept for backward compat.

    Returns:
        List of region dicts with name, x_start, x_end, y_start, y_end, content_type
    """
    from screenmind.engine import llm_client

    # Resize for Gemma (768px max — same as analyzer.py)
    img = image.copy()
    if img.mode != "RGB":
        img = img.convert("RGB")
    max_dim = 768
    if max(img.size) > max_dim:
        ratio = max_dim / max(img.size)
        img = img.resize(
            (int(img.size[0] * ratio), int(img.size[1] * ratio)),
            Image.Resampling.LANCZOS,
        )

    buf = io.BytesIO()
    img.save(buf, format="JPEG", quality=80)

    start = time.time()
    try:
        raw = llm_client.chat_with_images(
            prompt=LAYOUT_PROMPT,
            images=[buf.getvalue()],
            temperature=0.1,
            max_tokens=1200,
        )
        elapsed = time.time() - start
        logger.info(f"Gemma layout detection in {elapsed:.1f}s")
    except Exception as e:
        logger.error(f"Gemma layout detection failed: {e}")
        return _fallback_regions()

    return _parse_layout_json(raw)


def _parse_layout_json(raw: str) -> List[Dict]:
    """Parse Gemma's layout JSON response with recovery for truncated output."""
    try:
        # Strip markdown code fences (```json ... ```)
        cleaned = raw.strip()
        cleaned = re.sub(r'^```(?:json)?\s*\n?', '', cleaned)
        cleaned = re.sub(r'\n?```\s*$', '', cleaned)
        cleaned = cleaned.strip()

        # Try direct parse first
        try:
            regions = json.loads(cleaned)
            if isinstance(regions, list):
                return _validate_regions(regions)
        except json.JSONDecodeError:
            pass

        # Try to extract JSON array from response
        json_match = re.search(r'\[.*\]', cleaned, re.DOTALL)
        if json_match:
            try:
                regions = json.loads(json_match.group())
                return _validate_regions(regions)
            except json.JSONDecodeError:
                pass

        # Try to fix truncated JSON (Gemma ran out of tokens mid-array)
        if '[' in cleaned:
            # Find the start of the array
            arr_start = cleaned.index('[')
            partial = cleaned[arr_start:].rstrip()
            
            # Remove any trailing incomplete object
            last_complete = partial.rfind('}')
            if last_complete > 0:
                partial = partial[:last_complete + 1]
                # Close the array
                if not partial.endswith(']'):
                    partial = partial.rstrip(', \n') + ']'
                try:
                    regions = json.loads(partial)
                    logger.debug(f"Recovered {len(regions)} regions from truncated JSON")
                    return _validate_regions(regions)
                except json.JSONDecodeError:
                    pass

        logger.debug(f"Could not parse JSON, using fallback. Raw: {raw[:200]}")
        return _fallback_regions()

    except Exception as e:
        logger.debug(f"JSON parse error: {e}, using fallback")
        return _fallback_regions()


def _validate_regions(regions: List[Dict]) -> List[Dict]:
    """Validate and fill defaults for region dicts."""
    valid = []
    for r in regions:
        if all(k in r for k in ("name", "x_start", "x_end")):
            r.setdefault("y_start", 0.0)
            r.setdefault("y_end", 1.0)
            r.setdefault("content_type", "unknown")
            valid.append(r)
    return valid if valid else _fallback_regions()


def _fallback_regions() -> List[Dict]:
    """
    Fallback: treat entire screen as one region.
    This means organized_text = raw OCR order (no harm done).
    """
    return [
        {
            "name": "full_screen",
            "x_start": 0.0,
            "x_end": 1.0,
            "y_start": 0.0,
            "y_end": 1.0,
            "content_type": "unknown",
        }
    ]


# ── OCR Text Organization (Coordinate-Parsed) ───────────────────────────────

# Threshold for grouping OCR boxes into the same visual row (pixels)
_ROW_Y_THRESHOLD = 25

# Threshold for pairing username with timestamp (pixels Y distance)
_TIMESTAMP_Y_THRESHOLD = 15


def _is_timestamp(text: str) -> bool:
    """Check if text looks like a date/timestamp (e.g. '02-12-2024 20:02')."""
    return bool(re.search(r'\d{2}[-./]\d{2}[-./]\d{2,4}', text))


def _group_into_rows(boxes: List[Dict]) -> List[List[Dict]]:
    """Group OCR boxes into visual rows by Y-position proximity."""
    if not boxes:
        return []
    sorted_b = sorted(boxes, key=lambda b: (b['box'][0][1], b['box'][0][0]))
    rows = []
    current_row = []
    current_y = -999
    for b in sorted_b:
        y = b['box'][0][1]
        if abs(y - current_y) > _ROW_Y_THRESHOLD:
            if current_row:
                rows.append(current_row)
            current_row = [b]
            current_y = y
        else:
            current_row.append(b)
    if current_row:
        rows.append(current_row)
    return rows


def _format_rows_simple(boxes: List[Dict]) -> str:
    """Format boxes as simple Y-grouped rows, joined with two spaces."""
    rows = _group_into_rows(boxes)
    lines = []
    for row in rows:
        row.sort(key=lambda b: b['box'][0][0])
        texts = [b['text'].strip() for b in row]
        lines.append('  '.join(texts))
    return '\n'.join(lines)


def _format_chat_messages(boxes: List[Dict]) -> str:
    """
    Format chat-area boxes using timestamp-based sender attribution.

    Detects username+timestamp pairs (username is LEFT of timestamp on same Y row),
    then groups message lines below into 'sender: msg1 | msg2' format.
    """
    if not boxes:
        return ""

    sorted_b = sorted(boxes, key=lambda b: (b['box'][0][1], b['box'][0][0]))

    # Step 1: Find all timestamps and pair each with the username to its LEFT
    timestamps = [b for b in sorted_b if _is_timestamp(b['text'])]
    username_ids = set()  # id() of boxes identified as usernames

    for ts in timestamps:
        ts_x, ts_y = ts['box'][0]
        best = None
        for b in sorted_b:
            if b is ts:
                continue
            bx, by = b['box'][0]
            # Username must be LEFT of timestamp and on same Y (within threshold)
            if bx < ts_x and abs(by - ts_y) < _TIMESTAMP_Y_THRESHOLD and not _is_timestamp(b['text']):
                if best is None or bx > best['box'][0][0]:  # pick closest to the left
                    best = b
        if best:
            username_ids.add(id(best))

    # Step 2: Build message groups
    messages = []
    current_sender = None
    current_lines = []

    for b in sorted_b:
        text = b['text'].strip()
        if not text:
            continue

        if id(b) in username_ids:
            # Save previous group
            if current_sender and current_lines:
                messages.append((current_sender, current_lines))
            current_sender = text
            current_lines = []
        elif _is_timestamp(text):
            continue  # skip timestamp text (already used for pairing)
        else:
            current_lines.append(text)

    # Don't forget last group
    if current_sender and current_lines:
        messages.append((current_sender, current_lines))

    if messages:
        return '\n'.join(f"{s}: {' | '.join(ls)}" for s, ls in messages)

    # Fallback: no timestamps found — use simple row format
    return _format_rows_simple(boxes)


# ── OCR-Based Layout Clustering ──────────────────────────────────────────────

def cluster_ocr_layout(ocr_boxes: List[Dict], screen_width: int, screen_height: int) -> List[Dict]:
    """
    Detect layout regions purely from OCR bounding box spatial distribution.
    Clusters boxes into left/center/right columns by x-coordinate.
    No LLM call needed — instant.

    Returns regions in the same format as detect_layout() for compatibility
    with organize_ocr_text().
    """
    if not ocr_boxes:
        return []

    # Compute x-center fractions for all boxes
    x_centers = []
    for b in ocr_boxes:
        cx = ((b['box'][0][0] + b['box'][2][0]) / 2) / screen_width
        x_centers.append(cx)

    # Count boxes in each column zone
    left_count = sum(1 for x in x_centers if x < 0.22)
    right_count = sum(1 for x in x_centers if x > 0.78)
    center_count = len(x_centers) - left_count - right_count

    # Build regions only for zones that have content
    regions = []
    if left_count >= 2:
        regions.append({
            "name": "region_left",
            "x_start": 0.0,
            "x_end": 0.22,
            "y_start": 0.0,
            "y_end": 1.0,
            "content_type": "navigation",
        })
    if center_count >= 2:
        x_start = 0.22 if left_count >= 2 else 0.0
        x_end = 0.78 if right_count >= 2 else 1.0
        regions.append({
            "name": "region_center",
            "x_start": x_start,
            "x_end": x_end,
            "y_start": 0.0,
            "y_end": 1.0,
            "content_type": "content",
        })
    if right_count >= 2:
        regions.append({
            "name": "region_right",
            "x_start": 0.78,
            "x_end": 1.0,
            "y_start": 0.0,
            "y_end": 1.0,
            "content_type": "content",
        })

    # Fallback: if no clear columns, return single full-screen region
    if not regions:
        regions.append({
            "name": "main_content",
            "x_start": 0.0,
            "x_end": 1.0,
            "y_start": 0.0,
            "y_end": 1.0,
            "content_type": "content",
        })

    return regions


def organize_ocr_text(
    ocr_boxes: List[Dict],
    regions: List[Dict],
    screen_width: int,
    screen_height: int,
) -> str:
    """
    Classify OCR bounding boxes into Gemma-detected layout regions,
    then produce organized text with section labels.

    Uses coordinate-based parsing:
    - Regions sorted narrow-first to prevent overlap
    - Chat/message regions use timestamp-based sender attribution
    - Other regions use Y-row grouping

    Args:
        ocr_boxes: List of {"box": [[x1,y1],...], "text": str, "conf": float}
        regions: Layout regions from detect_layout()
        screen_width: Original screenshot width in pixels
        screen_height: Original screenshot height in pixels

    Returns:
        Organized text string with [SECTION] headers
    """
    if not ocr_boxes or not regions:
        return ""

    # Sort regions narrow-first so smaller panels match before wide ones
    # (prevents e.g. main_content x=0.25-1.0 from stealing profile x=0.75-1.0 boxes)
    sorted_regions = sorted(
        regions,
        key=lambda r: (r['x_end'] - r['x_start']) * (r.get('y_end', 1) - r.get('y_start', 0))
    )

    # Classify each box into a region
    region_boxes: Dict[str, List[Dict]] = {r['name']: [] for r in sorted_regions}
    unclassified = []

    for box in ocr_boxes:
        text = box.get("text", "").strip()
        if not text or len(text) <= 1 or box.get("conf", 0) < 0.3:
            continue

        # Get center point as fraction of screen
        coords = box["box"]
        x_frac = ((coords[0][0] + coords[2][0]) / 2) / screen_width
        y_frac = ((coords[0][1] + coords[2][1]) / 2) / screen_height

        # Find matching region (narrow-first ordering ensures correct match)
        matched = False
        for r in sorted_regions:
            if (r["x_start"] <= x_frac <= r["x_end"] and
                    r.get("y_start", 0) <= y_frac <= r.get("y_end", 1)):
                region_boxes[r['name']].append(box)
                matched = True
                break

        if not matched:
            unclassified.append(box)

    # Build organized text output
    # Use original region order (not narrow-first) for readable output
    sections = []
    for r in regions:
        name = r['name']
        boxes = region_boxes.get(name, [])
        if not boxes:
            continue

        content_type = r.get('content_type', 'unknown')

        # Chat/message regions: use timestamp-based sender attribution
        # Also auto-detect: if region contains timestamps, treat as chat
        has_timestamps = any(_is_timestamp(b.get('text', '')) for b in boxes)
        is_chat = (content_type in ('messages', 'chat')
                   or 'chat' in name.lower()
                   or 'message' in name.lower()
                   or (has_timestamps and content_type in ('content', 'unknown', 'navigation')))
        if is_chat:
            text = _format_chat_messages(boxes)
        else:
            text = _format_rows_simple(boxes)

        label = f"[{name.upper().replace('_', ' ')}]"
        sections.append(f"{label}\n{text}")

    if unclassified:
        text = _format_rows_simple(unclassified)
        sections.append(f"[OTHER]\n{text}")

    return "\n\n".join(sections)



def organize_with_layout(
    image: Image.Image,
    ocr_boxes: List[Dict],
    ollama_client=None,
    model: str = "",
) -> Tuple[str, List[Dict]]:
    """
    Full pipeline: Detect layout + organize OCR text.

    Args:
        image: Screenshot PIL Image
        ocr_boxes: OCR bounding boxes with text
        ollama_client: Deprecated, ignored. Kept for backward compat.
        model: Deprecated, ignored. Kept for backward compat.

    Returns:
        Tuple of (organized_text, layout_regions)
    """
    if not ocr_boxes:
        return "", []

    screen_w, screen_h = image.size

    # Step 1: Detect layout regions
    regions = detect_layout(image)
    logger.info(f"Detected {len(regions)} regions: "
          f"{', '.join(r['name'] for r in regions)}")

    # Step 2: Organize OCR text using regions
    organized = organize_ocr_text(ocr_boxes, regions, screen_w, screen_h)

    # Count classification stats
    total_boxes = len([b for b in ocr_boxes if len(b.get("text", "").strip()) > 1])
    logger.info(f"Organized {total_boxes} text blocks into {len(regions)} regions")

    return organized, regions
