package com.aquascope.smriti.llm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.aquascope.R

/** Keeps a 500 MB+ Hugging Face download alive if the System screen is left. */
class ModelDownloadService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(true)
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Downloading model"
        val notice = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_home)
            .setContentTitle("SMRITI")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF, notice)
        }
        return START_STICKY
    }

    companion object {
        private const val CHANNEL = "smriti_model_dl"
        private const val NOTIF = 7204
        private const val EXTRA_TEXT = "text"
        private const val ACTION_STOP = "stop"

        fun start(context: Context, text: String) {
            val app = context.applicationContext
            val intent = Intent(app, ModelDownloadService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent)
                else app.startService(intent)
            }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, ModelDownloadService::class.java)) }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Model download", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}
