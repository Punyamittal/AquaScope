package com.aquascope.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.aquascope.R
import kotlin.math.max

/** Two horizontal bars: baseline vs current, from stored feature magnitudes. */
class BaselineCompareView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11f * resources.displayMetrics.scaledDensity
        letterSpacing = 0.12f
    }

    private val navyMid = ContextCompat.getColor(context, R.color.navy_mid)
    private val cyan = ContextCompat.getColor(context, R.color.cyan)
    private val amber = ContextCompat.getColor(context, R.color.amber)
    private val muted = ContextCompat.getColor(context, R.color.warm_muted)

    private var baseline = 0f
    private var current = 0f
    private val rect = RectF()

    fun setComparison(baselineNorm: Float, currentNorm: Float) {
        baseline = baselineNorm.coerceIn(0f, 1f)
        current = currentNorm.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (72 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 4f * resources.displayMetrics.density
        val barH = 10f * resources.displayMetrics.density
        val labelH = text.textSize
        val w = width.toFloat()
        text.color = muted
        canvas.drawText("BASELINE", 0f, labelH, text)
        track.color = navyMid
        rect.set(0f, labelH + pad, w, labelH + pad + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, track)
        fill.color = cyan
        rect.right = max(barH, w * baseline)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, fill)

        val y2 = labelH + pad + barH + pad * 3
        canvas.drawText("CURRENT", 0f, y2 + labelH, text)
        rect.set(0f, y2 + labelH + pad, w, y2 + labelH + pad + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, track)
        fill.color = if (current > baseline * 1.08f) amber else cyan
        rect.right = max(barH, w * current)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, fill)
    }
}
