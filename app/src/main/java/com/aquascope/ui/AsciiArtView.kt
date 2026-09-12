package com.aquascope.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.aquascope.ui.ascii.AsciiArtParams
import com.aquascope.ui.ascii.AsciiArtRenderer
import com.aquascope.ui.ascii.StarryNightSource

/**
 * Ask hero visual — 21st.dev “starry nights” mosaic ASCII pipeline on Canvas.
 * Keeps [setActive]/[setPulse] so AskSmritiActivity can drive recall energy.
 */
class AsciiArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // bloom blur mask
    }

    private val renderer = AsciiArtRenderer(AsciiArtParams.starryNights())
    private var animator: ValueAnimator? = null
    private var time = 0f
    private var pulse = 0.55f
    private var active = false
    private var paused = false
    private var sourceW = 0
    private var sourceH = 0

    fun setActive(listening: Boolean) {
        active = listening
        invalidate()
    }

    fun setPulse(amount: Float) {
        pulse = amount.coerceIn(0.25f, 1f)
        invalidate()
    }

    /** Pause redraws during heavy LLM work to reduce OOM risk. */
    fun setPaused(paused: Boolean) {
        this.paused = paused
        if (!paused) invalidate()
    }

    fun setParams(params: AsciiArtParams) {
        renderer.params = params
        sourceW = 0
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        ensureSource(w, h)
        renderer.prepare(w, h)
    }

    private fun ensureSource(w: Int, h: Int) {
        if (sourceW == w && sourceH == h) return
        sourceW = w
        sourceH = h
        // Generate at 2× for sharper cell sampling
        val bmp = StarryNightSource.create((w * 1.5f).toInt(), (h * 1.5f).toInt())
        renderer.setSource(bmp)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animator == null) {
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 12_000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener {
                    if (!paused) {
                        time = (time + 0.016f) % 1000f
                        invalidate()
                    }
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
        if (width < 8 || height < 8) return
        ensureSource(width, height)
        renderer.prepare(width, height)
        renderer.render(canvas, time, pulse, active)
    }
}
