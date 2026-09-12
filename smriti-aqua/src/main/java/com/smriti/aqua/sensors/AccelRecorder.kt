package com.smriti.aqua.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Records the accelerometer Z-axis at SENSOR_DELAY_GAME (~100 Hz expected on
 * the iQOO 15; the actual rate is device-dependent and reported via
 * [actualRateHz] once running).
 *
 * Lifecycle: [start] registers the listener and clears the buffer; [stop]
 * unregisters and returns a snapshot of all Z values collected since start.
 * Sensor events arrive on a binder/sensor thread, so the sample list is
 * guarded by a lock.
 *
 * Note: accelerometer sampling on Android is only approximate while the screen
 * is off; during an [com.smriti.aqua.audio.AcousticProbe] the app is in the
 * foreground so ~100 Hz is achievable.
 */
class AccelRecorder(context: Context) {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accelerometer: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val lock = Object()
    private val samples = ArrayList<Float>(INITIAL_CAPACITY)

    @Volatile
    private var recording = false

    /** Nominal rate we request; used as accRate for coherence scoring. */
    val requestedRateHz: Int = 100

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            val z = event.values[2]
            synchronized(lock) { samples.add(z) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * Clears previously collected samples and starts listening.
     * No-op if the device has no accelerometer.
     */
    fun start() {
        synchronized(lock) { samples.clear() }
        val sensor = accelerometer ?: return
        recording = true
        sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME
        )
    }

    /**
     * Stops listening and returns all Z samples collected since [start]
     * (empty if no accelerometer or no events arrived).
     */
    fun stop(): FloatArray {
        recording = false
        sensorManager.unregisterListener(listener)
        synchronized(lock) {
            val out = FloatArray(samples.size)
            for (i in samples.indices) out[i] = samples[i]
            return out
        }
    }

    /** True between [start] and [stop]. */
    fun isRecording(): Boolean = recording

    private companion object {
        /** ~100 Hz for a 1.2 s probe window. */
        const val INITIAL_CAPACITY = 256
    }
}
