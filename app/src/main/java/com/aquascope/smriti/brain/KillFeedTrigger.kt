package com.aquascope.smriti.brain

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.aquascope.smriti.brain.peace.HighlightImportance
import com.aquascope.smriti.brain.peace.PeaceClipEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Live highlight monitor.
 * Importance signals drawn from open-source clippers (PEACE, BF6 template clip,
 * Auto-clipper vignette/HUD, Crispy icon cues, VPULSE feed clustering).
 */
class KillFeedTrigger(
    private val onKillGroup: (
        reason: String,
        score: Float,
        killCount: Int,
        clipStartMs: Long,
        clipEndMs: Long
    ) -> Unit
) {
    private var lastGray: IntArray? = null
    private var lastW = 0
    private var lastH = 0
    private var lastDetectAt = 0L
    private var lastLogAt = 0L
    private var framesSeen = 0
    private val centerLumaBuf = FloatArray(192)
    private var prevCenterLuma: FloatArray? = null

    private var templatesGray = emptyList<FloatArray>()
    private var templateSizes = emptyList<Pair<Int, Int>>()

    private val pending = mutableListOf<PeaceClipEngine.KillHit>()
    private var groupAnchorMs = 0L
    private val flushThread = HandlerThread("peace-flush").also { it.start() }
    private val flushHandler = Handler(flushThread.looper)
    private val finalizeRunnable = Runnable { finalizeGroup("peace_group") }
    private val flushing = AtomicBoolean(false)

    fun setTemplates(bitmaps: List<Bitmap>) {
        val grays = mutableListOf<FloatArray>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        for (bmp in bitmaps) {
            val (g, w, h) = PeaceClipEngine.bitmapToGrayFloat(bmp)
            if (w * h > 80_000) {
                val scaled = Bitmap.createScaledBitmap(
                    bmp,
                    (w * 0.5f).toInt().coerceAtLeast(8),
                    (h * 0.5f).toInt().coerceAtLeast(8),
                    true
                )
                val (g2, w2, h2) = PeaceClipEngine.bitmapToGrayFloat(scaled)
                if (scaled !== bmp) scaled.recycle()
                grays += g2
                sizes += (w2 to h2)
            } else {
                grays += g
                sizes += (w to h)
            }
        }
        templatesGray = grays
        templateSizes = sizes
        Log.i(TAG, "PEACE templates ready: ${grays.size}")
    }

    fun inspect(frame: Bitmap, nowMs: Long = System.currentTimeMillis()) {
        framesSeen++
        if (nowMs - lastDetectAt < DETECT_COOLDOWN_MS) return

        // Prefer top-right kill feed; also check a wider top band for mobile HUDs (VPULSE-style ROIs).
        val rois = listOf(
            cropRoi(frame, 0.52f, 0.02f, 0.46f, 0.28f),
            cropRoi(frame, 0.20f, 0.02f, 0.60f, 0.18f)
        )
        var feedRed = 0f
        var feedSad = 0f
        var bestNcc = 0f

        for (roi in rois) {
            val grayInt = toGrayInt(roi)
            val w = roi.width
            val h = roi.height
            val red = redDensity(roi)
            val prev = lastGray
            val sad = if (prev != null && lastW == w && lastH == h) {
                meanSad(prev, grayInt)
            } else 0f
            if (roi === rois.first()) {
                lastGray = grayInt
                lastW = w
                lastH = h
                feedRed = red
                feedSad = sad
            } else {
                feedRed = maxOf(feedRed, red)
                feedSad = maxOf(feedSad, sad)
            }
            if (templatesGray.isNotEmpty()) {
                val (grayF, _, _) = PeaceClipEngine.bitmapToGrayFloat(roi)
                for (i in templatesGray.indices) {
                    val (tw, th) = templateSizes[i]
                    val score = PeaceClipEngine.bestNccMatch(grayF, w, h, templatesGray[i], tw, th)
                    if (score > bestNcc) bestNcc = score
                }
            }
            if (roi !== frame) roi.recycle()
        }

        val hit = HighlightImportance.evaluate(
            frame = frame,
            feedRed = feedRed,
            feedSad = feedSad,
            bestNcc = bestNcc,
            prevCenterLuma = prevCenterLuma,
            outCenterLuma = centerLumaBuf
        )
        prevCenterLuma = centerLumaBuf.copyOf()

        if (nowMs - lastLogAt > 2_500L) {
            lastLogAt = nowMs
            Log.i(
                TAG,
                "watch frames=$framesSeen red=${"%.3f".format(feedRed)} sad=${"%.1f".format(feedSad)} " +
                    "ncc=${"%.2f".format(bestNcc)} hit=${hit?.reason ?: "-"}"
            )
        }

        if (hit != null) {
            lastDetectAt = nowMs
            noteKill(PeaceClipEngine.KillHit(nowMs, hit.score, hit.reason))
        }
    }

    /** Flush any open PEACE group (call when Capture stops). */
    fun flushPending() {
        flushHandler.removeCallbacks(finalizeRunnable)
        finalizeGroup("peace_flush_stop")
    }

    fun release() {
        flushHandler.removeCallbacks(finalizeRunnable)
        runCatching { flushThread.quitSafely() }
    }

    private fun noteKill(hit: PeaceClipEngine.KillHit) {
        synchronized(pending) {
            if (pending.isEmpty()) {
                groupAnchorMs = hit.atMs
                pending += hit
            } else if (hit.atMs - groupAnchorMs <= PeaceClipEngine.GROUP_WINDOW_MS) {
                pending += hit
            } else {
                val old = pending.toList()
                pending.clear()
                flushHandler.post { emitGroup(old, "peace_group_roll") }
                groupAnchorMs = hit.atMs
                pending += hit
            }
            // Short post-roll so clips actually land while Capture is still on.
            flushHandler.removeCallbacks(finalizeRunnable)
            flushHandler.postDelayed(finalizeRunnable, POST_ROLL_MS)
        }
        Log.i(TAG, "PEACE kill ${hit.reason} score=${"%.2f".format(hit.score)} pending=${pending.size}")
    }

    private fun finalizeGroup(reason: String) {
        val hits: List<PeaceClipEngine.KillHit>
        synchronized(pending) {
            if (pending.isEmpty()) return
            hits = pending.toList()
            pending.clear()
        }
        emitGroup(hits, reason)
    }

    private fun emitGroup(hits: List<PeaceClipEngine.KillHit>, reason: String) {
        if (hits.isEmpty()) return
        if (!flushing.compareAndSet(false, true)) {
            Log.i(TAG, "skip emit — flush already in progress")
            return
        }
        val group = PeaceClipEngine.groupKills(hits).firstOrNull()
        if (group == null) {
            flushing.set(false)
            return
        }
        val tag = "$reason×${group.killCount}"
        Log.i(
            TAG,
            "PEACE group flush kills=${group.killCount} reason=$tag " +
                "window=${group.clipStartMs}..${group.clipEndMs}"
        )
        try {
            onKillGroup(
                tag,
                group.peakScore,
                group.killCount,
                group.clipStartMs,
                group.clipEndMs
            )
        } finally {
            flushing.set(false)
        }
    }

    private fun cropRoi(frame: Bitmap, xf: Float, yf: Float, wf: Float, hf: Float): Bitmap {
        val x = (frame.width * xf).toInt().coerceIn(0, frame.width - 8)
        val y = (frame.height * yf).toInt().coerceIn(0, frame.height - 8)
        val w = (frame.width * wf).toInt().coerceAtLeast(8).coerceAtMost(frame.width - x)
        val h = (frame.height * hf).toInt().coerceAtLeast(8).coerceAtMost(frame.height - y)
        return Bitmap.createBitmap(frame, x, y, w, h)
    }

    private fun toGrayInt(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            px[i] = (Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100
        }
        return px
    }

    private fun redDensity(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var hits = 0
        for (c in px) {
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            if (r > 140 && r - g > 28 && r - b > 28) hits++
        }
        return hits / px.size.toFloat()
    }

    private fun meanSad(a: IntArray, b: IntArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0L
        for (i in 0 until n) s += abs(a[i] - b[i])
        return s / n.toFloat()
    }

    companion object {
        private const val TAG = "KillFeedTrigger"
        private const val DETECT_COOLDOWN_MS = 800L
        private const val POST_ROLL_MS = 1_800L
    }
}
