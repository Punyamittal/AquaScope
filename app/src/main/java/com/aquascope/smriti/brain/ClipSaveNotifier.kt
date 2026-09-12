package com.aquascope.smriti.brain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.aquascope.R
import java.io.File

/** Heads-up / shade notice when a PEACE or manual Capture clip lands in memory. */
object ClipSaveNotifier {
    private const val CHANNEL = "smriti_clips"
    private const val NOTIF_ID = 7110

    fun notifySaved(ctx: Context, result: ClipFlushResult.Saved) {
        ensureChannel(ctx)
        val preview = result.sceneText.trim().replace('\n', ' ').take(120)
            .ifBlank { "Highlight saved · open Neural Core to watch" }
        val open = PendingIntent.getActivity(
            ctx,
            0,
            Intent(ctx, SmritiBrainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = when {
            result.reason.startsWith("manual") -> "Clip saved"
            result.reason.startsWith("stop") -> "Capture saved"
            else -> "PEACE clip saved"
        }
        val sizeHint = runCatching {
            val f = File(result.path)
            if (f.exists()) " · ${(f.length() / 1024)} KB" else ""
        }.getOrDefault("")
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_scan)
            .setContentTitle(title)
            .setContentText((preview + sizeHint).take(180))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    (result.sceneText.ifBlank { preview } + sizeHint).take(500)
                )
            )
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
    }

    fun notifyFailed(ctx: Context, message: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx,
            1,
            Intent(ctx, SmritiBrainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_scan)
            .setContentTitle("Clip failed")
            .setContentText(message.take(140))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID + 1, n)
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Clip saves", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "PEACE auto-clips and manual Capture saves"
            }
        )
    }
}
