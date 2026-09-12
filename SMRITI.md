# SMRITI × AQUASCOPE integration

## Product

**SMRITI AQUA** — AquaScope senses; SMRITI remembers and reasons over local episodic memory.

Tagline: *Your home has a memory.*

## Architecture (unchanged AquaScope core)

```
audio/ + dsp/ + baseline/     ← sensing (untouched math)
        ↓
ScanActivity                  ← emits structured events
        ↓
com.aquascope.smriti/         ← intelligence layer
  model/        PhysicalEvent, EvidenceState, …
  memory/       EpisodicMemoryStore (on-device JSON)
  engine/       EventNormalizer, Retrieval, Evidence, Reasoning
  SmritiCore    facade
        ↓
UI: Home · Scan · Memory · Ask · Evidence
```

## Source of truth

Local episodic files under `filesDir/smriti_memory/`:
- `episodes.json`
- `home_model.json`
- `object_baselines.json`

The AI never invents history. Ask SMRITI runs **structured retrieval + local rule reasoning** (offline). An optional **on-device Gemma** (MediaPipe) may rephrase that grounded answer — see [LOCAL_MODELS.md](LOCAL_MODELS.md). Cloud LLMs are not required.

## Demo path

1. Open **Home** (SMRITI)
2. **Scan** → create location → dry baseline
3. Scan again (normal / elevated)
4. Repeat anomaly → memory shows REPEATED / POSSIBLE
5. **Ask**: “Has this happened before?” / “Is it definitely a leak?”
6. **View evidence** — supporting ✓ vs unknown ✕

## iQOO 15

Sensing defaults remain in `IqooDeviceProfile`. SMRITI adds negligible RAM vs DSP.
Optional Gemma 3 1B INT4 for Ask rephrase: ~1–2 GB extra when loaded (see `LOCAL_MODELS.md`).

**Monster Halo** is SMRITI’s physical expression — see [HALO.md](HALO.md). Screen state and camera-border light share one semantic palette (cyan / blue / purple / white / amber / red / teal).

## Merged iQOO 15 Android Modules

- **`:smriti-aqua`**: Native C++ KissFFT DSP engine (`dsp_core.cpp`, `jni_bridge.cpp`, CMake build), JNI wrapper (`DspEngine.kt`), acoustic and accelerometer fusion (`AcousticProbe.kt`, `AccelRecorder.kt`, `FusionGate.kt`), SQLite episodic memory store (`EpisodicStore.kt`), grounded recall engine (`RecallEngine.kt`), and offline Compose UI (`ScanScreen`, `TimelineScreen`, `ChatScreen`). Directly merged from the iQOO 15 Android Project.
- **`:smriti-core`**: Standalone second-brain offline hub (`com.smriti.core`).
- **Specification Contract**: See [docs/iQOO_15_SMRITI_AQUA_SPEC.md](docs/iQOO_15_SMRITI_AQUA_SPEC.md) and [docs/iQOO_15_SMRITI_CORE_SPEC.md](docs/iQOO_15_SMRITI_CORE_SPEC.md).
