package com.aquascope.smriti.brain

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.aquascope.audio.IqooAudioRouting
import com.aquascope.dsp.FFT
import com.aquascope.halo.SmritiLightState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

data class AcousticEvent(
    val label: String,
    val confidence: Float,
    val rms: Float
)

/**
 * Continuous YAMNet-style windowing (0.975 s) + IMU fall detector.
 * If `assets/yamnet.tflite` is absent, uses on-device spectral rules (alarms / transients / cough-like bursts).
 */
class AmbientAudioSensorManager(
    context: Context,
    private val onAcoustic: (AcousticEvent) -> Unit,
    private val onFall: () -> Unit,
    private val onAmplitude: (Float) -> Unit = {}
) : SensorEventListener {

    private val app = context.applicationContext
    private val sensors = app.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var audioJob: Job? = null
    @Volatile private var running = false

    private var lastAccel = FloatArray(3)
    private var lastGyro = FloatArray(3)
    private var jerkPeakAt = 0L
    private var stillSince = 0L

    fun start() {
        if (running) return
        running = true
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        audioJob = scope.launch { loopAudio() }
    }

    fun stop() {
        running = false
        audioJob?.cancel()
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val jerk = sqrt(
                    (ax - lastAccel[0]) * (ax - lastAccel[0]) +
                        (ay - lastAccel[1]) * (ay - lastAccel[1]) +
                        (az - lastAccel[2]) * (az - lastAccel[2])
                )
                lastAccel[0] = ax; lastAccel[1] = ay; lastAccel[2] = az
                val mag = sqrt(ax * ax + ay * ay + az * az)
                val now = SystemClock.elapsedRealtime()
                if (jerk > 28f) {
                    jerkPeakAt = now
                    stillSince = 0L
                } else if (jerkPeakAt > 0L && abs(mag - 9.8f) < 1.6f && jerk < 1.2f) {
                    if (stillSince == 0L) stillSince = now
                    if (now - jerkPeakAt in 400..4_000 && now - stillSince > 1_200) {
                        jerkPeakAt = 0L
                        stillSince = 0L
                        onFall()
                    }
                } else if (now - jerkPeakAt > 4_000) {
                    jerkPeakAt = 0L
                    stillSince = 0L
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                lastGyro[0] = event.values[0]
                lastGyro[1] = event.values[1]
                lastGyro[2] = event.values[2]
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private suspend fun loopAudio() {
        val rate = 16_000
        val minBuf = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (_: SecurityException) {
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return
        }
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            IqooAudioRouting.pinRecorder(record, IqooAudioRouting.pickBottomMic(am))
        } catch (_: Exception) {
        }
        val window = (rate * 0.975).toInt()
        val buf = ShortArray(window)
        record.startRecording()
        try {
            while (scope.isActive && running) {
                var filled = 0
                while (filled < window && running) {
                    val n = record.read(buf, filled, window - filled)
                    if (n <= 0) break
                    filled += n
                }
                if (filled < window / 2) continue
                val samples = DoubleArray(filled) { buf[it] / 32768.0 }
                var sum = 0.0
                for (s in samples) sum += s * s
                val rms = sqrt(sum / samples.size).toFloat()
                onAmplitude(rms)
                val event = classifyWindow(samples, rms)
                if (event != null) onAcoustic(event)
            }
        } finally {
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            record.release()
        }
    }

    private fun classifyWindow(samples: DoubleArray, rms: Float): AcousticEvent? {
        if (rms < 0.02f) return null
        val n = FFT.nextPowerOf2(samples.size)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (i in samples.indices) re[i] = samples[i]
        FFT.fft(re, im, inverse = false)
        val hzPerBin = 16_000.0 / n
        fun band(lo: Double, hi: Double): Double {
            val a = (lo / hzPerBin).toInt().coerceIn(1, n / 2 - 1)
            val b = (hi / hzPerBin).toInt().coerceIn(a + 1, n / 2)
            var e = 0.0
            for (i in a until b) e += re[i] * re[i] + im[i] * im[i]
            return e
        }
        val high = band(2800.0, 5000.0)
        val mid = band(400.0, 1800.0)
        val low = band(80.0, 300.0)
        val total = high + mid + low + 1e-9
        return when {
            high / total > 0.55 && rms > 0.08f ->
                AcousticEvent("smoke_alarm", (high / total).toFloat(), rms)
            rms > 0.18f && high > mid * 1.4 ->
                AcousticEvent("glass_break", (rms).coerceAtMost(1f), rms)
            mid / total > 0.45 && rms in 0.04f..0.16f && low / total < 0.25 ->
                AcousticEvent("cough", (mid / total).toFloat(), rms)
            rms > 0.12f && mid / total > 0.4 ->
                AcousticEvent("kitchen_alert", (mid / total).toFloat(), rms)
            else -> null
        }
    }

    companion object {
        fun haloFor(label: String): SmritiLightState = when (label) {
            "smoke_alarm", "glass_break" -> SmritiLightState.EMERGENCY
            else -> SmritiLightState.GUARDIAN
        }
    }
}
