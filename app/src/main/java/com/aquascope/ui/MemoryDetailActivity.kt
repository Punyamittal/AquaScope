package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aquascope.R
import com.aquascope.databinding.ActivityMemoryDetailBinding
import com.aquascope.smriti.SmritiCore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MemoryDetailActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMemoryDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra("event_id")
        val smriti = SmritiCore.get(this)
        val event = id?.let { smriti.store.getEvent(it) }
        if (event == null) {
            Toast.makeText(this, "Memory not found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val fmt = SimpleDateFormat("d MMMM", Locale.getDefault())
        val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        binding.textEventId.text = "REMEMBERED"
        binding.textEventObject.text = event.objectLabel.uppercase(Locale.getDefault())
        binding.textEventWhen.text =
            "${fmt.format(Date(event.timestampMs)).uppercase(Locale.getDefault())}    ${timeFmt.format(Date(event.timestampMs)).uppercase(Locale.getDefault())}"
        binding.textEventType.text = event.eventType.name.replace('_', ' ')
        binding.textEventSummary.text = event.summary

        val baseline = event.baselineId?.let { smriti.store.getBaseline(it) }
        val currentMag = featureMagnitude(event.features)
        val baseMag = baseline?.meanFeatures?.let { featureMagnitude(it) } ?: currentMag
        val maxMag = maxOf(currentMag, baseMag, 1e-6)
        binding.baselineCompare.setComparison(
            (baseMag / maxMag).toFloat(),
            (currentMag / maxMag).toFloat()
        )
        binding.textDeviation.text =
            if (event.anomalyScore > 0) "%+d%%".format(event.anomalyScore.toInt()) else "—"
        binding.textDeviation.setTextColor(
            getColor(if (event.anomalyScore > 30) R.color.amber else R.color.cyan)
        )

        val first = smriti.firstAnomalyAt(event.locationId)
        binding.textEventMeta.text = buildString {
            append("Status    ${event.status.name.replace('_', ' ')}\n")
            append("Evidence    ${event.evidenceState.name}\n")
            append("Previous occurrence    ${event.previousOccurrenceCount}")
            if (first != null && first.id != event.id) {
                append("\nEarlier observation    ${fmt.format(Date(first.timestampMs))}")
            }
            event.deviationVsYesterday?.let {
                append("\nvs last 24h    ${"%+.0f".format(it)}")
            }
        }

        binding.btnOpenEvidence.setOnClickListener {
            startActivity(Intent(this, EvidenceActivity::class.java).putExtra("event_id", event.id))
        }
        binding.btnCompare.setOnClickListener {
            startActivity(Intent(this, MemoryTimelineActivity::class.java))
        }
    }

    private fun featureMagnitude(features: Map<String, Double>): Double {
        if (features.isEmpty()) return 0.0
        return features.values.fold(0.0) { acc, v -> acc + abs(v) } / features.size
    }
}
