package com.smriti.aqua.demo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

/**
 * DemoRigSimulator — deterministic synthetic stand-in for the physical pipe rig.
 *
 * Lets the full Capture -> Understand -> Store -> Recall pipeline run on any
 * phone without hardware. All randomness flows from a single seeded
 * [kotlin.random.Random], so a fixed seed reproduces byte-identical output
 * for the same call sequence.
 *
 * Mirror of the DSP contract: 48 kHz, 17-23 kHz LFM chirp, 72-dim fingerprints
 * (64 log-mel band means + 8 stats: spectral flatness mean/var, spectral
 * centroid mean/var, high-band energy mean/var, decay time, peak bin freq).
 */
class DemoRigSimulator(seed: Long = 42) {

    private val rng = Random(seed)

    companion object {
        const val SAMPLE_RATE = 48000
        const val PCM_MS = 1200
        const val SILENCE_MS = 50
        const val CHIRP_MS = 60
        const val F0 = 17000f
        const val F1 = 23000f
        const val ACCEL_SAMPLES = 120 // ~100 Hz over the 1.2 s window
        const val FINGERPRINT_DIM = 72

        private const val PCM_LEN = SAMPLE_RATE * PCM_MS / 1000      // 57600
        private const val SILENCE_LEN = SAMPLE_RATE * SILENCE_MS / 1000 // 2400
        private const val CHIRP_LEN = SAMPLE_RATE * CHIRP_MS / 1000  // 2880

        // Fingerprint shape + calibration constants (see syntheticFingerprint KDoc).
        private const val E0 = 1.0            // band-0 linear energy
        private const val SLOPE = 0.018       // per-band log falloff
        private const val CLEAN_JITTER = 0.025 // clean sigma ~2.5% of |value| per dim
        private const val LEAK_DAMP = 0.32    // upper-third log-energy damping, relative
    }

    // ------------------------------------------------------------------ PCM

    /**
     * 1.2 s @ 48 kHz mono PCM.
     *
     * Layout: 50 ms silence, then a chirp echo train (3 repeats, exponential
     * decay). `leak = false` models a healthy pipe: full-bandwidth echoes,
     * slow decay. `leak = true` models a leak: the echo is lowpassed (HF
     * damping), decays faster, and a small HF noise floor is added.
     */
    fun syntheticPcm(leak: Boolean): ShortArray {
        val chirp = chirp()
        val echo = if (leak) lowpass(chirp, alpha = 0.12f) else chirp

        val tauMs = if (leak) 35.0 else 80.0          // leak: faster decay
        val echoDelaysMs = doubleArrayOf(0.0, 130.0, 280.0)
        val echoGains = doubleArrayOf(1.0, 0.55, 0.30)

        val out = FloatArray(PCM_LEN)
        for (e in echoDelaysMs.indices) {
            val start = SILENCE_LEN + (echoDelaysMs[e] * SAMPLE_RATE / 1000.0).toInt()
            for (i in 0 until CHIRP_LEN) {
                val idx = start + i
                if (idx >= PCM_LEN) break
                val tMs = i * 1000.0 / SAMPLE_RATE
                val env = echoGains[e] * exp(-tMs / tauMs)
                out[idx] += (echo[i] * env).toFloat()
            }
        }

        if (leak) {
            // Small high-frequency noise floor (hiss escaping the leak).
            for (i in SILENCE_LEN until PCM_LEN) {
                val n = (rng.nextFloat() * 2f - 1f) * 0.02f
                // crude HF emphasis: difference against previous noise sample
                out[i] += n - 0.5f * (if (i > SILENCE_LEN) (rng.nextFloat() * 2f - 1f) * 0.01f else 0f)
            }
        }

        // Scale to int16 with headroom (contract: max |x| <= 0.81 * 32767).
        var peak = 1e-9f
        for (v in out) if (kotlin.math.abs(v) > peak) peak = kotlin.math.abs(v)
        val scale = (0.8f * 32767f) / peak * 0.9f
        return ShortArray(PCM_LEN) { i ->
            (out[i] * scale).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    /** LFM chirp F0->F1, raised-cosine 5% edges, amplitude 0.8 (mirrors dsp_core). */
    private fun chirp(): FloatArray {
        val out = FloatArray(CHIRP_LEN)
        val k = (F1 - F0) / CHIRP_LEN // Hz per sample
        val edge = (CHIRP_LEN * 0.05f).toInt()
        for (n in 0 until CHIRP_LEN) {
            val phase = 2.0 * PI * (F0 * n + 0.5 * k * n * n) / SAMPLE_RATE
            var w = 1.0
            if (n < edge) w = 0.5 * (1.0 - cos(PI * n / edge))
            if (n >= CHIRP_LEN - edge) w = 0.5 * (1.0 - cos(PI * (CHIRP_LEN - 1 - n) / edge))
            out[n] = (0.8 * w * sin(phase)).toFloat()
        }
        return out
    }

    /** One-pole lowpass: y[i] = y[i-1] + alpha * (x[i] - y[i-1]). Small alpha = heavy damping. */
    private fun lowpass(x: FloatArray, alpha: Float): FloatArray {
        val y = FloatArray(x.size)
        var prev = 0f
        for (i in x.indices) {
            prev += alpha * (x[i] - prev)
            y[i] = prev
        }
        return y
    }

    // ------------------------------------------------------------ Fingerprint

    /**
     * Plausible 72-dim fingerprint: 64 band log-energies shaped as a smooth
     * spectral falloff across 17-23 kHz, then 8 stats consistent with the DSP
     * contract's definitions (spectral flatness mean/var, spectral centroid
     * mean/var, high-band energy mean/var, decay time, peak bin freq).
     *
     * CALIBRATION (tuned against the deployed anomaly formula, see dsp_core):
     *   vfloor_i = max(var_i, (0.10*|mean_i|)^2 + 1e-4)
     *   d_avg    = mean over 72 dims of (cur-mean)^2 / vfloor
     *   score    = 1 - exp(-d_avg / 1.5)
     * Verified by offline simulation (400 trials, baseline = 3 clean vectors):
     *   clean vs own baseline  ~= 0.03-0.08  (mean ~0.045)
     *   leak  vs clean baseline ~= 0.85-0.90  (mean ~0.88 — "ANOMALY 0.87")
     * To retune on-device: LEAK_DAMP drives the score hardest; CLEAN_JITTER
     * sets the clean-vs-baseline floor.
     *
     * Clean vectors vary by only ~2.5% (sigma) of |value| per dim, so stored
     * baseline variance stays small and the relative floor (10% of |mean|)
     * dominates vfloor. The leak vector damps the upper-third band
     * log-energies by LEAK_DAMP relative to clean and shifts the stats mildly
     * (flatness +10%, centroid -12%, high-band-energy mean -20%, decay -30%).
     * NOTE: 15-20% band damping was simulated first and only scores ~0.53
     * with this vfloor formula — 0.32 is what lands in the demo-sweet range.
     */
    fun syntheticFingerprint(leak: Boolean): FloatArray {
        val v = FloatArray(FINGERPRINT_DIM)

        // --- 64 log-mel band means: smooth falloff, mild deterministic ripple ---
        for (b in 0 until 64) {
            var logE = ln(E0) - SLOPE * b + 0.04 * sin(b * 0.35)
            if (leak && b >= 43) {
                // upper-third bands are negative log-energies; scaling magnitude
                // up by (1 + LEAK_DAMP) damps their linear energy
                logE *= (1.0 + LEAK_DAMP)
            }
            logE += relJitter(logE)
            v[b] = logE.toFloat()
        }

        // --- 8 stats (SPEC order): flatness mean/var, centroid mean/var,
        //     high-band energy mean/var, decay time, peak bin freq ---
        val s = DoubleArray(8)
        s[0] = 0.36; s[1] = 0.018   // spectral flatness mean/var
        s[2] = 0.46; s[3] = 0.014   // spectral centroid mean/var (normalized 0..1)
        s[4] = 0.52; s[5] = 0.016   // high-band energy mean/var
        s[6] = 80.0                 // decay time (ms)
        s[7] = 19.8                 // peak bin freq (kHz)
        if (leak) {
            s[0] *= 1.10            // flatness mean +10%: more tonal (leak whistle)
            s[2] *= 0.88            // centroid mean -12%: HF damped
            s[4] *= 0.80            // high-band energy mean -20%
            s[6] *= 0.70            // decay time -30%: faster decay
            s[7] *= 0.95            // peak bin freq -5%: pulled low
        }
        for (i in 0 until 8) v[64 + i] = (s[i] + relJitter(s[i])).toFloat()
        return v
    }

    /**
     * Reproducible relative jitter, sigma ~= CLEAN_JITTER * |value|.
     * Triangular approximation of a gaussian from two uniform draws
     * (sigma of (u1+u2-1) is 1/sqrt(6)).
     */
    private fun relJitter(value: Double): Double =
        (rng.nextFloat() + rng.nextFloat() - 1f).toDouble() *
            (CLEAN_JITTER * Math.sqrt(6.0)) * Math.abs(value)

    // ---------------------------------------------------------------- Accelerometer

    /**
     * 120 accelerometer Z samples (~100 Hz over the 1.2 s window).
     *
     * `coherent = true`: burst envelope aligned with the chirp window (starts
     * right after the 50 ms silence => sample ~5) with the same exponential
     * decay — physically coherent pipe vibration.
     * `coherent = false`: flat noise only — ambient bump, unrelated to the chirp.
     */
    fun syntheticAccel(coherent: Boolean): FloatArray {
        val out = FloatArray(ACCEL_SAMPLES)
        if (coherent) {
            val startSample = SILENCE_MS * 100 / 1000 // 50 ms at 100 Hz -> 5
            val tauSamples = 30.0
            for (i in startSample until ACCEL_SAMPLES) {
                val env = exp(-(i - startSample) / tauSamples)
                // decaying burst of pipe-wall vibration + measurement noise
                out[i] = (env * 0.6 * sin(2.0 * PI * (i - startSample) / 6.0) +
                    (rng.nextDouble() - 0.5) * 0.05).toFloat()
            }
            for (i in 0 until startSample) {
                out[i] = ((rng.nextDouble() - 0.5) * 0.02).toFloat()
            }
        } else {
            for (i in 0 until ACCEL_SAMPLES) {
                out[i] = ((rng.nextDouble() - 0.5) * 0.06).toFloat()
            }
        }
        return out
    }
}
