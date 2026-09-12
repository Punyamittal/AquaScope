package com.aquascope.smriti.llm

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * OpenAI Whisper log-mel frontend: 16 kHz → float32[1, 80, 3000] for a 30 s window.
 * Only computes STFT frames that cover non-silent audio (Speak uses ~6 s).
 */
object WhisperMelFrontend {

    const val SAMPLE_RATE = 16_000
    const val N_FFT = 400
    const val HOP = 160
    const val N_MELS = 80
    const val N_FRAMES = 3_000
    const val WINDOW_SAMPLES = SAMPLE_RATE * 30
    private const val N_FREQ = N_FFT / 2 + 1

    private val hann: FloatArray by lazy {
        FloatArray(N_FFT) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / (N_FFT - 1)))).toFloat()
        }
    }

    private val melFilters: Array<FloatArray> by lazy { buildMelFilters() }

    private val cosTable: Array<FloatArray> by lazy {
        Array(N_FREQ) { k ->
            FloatArray(N_FFT) { t -> cos(-2.0 * PI * k * t / N_FFT).toFloat() }
        }
    }
    private val sinTable: Array<FloatArray> by lazy {
        Array(N_FREQ) { k ->
            FloatArray(N_FFT) { t -> sin(-2.0 * PI * k * t / N_FFT).toFloat() }
        }
    }

    fun fromPcm16(pcm: ShortArray): Array<Array<FloatArray>> {
        val samples = FloatArray(WINDOW_SAMPLES)
        val n = min(pcm.size, WINDOW_SAMPLES)
        for (i in 0 until n) samples[i] = pcm[i] / 32768f
        return fromFloatMono(samples, audioLen = n)
    }

    fun fromFloatMono(audio: FloatArray, audioLen: Int = audio.size): Array<Array<FloatArray>> {
        val samples = FloatArray(WINDOW_SAMPLES)
        val n = min(audioLen, min(audio.size, WINDOW_SAMPLES))
        System.arraycopy(audio, 0, samples, 0, n)

        val framesNeeded = ((n + HOP - 1) / HOP + N_FFT / HOP)
            .coerceIn(1, N_FRAMES)

        val mel = Array(N_MELS) { FloatArray(N_FRAMES) }
        val frame = FloatArray(N_FFT)
        val power = FloatArray(N_FREQ)

        for (t in 0 until framesNeeded) {
            val start = t * HOP
            for (i in 0 until N_FFT) {
                val ix = start + i
                frame[i] = if (ix < n) samples[ix] * hann[i] else 0f
            }
            realDftPower(frame, power)
            for (m in 0 until N_MELS) {
                var sum = 0.0
                val filter = melFilters[m]
                for (k in 0 until N_FREQ) sum += power[k] * filter[k]
                mel[m][t] = sum.toFloat()
            }
        }

        var maxLog = -Float.MAX_VALUE
        for (m in 0 until N_MELS) {
            for (t in 0 until N_FRAMES) {
                val v = log10(max(mel[m][t].toDouble(), 1e-10)).toFloat()
                mel[m][t] = v
                if (v > maxLog) maxLog = v
            }
        }
        val floor = maxLog - 8f
        for (m in 0 until N_MELS) {
            for (t in 0 until N_FRAMES) {
                val clamped = max(mel[m][t], floor)
                mel[m][t] = (clamped + 4f) / 4f
            }
        }
        return arrayOf(mel)
    }

    private fun realDftPower(frame: FloatArray, power: FloatArray) {
        for (k in 0 until N_FREQ) {
            var r = 0.0
            var i = 0.0
            val c = cosTable[k]
            val s = sinTable[k]
            for (t in 0 until N_FFT) {
                val x = frame[t]
                r += x * c[t]
                i += x * s[t]
            }
            power[k] = (r * r + i * i).toFloat()
        }
    }

    private fun buildMelFilters(): Array<FloatArray> {
        val fMin = 0.0
        val fMax = SAMPLE_RATE / 2.0
        fun hzToMel(hz: Double) = 2595.0 * ln(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (Math.exp(mel / 2595.0) - 1.0)
        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = DoubleArray(N_MELS + 2) { i ->
            melMin + (melMax - melMin) * i / (N_MELS + 1)
        }
        val hzPoints = DoubleArray(melPoints.size) { melToHz(melPoints[it]) }
        val bins = IntArray(hzPoints.size) {
            min(N_FREQ - 1, ((N_FFT + 1) * hzPoints[it] / SAMPLE_RATE).toInt())
        }
        val filters = Array(N_MELS) { FloatArray(N_FREQ) }
        for (m in 1..N_MELS) {
            val left = bins[m - 1]
            val center = bins[m]
            val right = bins[m + 1]
            for (k in left until center) {
                if (center != left) {
                    filters[m - 1][k] = (k - left).toFloat() / (center - left)
                }
            }
            for (k in center until right) {
                if (right != center) {
                    filters[m - 1][k] = (right - k).toFloat() / (right - center)
                }
            }
        }
        for (m in 0 until N_MELS) {
            val enorm = 2.0 / (hzPoints[m + 2] - hzPoints[m])
            for (k in filters[m].indices) filters[m][k] = (filters[m][k] * enorm).toFloat()
        }
        return filters
    }
}
