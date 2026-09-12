package com.aquascope.ui

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aquascope.R
import com.aquascope.databinding.ActivityEvidenceBinding
import com.aquascope.smriti.SmritiCore

class EvidenceActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityEvidenceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra("event_id")
        val smriti = SmritiCore.get(this)
        val event = id?.let { smriti.store.getEvent(it) }
        val bundle = id?.let { smriti.evidenceFor(it) }

        if (event == null || bundle == null) {
            Toast.makeText(this, "No evidence available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.textClaim.text = bundle.claim
        binding.textState.text = bundle.evidenceState.name
        binding.textAnomalyScore.text = "${event.anomalyScore.toInt()}%"
        binding.textAnomalyScore.setTextColor(
            getColor(if (event.anomalyScore > 30) R.color.amber else R.color.cyan)
        )

        binding.layoutSupporting.removeAllViews()
        bundle.supporting.forEach { line ->
            binding.layoutSupporting.addView(TextView(this).apply {
                text = "✓  $line"
                setTextColor(getColor(R.color.warm_white))
                textSize = 14f
                setLineSpacing(0f, 1.25f)
                setPadding(0, 8, 0, 8)
            })
        }

        binding.layoutUnknown.removeAllViews()
        bundle.unknown.forEach { line ->
            binding.layoutUnknown.addView(TextView(this).apply {
                text = "✕  $line"
                setTextColor(getColor(R.color.warm_muted))
                textSize = 14f
                setLineSpacing(0f, 1.25f)
                setPadding(0, 8, 0, 8)
            })
        }

        binding.btnCloseEvidence.setOnClickListener { finish() }
    }
}
