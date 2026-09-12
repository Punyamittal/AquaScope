package com.aquascope.report

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.aquascope.baseline.AnomalyThresholds
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Professional multi-page A4 PDF for a completed AquaScope scan session.
 */
object PdfSessionReportWriter {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 42f
    private const val CONTENT_W = PAGE_W - 2 * MARGIN

    private val ink = android.graphics.Color.parseColor("#0F172A")
    private val muted = android.graphics.Color.parseColor("#64748B")
    private val faint = android.graphics.Color.parseColor("#94A3B8")
    private val line = android.graphics.Color.parseColor("#E2E8F0")
    private val brand = android.graphics.Color.parseColor("#0E7490")
    private val brandSoft = android.graphics.Color.parseColor("#ECFEFF")
    private val ok = android.graphics.Color.parseColor("#15803D")
    private val warn = android.graphics.Color.parseColor("#B45309")
    private val alert = android.graphics.Color.parseColor("#B91C1C")
    private val panel = android.graphics.Color.parseColor("#F8FAFC")

    fun write(report: SessionReport, outFile: File): File {
        outFile.parentFile?.mkdirs()
        val doc = PdfDocument()
        var state = newPage(doc, 1)

        state = drawCover(state, doc, report)
        state = drawSummary(state, doc, report)
        state = drawPointTable(state, doc, report)
        state = drawGuidelines(state, doc, report)
        drawFrameworkAndDisclaimer(state, doc, report)

        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        return outFile
    }

    private data class PageState(
        val page: PdfDocument.Page,
        val canvas: Canvas,
        var y: Float,
        val number: Int,
        val doc: PdfDocument
    )

    private fun newPage(doc: PdfDocument, number: Int): PageState {
        val info = PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, number).create()
        val page = doc.startPage(info)
        val canvas = page.canvas
        canvas.drawColor(android.graphics.Color.WHITE)
        drawHeaderBar(canvas)
        return PageState(page, canvas, MARGIN + 36f, number, doc)
    }

    private fun finish(state: PageState): PageState {
        drawFooter(state.canvas, state.number)
        state.doc.finishPage(state.page)
        return state
    }

    private fun ensureSpace(state: PageState, needed: Float, doc: PdfDocument): PageState {
        if (state.y + needed <= PAGE_H - 56f) return state
        finish(state)
        return newPage(doc, state.number + 1)
    }

    private fun drawHeaderBar(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = brand }
        canvas.drawRect(0f, 0f, PAGE_W.toFloat(), 8f, p)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = brand
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.08f
        }
        canvas.drawText("AQUASCOPE", MARGIN, 26f, title)
        val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = 9f
        }
        val right = "Acoustic Moisture Screening Report · India"
        canvas.drawText(right, PAGE_W - MARGIN - sub.measureText(right), 26f, sub)
    }

    private fun drawFooter(canvas: Canvas, pageNo: Int) {
        canvas.drawLine(MARGIN, PAGE_H - 40f, PAGE_W - MARGIN, PAGE_H - 40f, Paint().apply {
            color = line
            strokeWidth = 0.8f
        })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = faint
            textSize = 8f
        }
        canvas.drawText(
            "Confidential screening record · Not a certified structural certificate",
            MARGIN,
            PAGE_H - 24f,
            p
        )
        val page = "Page $pageNo"
        canvas.drawText(page, PAGE_W - MARGIN - p.measureText(page), PAGE_H - 24f, p)
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            typeface = Typeface.create(
                Typeface.SANS_SERIF,
                if (bold) Typeface.BOLD else Typeface.NORMAL
            )
        }

    private fun drawCover(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = state
        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.ENGLISH)
        val band = IndianComplianceGuidelines.riskBand(report)
        val bandColor = when (band) {
            IndianComplianceGuidelines.RiskBand.NORMAL -> ok
            IndianComplianceGuidelines.RiskBand.ELEVATED -> warn
            IndianComplianceGuidelines.RiskBand.ANOMALY -> alert
        }

        s.y += 8f
        s.canvas.drawText("SESSION INSPECTION REPORT", MARGIN, s.y, paint(18f, ink, true))
        s.y += 22f
        s.canvas.drawText(report.locationLabel, MARGIN, s.y, paint(14f, brand, true))
        s.y += 16f
        s.canvas.drawText(
            "Generated ${fmt.format(Date(report.generatedAtMs))}",
            MARGIN,
            s.y,
            paint(10f, muted)
        )
        s.y += 18f

        val badge = IndianComplianceGuidelines.riskTitle(band)
        val bp = paint(10f, android.graphics.Color.WHITE, true)
        val tw = bp.measureText(badge) + 24f
        val rect = RectF(MARGIN, s.y, MARGIN + tw, s.y + 22f)
        s.canvas.drawRoundRect(rect, 4f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bandColor })
        s.canvas.drawText(badge, MARGIN + 12f, s.y + 15f, bp)
        s.y += 36f

        s = ensureSpace(s, 70f, doc)
        val boxH = 58f
        val gap = 8f
        val boxW = (CONTENT_W - 2 * gap) / 3f
        val kpis = listOf(
            "POINTS" to report.points.size.toString(),
            "MAX SCORE" to "${report.maxScore.toInt()}%",
            "AVG SCORE" to "${"%.0f".format(report.avgScore)}%"
        )
        kpis.forEachIndexed { i, (label, value) ->
            val left = MARGIN + i * (boxW + gap)
            s.canvas.drawRoundRect(
                RectF(left, s.y, left + boxW, s.y + boxH),
                6f,
                6f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = panel }
            )
            s.canvas.drawText(label, left + 12f, s.y + 18f, paint(8f, faint, true))
            val vc = when (i) {
                1 -> colorForScore(report.maxScore)
                2 -> colorForScore(report.avgScore)
                else -> ink
            }
            s.canvas.drawText(value, left + 12f, s.y + 42f, paint(16f, vc, true))
        }
        s.y += boxH + 20f
        return s
    }

    private fun drawSummary(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = ensureSpace(state, 100f, doc)
        s = sectionTitle(s, "1. Executive assessment")
        s = drawParagraph(s, doc, IndianComplianceGuidelines.executiveSummary(report), paint(10f, ink))
        s.y += 8f
        s = drawParagraph(
            s,
            doc,
            "Classification thresholds: NORMAL < ${AnomalyThresholds.GREEN_MAX.toInt()}%  ·  " +
                "ELEVATED < ${AnomalyThresholds.YELLOW_MAX.toInt()}%  ·  " +
                "ANOMALY ≥ ${AnomalyThresholds.YELLOW_MAX.toInt()}%.",
            paint(9f, muted)
        )
        s.y += 14f
        return s
    }

    private fun drawPointTable(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = ensureSpace(state, 80f, doc)
        s = sectionTitle(s, "2. Point-wise findings")

        s = ensureSpace(s, 28f, doc)
        s.canvas.drawRect(MARGIN, s.y, PAGE_W - MARGIN, s.y + 22f, Paint().apply { color = brandSoft })
        val h = paint(8.5f, brand, true)
        s.canvas.drawText("#", MARGIN + 6f, s.y + 15f, h)
        s.canvas.drawText("POINT", MARGIN + 28f, s.y + 15f, h)
        s.canvas.drawText("SCORE", MARGIN + 100f, s.y + 15f, h)
        s.canvas.drawText("STATUS", MARGIN + 150f, s.y + 15f, h)
        s.canvas.drawText("RESONANCE", MARGIN + 220f, s.y + 15f, h)
        s.canvas.drawText("DECAY", MARGIN + 310f, s.y + 15f, h)
        s.canvas.drawText("CENTROID", MARGIN + 370f, s.y + 15f, h)
        s.canvas.drawText("TIME", MARGIN + 450f, s.y + 15f, h)
        s.y += 24f

        val fmt = SimpleDateFormat("HH:mm:ss", Locale.ENGLISH)
        val body = paint(8.5f, ink)
        report.points.forEachIndexed { index, p ->
            s = ensureSpace(s, 20f, doc)
            if (index % 2 == 1) {
                s.canvas.drawRect(
                    MARGIN,
                    s.y - 4f,
                    PAGE_W - MARGIN,
                    s.y + 14f,
                    Paint().apply { color = panel }
                )
            }
            val sc = colorForScore(p.anomalyScore)
            s.canvas.drawText("${index + 1}", MARGIN + 6f, s.y + 8f, body)
            s.canvas.drawText(p.label.take(8), MARGIN + 28f, s.y + 8f, body)
            s.canvas.drawText("${p.anomalyScore.toInt()}%", MARGIN + 100f, s.y + 8f, paint(8.5f, sc, true))
            s.canvas.drawText(
                ReportGenerator.statusLabel(p.anomalyScore),
                MARGIN + 150f,
                s.y + 8f,
                paint(8.5f, sc, true)
            )
            s.canvas.drawText("${p.features.resonanceFreqHz.toInt()} Hz", MARGIN + 220f, s.y + 8f, body)
            s.canvas.drawText("${"%.0f".format(p.features.decayTimeMs)} ms", MARGIN + 310f, s.y + 8f, body)
            s.canvas.drawText("${p.features.spectralCentroidHz.toInt()} Hz", MARGIN + 370f, s.y + 8f, body)
            s.canvas.drawText(fmt.format(Date(p.timestampMs)), MARGIN + 450f, s.y + 8f, paint(8.5f, muted))
            s.y += 18f
        }
        if (report.notes.isNotBlank()) {
            s.y += 8f
            s = sectionTitle(s, "Session notes")
            s = drawParagraph(s, doc, report.notes, paint(10f, ink))
        }
        s.y += 14f
        return s
    }

    private fun drawGuidelines(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = ensureSpace(state, 60f, doc)
        s = sectionTitle(s, "3. Recommended actions & Indian legal guidelines")
        s = drawParagraph(
            s,
            doc,
            "Guidance is matched to this session's overall risk band (" +
                IndianComplianceGuidelines.riskTitle(IndianComplianceGuidelines.riskBand(report)) +
                ").",
            paint(9f, muted)
        )
        s.y += 8f
        IndianComplianceGuidelines.actionGuidelines(report).forEachIndexed { i, bullet ->
            s = drawBullet(s, doc, "${i + 1}.", bullet)
        }
        s.y += 12f
        return s
    }

    private fun drawFrameworkAndDisclaimer(state: PageState, doc: PdfDocument, report: SessionReport): PageState {
        var s = ensureSpace(state, 80f, doc)
        s = sectionTitle(s, "4. Legal & standards framework referenced")
        IndianComplianceGuidelines.legalFrameworkNotes().forEach { note ->
            s = drawBullet(s, doc, "•", note)
        }
        s.y += 14f
        s = ensureSpace(s, 90f, doc)
        s = sectionTitle(s, "5. Disclaimer")
        s = drawParagraph(s, doc, IndianComplianceGuidelines.disclaimer(), paint(8.5f, muted))
        s.y += 16f
        s = drawParagraph(
            s,
            doc,
            "Report ID location: ${report.locationLabel} · Points: ${report.points.size} · " +
                "Anomaly points: ${report.anomalyCount} · Elevated points: ${report.elevatedCount}",
            paint(8f, faint)
        )
        return finish(s)
    }

    private fun sectionTitle(state: PageState, title: String): PageState {
        state.canvas.drawText(title.uppercase(Locale.ENGLISH), MARGIN, state.y, paint(11f, ink, true))
        state.y += 6f
        state.canvas.drawLine(
            MARGIN,
            state.y,
            PAGE_W - MARGIN,
            state.y,
            Paint().apply {
                color = line
                strokeWidth = 1f
            }
        )
        state.y += 16f
        return state
    }

    private fun drawParagraph(state: PageState, doc: PdfDocument, text: String, paint: Paint): PageState {
        var s = state
        val lines = wrap(text, paint, CONTENT_W)
        for (line in lines) {
            s = ensureSpace(s, paint.textSize + 6f, doc)
            s.canvas.drawText(line, MARGIN, s.y, paint)
            s.y += paint.textSize + 4f
        }
        return s
    }

    private fun drawBullet(state: PageState, doc: PdfDocument, marker: String, text: String): PageState {
        var s = state
        val body = paint(9.5f, ink)
        val markerPaint = paint(9.5f, brand, true)
        val indent = 18f
        val lines = wrap(text, body, CONTENT_W - indent)
        s = ensureSpace(s, body.textSize + 8f, doc)
        s.canvas.drawText(marker, MARGIN, s.y, markerPaint)
        lines.forEachIndexed { idx, line ->
            if (idx > 0) s = ensureSpace(s, body.textSize + 6f, doc)
            s.canvas.drawText(line, MARGIN + indent, s.y, body)
            s.y += body.textSize + 3.5f
        }
        s.y += 6f
        return s
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(Regex("\\s+"))
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (w in words) {
            val trial = if (current.isEmpty()) w else "$current $w"
            if (paint.measureText(trial) <= maxWidth) {
                current = StringBuilder(trial)
            } else {
                if (current.isNotEmpty()) lines += current.toString()
                current = StringBuilder(w)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    private fun colorForScore(score: Double): Int = when {
        score < AnomalyThresholds.GREEN_MAX -> ok
        score < AnomalyThresholds.YELLOW_MAX -> warn
        else -> alert
    }
}
