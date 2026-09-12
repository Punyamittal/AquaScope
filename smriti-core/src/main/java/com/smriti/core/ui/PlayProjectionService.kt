package com.smriti.core.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import com.smriti.core.SmritiApp

/**
 * UI-owned MediaProjection foreground host for SMRITI Play (SPEC §3; the
 * play module's [com.smriti.core.play.ScreenBufferRecorder] deliberately owns
 * no Service — this class is the manifest-declared host).
 *
 * Flow: RecorderControl obtains user consent via
 * `MediaProjectionManager.createScreenCaptureIntent()`, then launches this
 * service with [startIntent]. The service goes foreground FIRST (mandatory on
 * API 34 for `foregroundServiceType="mediaProjection"`) and only then invokes
 * `SmritiApp.recorder.start(resultCode, data)`. [onDestroy] always calls
 * `recorder.stop()` so the RAM ring and virtual display are released even on
 * system teardown.
 */
class PlayProjectionService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                val recorder = (application as SmritiApp).recorder
                val started = data != null && runCatching {
                    recorder.start(resultCode, data)
                }.getOrDefault(false)
                if (!started) {
                    // Consent data missing or recorder refused — shut down cleanly.
                    stopSelf()
                }
            }
            ACTION_STOP -> {
                runCatching { (application as SmritiApp).recorder.stop() }
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { (application as SmritiApp).recorder.stop() }
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SMRITI Play", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Screen buffer capture for SMRITI Play clip engine"
            }
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle("SMRITI Play")
            .setContentText("Screen buffer recording — 30 s RAM ring active")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // API 34+: mediaProjection FGS type is mandatory.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // FGS start denied (type/permission restriction) — never crash (SPEC §4).
        }
    }

    companion object {
        const val CHANNEL_ID = "smriti_play"
        const val NOTIFICATION_ID = 4301
        const val ACTION_START = "com.smriti.core.ui.action.PLAY_START"
        const val ACTION_STOP = "com.smriti.core.ui.action.PLAY_STOP"
        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, PlayProjectionService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)

        fun stopIntent(context: Context): Intent =
            Intent(context, PlayProjectionService::class.java).setAction(ACTION_STOP)
    }
}
