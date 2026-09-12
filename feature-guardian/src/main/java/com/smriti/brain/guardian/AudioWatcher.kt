package com.smriti.brain.guardian

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.File
import kotlin.math.sqrt

/**
 * 0.975s windows at 16 kHz. Uses YAMNet if `yamnet.tflite` is on disk; otherwise
 * reports energy/ZCR anomalies without inventing class labels.
 */
class AudioWatcher(private val modelFile: File?) {
    data class Result(val rms: Float, val zcr: Float, val yamnetTop: String?, val anomalous: Boolean)

    private var interpreter: Interpreter? = null
    private var record: AudioRecord? = null

    fun start(): Boolean {
        interpreter = modelFile?.takeIf { it.exists() }?.let {
            try {
                Interpreter(it)
            } catch (t: Throwable) {
                Log.w(TAG, "YAMNet load failed: ${t.message}")
                null
            }
        }
        val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            16_000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            min.coerceAtLeast(16_000)
        )
        if (record?.state != AudioRecord.STATE_INITIALIZED) return false
        record?.startRecording()
        return true
    }

    fun poll(): Result? {
        val rec = record ?: return null
        val n = (16_000 * 0.975).toInt()
        val buf = ShortArray(n)
        var got = 0
        while (got < n) {
            val r = rec.read(buf, got, n - got)
            if (r <= 0) break
            got += r
        }
        if (got < n / 2) return null
        var sum = 0.0
        var zc = 0
        for (i in 0 until got) {
            val x = buf[i] / 32768.0
            sum += x * x
            if (i > 0 && buf[i] >= 0 != buf[i - 1] >= 0) zc++
        }
        val rms = sqrt(sum / got).toFloat()
        val zcr = zc / got.toFloat()
        val yam = runYamnet(buf, got)
        val anomalous = rms > 0.18f && zcr > 0.12f
        return Result(rms, zcr, yam, anomalous)
    }

    fun stop() {
        try {
            record?.stop()
        } catch (_: Throwable) {
        }
        record?.release()
        record = null
        interpreter?.close()
        interpreter = null
    }

    private fun runYamnet(buf: ShortArray, n: Int): String? {
        val tflite = interpreter ?: return null
        return try {
            val input = Array(1) { FloatArray(n) { i -> buf[i] / 32768f } }
            val out = Array(1) { FloatArray(521) }
            tflite.run(input, out)
            val scores = out[0]
            val ix = scores.indices.maxByOrNull { scores[it] } ?: return null
            if (scores[ix] < 0.35f) null else "yamnet:$ix=${"%.2f".format(scores[ix])}"
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "SmritiAudio"
    }
}
