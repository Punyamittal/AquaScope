package com.aquascope.smriti.brain.peace

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max

/**
 * Multi-signal “is this frame worth clipping?” scorer, ported from common
 * open-source highlight pipelines (ideas only — no copied proprietary weights):
 *
 * - [PEACE](https://github.com/amogorkon/PEACE) — kill-feed template + time grouping
 * - [BF6 auto-clip](https://github.com/mydnic/battlefield6-highlights-auto-clip) — center kill-confirm, not whole feed
 * - [Auto-clipper](https://github.com/bendawg2010/Auto-clipper) — damage vignette, muzzle/HUD flash, motion
 * - [Crispy](https://github.com/Flowtter/crispy) — persistent kill icons / image recognition
 * - [VPULSE](https://github.com/verticalhost/vpulse) — kill-feed ROI profiles + streak clustering
 *
 * Returns a reason + score when the frame looks like an *important* moment.
 */
object HighlightImportance {

    data class Hit(val reason: String, val score: Float)

    fun evaluate(
        frame: Bitmap,
        feedRed: Float,
        feedSad: Float,
        bestNcc: Float,
        prevCenterLuma: FloatArray?,
        outCenterLuma: FloatArray
    ): Hit? {
        // 1) Explicit kill-feed template (PEACE / BF6 / Crispy)
        if (bestNcc >= 0.70f) {
            return Hit("peace_template", bestNcc)
        }

        // 2) Center kill-confirm flash (BF6-style personal “KILL” overlay)
        val center = sampleCenterLuma(frame, outCenterLuma)
        val centerFlash = if (prevCenterLuma != null && prevCenterLuma.size == center.size) {
            meanAbsDiff(prevCenterLuma, center)
        } else {
            0f
        }
        val centerBright = center.average().toFloat()
        if (centerFlash > 28f && centerBright > 90f) {
            return Hit("kill_confirm_center", (centerFlash / 80f).coerceIn(0.35f, 0.95f))
        }

        // 3) Edge damage vignette (Auto-clipper red vignette)
        val vignette = edgeRedVignette(frame)
        if (vignette > 0.08f && feedSad > 8f) {
            return Hit("damage_vignette", vignette.coerceIn(0.3f, 0.9f))
        }

        // 4) Kill-feed red banner / flash (PEACE soft heuristics + VPULSE feed ROI)
        if (feedRed > 0.035f && feedSad > 10f) {
            return Hit("kill_feed_flash", feedRed.coerceAtLeast(feedSad / 50f).coerceIn(0.25f, 0.9f))
        }
        if (feedRed > 0.06f) {
            return Hit("red_banner", feedRed.coerceIn(0.3f, 0.85f))
        }

        // 5) Strong HUD motion in feed (action without clean red)
        if (feedSad > 18f) {
            return Hit("hud_motion", (feedSad / 55f).coerceIn(0.2f, 0.7f))
        }

        // 6) Multi-signal combo: mild feed motion + center flash
        if (feedSad > 12f && centerFlash > 18f) {
            return Hit(
                "combo_action",
                ((feedSad / 60f) + (centerFlash / 90f)).coerceIn(0.25f, 0.8f)
            )
        }

        return null
    }

    private fun sampleCenterLuma(frame: Bitmap, out: FloatArray): FloatArray {
        val roi = crop(frame, 0.30f, 0.32f, 0.40f, 0.28f)
        val w = roi.width
        val h = roi.height
        val stepX = max(1, w / 16)
        val stepY = max(1, h / 12)
        var i = 0
        var y = 0
        while (y < h && i < out.size) {
            var x = 0
            while (x < w && i < out.size) {
                val c = roi.getPixel(x, y)
                out[i++] = (Color.red(c) * 0.3f + Color.green(c) * 0.59f + Color.blue(c) * 0.11f)
                x += stepX
            }
            y += stepY
        }
        if (roi !== frame) roi.recycle()
        // Zero unused slots so averages stay stable.
        for (j in i until out.size) out[j] = out[0]
        return out
    }

    private fun edgeRedVignette(frame: Bitmap): Float {
        val bands = listOf(
            crop(frame, 0f, 0f, 1f, 0.08f),
            crop(frame, 0f, 0.92f, 1f, 0.08f),
            crop(frame, 0f, 0.1f, 0.08f, 0.8f),
            crop(frame, 0.92f, 0.1f, 0.08f, 0.8f)
        )
        var hits = 0
        var total = 0
        for (b in bands) {
            val step = max(1, (b.width * b.height) / 400)
            var i = 0
            while (i < b.width * b.height) {
                val x = i % b.width
                val y = i / b.width
                if (y < b.height) {
                    val c = b.getPixel(x, y)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val bl = Color.blue(c)
                    if (r > 150 && r - g > 35 && r - bl > 35) hits++
                    total++
                }
                i += step
            }
            if (b !== frame) b.recycle()
        }
        return if (total == 0) 0f else hits / total.toFloat()
    }

    private fun crop(frame: Bitmap, xf: Float, yf: Float, wf: Float, hf: Float): Bitmap {
        val x = (frame.width * xf).toInt().coerceIn(0, frame.width - 8)
        val y = (frame.height * yf).toInt().coerceIn(0, frame.height - 8)
        val w = (frame.width * wf).toInt().coerceAtLeast(8).coerceAtMost(frame.width - x)
        val h = (frame.height * hf).toInt().coerceAtLeast(8).coerceAtMost(frame.height - y)
        return Bitmap.createBitmap(frame, x, y, w, h)
    }

    private fun meanAbsDiff(a: FloatArray, b: FloatArray): Float {
        val n = minOf(a.size, b.size)
        var s = 0f
        for (i in 0 until n) s += abs(a[i] - b[i])
        return s / n
    }
}
