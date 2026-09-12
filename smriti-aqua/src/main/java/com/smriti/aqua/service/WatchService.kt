package com.smriti.aqua.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Foreground "watch mode" service.
 *
 * MVP STATUS — DOCUMENTED STUB:
 * This service exists so the app can hold a foreground microphone while the
 * user leaves the UI. The duty-cycled probing loop (wake every N minutes ->
 * AcousticProbe.probe -> anomalyScore vs latest baseline -> FusionGate ->
 * insert event -> HapticFeedback.buzzAnomaly on confirmed anomaly, all inside
 * a ServiceScope with a WakeLock for the capture window) is intentionally NOT
 * implemented in the MVP — there is no always-on requirement for the demo
 * (SPEC section 6). The notification channel, foreground-start plumbing, and
 * service type are fully wired so the loop can be dropped into [onStartCommand]
 * post-MVP without touching the manifest or UI.
 *
 * Foreground service type: MICROPHONE on API 34+ (required by Android 14;
 * the manifest declares FOREGROUND_SERVICE_MICROPHONE and
 * android:foregroundServiceType="microphone").
 */
class WatchService : Service() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithType()

        // TODO(post-MVP): duty-cycled probing loop.
        //  - launch a Service-scoped coroutine that, every DUTY_CYCLE_MS:
        //      AcousticProbe(accel).probe() -> DspEngine.anomalyScore(fingerprint,
        //      baseline.mean, baseline.variance) -> DspEngine.coherenceScore(pcm,
        //      accelZ, ...) -> FusionGate.decide -> EpisodicStore.insertEvent ->
        //      HapticFeedback.buzzAnomaly(score) when Verdict.PROCESS && anomaly
        //  - hold a partial WakeLock only across the ~1.2 s capture window
        //  - stopSelf() when watch mode is toggled off (ACTION_STOP)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // TODO(post-MVP): cancel the probing coroutine scope here.
        super.onDestroy()
    }

    /** No binding; start/stop only. */
    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundWithType() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: microphone foreground-service type is mandatory.
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Watch mode", // keep literals here; strings.xml is owned by the UI module.
                // Low importance: silent, no badge — watch mode is ambient.
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background plumbing-acoustics watch"
                setShowBadge(false)
            }
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        // Tap opens the app. MainActivity is resolved implicitly so this module
        // does not depend on the UI module's class directly.
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SMRITI AQUA — watch mode")
            .setContentText("Listening for plumbing anomalies in the background")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        const val CHANNEL_ID = "smriti_watch"
        const val NOTIFICATION_ID = 1001

        /** Send as the intent action to stop watch mode. */
        const val ACTION_STOP = "com.smriti.aqua.action.STOP_WATCH"

        /** Post-MVP: interval between automatic probes in watch mode. */
        const val DUTY_CYCLE_MS = 5 * 60 * 1000L

        fun startIntent(context: Context): Intent =
            Intent(context, WatchService::class.java)

        fun stopIntent(context: Context): Intent =
            Intent(context, WatchService::class.java).setAction(ACTION_STOP)
    }
}
