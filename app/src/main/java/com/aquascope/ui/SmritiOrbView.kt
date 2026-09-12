package com.aquascope.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.aquascope.R
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Soft cyan “memory orb” for Ask SMRITI — visual twin of Monster Halo idle/recall blue.
 */
class SmritiOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // blur mask
    }

    private val cyan = ContextCompat.getColor(context, R.color.cyan)
    private val blue = 0xFF2E6FA8.toInt()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * resources.displayMetrics.density
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var phase = 0f
    private var pulse = 0.55f
    private var animator: ValueAnimator? = null
    private var active = false

    fun setActive(listening: Boolean) {
        active = listening
        invalidate()
    }

    fun setPulse(amount: Float) {
        pulse = amount.coerceIn(0.25f, 1f)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 9000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    phase = it.animatedFraction
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 4f || h < 4f) return
        val cx = w * 0.5f
        val cy = h * 0.52f
        val r = min(w, h) * 0.28f * (0.92f + 0.08f * pulse)

        // Soft outer glow
        val glowR = r * (2.4f + if (active) 0.4f else 0f)
        glowPaint.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(
                withAlpha(cyan, (90 * pulse).toInt()),
                withAlpha(blue, 40),
                0x00000000
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, glowR, glowPaint)
        glowPaint.shader = null

        // Wispy strands
        strandPaint.maskFilter = BlurMaskFilter(
            1.6f * resources.displayMetrics.density,
            BlurMaskFilter.Blur.NORMAL
        )
        val strands = 14
        for (i in 0 until strands) {
            val t = (phase + i / strands.toFloat()) % 1f
            val a0 = t * Math.PI * 2 + i * 0.37
            val a1 = a0 + 1.1 + 0.35 * sin(phase * Math.PI * 2 + i)
            val wobble = 0.72f + 0.28f * sin(phase * 4f * Math.PI + i).toFloat()
            val rr = r * wobble
            strandPaint.color = withAlpha(
                if (i % 2 == 0) cyan else blue,
                (110 + 80 * pulse).toInt().coerceAtMost(220)
            )
            strandPaint.strokeWidth =
                (1.4f + (i % 3) * 0.55f) * resources.displayMetrics.density
            val path = android.graphics.Path()
            val steps = 18
            for (s in 0..steps) {
                val u = s / steps.toFloat()
                val ang = a0 + (a1 - a0) * u
                val rad = rr * (0.35f + 0.65f * sin(u * Math.PI).toFloat())
                val x = cx + cos(ang).toFloat() * rad
                val y = cy + sin(ang).toFloat() * rad * 0.92f
                if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, strandPaint)
        }
        strandPaint.maskFilter = null

        // Bright core
        corePaint.shader = RadialGradient(
            cx, cy, r * 0.55f,
            intArrayOf(0xEEFFFFFF.toInt(), withAlpha(cyan, 180), 0x00000000),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 0.55f, corePaint)
        corePaint.shader = null
    }

    private fun withAlpha(argb: Int, alpha: Int): Int =
        (alpha.coerceIn(0, 255) shl 24) or (argb and 0x00FFFFFF)
}
