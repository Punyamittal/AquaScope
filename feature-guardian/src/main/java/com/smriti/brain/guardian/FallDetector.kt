package com.smriti.brain.guardian

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class FallDetector(
    private val sensors: SensorManager,
    private val onFall: () -> Unit
) : SensorEventListener {
    private var lastHighGAt = 0L
    private var stillSamples = 0

    fun start() {
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val g = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2]) / 9.81f
        val now = android.os.SystemClock.elapsedRealtime()
        if (g > 3.5f) {
            lastHighGAt = now
            stillSamples = 0
        } else if (lastHighGAt > 0 && now - lastHighGAt < 1200 && g < 0.4f) {
            stillSamples++
            if (stillSamples > 4) {
                lastHighGAt = 0
                stillSamples = 0
                onFall()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
