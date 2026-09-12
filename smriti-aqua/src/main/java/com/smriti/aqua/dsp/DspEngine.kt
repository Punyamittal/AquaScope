package com.smriti.aqua.dsp

/**
 * JNI bridge to the native smriti_dsp library (see app/src/main/cpp).
 * Signatures are fixed by SPEC section 3 — do not change without updating
 * the native bridge (jni_bridge.cpp).
 *
 * All methods are thread-safe at the Kotlin level; callers should invoke the
 * heavy compute functions off the main thread (e.g. Dispatchers.Default).
 */
object DspEngine {

    init {
        System.loadLibrary("smriti_dsp")
    }

    const val SAMPLE_RATE = 48000
    const val FINGERPRINT_DIM = 72

    /**
     * Linear-frequency-modulated chirp, raised-cosine 5% edges, amplitude 0.8.
     * @return PCM samples, length = sampleRate * durationMs / 1000.
     */
    external fun generateChirp(sampleRate: Int, f0: Float, f1: Float, durationMs: Int): ShortArray

    /**
     * 72-dim fingerprint: 64 log-mel band means (17-23 kHz) + 8 summary stats.
     */
    external fun computeFingerprint(pcm: ShortArray, sampleRate: Int): FloatArray

    /**
     * Row-major [frames][64] log-mel spectrogram, normalized 0..1.
     * Pair with [spectrogramDims] to know the shape.
     */
    external fun computeSpectrogram(pcm: ShortArray, sampleRate: Int): FloatArray

    /**
     * @return intArrayOf(frames, mels) for a PCM buffer of [pcmLen] samples.
     */
    external fun spectrogramDims(pcmLen: Int): IntArray

    /**
     * Diagonal Mahalanobis distance of [current] against baseline mean/var,
     * squashed to [0,1] via 1 - exp(-d/6).
     */
    external fun anomalyScore(current: FloatArray, baseMean: FloatArray, baseVar: FloatArray): Float

    /**
     * Envelope cross-correlation peak between mic capture and accelerometer Z,
     * in [0,1]. Low coherence + high anomaly => ambient/vibration artifact.
     */
    external fun coherenceScore(mic: ShortArray, accelZ: FloatArray, micRate: Int, accRate: Int): Float
}
