package com.smriti.core.actuators

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.smriti.core.SmritiApp

/**
 * Foreground service keeping the hardware actuator link alive (SPEC §2.3).
 *
 * Bound clients obtain the app-wide [HardwareActuators] singleton from
 * [SmritiApp.actuators] via [LocalBinder]. The foreground notification posts on
 * the low-importance channel [CHANNEL_ID] ("SMRITI hardware link active").
 * startForeground is wrapped in try/catch so a missing FOREGROUND_SERVICE grant
 * or type restriction never crashes the app.
 */
class HardwareActuatorService : Service() {

    inner class LocalBinder : Binder() {
        fun getActuators(): HardwareActuators = (application as SmritiApp).actuators
    }

    private val binder = LocalBinder()

    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        goForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        goForeground()
        return binder
    }

    private fun goForeground() {
        if (foregroundStarted) return
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SMRITI actuators", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Keeps the SMRITI hardware actuator link active"
                }
            )
            val notification = Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("SMRITI")
                .setContentText("SMRITI hardware link active")
                .setOngoing(true)
                .build()
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } catch (e: Exception) {
            // Foreground start denied (permission/type restrictions) — stay a bound service.
        }
    }

    companion object {
        const val CHANNEL_ID = "smriti_actuators"
        const val NOTIFICATION_ID = 4201
    }
}
