package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.aquascope.R
import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.databinding.ActivityReportBinding
import com.aquascope.report.ReportGenerator
import com.aquascope.report.SessionReport
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityReportBinding
    private lateinit var report: SessionReport

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityReportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val json = intent.getStringExtra(EXTRA_REPORT_JSON)
        if (json.isNullOrBlank()) {
            Toast.makeText(this, "No report data", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        report = try {
            Gson().fromJson(json, SessionReport::class.java)
        } catch (_: Exception) {
            Toast.makeText(this, "Could not open report", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        bindReport(report)
        binding.btnShareReport.setOnClickListener { shareReport() }
        binding.btnCloseReport.setOnClickListener { finish() }
    }

    private fun bindReport(report: SessionReport) {
        val fmt = SimpleDateFormat("MMM d, yyyy · HH:mm", Locale.getDefault())
        binding.textReportLocation.text = report.locationLabel
        binding.textReportTime.text = fmt.format(Date(report.generatedAtMs))
        binding.textAssessment.text = ReportGenerator.overallAssessment(report)
        binding.textMaxScore.text = "${report.maxScore.toInt()}%"
        binding.textAvgScore.text = "${report.avgScore.toInt()}%"
        binding.textPointCount.text = report.points.size.toString()

        binding.textMaxScore.setTextColor(
            ContextCompat.getColor(this, colorForScore(report.maxScore))
        )

        binding.layoutPointRows.removeAllViews()
        for (point in report.points) {
            val row = layoutInflater.inflate(R.layout.item_report_point, binding.layoutPointRows, false)
            val stripe = row.findViewById<android.view.View>(R.id.pointStripe)
            val label = row.findViewById<android.widget.TextView>(R.id.textPointLabel)
            val features = row.findViewById<android.widget.TextView>(R.id.textPointFeatures)
            val score = row.findViewById<android.widget.TextView>(R.id.textPointScore)
            val status = row.findViewById<android.widget.TextView>(R.id.textPointStatus)

            val color = ContextCompat.getColor(this, colorForScore(point.anomalyScore))
            label.text = point.label
            features.text =
                "${point.features.resonanceFreqHz.toInt()} Hz · " +
                    "${"%.0f".format(point.features.decayTimeMs)} ms decay · " +
                    "${point.features.spectralCentroidHz.toInt()} Hz centroid"
            score.text = "${point.anomalyScore.toInt()}%"
            status.text = ReportGenerator.statusLabel(point.anomalyScore)
            score.setTextColor(color)
            status.setTextColor(color)
            stripe.setBackgroundColor(color)
            binding.layoutPointRows.addView(row)
        }
    }

    private fun colorForScore(score: Double): Int = when {
        score < AnomalyThresholds.GREEN_MAX -> R.color.status_ok
        score < AnomalyThresholds.YELLOW_MAX -> R.color.status_warn
        else -> R.color.status_alert
    }

    private fun shareReport() {
        val text = ReportGenerator.toShareText(report)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "AquaScope report — ${report.locationLabel}")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share_report)))
    }

    companion object {
        const val EXTRA_REPORT_JSON = "report_json"

        fun reportToJson(report: SessionReport): String = Gson().toJson(report)
    }
}
