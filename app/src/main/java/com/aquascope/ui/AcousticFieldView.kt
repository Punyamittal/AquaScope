package com.aquascope.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.aquascope.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Organic acoustic field for AquaScope scanning.
 * Live amplitude and spectrum come from real capture — never invented.
 */
class AcousticFieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * resources.displayMetrics.density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val path = Path()

    private val navy = ContextCompat.getColor(context, R.color.navy_deep)
    private val cyan = ContextCompat.getColor(context, R.color.cyan)
    private val ocean = ContextCompat.getColor(context, R.color.ocean)
    private val amber = ContextCompat.getColor(context, R.color.amber)
    private val warm = ContextCompat.getColor(context, R.color.warm_white)

    private var scanning = false
    private var liveAmp = 0f
    private var spectrum: FloatArray = FloatArray(0)
    private var phase = 0f
    private var highlightAmber = false
    private var animator: ValueAnimator? = null

    fun setScanning(value: Boolean) {
        scanning = value
        if (!value) liveAmp = 0f
        invalidate()
    }

    fun setLiveAmplitude(rms: Float) {
        liveAmp = rms.coerceIn(0f, 1f)
        invalidate()
    }

    fun setSpectrum(bands: FloatArray, anomaly: Boolean = false) {
        spectrum = bands
        highlightAmber = anomaly
        scanning = false
        invalidate()
    }

    fun clearSignal() {
        spectrum = FloatArray(0)
        liveAmp = 0f
        highlightAmber = false
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 8000
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
        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(cx, cy) * 0.92f
        canvas.drawColor(navy)

        fillPaint.color = cyan
        fillPaint.alpha = 28
        canvas.drawCircle(cx, cy, maxR * (0.18f + liveAmp * 0.08f), fillPaint)
        fillPaint.alpha = 210
        fillPaint.color = if (highlightAmber) amber else cyan
        canvas.drawCircle(cx, cy, maxR * 0.045f, fillPaint)

        val rings = 8
        for (i in 0 until rings) {
            val t = (i + 1f) / (rings + 1f)
            val warp = 0.028f + if (scanning) liveAmp * 0.07f else 0.018f
            ringPaint.color = if (highlightAmber && i == rings - 1) amber else if (i % 2 == 0) cyan else ocean
            ringPaint.alpha = (36 + (1f - t) * 88).toInt()
            path.reset()
            val steps = 64
            var prevX = 0f
            var prevY = 0f
            var prev2X = 0f
            var prev2Y = 0f
            fun point(s: Int): Pair<Float, Float> {
                val a = (s / steps.toFloat()) * 2.0 * PI + phase * 2.0 * PI * 0.12
                val spec = if (spectrum.isNotEmpty()) {
                    spectrum[(s * spectrum.size / steps).coerceIn(0, spectrum.lastIndex)]
                } else 0f
                val extra = if (scanning) liveAmp * 0.10f else spec * 0.18f
                val r = maxR * (0.20f + t * 0.74f) * (1f + warp * sin(a * 2 + i * 0.4).toFloat() + extra)
                return (cx + (r * kotlin.math.cos(a)).toFloat()) to (cy + (r * kotlin.math.sin(a)).toFloat())
            }
            val first = point(0)
            path.moveTo(first.first, first.second)
            prev2X = first.first
            prev2Y = first.second
            prevX = first.first
            prevY = first.second
            for (s in 1..steps) {
                val (x, y) = point(s)
                val c1x = prevX + (x - prev2X) / 6f
                val c1y = prevY + (y - prev2Y) / 6f
                val c2x = x - (x - prevX) / 6f
                val c2y = y - (y - prevY) / 6f
                path.cubicTo(c1x, c1y, c2x, c2y, x, y)
                prev2X = prevX
                prev2Y = prevY
                prevX = x
                prevY = y
            }
            path.close()
            canvas.drawPath(path, ringPaint)
        }
    }
}
