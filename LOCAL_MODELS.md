# Local on-device models (SMRITI Ask)

Ask SMRITI always retrieves episodic memory and answers with the **rule engine** first.
An optional **MediaPipe** local model (Gemma or Qwen) may only **rephrase** that grounded answer.

No model → rules only. App launch never depends on a download.

## Recommended for iQOO 15

| RAM | Model | Why |
|-----|--------|-----|
| 12 GB | **Gemma 3 1B IT INT4** (`.task`) | Light, fits Ask rephrase |
| 16 GB | Gemma 3 1B or Qwen 2.5 1.5B INT8 | More fluent phrasing |

## Install in the app

1. Open **Ask → tap model badge → Local model** (System)
2. Tap **Download** on Gemma 3 1B (recommended) or Qwen 2.5 1.5B
3. **Gemma** is gated: accept the license on Hugging Face, create an access token, paste it, then download
4. **Qwen** downloads without a token
5. Leave **Use local model when available** on

Keep the app open until the progress bar finishes. A dropped download can resume.

Import still works: **Import .task / .bin** and pick a file from Downloads.

### adb

```bat
adb push gemma3-1b-it-int4.task /sdcard/Download/
```

Then Import from Downloads, or copy into app files:

```bat
adb shell mkdir -p /data/local/tmp/smriti
adb push gemma3-1b-it-int4.task /data/local/tmp/smriti/
adb shell run-as com.aquascope cp /data/local/tmp/smriti/gemma3-1b-it-int4.task files/smriti_models/
```

Reload in **Local model**.

Expected path:

`/data/data/com.aquascope/files/smriti_models/`

## Grounding rules (unchanged)

- Source of truth = local episodic memory + `ReasoningEngine`
- Local LLM must not invent timestamps, scores, or confirmed leaks
- Soft post-check drops unsafe model text and falls back to rules
- Evidence screen stays authoritative

## Dependency

`com.google.mediapipe:tasks-genai:0.10.27` (optional at runtime — fails soft if the `.task` is missing or incompatible).
