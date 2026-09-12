# ATTRIBUTION — SMRITI AQUA

## Third-party components

| Component | Author | License | Use |
|---|---|---|---|
| KissFFT (`app/src/main/cpp/kiss_fft.*`, `kiss_fftr.*`) | Mark Borgerding | BSD-3-Clause | FFT used by the native DSP core (STFT -> log-mel fingerprints). |

KissFFT copyright (c) 2003-2010 Mark Borgerding. Redistribution and use in
source and binary forms, with or without modification, are permitted under the
BSD 3-Clause License. Full license text is retained alongside the vendored
sources in `app/src/main/cpp/`.

## Optional future narrators (not shipped in this build)

The `LlmNarratorStub` drop-in point is designed for an on-device small LLM
(e.g. **Gemma 3 270M INT4** or **Qwen** class models) running via
**LiteRT / MediaPipe LLM Inference API**. These are optional, future,
fully offline narrators and are licensed under **Apache-2.0**. No model
weights are bundled in this repository.

## Everything else

All other code in this repository — DSP core, JNI bridge, audio/sensor
pipeline, episodic memory store, recall engine, demo simulator, and the
entire Compose UI — was written in-window for the hackathon by the
SMRITI AQUA team.
