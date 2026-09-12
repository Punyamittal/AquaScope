package com.aquascope.smriti.brain

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs

/**
 * 10 Hz kill-feed / victory-region monitor using grayscale SAD + red-ink density.
 * Drop optional PNG templates into assets/killfeed/ to tighten matching.
 */
class KillFeedTrigger(
    private val onKill: (reason: String, score: Float) -> Unit
) {
    private var lastGray: IntArray? = null
    private var lastW = 0
    private var lastH = 0
    private var lastFireAt = 0L
    private var templates: List<IntArray> = emptyList()
    private var templateW = 0
    private var templateH = 0

    fun setTemplates(bitmaps: List<Bitmap>) {
        templates = bitmaps.map { toGray(it) }
        bitmaps.firstOrNull()?.let {
            templateW = it.width
            templateH = it.height
        }
    }

    fun inspect(frame: Bitmap, nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastFireAt < COOLDOWN_MS) return
        val roi = cropKillFeed(frame)
        val gray = toGray(roi)
        val w = roi.width
        val h = roi.height
        val red = redDensity(roi)
        val prev = lastGray
        val sad = if (prev != null && lastW == w && lastH == h) {
            meanSad(prev, gray)
        } else 0f
        lastGray = gray
        lastW = w
        lastH = h
        val templateHit = templates.any { t ->
            if (t.size != gray.size) false
            else meanSad(t, gray) < 18f
        }
        val fired = when {
            templateHit -> "template"
            red > 0.07f && sad > 22f -> "kill_feed_flash"
            red > 0.12f -> "red_banner"
            else -> null
        }
        if (fired != null) {
            lastFireAt = nowMs
            onKill(fired, red.coerceAtLeast(sad / 40f))
        }
        if (roi !== frame) roi.recycle()
    }

    private fun cropKillFeed(frame: Bitmap): Bitmap {
        val x = (frame.width * 0.58f).toInt()
        val y = (frame.height * 0.04f).toInt()
        val w = (frame.width * 0.38f).toInt().coerceAtLeast(8)
        val h = (frame.height * 0.22f).toInt().coerceAtLeast(8)
        return Bitmap.createBitmap(frame, x.coerceAtMost(frame.width - w), y, w, h)
    }

    private fun toGray(bmp: Bitmap): IntArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val c = px[i]
            val r = Color.red(c)
            val g = Color.green(c)
            val b = Color.blue(c)
            px[i] = (r * 30 + g * 59 + b * 11) / 100
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
            if (r > 160 && r - g > 40 && r - b > 40) hits++
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
        private const val COOLDOWN_MS = 8_000L
    }
}
