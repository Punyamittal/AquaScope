package com.aquascope.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aquascope.R
import kotlin.math.max

/**
 * Draws a real downsampled waveform or magnitude bars. Empty until samples are supplied.
 */
class WaveformStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.1f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
    }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val cyanBright = ContextCompat.getColor(context, R.color.cyan_bright)
    private val cyanBloom = ContextCompat.getColor(context, R.color.cyan_bloom)
    private val paper = ContextCompat.getColor(context, R.color.paper_mist)
    private val faint = ContextCompat.getColor(context, R.color.hairline_dark)

    private var samples: FloatArray = FloatArray(0)
    private var asBars = false

    fun setWaveform(values: FloatArray) {
        samples = values
        asBars = false
        invalidate()
    }

    fun setBars(values: FloatArray) {
        samples = values
        asBars = true
        invalidate()
    }

    fun clear() {
        samples = FloatArray(0)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        paint.color = faint
        canvas.drawLine(0f, h / 2f, w, h / 2f, paint)
        if (samples.isEmpty()) return

        if (asBars) {
            val n = samples.size
            val gap = 2f * resources.displayMetrics.density
            val bw = max(2f, (w - gap * (n - 1)) / n)
            barPaint.color = cyanBright
            barPaint.alpha = 235
            samples.forEachIndexed { i, v ->
                val bh = (v.coerceIn(0f, 1f) * (h * 0.92f))
                val x = i * (bw + gap)
                canvas.drawRoundRect(x, h - bh, x + bw, h, 3f, 3f, barPaint)
            }
        } else {
            val mid = h / 2f
            val n = samples.size
            var prevX = 0f
            var prevY = mid
            glowPaint.color = cyanBloom
            glowPaint.alpha = 90
            paint.color = cyanBright
            paint.alpha = 230
            corePaint.color = paper
            corePaint.alpha = 180
            samples.forEachIndexed { i, v ->
                val x = if (n == 1) 0f else i * w / (n - 1)
                val y = mid - v.coerceIn(-1f, 1f) * (h * 0.42f)
                if (i > 0) {
                    canvas.drawLine(prevX, prevY, x, y, glowPaint)
                    canvas.drawLine(prevX, prevY, x, y, paint)
                    canvas.drawLine(prevX, prevY, x, y, corePaint)
                }
                prevX = x
                prevY = y
            }
        }
    }
}
