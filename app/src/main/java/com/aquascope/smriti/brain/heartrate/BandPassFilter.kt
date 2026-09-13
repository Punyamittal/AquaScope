package com.aquascope.smriti.brain.heartrate

/**
 * Simple IIR Butterworth-style band-pass for PPG (≈0.67–3.33 Hz at typical camera rates).
 * Coefficients are computed for a nominal sample rate and refreshed when FPS drifts.
 */
class BandPassFilter(
    private var sampleRateHz: Double = 30.0,
    private val lowHz: Double = 0.67,
    private val highHz: Double = 3.33
) {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    init {
        redesign(sampleRateHz)
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    fun updateSampleRate(hz: Double) {
        if (hz < 8.0 || hz > 120.0) return
        if (kotlin.math.abs(hz - sampleRateHz) < 1.0) return
        redesign(hz)
    }

    fun filter(input: Double): Double {
        val out = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = out
        return out
    }

    private fun redesign(hz: Double) {
        sampleRateHz = hz
        reset()
        // Cascaded high-pass then low-pass via bilinear transform (2nd-order band-pass).
        val wLow = 2.0 * kotlin.math.PI * lowHz / hz
        val wHigh = 2.0 * kotlin.math.PI * highHz / hz
        val bw = wHigh - wLow
        val w0 = kotlin.math.sqrt(wLow * wHigh)
        val cos = kotlin.math.cos(w0)
        val alpha = kotlin.math.sin(w0) * kotlin.math.sinh((kotlin.math.ln(2.0) / 2.0) * bw * w0 / kotlin.math.sin(w0))
        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b1 = 0.0
        b2 = -alpha / a0
        a1 = (-2.0 * cos) / a0
        a2 = (1.0 - alpha) / a0
    }
}
