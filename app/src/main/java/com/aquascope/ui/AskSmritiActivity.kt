package com.aquascope.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.databinding.ActivityAskSmritiBinding
import com.aquascope.halo.SmritiLightMapper
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.SmritiAnswer
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AskSmritiActivity : SmritiScreenActivity() {

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

        // Warm MediaPipe in :smriti_llm only. A native crash there must not kill Ask.
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { smriti.ensureLocalLlm() }
            withContext(Dispatchers.Main) {
                if (!isFinishing && !isDestroyed) refreshModelBadge()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!answering && binding.orbView.visibility == View.VISIBLE) {
            binding.orbView.onResume()
        }
        haloBind.start()
        if (!answering) {
            haloBind.controller().setState(SmritiLightState.NORMAL)
            binding.orbView.setActive(false)
            binding.orbView.setPulse(0.55f)
        }
        refreshModelBadge()
    }

    override fun onPause() {
        if (binding.orbView.visibility == View.VISIBLE) {
            binding.orbView.onPause()
        }
        haloBind.stop()
        super.onPause()
    }

    private fun showIdleHero() {
        answering = false
        binding.orbView.visibility = View.VISIBLE
        binding.orbView.onResume()
        binding.orbView.setPaused(false)
        binding.orbView.setActive(false)
        binding.orbView.setPulse(0.55f)
        binding.heroCopy.visibility = View.VISIBLE
        binding.scrollChips.visibility = View.VISIBLE
        binding.scrollAnswer.visibility = View.GONE
    }

    private fun showAnswerMode() {
        answering = true
        hideBlackHole()
        binding.heroCopy.visibility = View.GONE
        binding.scrollChips.visibility = View.GONE
        binding.scrollAnswer.visibility = View.VISIBLE
    }

    private fun hideBlackHole() {
        try {
            binding.orbView.setPaused(true)
            binding.orbView.visibility = View.GONE
        } catch (t: Throwable) {
            Log.e(TAG, "hide orb failed", t)
        }
    }

    private fun refreshModelBadge() {
        val mode = when {
            !smriti.llmPrefs.enabled -> "Rules"
            smriti.isLocalLlmReady() -> "Local"
            smriti.modelStore.findInstalled() != null -> "Local…"
            else -> "Rules"
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
        hideBlackHole()
        binding.heroCopy.visibility = View.GONE
        binding.scrollChips.visibility = View.GONE
        haloBind.controller().setState(SmritiLightState.MEMORY_RECALL)
        if (smriti.llmPrefs.enabled && smriti.modelStore.findInstalled() != null && !smriti.isLocalLlmReady()) {
            Toast.makeText(this, "Loading local model… first answer may take a minute", Toast.LENGTH_SHORT).show()
        }
        lifecycleScope.launch {
            val answer = try {
                withContext(Dispatchers.Default) {
                    smriti.ask(question)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Ask failed", t)
                SmritiAnswer(
                    text = "I couldn't finish that with the local model. Try again, or open System and reload the model.",
                    evidenceState = EvidenceState.UNKNOWN,
                    relatedEvents = emptyList(),
                    evidence = null
                )
            } finally {
                if (!isFinishing && !isDestroyed) binding.btnAsk.isEnabled = true
            }

            if (isFinishing || isDestroyed) return@launch

            showAnswerMode()
            binding.textAsked.text = question

            val settle = SmritiLightMapper.fromAsk(
                answer.evidenceState,
                answer.relatedEvents.size,
                answer.relatedEvents.maxOfOrNull { it.anomalyScore } ?: 0.0
            )
            binding.textEvidenceState.text = when (answer.evidenceState) {
                EvidenceState.UNKNOWN -> getString(R.string.ask_state_unknown)
                EvidenceState.OBSERVED -> getString(R.string.ask_state_observed)
                EvidenceState.INFERRED -> getString(R.string.ask_state_inferred)
                EvidenceState.POSSIBLE -> getString(R.string.ask_state_possible)
                EvidenceState.CONFIRMED -> getString(R.string.ask_state_confirmed)
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
            binding.textAnswer.text = com.aquascope.smriti.llm.AskAnswerCleaner.cleanForDisplay(answer.text)
            lastEvidenceEventId = answer.relatedEvents.firstOrNull()?.id
            binding.btnWhy.visibility = if (lastEvidenceEventId != null) View.VISIBLE else View.GONE

            if (answer.relatedEvents.isNotEmpty()) {
                haloBind.controller().setStateBrief(SmritiLightState.NEW_MEMORY, settle, 500L)
            } else {
                haloBind.controller().setState(settle)
            }
            refreshModelBadge()
            if (smriti.llmPrefs.enabled && !answer.usedLocalModel && smriti.modelStore.findInstalled() != null) {
                val hint = smriti.llmFailureHint()
                    ?: smriti.modelStatusLight().message
                Toast.makeText(
                    this@AskSmritiActivity,
                    if (smriti.isLocalLlmReady()) {
                        "Rules answer — model ran but that reply was discarded. $hint"
                    } else {
                        "Rules answer — local model unavailable. $hint"
                    },
                    Toast.LENGTH_LONG
                ).show()
            } else if (answer.usedLocalModel) {
                Toast.makeText(
                    this@AskSmritiActivity,
                    "Answered with ${answer.modelName ?: "local model"}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        private const val TAG = "AskSmriti"
    }
}
