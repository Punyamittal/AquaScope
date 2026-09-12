# ATTRIBUTION

SMRITI Core is built on the following open-source / third-party components.
All application code in this repository was written in-window for this project.

| Component | Version | License / Terms | Use |
|---|---|---|---|
| Vosk (`com.alphacephei:vosk-android`) | 0.3.47 | Apache-2.0 | Offline streaming speech recognition (guardian) |
| JNA (`net.java.dev.jna:jna`) | 5.13.0 | Apache-2.0 / LGPL-2.1 (dual) | Native bindings for libvosk |
| YAMNet / TensorFlow Lite (`org.tensorflow:tensorflow-lite*`, `tensorflow-lite-support`) | 2.16.1 / 0.4.4 | Apache-2.0 | On-device ambient sound classification, inference runtime |
| MediaPipe Tasks GenAI (`com.google.mediapipe:tasks-genai`) | 0.10.14 | Apache-2.0 | Optional on-device embeddings / LLM drop-in |
| Google ML Kit Text Recognition (`com.google.mlkit:text-recognition`) | 16.0.1 | Google Terms of Service (bundled, on-device) | Offline OCR for memory ingestion |
| AndroidX / Jetpack Compose / Room / Lifecycle | per `app/build.gradle.kts` | Apache-2.0 | App framework |
| Kotlin | 2.0.20 | Apache-2.0 | Language |

YAMNet model weights (when dropped in by the user) are released by Google under
Apache-2.0 (TensorFlow model garden). Vosk models are released by Alpha Cephei
under Apache-2.0. Model files are **not** committed to this repository; see
`README.md` for exact drop-in paths.

No component requires network access at runtime; SMRITI Core ships without the
`INTERNET` permission.
