package com.smriti.brain.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.smriti.brain.hardware.HaloActuator
import com.smriti.brain.hardware.HaloColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

class GuardianForegroundService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var wake: PowerManager.WakeLock? = null
    private var fall: FallDetector? = null
    private var audio: AudioWatcher? = null
    private val halo = HaloActuator("com.aquascope")

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel("smriti_guardian", "SMRITI guardian", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val n = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, "smriti_guardian")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setContentTitle("SMRITI Guardian")
            .setContentText("Ambient sensing on-device")
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
        startForeground(43, n)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smriti:guardian").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
        val sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        fall = FallDetector(sensors) {
            halo.pulse(HaloColor.WHITE, periodMs = 180, strobe = true)
            lastEvent = "FALL VECTOR"
        }.also { it.start() }
        audio = AudioWatcher(File(filesDir, "models/yamnet.tflite")).also { it.start() }
        halo.pulse(HaloColor.EMERALD)
        scope.launch {
            while (isActive) {
                val r = audio?.poll()
                if (r?.anomalous == true) {
                    halo.pulse(HaloColor.AMBER)
                    lastEvent = r.yamnetTop ?: "AUDIO ANOMALY rms=${"%.2f".format(r.rms)}"
                }
                delay(50)
            }
        }
    }

    override fun onDestroy() {
        fall?.stop()
        audio?.stop()
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var lastEvent: String = "idle"
    }
}
