package com.aquascope.smriti.brain

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aquascope.R

class CaptureProjectionService : Service() {
    override fun onCreate() {
        super.onCreate()
        HardwareActuatorService.ensureChannel(this)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_scan)
            .setContentTitle("SMRITI Play")
            .setContentText("30s ring buffer armed")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF, n)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "smriti_hw"
        private const val NOTIF = 7102

        fun start(context: Context) {
            HardwareActuatorService.ensureChannel(context)
            val i = Intent(context, CaptureProjectionService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureProjectionService::class.java))
        }
    }
}

class GuardianService : Service() {
    override fun onCreate() {
        super.onCreate()
        HardwareActuatorService.ensureChannel(this)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_home)
            .setContentTitle("SMRITI Guardian")
            .setContentText("Ambient listening on-device")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF, n)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "smriti_hw"
        private const val NOTIF = 7103

        fun start(context: Context) {
            HardwareActuatorService.ensureChannel(context)
            val i = Intent(context, GuardianService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardianService::class.java))
        }
    }
}
