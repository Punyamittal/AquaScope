package com.aquascope.ui.ascii

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Canvas2D-equivalent ASCII / mosaic render pipeline (21st.dev recipe).
 */
class AsciiArtRenderer(var params: AsciiArtParams = AsciiArtParams.starryNights()) {

    private var source: Bitmap? = null
    private var cellRgb: IntArray = IntArray(0)
    private var cellLum: FloatArray = FloatArray(0)
    private var cols = 0
    private var rows = 0
    private var cell = 16
    private var viewW = 0
    private var viewH = 0

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val postPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var layerBmp: Bitmap? = null

    private val standardChars = " .:-=+*#%@"
    private val matrixChars = "01アイウエオカキクケコ"
    private val braille = "⠀⠁⠂⠃⠄⠅⠆⠇⠈⠉⠊⠋⠌⠍⠎⠏"

    fun setSource(bitmap: Bitmap?) {
        source = bitmap
        rebuildGrid()
    }

    fun prepare(width: Int, height: Int) {
        if (width == viewW && height == viewH && cellRgb.isNotEmpty()) return
        viewW = width
        viewH = height
        rebuildGrid()
    }

    private fun rebuildGrid() {
        val src = source ?: return
        if (viewW < 8 || viewH < 8) return
        cell = params.cellSize.coerceIn(4, 48)
        cols = max(1, viewW / cell)
        rows = max(1, viewH / cell)
        val n = cols * rows
        cellRgb = IntArray(n)
        cellLum = FloatArray(n)

        val scaled = Bitmap.createScaledBitmap(src, cols, rows, true)
        val pixels = IntArray(n)
        scaled.getPixels(pixels, 0, cols, 0, 0, cols, rows)
        if (scaled !== src) scaled.recycle()

        val edge = FloatArray(n)
        for (i in 0 until n) {
            val p = pixels[i]
            var r = Color.red(p) / 255f
            var g = Color.green(p) / 255f
            var b = Color.blue(p) / 255f
            // brightness
            val br = params.brightness / 100f
            r = (r + br).coerceIn(0f, 1f)
            g = (g + br).coerceIn(0f, 1f)
            b = (b + br).coerceIn(0f, 1f)
            // contrast
            val cFactor = (params.contrast / 100f)
            r = ((r - 0.5f) * cFactor + 0.5f).coerceIn(0f, 1f)
            g = ((g - 0.5f) * cFactor + 0.5f).coerceIn(0f, 1f)
            b = ((b - 0.5f) * cFactor + 0.5f).coerceIn(0f, 1f)
            // saturation
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val sat = params.saturation / 100f
            r = (lum + (r - lum) * sat).coerceIn(0f, 1f)
            g = (lum + (g - lum) * sat).coerceIn(0f, 1f)
            b = (lum + (b - lum) * sat).coerceIn(0f, 1f)
            // grayscale mix
            val gs = params.grayscale / 100f
            r = r * (1f - gs) + lum * gs
            g = g * (1f - gs) + lum * gs
            b = b * (1f - gs) + lum * gs
            // tone curve on luminance then reapply
            var L = toneMap(lum)
            if (params.invert) L = 1f - L
            val scale = if (lum > 1e-4f) L / lum else L
            r = (r * scale).coerceIn(0f, 1f)
            g = (g * scale).coerceIn(0f, 1f)
            b = (b * scale).coerceIn(0f, 1f)
            // tint overlay
            if (params.tintOpacity > 0.5f) {
                val tr = Color.red(params.tint) / 255f
                val tg = Color.green(params.tint) / 255f
                val tb = Color.blue(params.tint) / 255f
                val to = (params.tintOpacity / 100f).coerceIn(0f, 1f)
                when (params.overlayBlend) {
                    "multiply" -> {
                        r = r * (1f - to) + (r * tr) * to
                        g = g * (1f - to) + (g * tg) * to
                        b = b * (1f - to) + (b * tb) * to
                    }
                    else -> {
                        r = r * (1f - to) + tr * to
                        g = g * (1f - to) + tg * to
                        b = b * (1f - to) + tb * to
                    }
                }
            }
            cellRgb[i] = Color.rgb((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
            cellLum[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b
        }

        if (params.edgeEmphasis > 0.5f) {
            for (y in 1 until rows - 1) {
                for (x in 1 until cols - 1) {
                    val i = y * cols + x
                    val gx = cellLum[i + 1] - cellLum[i - 1]
                    val gy = cellLum[i + cols] - cellLum[i - cols]
                    edge[i] = sqrt(gx * gx + gy * gy)
                }
            }
            val em = params.edgeEmphasis / 100f
            for (i in 0 until n) {
                val boost = (1f + edge[i] * em * 2f).coerceAtMost(1.8f)
                cellLum[i] = (cellLum[i] * boost).coerceIn(0f, 1f)
            }
        }
    }

    private fun toneMap(t: Float): Float {
        val pts = params.toneCurve
        if (pts.size < 2) return t
        val x = t.coerceIn(0f, 1f)
        for (i in 0 until pts.size - 1) {
            val a = pts[i]
            val b = pts[i + 1]
            if (x >= a.x && x <= b.x) {
                val u = if (b.x > a.x) (x - a.x) / (b.x - a.x) else 0f
                return a.y + (b.y - a.y) * u
            }
        }
        return pts.last().y
    }

    fun render(canvas: Canvas, time: Float, pulse: Float = 0.55f, active: Boolean = false) {
        val w = viewW
        val h = viewH
        if (w < 8 || h < 8 || cellRgb.isEmpty()) return

        drawBackground(canvas, w, h)

        val layer = obtainLayer(w, h)
        layer.eraseColor(0x00000000)
        val lc = Canvas(layer)
        drawCells(lc, time, pulse, active)

        applyBlurType(layer)
        applyPostFx(lc, layer, time)
        applyLights(lc, w, h)
        applyMask(lc, w, h)

        canvas.drawBitmap(layer, 0f, 0f, null)
    }

    private fun obtainLayer(w: Int, h: Int): Bitmap {
        val existing = layerBmp
        if (existing != null && existing.width == w && existing.height == h && !existing.isRecycled) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { layerBmp = it }
    }

    private fun drawBackground(canvas: Canvas, w: Int, h: Int) {
        val alpha = ((params.bgOpacity / 100f) * 255).toInt().coerceIn(0, 255)
        when (params.bgMode) {
            "none" -> { /* transparent */ }
            "photo" -> {
                source?.let {
                    paint.alpha = alpha
                    canvas.drawBitmap(it, null, Rect(0, 0, w, h), paint)
                    paint.alpha = 255
                }
            }
            "blur" -> {
                source?.let {
                    val small = Bitmap.createScaledBitmap(it, max(8, w / 8), max(8, h / 8), true)
                    paint.alpha = alpha
                    canvas.drawBitmap(small, null, Rect(0, 0, w, h), paint)
                    paint.alpha = 255
                    if (small !== it) small.recycle()
                }
            }
            else -> { // solid
                paint.color = (alpha shl 24) or (params.solidBgColor and 0x00FFFFFF)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
        }
    }

    private fun animMod(x: Int, y: Int, time: Float): Float {
        if (!params.animated) return 0f
        val speed = (params.animSpeed / 100f) * 2.2f
        val amp = (params.animIntensity / 100f) * 0.45f
        val t = time * speed
        return when (params.animStyle) {
            "pulse" -> sin(t * Math.PI * 2).toFloat() * amp
            "shimmer" -> (sin((x * 0.7f + y * 0.4f) + t * 4f) * 0.5f +
                sin((x * 1.3f - y) + t * 2.5f) * 0.5f) * amp
            "ripple" -> {
                val cx = cols * 0.5f
                val cy = rows * 0.5f
                val d = hypot(x - cx, y - cy)
                sin(d * 0.45f - t * 3f).toFloat() * amp
            }
            "flicker" -> {
                val h = ((x * 73856093) xor (y * 19349663) xor floor(t * 12).toInt())
                ((h and 255) / 255f - 0.5f) * amp * 1.4f
            }
            else -> { // wave
                sin(x * 0.35f + t * 2.4f).toFloat() * amp +
                    cos(y * 0.22f + t * 1.6f).toFloat() * amp * 0.5f
            }
        }
    }

    private fun coverageOk(x: Int, y: Int): Boolean {
        val cov = params.coverage.coerceIn(0f, 100f) / 100f
        if (cov >= 0.999f) return true
        val h = ((x * 374761393) xor (y * 668265263)) and 0x7fffffff
        return (h % 1000) / 1000f < cov
    }

    private fun drawCells(c: Canvas, time: Float, pulse: Float, active: Boolean) {
        val mode = params.renderMode.lowercase()
        val dens = (params.density / 100f).coerceIn(0f, 0.85f)
        val inset = dens * cell * 0.45f
        val chars = when {
            params.customChars.isNotBlank() -> params.customChars
            params.charSet == "blocks" -> " ░▒▓█"
            params.charSet == "binary" -> "01"
            else -> standardChars
        }
        textPaint.textSize = cell * 0.85f

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                if (!coverageOk(x, y)) continue
                val i = y * cols + x
                val mod = animMod(x, y, time)
                var lum = (cellLum[i] + mod + if (active) 0.08f * pulse else 0f).coerceIn(0f, 1f)
                val rgb = cellRgb[i]
                val r = Color.red(rgb)
                val g = Color.green(rgb)
                val b = Color.blue(rgb)
                val a = (220 + lum * 35).toInt().coerceIn(0, 255)
                val color = Color.argb(a, r, g, b)
                val left = x * cell + inset
                val top = y * cell + inset + mod * cell * 0.35f
                val right = (x + 1) * cell - inset
                val bottom = (y + 1) * cell - inset + mod * cell * 0.35f
                val cx = (left + right) * 0.5f
                val cy = (top + bottom) * 0.5f
                val sz = (right - left).coerceAtLeast(1f)

                paint.style = Paint.Style.FILL
                paint.color = color

                when (mode) {
                    "characters", "hexdump", "matrix" -> {
                        val set = when (mode) {
                            "hexdump" -> "0123456789ABCDEF"
                            "matrix" -> matrixChars
                            else -> chars
                        }
                        val idx = ((1f - lum) * (set.length - 1)).toInt().coerceIn(0, set.length - 1)
                        val ch = set[idx].toString()
                        textPaint.color = if (mode == "matrix") {
                            Color.argb(a, 40, (180 + lum * 75).toInt(), 60)
                        } else color
                        textPaint.textSize = cell * (0.55f + lum * 0.5f)
                        // matrix rain offset
                        val rain = if (mode == "matrix") ((time * 8 + x * 1.7f) % rows) else 0f
                        c.drawText(ch, cx, cy + cell * 0.3f + rain * 0.15f * cell, textPaint)
                    }
                    "dots" -> {
                        paint.color = color
                        c.drawCircle(cx, cy, sz * (0.15f + lum * 0.35f), paint)
                    }
                    "cross" -> {
                        paint.strokeWidth = 1.5f + lum * 2f
                        paint.style = Paint.Style.STROKE
                        c.drawLine(cx - sz * 0.35f, cy, cx + sz * 0.35f, cy, paint)
                        c.drawLine(cx, cy - sz * 0.35f, cx, cy + sz * 0.35f, paint)
                        paint.style = Paint.Style.FILL
                    }
                    "diamond" -> {
                        val path = Path().apply {
                            moveTo(cx, top)
                            lineTo(right, cy)
                            lineTo(cx, bottom)
                            lineTo(left, cy)
                            close()
                        }
                        c.drawPath(path, paint)
                    }
                    "voxel", "lego" -> {
                        c.drawRect(left, top, right, bottom, paint)
                        paint.color = Color.argb(80, 255, 255, 255)
                        c.drawRect(left, top, right, top + sz * 0.18f, paint)
                        paint.color = Color.argb(60, 0, 0, 0)
                        c.drawRect(left, bottom - sz * 0.15f, right, bottom, paint)
                    }
                    "lines" -> {
                        paint.strokeWidth = 1f + lum * 3f
                        paint.style = Paint.Style.STROKE
                        c.drawLine(left, cy, right, cy, paint)
                        paint.style = Paint.Style.FILL
                    }
                    "diagonal" -> {
                        paint.strokeWidth = 1f + lum * 2.5f
                        paint.style = Paint.Style.STROKE
                        c.drawLine(left, bottom, right, top, paint)
                        paint.style = Paint.Style.FILL
                    }
                    "braille" -> {
                        val idx = (lum * (braille.length - 1)).toInt().coerceIn(0, braille.length - 1)
                        textPaint.color = color
                        textPaint.textSize = cell.toFloat()
                        c.drawText(braille[idx].toString(), cx, cy + cell * 0.3f, textPaint)
                    }
                    "disco" -> {
                        val hueShift = ((x + y + (time * 40).toInt()) % 12) / 12f
                        paint.color = Color.HSVToColor(a, floatArrayOf(hueShift * 360f, 0.7f, 0.4f + lum * 0.6f))
                        c.drawRect(left, top, right, bottom, paint)
                    }
                    "rings" -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 1.2f + lum * 2f
                        c.drawCircle(cx, cy, sz * (0.2f + lum * 0.3f), paint)
                        paint.style = Paint.Style.FILL
                    }
                    "hearts" -> {
                        textPaint.color = color
                        textPaint.textSize = cell * (0.5f + lum * 0.5f)
                        c.drawText("♥", cx, cy + cell * 0.3f, textPaint)
                    }
                    "stars" -> {
                        textPaint.color = color
                        textPaint.textSize = cell * (0.4f + lum * 0.7f)
                        c.drawText("✦", cx, cy + cell * 0.28f, textPaint)
                    }
                    "hexagons" -> {
                        c.drawPath(hexPath(cx, cy, sz * 0.48f), paint)
                    }
                    "triangles" -> {
                        val path = Path().apply {
                            if ((x + y) % 2 == 0) {
                                moveTo(left, bottom); lineTo(cx, top); lineTo(right, bottom)
                            } else {
                                moveTo(left, top); lineTo(right, top); lineTo(cx, bottom)
                            }
                            close()
                        }
                        c.drawPath(path, paint)
                    }
                    "bubbles" -> {
                        paint.alpha = (a * (0.4f + lum * 0.6f)).toInt()
                        c.drawCircle(cx, cy, sz * (0.25f + lum * 0.35f), paint)
                        paint.alpha = 255
                    }
                    "hatch" -> {
                        paint.strokeWidth = 1f
                        paint.style = Paint.Style.STROKE
                        val steps = (2 + lum * 4).toInt()
                        for (s in 0..steps) {
                            val u = s / steps.toFloat()
                            c.drawLine(left, top + u * sz, left + u * sz, top, paint)
                            c.drawLine(left + u * sz, bottom, right, top + u * sz, paint)
                        }
                        paint.style = Paint.Style.FILL
                    }
                    "contour" -> {
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 1.2f
                        val band = floor(lum * 8f)
                        if (band.toInt() % 2 == 0) {
                            c.drawCircle(cx, cy, sz * 0.35f, paint)
                        }
                        paint.style = Paint.Style.FILL
                    }
                    "halfblocks" -> {
                        val mid = (top + bottom) * 0.5f
                        val botLum = (lum * 0.7f + mod * 0.2f).coerceIn(0f, 1f)
                        paint.color = Color.argb(a, r, g, b)
                        c.drawRect(left, top, right, mid, paint)
                        paint.color = Color.argb(
                            a,
                            (r * botLum + 20).toInt().coerceIn(0, 255),
                            (g * botLum + 20).toInt().coerceIn(0, 255),
                            (b * botLum + 30).toInt().coerceIn(0, 255)
                        )
                        c.drawRect(left, mid, right, bottom, paint)
                    }
                    "dither", "pixel", "mixed", "mosaic" -> {
                        // mosaic / pixel / dither: filled cell (dither skips by Bayer)
                        if (mode == "dither") {
                            val bayer = BAYER[y and 3][x and 3] / 16f
                            if (lum < bayer) continue
                        }
                        if (mode == "mixed" && (x + y) % 3 == 0) {
                            c.drawCircle(cx, cy, sz * 0.4f, paint)
                        } else {
                            c.drawRect(left, top, right, bottom, paint)
                        }
                    }
                    else -> c.drawRect(left, top, right, bottom, paint)
                }
            }
        }
    }

    private fun hexPath(cx: Float, cy: Float, r: Float): Path {
        val p = Path()
        for (i in 0..5) {
            val a = Math.toRadians(60.0 * i - 30.0)
            val x = cx + cos(a).toFloat() * r
            val y = cy + sin(a).toFloat() * r
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return p
    }

    private fun applyBlurType(layer: Bitmap) {
        if (params.blurType == "off" || params.blurAmount < 1f) return
        // Lightweight: soft color filter approximating mild blur presence
        val amount = (params.blurAmount / 100f).coerceIn(0f, 1f)
        val c = Canvas(layer)
        postPaint.color = Color.argb((amount * 40).toInt(), 0, 0, 0)
        c.drawRect(0f, 0f, layer.width.toFloat(), layer.height.toFloat(), postPaint)
    }

    private fun applyPostFx(c: Canvas, layer: Bitmap, time: Float) {
        val w = layer.width.toFloat()
        val h = layer.height.toFloat()
        val pfx = params.pfx

        if (pfx.bloom.enabled && pfx.bloom.intensity > 0f) {
            val inten = pfx.bloom.intensity / 100f
            val small = Bitmap.createScaledBitmap(layer, max(8, layer.width / 6), max(8, layer.height / 6), true)
            postPaint.alpha = (inten * 120).toInt().coerceIn(0, 180)
            postPaint.maskFilter = BlurMaskFilter(12f, BlurMaskFilter.Blur.NORMAL)
            c.drawBitmap(small, null, RectF(0f, 0f, w, h), postPaint)
            postPaint.maskFilter = null
            postPaint.alpha = 255
            if (small !== layer) small.recycle()
        }

        if (pfx.vignette.enabled && pfx.vignette.intensity > 0f) {
            val inten = pfx.vignette.intensity / 100f
            postPaint.shader = RadialGradient(
                w * 0.5f, h * 0.45f, hypot(w, h) * 0.55f,
                intArrayOf(0x00000000, Color.argb((inten * 200).toInt(), 0, 0, 0)),
                floatArrayOf(0.45f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, postPaint)
            postPaint.shader = null
        }

        if (pfx.scanLines.enabled) {
            val inten = pfx.scanLines.intensity / 100f
            postPaint.color = Color.argb((inten * 90).toInt(), 0, 0, 0)
            var y = 0f
            while (y < h) {
                c.drawRect(0f, y, w, y + 1.5f, postPaint)
                y += 3.5f
            }
        }

        if (pfx.chromatic.enabled) {
            val shift = (pfx.chromatic.intensity / 100f) * 4f
            postPaint.colorFilter = PorterDuffColorFilter(0x44FF0000.toInt(), PorterDuff.Mode.SCREEN)
            c.drawBitmap(layer, -shift, 0f, postPaint)
            postPaint.colorFilter = PorterDuffColorFilter(0x4400FFFF.toInt(), PorterDuff.Mode.SCREEN)
            c.drawBitmap(layer, shift, 0f, postPaint)
            postPaint.colorFilter = null
        }

        if (pfx.filmGrain.enabled) {
            val inten = pfx.filmGrain.intensity / 100f
            val rnd = Random((time * 60).toInt())
            for (i in 0 until (w * h * inten * 0.02f).toInt()) {
                val x = rnd.nextFloat() * w
                val y = rnd.nextFloat() * h
                val g = rnd.nextInt(255)
                postPaint.color = Color.argb((inten * 70).toInt(), g, g, g)
                c.drawRect(x, y, x + 1.5f, y + 1.5f, postPaint)
            }
        }

        if (pfx.glitch.enabled && ((time * 10).toInt() % 7 == 0)) {
            val inten = pfx.glitch.intensity / 100f
            val rnd = Random((time * 100).toInt())
            for (i in 0 until 4) {
                val yy = rnd.nextFloat() * h
                val hh = 4f + rnd.nextFloat() * 18f
                val dx = (rnd.nextFloat() - 0.5f) * 40f * inten
                c.drawBitmap(layer, Rect(0, yy.toInt(), layer.width, (yy + hh).toInt()),
                    RectF(dx, yy, w + dx, yy + hh), null)
            }
        }

        if (pfx.halftone.enabled) {
            val inten = pfx.halftone.intensity / 100f
            postPaint.color = Color.argb((inten * 50).toInt(), 0, 0, 0)
            val step = 6f
            var y = 0f
            while (y < h) {
                var x = 0f
                while (x < w) {
                    c.drawCircle(x, y, 1.2f * inten, postPaint)
                    x += step
                }
                y += step
            }
        }

        if (pfx.pixelate.enabled && pfx.pixelate.intensity > 5f) {
            // already mosaic; intensify by overlaying coarser grid
            val s = (8 + pfx.pixelate.intensity / 5f).toInt()
            val tiny = Bitmap.createScaledBitmap(layer, max(4, layer.width / s), max(4, layer.height / s), false)
            c.drawBitmap(tiny, null, RectF(0f, 0f, w, h), null)
            if (tiny !== layer) tiny.recycle()
        }

        if (pfx.filmDust.enabled) {
            val inten = pfx.filmDust.intensity / 100f
            val rnd = Random(42 + (time * 3).toInt())
            postPaint.color = Color.argb((inten * 160).toInt(), 240, 240, 220)
            for (i in 0 until (8 * inten).toInt()) {
                val x = rnd.nextFloat() * w
                val y = rnd.nextFloat() * h
                c.drawCircle(x, y, 1f + rnd.nextFloat() * 2f, postPaint)
            }
        }
    }

    private fun applyLights(c: Canvas, w: Int, h: Int) {
        val lights = params.lights
        if (!lights.enabled || lights.points.isEmpty()) return
        for (pt in lights.points) {
            val cx = pt.x * w
            val cy = pt.y * h
            val r = pt.radius * max(w, h)
            postPaint.shader = RadialGradient(
                cx, cy, r.coerceAtLeast(8f),
                intArrayOf(
                    Color.argb((pt.intensity * 180).toInt().coerceIn(0, 255), 100, 180, 255),
                    0x00000000
                ),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            c.drawCircle(cx, cy, r, postPaint)
            postPaint.shader = null
        }
    }

    private fun applyMask(c: Canvas, w: Int, h: Int) {
        val mask = params.mask
        if (!mask.enabled) return
        // Optional reveal mask — supply via params.mask.dataUrl when available.
        // Without a decoded bitmap, soft radial falloff keeps the hero focused.
        postPaint.shader = RadialGradient(
            w * 0.5f, h * 0.42f, max(w, h) * 0.55f,
            intArrayOf(0x00000000, 0xCC000000.toInt()),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        if (mask.invert) {
            // invert: darken center instead
            postPaint.shader = RadialGradient(
                w * 0.5f, h * 0.42f, max(w, h) * 0.55f,
                intArrayOf(0x99000000.toInt(), 0x00000000),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), postPaint)
        postPaint.shader = null
    }

    companion object {
        private val BAYER = arrayOf(
            intArrayOf(0, 8, 2, 10),
            intArrayOf(12, 4, 14, 6),
            intArrayOf(3, 11, 1, 9),
            intArrayOf(15, 7, 13, 5)
        )
    }
}
