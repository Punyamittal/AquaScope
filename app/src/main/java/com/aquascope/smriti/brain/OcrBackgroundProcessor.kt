package com.aquascope.smriti.brain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aquascope.R
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.brain.library.ScreenLibraryStore
import com.aquascope.smriti.brain.screenmind.ForegroundAppResolver
import com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Always-on OCR sink so swipe captures work even when Neural Core UI is closed.
 * Saves image + OCR into [ScreenLibraryStore] under app / website / keyword categories.
 */
object OcrBackgroundProcessor {
    private const val TAG = "OcrBg"
    private const val CHANNEL = "ocr_ready"
    private const val NOTIF_ID = 7143

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listener = OcrIngestHub.Listener { file -> process(file) }

    @Volatile private var appCtx: Context? = null

    fun install(context: Context) {
        appCtx = context.applicationContext
        OcrIngestHub.addListener(listener)
        ensureChannel(context.applicationContext)
        Log.i(TAG, "background OCR processor installed")
    }

    private fun process(file: File) {
        val ctx = appCtx ?: return
        scope.launch {
            try {
                Log.i(TAG, "OCR file ${file.absolutePath} bytes=${file.length()}")
                val text = LocalOcr.readFile(file, ctx).trim()
                if (text.isBlank()) {
                    notify(ctx, "OCR found no text", "Try a clearer screen")
                    return@launch
                }
                val fg = ForegroundAppResolver.current(ctx)
                val mind = ScreenMindAnalyzer.withForegroundHint(
                    ScreenMindAnalyzer.fromOcrOnly(text, "swipe"),
                    fg?.label
                )
                val clip = ScreenLibraryStore.ingest(
                    ctx = ctx,
                    ocrText = text,
                    imageFile = file,
                    mind = mind,
                    foreground = fg
                )
                NeuralCoreSession.lastScreenOcr = text
                NeuralCoreSession.lastScreenOcrPath = clip.ocrPath
                runCatching {
                    NeuralCoreMemory.remember(
                        context = ctx,
                        raw = mind.memoryText("swipe"),
                        source = "OCR",
                        evidencePath = clip.imagePath ?: clip.ocrPath
                    )
                }
                runCatching {
                    HardwareActuators.get(ctx).setHalo(SmritiLightState.EXTRACTION)
                }
                val siteBit = if (clip.websites.isNotEmpty()) {
                    " · ${clip.websites.first()}"
                } else {
                    ""
                }
                notify(
                    ctx,
                    "Saved · ${clip.appName} · ${clip.category}$siteBit",
                    "${clip.keywords.take(4).joinToString(", ").ifBlank { "${text.length} chars" }} — open Library"
                )
                Log.i(
                    TAG,
                    "library clip id=${clip.id} app=${clip.appName} cat=${clip.category} sites=${clip.websites.size}"
                )
            } catch (t: Throwable) {
                Log.e(TAG, "background OCR failed", t)
                notify(ctx, "OCR failed", t.message ?: "unknown error")
            } finally {
                // Original swipe temp file can go; library keeps its own copy.
                runCatching { file.delete() }
            }
        }
    }

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "OCR results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun notify(ctx: Context, title: String, body: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, ScreenLibraryActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, n)
    }
}
