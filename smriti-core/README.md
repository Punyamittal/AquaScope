# SMRITI Core

**100% offline, air-gapped "second brain" for the iQOO flagship** — episodic memory hub +
gaming clip engine + ambient guardian + health recall. No `android.permission.INTERNET`
anywhere in the app; every inference path runs on-device.

Target hardware: iQOO flagship (Snapdragon 8 Elite Gen 5, 32 GB effective RAM, UFS 4.0,
dual X-axis haptics, IR blaster, IMU, Monster Halo RGB).

- Package: `com.smriti.core` · minSdk 26 · target/compileSdk 34
- Kotlin 2.0.20 (+ kapt) · AGP 8.5.2 · Compose BOM 2024.09.00
- UI: **Cyber-Tactile Glassmorphism, dark only** — bg `#0A0A0C`, phosphor cyan `#00F0FF`,
  crimson `#FF003C`, amber `#FFB800`; glass cards `#14FFFFFF` bg / `1dp #33FFFFFF` border / 24 dp corner.

## Architecture

```
┌─────────────────────────────── UI (com.smriti.core.ui) ───────────────────────────────┐
│ SmritiTelemetryUI: TelemetryHeader · NeuralOrb · MicWaveform · MemoryTimeline (gyro   │
│ tilt glass cards) · VoiceRecallBar · RecorderControl · GuardianAlertBanner            │
│ SmritiViewModel: telemetry/episodes/verdict/guardianAlerts/orbState/halo/recorderState│
│ TelemetryMonitor: 1 Hz RAM + TokenMeter + /sys/class/thermal scan + NPU label         │
│ PlayProjectionService: mediaProjection FGS host for SMRITI Play                       │
└───────────────┬───────────────┬──────────────────┬──────────────────┬─────────────────┘
                │               │                  │                  │
        ┌───────▼──────┐ ┌──────▼───────┐ ┌────────▼────────┐ ┌───────▼─────────┐
        │ memory/ (MEM)│ │ play/ (PLAY) │ │ actuators/ (ACT)│ │ guardian/ (GRD) │
        │ Room smriti.db│ │30s RAM ring  │ │ Monster Halo RGB│ │ YAMNet TFLite   │
        │ FTS5 + 128-d │ │ H.264 1080p60│ │ haptic waveforms│ │ fall detector   │
        │ hash embeds, │ │ MediaMuxer   │ │ NEC IR blaster  │ │ Vosk streaming  │
        │ zero-halluc. │ │ clip save    │ │                 │ │ STT             │
        │ recall        │ │              │ │                 │ │                 │
        └──────────────┘ └──────────────┘ └─────────────────┘ └─────────────────┘
                              SmritiApp singletons: engine · actuators · recorder · guardian
```

Cross-module contracts are frozen in `SPEC.md` §2. The UI never reaches around them.

## Model assets (drop-in — not committed)

The code paths are complete and degrade gracefully (return false / skip, never crash)
when an asset is absent. Drop weights in at these **exact paths**:

| Asset | Path | Used by |
|---|---|---|
| YAMNet TFLite (required for guardian sound classification) | `app/src/main/assets/yamnet.tflite` | GRD `AmbientAudioSensorManager` (NNAPI delegate) |
| YAMNet labels (optional; built-in 12-class map otherwise) | `app/src/main/assets/yamnet_labels.csv` | GRD `AmbientAudioSensorManager` |
| Vosk small model, unzipped (required for offline STT) | `filesDir/vosk-model/` — e.g. push to `/data/data/com.smriti.core/files/vosk-model` | GRD `VoskStt` |
| MediaPipe GenAI embedder/LLM (optional drop-in for semantic embeddings + tok/s) | `app/src/main/assets/*.task` (see `memory/EmbeddingProvider.kt` KDoc) | MEM `EmbeddingProvider` / future LLM path (`ui.TokenMeter`) |

`.tflite` assets are packaged uncompressed (`noCompress`) for direct mmap;
APK is `arm64-v8a` only.

## Build

```bash
# Android Studio Hedgehog+ or CLI with JDK 17, Android SDK 34
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease    # R8 minified, rules in app/proguard-rules.pro
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Offline guarantee

- No `INTERNET` permission in `AndroidManifest.xml`; no network libraries on the classpath.
- ML Kit **bundled** text recognition (`com.google.mlkit:text-recognition`) ships models in-app.
- Vosk, YAMNet/TFLite, MediaPipe all run fully on-device (NNAPI/GPU where available).
- All memory stays in the on-device Room database (`smriti.db`, cap 200 000 rows, trimmed on start).

## Demo flow

1. **Capture** — SMRITI ingests episodes (OCR / manual / guardian speech) into episodic memory.
2. **Ask** — type or speak a question in the VoiceRecallBar; the orb goes
   LISTENING → COMPUTING → SPEAKING.
3. **Halo/haptic confirm** — on a `Found` verdict the Monster Halo flips CONFIRM_AMBER with a
   RECALL_CONFIRM haptic, TTS speaks the citation (zero-hallucination: no record →
   "No record found" in crimson), orb lands VERIFIED.
4. **SMRITI Play** — tap PLAY, grant the MediaProjection consent; the 30 s RAM ring records.
   Trigger events flush a clip from the last IDR frame; the chip confirms `SAVED <file>`.

Guardian alerts (smoke alarm / fall / speech) raise the banner and drive
EMERGENCY_STROBE + FALL_ALARM (fall) or CONFIRM_AMBER + RECALL_CONFIRM (sound/speech).
