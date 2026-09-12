package com.aquascope.smriti.brain.peace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import kotlin.math.sqrt

/**
 * On-device port of the PEACE highlight pipeline ideas
 * (https://github.com/amogorkon/PEACE):
 * - template match kill-feed (OpenCV TM_CCOEFF_NORMED ≥ 0.75 → NCC here)
 * - group kills within 20s of the group start
 * - clip window = first−5s … last+5s (live ring already holds ~30s)
 */
object PeaceClipEngine {
    const val MATCH_THRESHOLD = 0.75f
    const val GROUP_WINDOW_MS = 20_000L
    const val PRE_ROLL_MS = 5_000L
    const val POST_ROLL_MS = 5_000L

    data class KillHit(val atMs: Long, val score: Float, val reason: String)

    data class KillGroup(
        val kills: List<KillHit>,
        val startMs: Long,
        val endMs: Long
    ) {
        val clipStartMs: Long get() = (startMs - PRE_ROLL_MS).coerceAtLeast(0L)
        val clipEndMs: Long get() = endMs + POST_ROLL_MS
        val killCount: Int get() = kills.size
        val peakScore: Float get() = kills.maxOfOrNull { it.score } ?: 0f
    }

    /** PEACE timeGrouping: cluster times within [GROUP_WINDOW_MS] of the group’s first kill. */
    fun groupKills(sortedHits: List<KillHit>): List<KillGroup> {
        if (sortedHits.isEmpty()) return emptyList()
        val ordered = sortedHits.sortedBy { it.atMs }
        val groups = mutableListOf<KillGroup>()
        var current = mutableListOf(ordered.first())
        var anchor = ordered.first().atMs
        for (i in 1 until ordered.size) {
            val hit = ordered[i]
            if (hit.atMs - anchor <= GROUP_WINDOW_MS) {
                current.add(hit)
            } else {
                groups += KillGroup(current.toList(), current.first().atMs, current.last().atMs)
                current = mutableListOf(hit)
                anchor = hit.atMs
            }
        }
        groups += KillGroup(current.toList(), current.first().atMs, current.last().atMs)
        return groups
    }

    /**
     * Coarse-grid normalized cross-correlation (≈ OpenCV TM_CCOEFF_NORMED).
     * Returns best score in 0..1.
     */
    fun bestNccMatch(
        imageGray: FloatArray,
        iw: Int,
        ih: Int,
        templateGray: FloatArray,
        tw: Int,
        th: Int
    ): Float {
        if (tw > iw || th > ih || templateGray.size != tw * th || imageGray.size != iw * ih) return 0f
        val n = (tw * th).toFloat()
        var tMean = 0f
        for (v in templateGray) tMean += v
        tMean /= n
        var tVar = 0f
        for (v in templateGray) {
            val d = v - tMean
            tVar += d * d
        }
        if (tVar < 1e-3f) return 0f
        val tNorm = sqrt(tVar)
        val stepX = maxOf(1, tw / 6)
        val stepY = maxOf(1, th / 6)
        var best = 0f
        var y = 0
        while (y <= ih - th) {
            var x = 0
            while (x <= iw - tw) {
                var sum = 0f
                var sumSq = 0f
                var ti = 0
                for (ty in 0 until th) {
                    val row = (y + ty) * iw + x
                    for (tx in 0 until tw) {
                        val iv = imageGray[row + tx]
                        sum += iv
                        sumSq += iv * iv
                        ti++
                    }
                }
                val iMean = sum / n
                val iVar = sumSq - sum * sum / n
                if (iVar >= 1e-3f) {
                    var dot = 0f
                    ti = 0
                    for (ty in 0 until th) {
                        val row = (y + ty) * iw + x
                        for (tx in 0 until tw) {
                            val iv = imageGray[row + tx] - iMean
                            val tv = templateGray[ti++] - tMean
                            dot += iv * tv
                        }
                    }
                    val score = (dot / (sqrt(iVar) * tNorm)).coerceIn(-1f, 1f)
                    if (score > best) best = score
                }
                x += stepX
            }
            y += stepY
        }
        return best.coerceAtLeast(0f)
    }

    fun bitmapToGrayFloat(bmp: Bitmap): Triple<FloatArray, Int, Int> {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = FloatArray(px.size)
        for (i in px.indices) {
            val c = px[i]
            out[i] = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
        }
        return Triple(out, w, h)
    }

    fun loadTemplatesFromAssets(context: Context): List<Bitmap> {
        val am = context.assets
        val names = runCatching { am.list("killfeed")?.toList().orEmpty() }.getOrDefault(emptyList())
        val bitmaps = mutableListOf<Bitmap>()
        for (name in names) {
            val lower = name.lowercase()
            if (!lower.endsWith(".png") && !lower.endsWith(".jpg") && !lower.endsWith(".jpeg")) continue
            runCatching {
                am.open("killfeed/$name").use { stream ->
                    BitmapFactory.decodeStream(stream)?.let { bitmaps.add(it) }
                }
            }.onFailure { Log.w(TAG, "template $name: ${it.message}") }
        }
        Log.i(TAG, "Loaded ${bitmaps.size} PEACE kill-feed templates from assets/killfeed")
        return bitmaps
    }

    private const val TAG = "PeaceClip"
}
