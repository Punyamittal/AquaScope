package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.databinding.ActivityAskSmritiBinding
import com.aquascope.halo.SmritiLightMapper
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.model.EvidenceState
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AskSmritiActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAskSmritiBinding
    private lateinit var smriti: SmritiCore
    private lateinit var haloBind: HaloBinding
    private var lastEvidenceEventId: String? = null
    private var answering = false

    private val suggestions = listOf(
        "Has this happened before?",
        "What changed today?",
        "What's normal?",
        "Show unusual activity."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAskSmritiBinding.inflate(layoutInflater)
        setContentView(binding.root)
        smriti = SmritiCore.get(this)
        SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_ASK)
        haloBind = HaloBinding(this, binding.haloIndicator)

        suggestions.forEach { q ->
            val chip = Chip(this).apply {
                text = q
                isCheckable = false
                setChipBackgroundColorResource(R.color.navy)
                chipStrokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.ocean))
                chipStrokeWidth = resources.displayMetrics.density
                setTextColor(getColor(R.color.warm_muted))
                setOnClickListener {
                    binding.inputQuestion.setText(q)
                    ask(q)
                }
            }
            binding.chipQuestions.addView(chip)
        }

        binding.textModelBadge.setOnClickListener {
            startActivity(Intent(this, LocalModelActivity::class.java))
        }

        binding.btnAsk.setOnClickListener { submitFromInput() }
        binding.inputQuestion.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_SEND ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) {
                submitFromInput()
                true
            } else false
        }

        binding.btnWhy.setOnClickListener {
            val id = lastEvidenceEventId
            if (id != null) {
                startActivity(Intent(this, EvidenceActivity::class.java).putExtra("event_id", id))
            }
        }

        showIdleHero()
    }

    override fun onResume() {
        super.onResume()
        haloBind.start()
        if (!answering) {
            haloBind.controller().setState(SmritiLightState.NORMAL)
            binding.orbView.setActive(false)
            binding.orbView.setPulse(0.55f)
        }
        refreshModelBadge()
    }

    override fun onPause() {
        haloBind.stop()
        super.onPause()
    }

    private fun showIdleHero() {
        answering = false
        binding.heroCopy.visibility = View.VISIBLE
        binding.scrollChips.visibility = View.VISIBLE
        binding.scrollAnswer.visibility = View.GONE
        binding.orbView.setActive(false)
        binding.orbView.setPulse(0.55f)
    }

    private fun showAnswerMode() {
        answering = true
        binding.heroCopy.visibility = View.GONE
        binding.scrollChips.visibility = View.GONE
        binding.scrollAnswer.visibility = View.VISIBLE
    }

    private fun refreshModelBadge() {
        val status = smriti.modelStatusLight()
        val mode = when {
            !smriti.llmPrefs.enabled -> "Rules"
            status.ready -> "Local"
            else -> "System"
        }
        binding.textModelBadge.text = mode
    }

    private fun submitFromInput() {
        val q = binding.inputQuestion.text?.toString()?.trim().orEmpty()
        if (q.isEmpty()) {
            Toast.makeText(this, "Ask anything about your home memory", Toast.LENGTH_SHORT).show()
        } else ask(q)
    }

    private fun ask(question: String) {
        binding.btnAsk.isEnabled = false
        binding.orbView.setActive(true)
        binding.orbView.setPulse(0.9f)
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)
        lifecycleScope.launch {
            val answer = withContext(Dispatchers.Default) {
                smriti.ask(question)
            }
            binding.btnAsk.isEnabled = true
            showAnswerMode()
            binding.textAsked.text = question

            val settle = SmritiLightMapper.fromAsk(
                answer.evidenceState,
                answer.relatedEvents.size
            )
            if (settle == SmritiLightState.UNKNOWN) {
                binding.textEvidenceState.text = getString(R.string.insufficient_evidence)
            } else {
                binding.textEvidenceState.text = answer.evidenceState.name
            }
            binding.textEvidenceState.setTextColor(
                getColor(
                    when (answer.evidenceState) {
                        EvidenceState.UNKNOWN -> R.color.warm_faint
                        EvidenceState.INFERRED, EvidenceState.POSSIBLE -> R.color.amber
                        else -> R.color.cyan
                    }
                )
            )
            binding.textAnswer.text = answer.text
            lastEvidenceEventId = answer.relatedEvents.firstOrNull()?.id
            binding.btnWhy.visibility = if (lastEvidenceEventId != null) View.VISIBLE else View.GONE

            binding.orbView.setActive(false)
            binding.orbView.setPulse(if (settle == SmritiLightState.UNKNOWN) 0.35f else 0.7f)

            if (answer.relatedEvents.isNotEmpty()) {
                haloBind.controller().setStateBrief(SmritiLightState.NEW_MEMORY, settle, 500L)
            } else {
                haloBind.controller().setState(settle)
            }
            refreshModelBadge()
        }
    }
}
