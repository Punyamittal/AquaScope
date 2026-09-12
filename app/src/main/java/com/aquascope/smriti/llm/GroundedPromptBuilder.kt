package com.aquascope.smriti.llm

import com.aquascope.smriti.model.PhysicalEvent
import com.aquascope.smriti.model.SmritiAnswer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a strict grounded prompt: local model may only use FACTS from memory / rules.
 */
object GroundedPromptBuilder {

    private val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)

    fun build(question: String, ruleAnswer: SmritiAnswer, events: List<PhysicalEvent>): String {
        val facts = factsBlock(ruleAnswer, events)
        return """
You are SMRITI, a home acoustic-memory assistant.
Rewrite the CANONICAL_ANSWER in clear, calm spoken English for the user.

HARD RULES:
- Use ONLY facts in CANONICAL_ANSWER and MEMORY_EVENTS.
- Do NOT invent leaks, timestamps, scores, locations, or confirmations.
- If EVIDENCE_STATE is UNKNOWN, say you don't have that record.
- Never say a leak is confirmed unless CANONICAL_ANSWER already says so.
- Keep under 120 words. No markdown. No preamble like "Sure".

USER_QUESTION: $question

$facts

ANSWER:
""".trimIndent()
    }

    /**
     * Free-form Ask: answer the user's question from memory facts (not only rephrase a list).
     */
    fun buildChat(question: String, ruleAnswer: SmritiAnswer, events: List<PhysicalEvent>): String {
        val facts = factsBlock(ruleAnswer, events)
        return """
You are SMRITI, an on-device home memory assistant.
Answer USER_QUESTION using ONLY the facts below (CANONICAL_ANSWER + MEMORY_EVENTS).

HARD RULES:
- Stay faithful to those facts. Do not invent events, leaks, times, places, or scores.
- If facts are insufficient or EVIDENCE_STATE is UNKNOWN, say you don't have that in memory yet and suggest a scan or a more specific question.
- Never claim a confirmed leak unless CANONICAL_ANSWER already does.
- Be concise (under 120 words). Plain sentences. No markdown. No "As an AI".

USER_QUESTION: $question

$facts

ANSWER:
""".trimIndent()
    }

    private fun factsBlock(ruleAnswer: SmritiAnswer, events: List<PhysicalEvent>): String =
        buildString {
            appendLine("EVIDENCE_STATE: ${ruleAnswer.evidenceState}")
            appendLine("CANONICAL_ANSWER:")
            appendLine(ruleAnswer.text.trim())
            appendLine()
            appendLine("MEMORY_EVENTS (${events.size}):")
            if (events.isEmpty()) {
                appendLine("- (none)")
            } else {
                events.take(8).forEach { e ->
                    appendLine(
                        "- ${fmt.format(Date(e.timestampMs))} | ${e.locationLabel} | " +
                            "${e.eventType} | score=${"%.1f".format(e.anomalyScore)} | ${e.summary}"
                    )
                }
            }
            if (ruleAnswer.suggestedActions.isNotEmpty()) {
                appendLine()
                appendLine("SUGGESTED_ACTIONS: ${ruleAnswer.suggestedActions.joinToString("; ")}")
            }
        }
}
