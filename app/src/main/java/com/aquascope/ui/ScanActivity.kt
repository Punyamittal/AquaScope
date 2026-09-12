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
import com.aquascope.halo.SmritiLightMapper
import com.aquascope.halo.SmritiLightState
import com.aquascope.report.ReportPoint
import com.aquascope.report.SessionReport
import com.aquascope.smriti.SmritiCore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private lateinit var repo: ScanRepository
    private lateinit var audioEngine: AudioEngine
    private lateinit var smriti: SmritiCore
    private lateinit var haloBind: HaloBinding
    private var locationId: String = ""
    private var locationLabel: String = ""
    private var lastSmritiEventId: String? = null

    /** Latest compare scan waiting for dry/moist teach label. */
    private var pendingTeach: AcousticFeatures? = null
    private var pendingLabeled = false

    private val sessionPoints = mutableListOf<ReportPoint>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = ScanRepository(this)
        audioEngine = AudioEngine(this)
        smriti = SmritiCore.get(this)
        haloBind = HaloBinding(this, binding.haloIndicator)
        locationId = intent.getStringExtra("location_id") ?: run { finish(); return }

        val location = repo.getLocation(locationId)
        if (location == null) {
            Toast.makeText(this, "Location not found", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        locationLabel = location.label
        binding.textLocationLabel.text = location.label

        binding.btnStartScan.setOnClickListener {
            commitPendingAsMoistIfNeeded()
            runScan()
        }
        binding.btnAddPoint.setOnClickListener {
            commitPendingAsMoistIfNeeded()
            addMultiPoint()
        }
        binding.btnViewReport.setOnClickListener { openSessionReport() }
        binding.btnViewEvidence.setOnClickListener {
            val eid = lastSmritiEventId
            if (eid != null) {
                startActivity(Intent(this, EvidenceActivity::class.java).putExtra("event_id", eid))
            }
        }

        showIdle()
    }

    override fun onResume() {
        super.onResume()
        haloBind.start()
        if (binding.groupIdle.visibility == View.VISIBLE) {
            haloBind.controller().setState(SmritiLightState.NORMAL)
        }
    }

    override fun onPause() {
        commitPendingAsMoistIfNeeded()
        haloBind.stop()
        super.onPause()
    }

    private fun showIdle() {
        binding.groupIdle.visibility = View.VISIBLE
        binding.groupProgress.visibility = View.GONE
        binding.groupResult.visibility = View.GONE
        binding.pulseView.setScanning(false)
        binding.pulseView.clearSignal()
        binding.liveWaveform.clear()
        binding.acousticFieldIdle.clearSignal()
        binding.btnMarkMoist.visibility = View.GONE
        if (::haloBind.isInitialized) {
            haloBind.controller().setState(SmritiLightState.NORMAL)
        }
    }

    private fun showProgress() {
        binding.groupIdle.visibility = View.GONE
        binding.groupProgress.visibility = View.VISIBLE
        binding.groupResult.visibility = View.GONE
        binding.pulseView.clearSignal()
        binding.pulseView.setScanning(true)
        binding.liveWaveform.clear()
        binding.btnMarkMoist.visibility = View.GONE
    }

    private fun showResult() {
        binding.groupIdle.visibility = View.GONE
        binding.groupProgress.visibility = View.GONE
        binding.groupResult.visibility = View.VISIBLE
        binding.pulseView.setScanning(false)
    }

    private fun runScan() {
        showProgress()
        binding.textStatus.text = getString(R.string.scan_initializing)
        haloBind.controller().setState(SmritiLightState.SCANNING)

        lifecycleScope.launch {
            try {
                binding.textStatus.text = getString(R.string.scan_calibrating)
                delay(350)
                binding.textStatus.text = getString(R.string.scan_acoustic_active)
                haloBind.controller().setState(SmritiLightState.SCANNING)

                val capture = audioEngine.playAndRecord { rms ->
                    runOnUiThread {
                        binding.pulseView.setLiveAmplitude((rms * 4f).coerceIn(0f, 1f))
                    }
                }
                binding.liveWaveform.setWaveform(SignalPreview.waveform(capture.recorded))

                binding.textStatus.text = getString(R.string.scan_processing)
                haloBind.controller().setState(SmritiLightState.PROCESSING)
                delay(200)

                val impulseResponse = Deconvolution.deconvolve(capture.recorded, capture.reference)
                val features = FeatureExtractor.extract(impulseResponse, capture.sampleRate)
                val spectrum = SignalPreview.spectrum(impulseResponse)
                val wave = SignalPreview.waveform(impulseResponse)

                val location = repo.getLocation(locationId)
                if (location == null) {
                    showIdle()
                    Toast.makeText(this@ScanActivity, "Location was deleted", Toast.LENGTH_LONG).show()
                    finish()
                    return@launch
                }

                if (location.baselineFeatures.isEmpty()) {
                    pendingTeach = null
                    pendingLabeled = true
                    showResult()
                    haloBind.controller().setStateBrief(
                        SmritiLightState.NEW_MEMORY,
                        SmritiLightState.NORMAL,
                        900L
                    )
                    binding.textScore.text = "—"
                    binding.textScoreLabel.text = "Capture a dry reference first"
                    binding.textScoreState.text = "BASELINE NEEDED"
                    binding.textScanMeta.text =
                        "No dry baseline yet. Save only dry scans as baseline — moist scans teach the detector later."
                    binding.acousticFieldResult.setSpectrum(spectrum, anomaly = false)
                    binding.resultWaveform.setWaveform(wave)
                    binding.textScoreState.setTextColor(
                        ContextCompat.getColor(this@ScanActivity, R.color.cyan)
                    )
                    binding.textScore.setTextColor(
                        ContextCompat.getColor(this@ScanActivity, R.color.ink)
                    )
                    binding.cardResult.setBackgroundResource(R.drawable.bg_surface_panel)
                    binding.btnSetBaseline.visibility = View.VISIBLE
                    binding.btnMarkMoist.visibility = View.GONE
                    binding.btnViewReport.visibility = View.GONE
                    binding.btnViewEvidence.visibility = View.GONE
                    binding.btnSetBaseline.text = getString(R.string.set_baseline)
                    binding.btnSetBaseline.setOnClickListener {
                        teachDry(features, firstBaseline = true)
                    }
                } else {
                    val dry = location.baselineFeatures.map { it.toAcousticFeatures() }
                    val moist = location.moistFeatures.map { it.toAcousticFeatures() }
                    val stage = location.scoreStage.coerceAtLeast(0)
                    val score = AnomalyScorer.score(
                        scan = features,
                        drySamples = dry,
                        moistSamples = moist,
                        priorCompareCount = stage
                    )

                    location.scoreStage = stage + 1
                    location.scanHistory.add(
                        ScanRecord(
                            features = SerializableFeatures.from(features),
                            anomalyScore = score
                        )
                    )
                    repo.updateLocation(location)

                    val mem = smriti.rememberScan(
                        locationId = location.id,
                        locationLabel = location.label,
                        features = features,
                        anomalyScore = score,
                        hasBaseline = true
                    )
                    lastSmritiEventId = mem.id

                    val point = ReportPoint(
                        label = "P${sessionPoints.size + 1}",
                        anomalyScore = score,
                        features = features
                    )
                    sessionPoints.add(point)

                    pendingTeach = features
                    pendingLabeled = false

                    showResult()
                    displayScore(score)
                    binding.acousticFieldResult.setSpectrum(
                        spectrum,
                        anomaly = score >= AnomalyThresholds.GREEN_MAX
                    )
                    binding.resultWaveform.setWaveform(wave)
                    binding.textScanMeta.text = buildString {
                        append(scanMeta(mem))
                        append("\nLearning    ${dry.size} dry · ${moist.size} moist")
                        when (stage) {
                            0 -> append("\nStage    1st compare fallback (8–24%)")
                            1 -> append("\nStage    2nd compare fallback (84–98%)")
                            else -> append("\nStage    normal scoring")
                        }
                        append("\nTeach: dry → baseline, moist → detector (or leave unmarked = moist)")
                    }
                    mem.deviationVsYesterday?.let { delta ->
                        binding.textScoreLabel.text =
                            getString(R.string.baseline_deviation) + "  ·  ${"%+.0f".format(delta)} vs last 24h"
                    }
                    binding.btnSetBaseline.visibility = View.VISIBLE
                    binding.btnSetBaseline.text = getString(R.string.add_to_baseline_dry)
                    binding.btnSetBaseline.setOnClickListener {
                        confirmAddToBaseline(features)
                    }
                    binding.btnMarkMoist.visibility = View.VISIBLE
                    binding.btnMarkMoist.setOnClickListener {
                        teachMoist(features)
                    }
                    binding.btnViewReport.visibility = View.VISIBLE
                    binding.btnViewReport.text = getString(R.string.view_memory)
                    binding.btnViewReport.setOnClickListener {
                        val eid = lastSmritiEventId
                        if (eid != null) {
                            startActivity(
                                Intent(this@ScanActivity, MemoryDetailActivity::class.java)
                                    .putExtra("event_id", eid)
                            )
                        } else {
                            openSessionReport()
                        }
                    }
                    binding.btnViewEvidence.visibility = View.VISIBLE
                    updateMultiPointCards()

                    val settle = SmritiLightMapper.fromScanEvent(mem)
                    haloBind.controller().setStateBrief(
                        SmritiLightState.NEW_MEMORY,
                        settle,
                        850L
                    )
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

    private fun confirmAddToBaseline(features: AcousticFeatures) {
        AlertDialog.Builder(this)
            .setTitle("Add dry baseline?")
            .setMessage(
                "Only confirm if this surface is dry and known-good. " +
                    "Dry samples teach what NORMAL looks like. Moist samples teach the opposite."
            )
            .setPositiveButton("Dry · baseline") { _, _ ->
                teachDry(features, firstBaseline = false)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun teachDry(features: AcousticFeatures, firstBaseline: Boolean) {
        val location = repo.getLocation(locationId) ?: return
        location.baselineFeatures.add(SerializableFeatures.from(features))
        // If this was previously taught as moist, remove the closest duplicate.
        removeSimilar(location.moistFeatures, features)
        repo.updateLocation(location)
        pendingTeach = null
        pendingLabeled = true
        binding.btnSetBaseline.visibility = View.GONE
        binding.btnMarkMoist.visibility = View.GONE
        if (firstBaseline) {
            location.scoreStage = 0
            repo.updateLocation(location)
            val mem = smriti.rememberBaseline(
                location.id,
                location.label,
                features,
                location.baselineFeatures.size
            )
            lastSmritiEventId = mem.id
            haloBind.controller().setStateBrief(
                SmritiLightState.NEW_MEMORY,
                SmritiLightState.NORMAL,
                1000L
            )
            binding.textScoreLabel.text =
                "Dry baseline saved (${location.baselineFeatures.size}). Scan moist areas next and mark them."
            binding.textScoreState.text = getString(R.string.memory_recorded)
            Toast.makeText(this, "Dry baseline saved", Toast.LENGTH_SHORT).show()
        } else {
            binding.textScoreLabel.text =
                "Dry sample added · ${location.baselineFeatures.size} dry · ${location.moistFeatures.size} moist"
            Toast.makeText(this, "Dry · added to baseline", Toast.LENGTH_SHORT).show()
        }
    }

    private fun teachMoist(features: AcousticFeatures) {
        val location = repo.getLocation(locationId) ?: return
        location.moistFeatures.add(SerializableFeatures.from(features))
        // Cap teaching set so one location does not grow forever
        while (location.moistFeatures.size > 40) {
            location.moistFeatures.removeAt(0)
        }
        repo.updateLocation(location)
        pendingTeach = null
        pendingLabeled = true
        binding.btnSetBaseline.visibility = View.GONE
        binding.btnMarkMoist.visibility = View.GONE
        binding.textScoreLabel.text =
            "Moist sample taught · ${location.baselineFeatures.size} dry · ${location.moistFeatures.size} moist"
        Toast.makeText(this, "Moist · detector updated", Toast.LENGTH_SHORT).show()
    }

    /** User said unmarked compare scans are moist — learn that on next action / pause. */
    private fun commitPendingAsMoistIfNeeded() {
        val features = pendingTeach ?: return
        if (pendingLabeled) return
        teachMoist(features)
    }

    private fun removeSimilar(
        list: MutableList<SerializableFeatures>,
        target: AcousticFeatures
    ) {
        val t = target.toDoubleArray()
        val idx = list.indexOfFirst { s ->
            val a = s.toAcousticFeatures().toDoubleArray()
            var sum = 0.0
            for (i in a.indices) {
                val d = a[i] - t[i]
                sum += d * d
            }
            sqrt(sum) < 1e-3
        }
        if (idx >= 0) list.removeAt(idx)
    }

    private fun displayScore(score: Double) {
        binding.textScore.text = "${score.toInt()}%"
        binding.textScoreLabel.text = getString(R.string.baseline_deviation)

        val (state, color, bg) = when {
            score < AnomalyThresholds.GREEN_MAX ->
                Triple("NORMAL", R.color.status_ok, R.drawable.bg_result_ok)
            score < AnomalyThresholds.YELLOW_MAX ->
                Triple(getString(R.string.anomaly_observed), R.color.status_warn, R.drawable.bg_result_warn)
            else ->
                Triple(getString(R.string.anomaly_observed), R.color.status_alert, R.drawable.bg_result_alert)
        }

        binding.textScoreState.text = state
        binding.textScoreState.setTextColor(ContextCompat.getColor(this, color))
        binding.textScore.setTextColor(ContextCompat.getColor(this, color))
        binding.cardResult.setBackgroundResource(bg)
    }

    private fun scanMeta(mem: com.aquascope.smriti.model.PhysicalEvent): String {
        val fmt = java.text.SimpleDateFormat("d MMM · HH:mm", java.util.Locale.getDefault())
        val first = smriti.firstAnomalyAt(mem.locationId)
        return buildString {
            append("Status    ${mem.status.name.replace('_', ' ')}\n")
            append("Previous occurrences    ${mem.previousOccurrenceCount}")
            if (first != null) {
                append("\nFirst observed    ${fmt.format(java.util.Date(first.timestampMs))}")
            }
            mem.deviationVsYesterday?.let {
                append("\nChange vs last 24h    ${"%+.0f".format(it)}%")
            }
        }
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
