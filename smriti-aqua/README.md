# SMRITI AQUA

> **SMRITI AQUA gives your phone a memory of your home's plumbing. It hears the normal. It remembers the change.**

An offline, on-device **episodic-memory acoustic app** for the iQOO 15. It
chirps into a pipe (17–23 kHz), listens to the echo, learns what "normal"
sounds like, and remembers every change — then answers questions about its
own memory, grounded only in stored events. **100% offline: the APK has no
`INTERNET` permission. Airplane mode is the proof.**

## Architecture

```
            +------------------- iQOO 15 (arm64) -------------------+
            |                                                     |
  chirp --->|  ChirpPlayer   AcousticProbe   AudioCapture (mic)   |
  (speaker) |        \           |              /                 |
            |         +----------v----------+                     |
            |         |   DspEngine (JNI)   |  C++: KissFFT STFT  |
            |         |  fingerprint 72-d   |  -> log-mel 17-23k  |
            |         |  anomalyScore       |  -> Mahalanobis     |
            |         +----------+----------+                     |
            |                    |                                |
  accel ----|--> AccelRecorder ->+-> coherenceScore -> FusionGate |
            |                    |           (ambient discard)    |
            |            +-------v--------+     +--------------+  |
            |            | EpisodicStore  |---->| RecallEngine |  |
            |            | SQLite events  |     | grounded Q&A |  |
            |            | + baselines    |     +------+-------+  |
            |            +-------+--------+            |          |
            |                    |              Narrator (tmpl /  |
            |            +-------v--------+     optional LLM)     |
            |            | Compose UI:    |<-----------+          |
            |            | Scan / Memory / Ask + PrivacyHud       |
            |            +---------------+                        |
            +-----------------------------------------------------+

  Flow: Capture -> Understand -> Store -> Recall -> Act (haptic buzz).
  No cloud. No account. No server. Network calls: 0.
```

## Module map

| Path | Module | What it does |
|---|---|---|
| `app/src/main/cpp/` | A — DSP core | KissFFT (vendored, BSD), `dsp_core` (chirp, fingerprint, spectrogram, anomalyScore, coherenceScore), JNI bridge. |
| `app/src/main/java/com/smriti/aqua/dsp/` | B — JNI wrapper | `DspEngine` external functions, loads `libsmriti_dsp.so`. |
| `.../audio/`, `.../sensors/` | B — capture | `AcousticProbe`, `AudioCapture`, `ChirpPlayer`, `AccelRecorder`, `FusionGate`, `HapticFeedback`. |
| `.../memory/` | C — episodic memory | `Event`/`Baseline` models, `EpisodicStore` (SQLite), `RecallEngine` (grounded Q&A), `Narrator` (template now, on-device LLM later). |
| `.../demo/` | C — demo rig | `DemoRigSimulator`: deterministic synthetic pcm/fingerprint/accel. |
| `.../ui/`, `MainActivity.kt`, `AquaApp.kt` | D — UI + wiring | Compose screens (Scan / Memory / Ask), `ScanViewModel`, warm theme. |
| `.../service/WatchService.kt` | D — watch stub | Foreground service (duty-cycle comment; not required for demo). |
| `tests/dsp_test.cpp` | A — native test | Desktop g++ test: `g++ -std=c++17 tests/dsp_test.cpp app/src/main/cpp/dsp_core.cpp app/src/main/cpp/kiss_fft.c app/src/main/cpp/kiss_fftr.c -o dsp_test && ./dsp_test`. |

## Build (Android Studio)

1. Install **Android Studio Hedgehog (2023.1.1) or newer**.
2. In **SDK Manager → SDK Tools**, install **NDK (Side by side)** and **CMake**.
3. **File → Open** this repository root (the folder containing `settings.gradle.kts`).
4. Let Gradle sync (AGP 8.5.2, Kotlin 2.0.20, Compose BOM 2024.06.00).
5. Connect the **iQOO 15** (arm64-v8a) with USB debugging on, then **Run**.
   Only `arm64-v8a` is built; min SDK 26, target SDK 34.
6. Grant the **microphone** permission when prompted.

## Demo script (5 minutes)

1. Open the app — note the **PrivacyHud**: *"PRIVATE BY DEFAULT · Network calls: 0 · Airplane-mode ready · No account · No server"*.
2. Keep the **"Demo rig simulator"** switch ON (no physical rig needed).
3. Object id is `PIPE_01_KITCHEN`. Tap **CALIBRATE** — 3 clean fingerprints
   are saved as the baseline; verdict card: **"BASELINE FINGERPRINT SAVED"**.
4. Tap **INSPECT** — the simulated pipe now "leaks": the echo's high bands
   decay. Verdict: **"ANOMALY 0.87 — NOT CONFIRMED"**, phone buzzes, the
   spectrogram shows the damped high-frequency energy.
   SMRITI never says "leak" — it reports an *abnormal acoustic signature*.
5. Go to **Ask** and tap the suggestion chips:
   - *"When did it start?"* — answered from the earliest anomaly in the burst.
   - *"Has this happened before?"* — counts prior anomalies from the store.
   - *"Are you sure it's a leak?"* — **the refusal**: *"No. I can confirm an
     abnormal acoustic signature (87% deviation), but I cannot confirm a leak
     from the available evidence."*
6. **The reveal:** pull down the shade, enable **airplane mode**, repeat the
   whole flow. Everything works — there is no `INTERNET` permission in the
   manifest at all.
7. Flip **"Demo rig simulator"** OFF to use the real mic + speaker chirp path.

## Hour-12 MVP framing

This is a 12-hour hackathon build. The MVP is deliberately narrow: one pipe,
one sensor fusion (mic × accelerometer), one memory store, deterministic
grounded recall. The `Narrator` interface is the documented drop-in point for
an on-device small LLM (Gemma 3 270M INT4 via LiteRT) — it may rephrase, but
it can never add facts that are not in the stored evidence.

## Measured metrics (reference validation)

Validated on desktop (g++ 12, `tests/dsp_test.cpp`, commit-tagged) and with the
Python reference model (`tools/dsp_reference_sim.py` — run it, no deps beyond numpy):

| Condition | Anomaly score |
|---|---|
| Clean pipe vs. own baseline | 0.00–0.08 |
| 35% high-frequency damping | ~0.36 |
| 50% damping | ~0.60 |
| DemoRigSimulator leak (LEAK_DAMP=0.32) | ~0.85–0.88 (measured 0.88) |
| Ambient noise rejection (mic-only, no IMU coherence) | coherence 0.15 → DISCARD |
| Structural contact (mic + IMU coherent) | coherence 0.76 → PROCESS |

`tools/spectrogram_diff.png` is the pitch-ready hero visual generated from the
reference model (baseline vs. damped vs. memory diff).

**On-device recalibration note:** the squash constant (`1.5`) and the FusionGate
thresholds were tuned on synthetic responses. Before the live demo, run
`tools/dsp_reference_sim.py` against fingerprints captured from the actual demo
rig (the real pipe's impulse response saturates the score faster than the Python
model — see `dsp_core.h` notes) and retune `LEAK_DAMP` in `DemoRigSimulator` and
the squash constant if the gradient compresses. Measure, don't guess — that is
the product's whole philosophy.
