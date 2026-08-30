package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.audio.AudioEngine
import com.aquascope.baseline.AnomalyScorer
import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.data.ScanRecord
import com.aquascope.data.ScanRepository
import com.aquascope.data.SerializableFeatures
import com.aquascope.dsp.AcousticFeatures
import com.aquascope.dsp.Deconvolution
import com.aquascope.dsp.FeatureExtractor
import com.aquascope.databinding.ActivityScanBinding
import com.aquascope.report.ReportPoint
import com.aquascope.report.SessionReport
import kotlinx.coroutines.launch

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var repo: ScanRepository
    private lateinit var audioEngine: AudioEngine
    private var locationId: String = ""
    private var locationLabel: String = ""

    private val sessionPoints = mutableListOf<ReportPoint>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ScanRepository(this)
        audioEngine = AudioEngine(this)
        locationId = intent.getStringExtra("location_id") ?: run { finish(); return }

        val location = repo.getLocation(locationId)
        if (location == null) {
            Toast.makeText(this, "Location not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        locationLabel = location.label
        binding.textLocationLabel.text = location.label

        binding.btnStartScan.setOnClickListener { runScan() }
        binding.btnAddPoint.setOnClickListener { addMultiPoint() }
        binding.btnViewReport.setOnClickListener { openSessionReport() }

        showIdle()
    }

    private fun showIdle() {
        binding.groupIdle.visibility = View.VISIBLE
        binding.groupProgress.visibility = View.GONE
        binding.groupResult.visibility = View.GONE
        binding.pulseView.stopPulse()
    }

    private fun showProgress() {
        binding.groupIdle.visibility = View.GONE
        binding.groupProgress.visibility = View.VISIBLE
        binding.groupResult.visibility = View.GONE
        binding.pulseView.startPulse()
    }

    private fun showResult() {
        binding.groupIdle.visibility = View.GONE
        binding.groupProgress.visibility = View.GONE
        binding.groupResult.visibility = View.VISIBLE
        binding.pulseView.stopPulse()
    }

    private fun runScan() {
        showProgress()
        binding.textStatus.text = getString(R.string.hold_instruction)

        lifecycleScope.launch {
            try {
                binding.textStatus.text = getString(R.string.scanning)
                val capture = audioEngine.playAndRecord()

                binding.textStatus.text = getString(R.string.analyzing)
                val impulseResponse = Deconvolution.deconvolve(capture.recorded, capture.reference)
                val features = FeatureExtractor.extract(impulseResponse, capture.sampleRate)

                val location = repo.getLocation(locationId)
                if (location == null) {
                    showIdle()
                    Toast.makeText(this@ScanActivity, "Location was deleted", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                if (location.baselineFeatures.isEmpty()) {
                    showResult()
                    binding.textScore.text = "—"
                    binding.textScoreLabel.text = "Capture a dry reference first"
                    binding.textScoreState.text = "BASELINE NEEDED"
                    binding.textScoreState.setTextColor(
                        ContextCompat.getColor(this@ScanActivity, R.color.brand_mid)
                    )
                    binding.textScore.setTextColor(
                        ContextCompat.getColor(this@ScanActivity, R.color.ink)
                    )
                    binding.cardResult.setBackgroundResource(R.drawable.bg_surface_panel)
                    binding.btnSetBaseline.visibility = View.VISIBLE
                    binding.btnViewReport.visibility = View.GONE
                    binding.btnSetBaseline.text = getString(R.string.set_baseline)
                    binding.btnSetBaseline.setOnClickListener {
                        location.baselineFeatures.add(SerializableFeatures.from(features))
                        repo.updateLocation(location)
                        binding.btnSetBaseline.visibility = View.GONE
                        binding.textScoreLabel.text = "Baseline saved. Scan again to compare."
                        binding.textScoreState.text = "READY"
                        Toast.makeText(this@ScanActivity, "Baseline saved", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val baselineFeatures = location.baselineFeatures.map { it.toAcousticFeatures() }
                    val score = AnomalyScorer.score(features, baselineFeatures)

                    location.scanHistory.add(
                        ScanRecord(
                            features = SerializableFeatures.from(features),
                            anomalyScore = score
                        )
                    )
                    repo.updateLocation(location)

                    val point = ReportPoint(
                        label = "P${sessionPoints.size + 1}",
                        anomalyScore = score,
                        features = features
                    )
                    sessionPoints.add(point)

                    showResult()
                    displayScore(score)
                    binding.btnSetBaseline.visibility = View.VISIBLE
                    binding.btnSetBaseline.text = "Add to baseline"
                    binding.btnSetBaseline.setOnClickListener {
                        confirmAddToBaseline(location.id, features)
                    }
                    binding.btnViewReport.visibility = View.VISIBLE
                    updateMultiPointCards()
                }
            } catch (e: Exception) {
                showIdle()
                Toast.makeText(this@ScanActivity, "Scan failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openSessionReport() {
        if (sessionPoints.isEmpty()) {
            Toast.makeText(this, "Scan at least one point first", Toast.LENGTH_SHORT).show()
            return
        }
        val report = SessionReport(
            locationLabel = locationLabel,
            points = sessionPoints.toList()
        )
        startActivity(Intent(this, ReportActivity::class.java).apply {
            putExtra(ReportActivity.EXTRA_REPORT_JSON, ReportActivity.reportToJson(report))
        })
    }

    private fun confirmAddToBaseline(locId: String, features: AcousticFeatures) {
        AlertDialog.Builder(this)
            .setTitle("Add to baseline?")
            .setMessage(
                "Only add this scan if the surface is dry and known-good. " +
                    "Adding a wet or anomalous reading will weaken future detection."
            )
            .setPositiveButton("Add") { _, _ ->
                val location = repo.getLocation(locId) ?: return@setPositiveButton
                location.baselineFeatures.add(SerializableFeatures.from(features))
                repo.updateLocation(location)
                Toast.makeText(this, "Added to baseline calibration", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun displayScore(score: Double) {
        binding.textScore.text = "${score.toInt()}%"
        binding.textScoreLabel.text = getString(R.string.anomaly_score)

        val (state, color, bg) = when {
            score < AnomalyThresholds.GREEN_MAX ->
                Triple("NORMAL", R.color.status_ok, R.drawable.bg_result_ok)
            score < AnomalyThresholds.YELLOW_MAX ->
                Triple("ELEVATED", R.color.status_warn, R.drawable.bg_result_warn)
            else ->
                Triple("ANOMALY", R.color.status_alert, R.drawable.bg_result_alert)
        }

        binding.textScoreState.text = state
        binding.textScoreState.setTextColor(ContextCompat.getColor(this, color))
        binding.textScore.setTextColor(ContextCompat.getColor(this, color))
        binding.cardResult.setBackgroundResource(bg)
    }

    private fun addMultiPoint() {
        showIdle()
        binding.textInstruction.text = "Move to the next point on the wall, then start again."
    }

    private fun updateMultiPointCards() {
        if (sessionPoints.isEmpty()) {
            binding.scrollMultiPoint.visibility = View.GONE
            return
        }
        binding.scrollMultiPoint.visibility = View.VISIBLE
        binding.layoutMultiPoint.removeAllViews()

        for (point in sessionPoints) {
            val card = layoutInflater.inflate(R.layout.item_result_card, binding.layoutMultiPoint, false)
            val tv = card.findViewById<android.widget.TextView>(R.id.textCardLabel)
            val ts = card.findViewById<android.widget.TextView>(R.id.textCardScore)
            tv.text = point.label
            ts.text = "${point.anomalyScore.toInt()}%"
            val color = when {
                point.anomalyScore < AnomalyThresholds.GREEN_MAX -> R.color.status_ok
                point.anomalyScore < AnomalyThresholds.YELLOW_MAX -> R.color.status_warn
                else -> R.color.status_alert
            }
            ts.setTextColor(ContextCompat.getColor(this, color))
            binding.layoutMultiPoint.addView(card)
        }
    }
}
