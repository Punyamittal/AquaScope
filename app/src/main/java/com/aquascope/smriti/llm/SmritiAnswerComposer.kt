package com.aquascope.smriti.llm

import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.QueryIntent
import com.aquascope.smriti.model.SmritiAnswer

/**
 * Rules are the source of truth. Local LLM rephrases or answers free-form from grounded facts.
 */
class SmritiAnswerComposer(
    private val llm: LocalLlmEngine?,
    private val prefs: LocalLlmPreferences
) {

    fun compose(
        question: String,
        ruleAnswer: SmritiAnswer,
        intent: QueryIntent = QueryIntent.GENERAL
    ): SmritiAnswer {
        if (!prefs.enabled || llm == null || !llm.isReady) {
            return ruleAnswer.copy(usedLocalModel = false, modelName = null)
        }

        val prompt = if (intent == QueryIntent.GENERAL) {
            GroundedPromptBuilder.buildChat(question, ruleAnswer, ruleAnswer.relatedEvents)
        } else {
            GroundedPromptBuilder.build(question, ruleAnswer, ruleAnswer.relatedEvents)
        }

        val polished = try {
            llm.generate(prompt)
        } catch (t: Throwable) {
            null
        } ?: return ruleAnswer.copy(usedLocalModel = false, modelName = null)

        if (!passesGroundingCheck(polished, ruleAnswer, intent)) {
            return ruleAnswer.copy(usedLocalModel = false, modelName = null)
        }

        return ruleAnswer.copy(
            text = polished,
            usedLocalModel = true,
            modelName = llm.modelLabel
        )
    }

    companion object {
        /**
         * Soft guard: reject answers that assert confirmation when evidence is weaker,
         * or invent "confirmed leak" language the rules never used.
         */
        fun passesGroundingCheck(
            modelText: String,
            ruleAnswer: SmritiAnswer,
            intent: QueryIntent = QueryIntent.GENERAL
        ): Boolean {
            val t = modelText.lowercase()
            if (t.isBlank()) return false
            val rule = ruleAnswer.text.lowercase()

            val modelClaimsConfirmedLeak = positiveLeakClaim(t)
            val rulesAllowConfirmed =
                ruleAnswer.evidenceState == EvidenceState.CONFIRMED ||
                    positiveLeakClaim(rule)

            if (modelClaimsConfirmedLeak && !rulesAllowConfirmed) return false

            val maxLen = if (intent == QueryIntent.GENERAL) {
                900
            } else {
                maxOf(ruleAnswer.text.length * 5 + 280, 720)
            }
            if (modelText.length > maxLen) return false

            return true
        }

        /** True only for affirmative leak confirmation language (ignores "not confirmed"). */
        private fun positiveLeakClaim(text: String): Boolean {
            val t = text.lowercase()
            if (Regex("""\b(not|no|never|without)\b[^.?]{0,24}\b(confirmed\s+)?leak\b""").containsMatchIn(t)) {
                return false
            }
            if (t.contains("unconfirmed") || t.contains("not confirmed") || t.contains("not a confirmed")) {
                val stripped = t
                    .replace("not a confirmed leak", " ")
                    .replace("not confirmed", " ")
                    .replace("unconfirmed", " ")
                    .replace("no confirmed leak", " ")
                return Regex("""\b(confirmed\s+leak|leak\s+is\s+confirmed|definitely\s+(a\s+|the\s+)?leak|there\s+is\s+(a\s+|the\s+)?leak)\b""")
                    .containsMatchIn(stripped)
            }
            return Regex("""\b(confirmed\s+leak|leak\s+is\s+confirmed|definitely\s+(a\s+|the\s+)?leak|there\s+is\s+(a\s+|the\s+)?leak)\b""")
                .containsMatchIn(t)
        }
    }
}
