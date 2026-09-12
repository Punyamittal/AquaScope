package com.aquascope.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ask hero — night sky with twinkling stars and occasional shooting stars.
 * Hides after the first chat (same contract as the previous orb).
 */
class ShootingStarsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val twinkles = ArrayList<Twinkle>()
    private val meteors = ArrayList<Meteor>()
    private var animator: ValueAnimator? = null
    private var lastNs = 0L
    private var spawnAcc = 0f
    private var pulse = 0.55f
    private var active = false
    private var paused = false
    private var seeded = false
    private val rnd = Random(42)

    fun setActive(listening: Boolean) {
        active = listening
        invalidate()
    }

    fun setPulse(amount: Float) {
        pulse = amount.coerceIn(0.25f, 1f)
        invalidate()
    }

    fun setPaused(paused: Boolean) {
        this.paused = paused
        if (!paused) invalidate()
    }

    /** No-op kept so Ask activity can call the same lifecycle hooks. */
    fun onResume() {
        ensureAnimator()
    }

    fun onPause() {
        animator?.cancel()
        animator = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) seedSky(w, h)
    }

    private fun seedSky(w: Int, h: Int) {
        twinkles.clear()
        meteors.clear()
        val count = 55 + (w * h / 18000).coerceIn(20, 80)
        repeat(count) {
            twinkles += Twinkle(
                x = rnd.nextFloat() * w,
                y = rnd.nextFloat() * h,
                r = 0.6f + rnd.nextFloat() * 1.8f,
                phase = rnd.nextFloat() * Math.PI.toFloat() * 2f,
                speed = 0.6f + rnd.nextFloat() * 1.6f,
                bright = 0.35f + rnd.nextFloat() * 0.65f
            )
        }
        seeded = true
        lastNs = System.nanoTime()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureAnimator()
    }

    override fun onDetachedFromWindow() {
        onPause()
        super.onDetachedFromWindow()
    }

    private fun ensureAnimator() {
        if (animator != null) return
        lastNs = System.nanoTime()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                if (!paused) {
                    tick()
                    invalidate()
                }
            }
            start()
        }
    }

    private fun tick() {
        val now = System.nanoTime()
        val dt = ((now - lastNs) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        lastNs = now
        if (!seeded || width < 2 || height < 2) return

        for (s in twinkles) {
            s.phase += dt * s.speed
        }

        val spawnRate = if (active) 1.8f else 0.85f
        spawnAcc += dt * spawnRate * (0.7f + pulse * 0.5f)
        while (spawnAcc >= 1f && meteors.size < 4) {
            spawnAcc -= 1f
            spawnMeteor()
        }

        val it = meteors.iterator()
        while (it.hasNext()) {
            val m = it.next()
            m.life += dt / m.duration
            m.x += m.vx * dt
            m.y += m.vy * dt
            if (m.life >= 1f || m.x < -80f || m.y > height + 80f || m.x > width + 80f) {
                it.remove()
            }
        }
    }

    private fun spawnMeteor() {
        val fromTop = rnd.nextBoolean()
        val x0 = if (fromTop) rnd.nextFloat() * width * 0.85f else -20f
        val y0 = if (fromTop) -10f else rnd.nextFloat() * height * 0.45f
        val speed = (280f + rnd.nextFloat() * 420f) * (0.85f + pulse * 0.35f)
        val angle = Math.toRadians(28.0 + rnd.nextDouble() * 28.0)
        meteors += Meteor(
            x = x0,
            y = y0,
            vx = (cos(angle) * speed).toFloat(),
            vy = (sin(angle) * speed).toFloat(),
            life = 0f,
            duration = 0.55f + rnd.nextFloat() * 0.75f,
            length = 48f + rnd.nextFloat() * 90f,
            thickness = 1.6f + rnd.nextFloat() * 2.2f
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 2f || h < 2f) return

        // Soft night wash (transparent over Ask bg)
        canvas.drawColor(0x00000000)

        for (s in twinkles) {
            val tw = 0.45f + 0.55f * (0.5f + 0.5f * sin(s.phase))
            val a = (s.bright * tw * 255f * (0.75f + pulse * 0.35f)).toInt().coerceIn(30, 255)
            starPaint.color = withAlpha(0xFFE8F0FF.toInt(), a)
            canvas.drawCircle(s.x, s.y, s.r * (0.85f + 0.25f * tw), starPaint)
            if (s.bright > 0.75f) {
                starPaint.color = withAlpha(0xFF9FD0FF.toInt(), (a * 0.25f).toInt())
                canvas.drawCircle(s.x, s.y, s.r * 2.8f, starPaint)
            }
        }

        for (m in meteors) {
            val fade = when {
                m.life < 0.12f -> m.life / 0.12f
                m.life > 0.7f -> (1f - m.life) / 0.3f
                else -> 1f
            }.coerceIn(0f, 1f)
            val dx = -m.vx
            val dy = -m.vy
            val len = hypot(dx, dy).coerceAtLeast(1f)
            val tx = dx / len * m.length
            val ty = dy / len * m.length
            val x1 = m.x
            val y1 = m.y
            val x0 = m.x + tx
            val y0 = m.y + ty

            trailPaint.strokeWidth = m.thickness * (0.7f + pulse * 0.4f)
            trailPaint.shader = LinearGradient(
                x0, y0, x1, y1,
                intArrayOf(
                    withAlpha(0x00A8D8FF, 0),
                    withAlpha(0xFFB8E8FF.toInt(), (90 * fade).toInt()),
                    withAlpha(0xFFFFFFFF.toInt(), (220 * fade).toInt())
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(x0, y0, x1, y1, trailPaint)
            trailPaint.shader = null

            headPaint.shader = RadialGradient(
                x1, y1, m.thickness * 4f,
                intArrayOf(
                    withAlpha(0xFFFFFFFF.toInt(), (240 * fade).toInt()),
                    withAlpha(0xFF7EC8FF.toInt(), (100 * fade).toInt()),
                    0x00000000
                ),
                floatArrayOf(0f, 0.35f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(x1, y1, m.thickness * 3.2f, headPaint)
            headPaint.shader = null
        }
    }

    private fun hypot(a: Float, b: Float): Float =
        kotlin.math.sqrt(a * a + b * b)

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)

    private class Twinkle(
        var x: Float,
        var y: Float,
        val r: Float,
        var phase: Float,
        val speed: Float,
        val bright: Float
    )

    private class Meteor(
        var x: Float,
        var y: Float,
        val vx: Float,
        val vy: Float,
        var life: Float,
        val duration: Float,
        val length: Float,
        val thickness: Float
    )
}
