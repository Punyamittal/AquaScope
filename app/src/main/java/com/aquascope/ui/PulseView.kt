package com.aquascope.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import com.aquascope.R

/**
 * Expanding concentric rings used while a chirp is playing / recording.
 */
class PulseView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * resources.displayMetrics.density
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var running = false

    private val brand = ContextCompat.getColor(context, R.color.cyan)
    private val brandMid = ContextCompat.getColor(context, R.color.ocean)

    fun startPulse() {
        if (running) return
        running = true
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopPulse() {
        running = false
        animator?.cancel()
        animator = null
        progress = 0f
        liveAmp = 0f
        invalidate()
    }

    /** Compatibility with ScanActivity sensing UI. */
    fun setScanning(value: Boolean) {
        if (value) startPulse() else stopPulse()
    }

    fun setLiveAmplitude(rms: Float) {
        liveAmp = rms.coerceIn(0f, 1f)
        invalidate()
    }

    fun clearSignal() {
        liveAmp = 0f
        invalidate()
    }

    private var liveAmp = 0f

    override fun onDetachedFromWindow() {
        stopPulse()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val maxR = minOf(cx, cy) * 0.92f

        fillPaint.color = brand
        fillPaint.alpha = 40
        canvas.drawCircle(cx, cy, maxR * 0.28f, fillPaint)

        fillPaint.alpha = 220
        canvas.drawCircle(cx, cy, maxR * 0.12f, fillPaint)

        for (i in 0..2) {
            val phase = (progress + i / 3f) % 1f
            paint.color = if (i % 2 == 0) brand else brandMid
            paint.alpha = ((1f - phase) * 180).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, maxR * (0.2f + phase * 0.75f), paint)
        }
    }
}
