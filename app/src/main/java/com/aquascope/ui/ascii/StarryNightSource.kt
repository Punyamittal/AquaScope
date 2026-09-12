package com.aquascope.ui.ascii

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural stand-in for the unrecorded starry-nights source photo:
 * clear moon subject, swirling sky, dark hills — samples well under mosaic cells.
 */
object StarryNightSource {

    fun create(width: Int, height: Int, seed: Int = 21): Bitmap {
        val w = width.coerceAtLeast(64)
        val h = height.coerceAtLeast(64)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = Random(seed)

        // Night sky base
        val sky = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFF050814.toInt(), 0xFF0A1A3A.toInt(), 0xFF061022.toInt()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), sky)

        // Soft cyan wash
        val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w * 0.55f, h * 0.35f, w * 0.7f,
                intArrayOf(0x553CA6FF, 0x220E4A8A, 0x00000000),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), wash)

        // Swirl strokes (Van Gogh–ish ribbons)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        for (i in 0 until 18) {
            val cx = w * (0.15f + rnd.nextFloat() * 0.7f)
            val cy = h * (0.12f + rnd.nextFloat() * 0.55f)
            val turns = 1.2 + rnd.nextDouble() * 1.8
            val baseR = (w * (0.08f + rnd.nextFloat() * 0.18f))
            stroke.strokeWidth = (4f + rnd.nextFloat() * 10f) * (w / 320f)
            val cool = rnd.nextBoolean()
            stroke.color = if (cool) {
                Color.argb(140 + rnd.nextInt(80), 40 + rnd.nextInt(40), 120 + rnd.nextInt(80), 200 + rnd.nextInt(55))
            } else {
                Color.argb(100 + rnd.nextInt(60), 180 + rnd.nextInt(50), 170 + rnd.nextInt(40), 60 + rnd.nextInt(40))
            }
            val path = Path()
            val steps = 48
            for (s in 0..steps) {
                val t = s / steps.toFloat()
                val ang = t * turns * Math.PI * 2
                val r = baseR * (0.35f + t * 1.1f)
                val x = (cx + cos(ang) * r).toFloat()
                val y = (cy + sin(ang) * r * 0.55).toFloat()
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            c.drawPath(path, stroke)
        }

        // Stars
        val star = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 90) {
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h * 0.72f
            val bright = 160 + rnd.nextInt(95)
            star.color = Color.argb(bright, 230, 240, 255)
            c.drawCircle(x, y, 0.8f + rnd.nextFloat() * 2.2f * (w / 320f), star)
        }

        // Moon — clear subject
        val mx = w * 0.72f
        val my = h * 0.28f
        val mr = w * 0.11f
        val moonGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                mx, my, mr * 2.4f,
                intArrayOf(0xAAFFE08A.toInt(), 0x44FFC94A, 0x00000000),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(mx, my, mr * 2.4f, moonGlow)
        val moon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                mx - mr * 0.25f, my - mr * 0.2f, mr,
                intArrayOf(0xFFFFF6C8.toInt(), 0xFFFFD56A.toInt(), 0xFFE8A820.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        c.drawCircle(mx, my, mr, moon)

        // Hills silhouette
        val hill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF02060E.toInt() }
        val hills = Path().apply {
            moveTo(0f, h.toFloat())
            lineTo(0f, h * 0.78f)
            cubicTo(w * 0.2f, h * 0.68f, w * 0.35f, h * 0.82f, w * 0.5f, h * 0.74f)
            cubicTo(w * 0.68f, h * 0.66f, w * 0.82f, h * 0.8f, w.toFloat(), h * 0.72f)
            lineTo(w.toFloat(), h.toFloat())
            close()
        }
        c.drawPath(hills, hill)

        // Cypress-like subject accent (left)
        val tree = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF031018.toInt() }
        val cypress = Path().apply {
            moveTo(w * 0.18f, h * 0.95f)
            cubicTo(w * 0.12f, h * 0.7f, w * 0.22f, h * 0.45f, w * 0.16f, h * 0.22f)
            cubicTo(w * 0.2f, h * 0.38f, w * 0.28f, h * 0.55f, w * 0.24f, h * 0.95f)
            close()
        }
        c.drawPath(cypress, tree)

        return bmp
    }
}
