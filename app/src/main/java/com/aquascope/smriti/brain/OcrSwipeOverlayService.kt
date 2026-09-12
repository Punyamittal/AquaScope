package com.aquascope.smriti.brain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.aquascope.R

/**
 * Arms system-wide OCR capture **without** any touch overlay.
 * No blue strip, no stolen touches — phone stays fully normal.
 * Capture via notification "Capture" (or Neural Core Library flow).
 */
class OcrSwipeOverlayService : Service() {

    private var lastFireElapsed = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        Log.i(TAG, "OCR capture armed (notification only — no overlay)")
        when {
            !SmritiOcrAccessService.isEnabled(this) ->
                toast("Enable Accessibility → SMRITI OCR Capture")
            !SmritiOcrAccessService.isConnected() ->
                toast("Toggle SMRITI OCR Capture OFF then ON")
            else ->
                toast("Screen OCR ready — tap Capture in the notification")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                OcrGesturePreferences(this).swipeEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE_NOW -> fireCapture(force = true)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "OCR capture disarmed")
    }

    private fun fireCapture(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastFireElapsed < COOLDOWN_MS) return
        lastFireElapsed = now

        when {
            !SmritiOcrAccessService.isEnabled(this) -> {
                toast("Turn on Accessibility → SMRITI OCR Capture")
                openAccessibility()
            }
            !SmritiOcrAccessService.isConnected() -> {
                toast("Toggle SMRITI OCR Capture OFF then ON")
                openAccessibility()
            }
            else -> {
                toast("Capturing screen…")
                if (!SmritiOcrAccessService.requestCapture()) {
                    toast("Screenshot failed — re-toggle Accessibility")
                }
            }
        }
    }

    private fun openAccessibility() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "OCR capture", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, OcrSwipeOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val capture = PendingIntent.getService(
            this, 1,
            Intent(this, OcrSwipeOverlayService::class.java).setAction(ACTION_CAPTURE_NOW),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 2,
            Intent(this, SmritiBrainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val library = PendingIntent.getActivity(
            this, 3,
            Intent(this, ScreenLibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("SMRITI Screen OCR")
            .setContentText("No overlay — tap Capture · phone stays normal")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "Capture", capture)
            .addAction(0, "Library", library)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val TAG = "OcrSwipeOverlay"
        private const val CHANNEL = "ocr_swipe"
        private const val NOTIF_ID = 7142
        const val ACTION_STOP = "com.aquascope.OCR_SWIPE_STOP"
        const val ACTION_CAPTURE_NOW = "com.aquascope.OCR_SWIPE_CAPTURE"
        private const val COOLDOWN_MS = 1800L

        fun start(context: Context) {
            val i = Intent(context, OcrSwipeOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, OcrSwipeOverlayService::class.java).setAction(ACTION_STOP)
            )
            context.stopService(Intent(context, OcrSwipeOverlayService::class.java))
        }
    }
}
