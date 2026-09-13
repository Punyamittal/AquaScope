package com.aquascope.smriti.llm

import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.QueryIntent
import com.aquascope.smriti.model.SmritiAnswer
import com.aquascope.smriti.skills.SkillMatch

/**
 * Rules compute the evidence state (UNKNOWN/OBSERVED/POSSIBLE/CONFIRMED). The local LLM
 * (and, for Hindi/Hinglish, Sarvam on top) is free to phrase, elaborate, and draw on
 * general knowledge — the only hard constraint is that it can never claim a confirmed
 * leak, or a specific reading, that the rules didn't already establish.
 * Edge Gallery–style skills may inject instructions / tool results.
 * Multilingual fallback ensures natural responses in Hindi/Hinglish when LLM is absent or fallback needed.
 */
class SmritiAnswerComposer(
    private val llm: LocalLlmEngine?,
    private val prefs: LocalLlmPreferences,
    private val sarvamClient: SarvamClient? = null
) {

    fun compose(
        question: String,
        ruleAnswer: SmritiAnswer,
        intent: QueryIntent = QueryIntent.GENERAL,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = "",
        language: String = prefs.targetLanguage
    ): SmritiAnswer {
        val target = GroundedPromptBuilder.detectLanguage(question, language)
        val cleanedRule = fallbackAnswer(
            ruleAnswer.copy(text = AskAnswerCleaner.cleanForDisplay(ruleAnswer.text)),
            target
        )

        // Tool-only skills can answer without a model (hash / email / wikipedia).
        if (skillMatch != null &&
            !skillMatch.toolResult.isNullOrBlank() &&
            (llm == null || !llm.isReady || !prefs.enabled)
        ) {
            return cleanedRule.copy(
                text = AskAnswerCleaner.cleanForDisplay(skillMatch.toolResult!!),
                usedLocalModel = false,
                modelName = "skill:${skillMatch.skill.name}"
            )
        }

        if (!prefs.enabled || llm == null || !llm.isReady) {
            return cleanedRule.copy(usedLocalModel = false, modelName = null)
        }

        val prompt = if (intent == QueryIntent.GENERAL || skillMatch?.skill?.id == "kitchen-adventure") {
            GroundedPromptBuilder.buildChat(
                question, cleanedRule, cleanedRule.relatedEvents, skillMatch, skillCatalogBlurb, target
            )
        } else {
            GroundedPromptBuilder.build(
                question, cleanedRule, cleanedRule.relatedEvents, skillMatch, skillCatalogBlurb, target
            )
        }

        val polished = try {
            val raw = llm.generate(prompt)
            raw?.let { BpeDecoder.cleanModelOutput(it) }
        } catch (t: Throwable) {
            null
        } ?: return if (!skillMatch?.toolResult.isNullOrBlank()) {
            cleanedRule.copy(
                text = AskAnswerCleaner.cleanForDisplay(skillMatch!!.toolResult!!),
                usedLocalModel = false,
                modelName = "skill:${skillMatch!!.skill.name}"
            )
        } else {
            cleanedRule.copy(usedLocalModel = false, modelName = null)
        }

        val cleaned = AskAnswerCleaner.cleanModelOutput(polished)
        val hasScreenOcr = com.aquascope.smriti.brain.NeuralCoreSession.lastScreenOcr.isNotBlank()

        if (cleaned.isBlank() || AskAnswerCleaner.looksLikePromptLeak(cleaned)) {
            return if (!skillMatch?.toolResult.isNullOrBlank() && !hasScreenOcr) {
                cleanedRule.copy(
                    text = AskAnswerCleaner.cleanForDisplay(skillMatch!!.toolResult!!),
                    usedLocalModel = false,
                    modelName = "skill:${skillMatch!!.skill.name}"
                )
            } else {
                cleanedRule.copy(usedLocalModel = false, modelName = null)
            }
        }

        // The only hard constraint left: never let the model claim a confirmed leak
        // (or a specific reading) that the rule engine's evidence doesn't support.
        // Everything else — phrasing, elaboration, general knowledge — is unrestricted.
        if (!passesLeakSafetyCheck(cleaned, cleanedRule)) {
            return cleanedRule.copy(usedLocalModel = false, modelName = null)
        }

        // Qwen already produced a valid answer at this point. For Indian languages,
        // layer one more pass on top: ask Sarvam AI to rewrite it more naturally.
        // Never a reasoning step — falls back to Qwen's own text on any failure
        // (no key, network error, empty reply).
        val finalText = if (target in SarvamPreferences.INDIAN_LANGUAGE_TARGETS && sarvamClient != null) {
            sarvamClient.rewrite(question, cleaned, target)
                ?.let { AskAnswerCleaner.cleanForDisplay(it) }
                ?.takeIf { it.isNotBlank() }
                ?: cleaned
        } else {
            cleaned
        }

        return cleanedRule.copy(
            text = finalText,
            usedLocalModel = true,
            modelName = llm.modelLabel
        )
    }

    private fun fallbackAnswer(ruleAnswer: SmritiAnswer, target: String): SmritiAnswer {
        if (target != "hi" && target != "hinglish") return ruleAnswer
        val translated = when (target) {
            "hi" -> when (ruleAnswer.evidenceState) {
                EvidenceState.UNKNOWN -> "मुझे मेमोरी में इसका कोई रिकॉर्ड नहीं मिला है। आप एक नया एक्वास्कोप स्कैन चला सकते हैं।"
                EvidenceState.OBSERVED -> "मेमोरी में असामान्य आवाज़ के रिकॉर्ड मिले हैं (${ruleAnswer.relatedEvents.size} घटनाएँ)। अभी किसी पक्के लीक की पुष्टि नहीं हुई है, कृपया पाइप की जांच करें।"
                EvidenceState.POSSIBLE, EvidenceState.INFERRED -> "संभावित असामान्य बहाव या रिसाव का संकेत मिला है। पाइप और फिटिंग्स की जांच करने की सलाह दी जाती है।"
                EvidenceState.CONFIRMED -> "पुष्ट पानी के रिसाव का रिकॉर्ड मौजूद है। कृपया तुरंत समस्या को ठीक करें।"
                else -> "मेमोरी में असामान्य आवाज़ के रिकॉर्ड मिले हैं। कृपया एक बार जांच कर लें।"
            }
            "hinglish" -> when (ruleAnswer.evidenceState) {
                EvidenceState.UNKNOWN -> "Mujhe memory me iska koi record nahi mila hai. Aap ek naya AquaScope scan chala sakte hain."
                EvidenceState.OBSERVED -> "Memory me abnormal sound ke records mile hain (${ruleAnswer.relatedEvents.size} events). Abhi kisi pakke leak ki pushti nahi hui hai, kripya pipe check karein."
                EvidenceState.POSSIBLE, EvidenceState.INFERRED -> "Sambhavit abnormal flow ya leak ka signal mila hai. Pipe aur fittings check karne ki salaah di jaati hai."
                EvidenceState.CONFIRMED -> "Confirmed leak ka record maujood hai. Kripya turant inspect karein."
                else -> "Memory me abnormal sound ke records mile hain. Kripya check kar lijiye."
            }
            else -> ruleAnswer.text
        }
        return ruleAnswer.copy(text = translated)
    }

    companion object {
        /**
         * The one hard safety constraint: reject answers that assert a confirmed leak,
         * or invent "confirmed leak" language, the rules never established. Everything
         * else (length, phrasing, elaboration, general knowledge) is unrestricted.
         */
        fun passesLeakSafetyCheck(modelText: String, ruleAnswer: SmritiAnswer): Boolean {
            val t = modelText.lowercase()
            if (t.isBlank()) return false
            if (AskAnswerCleaner.looksLikePromptLeak(modelText)) return false

            val modelClaimsConfirmedLeak = positiveLeakClaim(t)
            val rulesAllowConfirmed =
                ruleAnswer.evidenceState == EvidenceState.CONFIRMED ||
                    positiveLeakClaim(ruleAnswer.text.lowercase())

            return !(modelClaimsConfirmedLeak && !rulesAllowConfirmed)
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
