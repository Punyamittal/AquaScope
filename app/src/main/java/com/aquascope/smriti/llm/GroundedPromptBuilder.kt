package com.aquascope.smriti.llm

import com.aquascope.smriti.brain.NeuralCoreSession
import com.aquascope.smriti.model.PhysicalEvent
import com.aquascope.smriti.model.SmritiAnswer
import com.aquascope.smriti.skills.SkillMatch
import com.aquascope.smriti.skills.SkillPromptInjector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a strict grounded prompt: local model may only use FACTS from memory / rules.
 * Optionally injects Edge Gallery–style skill instructions.
 */
object GroundedPromptBuilder {

    private val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)

    fun build(
        question: String,
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = ""
    ): String {
        val facts = factsBlock(ruleAnswer, events)
        val skills = skillSections(skillMatch, skillCatalogBlurb)
        return """
You are SMRITI, an on-device home memory assistant (screen OCR, Guardian, scans).
Rewrite the CANONICAL_ANSWER in clear, calm spoken English for the user.

HARD RULES:
- Use ONLY facts in CANONICAL_ANSWER, MEMORY_EVENTS, and SCREEN_OCR${if (skillMatch?.toolResult != null) " and SKILL_TOOL_RESULT" else ""}.
- Do NOT invent leaks, timestamps, scores, locations, or confirmations.
- If EVIDENCE_STATE is UNKNOWN and SCREEN_OCR / MEMORY_EVENTS are empty, say you don't have that record.
- Never say a leak is confirmed unless CANONICAL_ANSWER already says so.
- When the user asks what was on screen / in a recording, answer from SCREEN_OCR and MEMORY_EVENTS text.
- Keep under 160 words. Spoken sentences only.
- Do not repeat labels such as EVIDENCE_STATE, CANONICAL_ANSWER, MEMORY_EVENTS, SCREEN_OCR, file paths, or RMS numbers.
- If ACTIVE_SKILL is present, follow its instructions when they do not conflict with the hard rules above.

USER_QUESTION: $question

$skills
$facts

ANSWER:
""".trimIndent()
    }

    fun buildChat(
        question: String,
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = ""
    ): String {
        val facts = factsBlock(ruleAnswer, events)
        val skills = skillSections(skillMatch, skillCatalogBlurb)
        val adventure = skillMatch?.skill?.id == "kitchen-adventure"
        return if (adventure) {
            """
You are running an Edge Gallery skill.

$skills

USER_QUESTION: $question

Follow ACTIVE_SKILL instructions exactly. Keep replies short.

ANSWER:
""".trimIndent()
        } else {
            """
You are SMRITI, an on-device home memory assistant.
Answer USER_QUESTION using ONLY the facts below (CANONICAL_ANSWER + MEMORY_EVENTS + SCREEN_OCR${if (skillMatch?.toolResult != null) " + SKILL_TOOL_RESULT" else ""}).

HARD RULES:
- Stay faithful to those facts. Do not invent events, leaks, times, places, or scores.
- Prefer SCREEN_OCR first. If SCREEN_OCR names an app/game, that is the answer for "what game/app" questions — ignore unrelated MEMORY_EVENTS.
- Do not answer from general knowledge or Wikipedia when SCREEN_OCR is present.
- If facts are insufficient or EVIDENCE_STATE is UNKNOWN and there is no SCREEN_OCR / MEMORY text, say you don't have that in memory yet and suggest Capture → Stop or a more specific question — unless SKILL_TOOL_RESULT has the answer.
- Never claim a confirmed leak unless CANONICAL_ANSWER already does.
- Be concise (under 160 words). Spoken sentences only. No markdown. No "As an AI".
- Do not repeat labels such as EVIDENCE_STATE, CANONICAL_ANSWER, MEMORY_EVENTS, SCREEN_OCR, file paths, or RMS numbers.
- If ACTIVE_SKILL is present, follow its instructions when they do not conflict with the hard rules above.

USER_QUESTION: $question

$skills
$facts

ANSWER:
""".trimIndent()
        }
    }

    private fun skillSections(skillMatch: SkillMatch?, catalogBlurb: String): String =
        buildString {
            if (catalogBlurb.isNotBlank()) {
                appendLine(catalogBlurb)
                appendLine()
            }
            val active = SkillPromptInjector.activeBlock(skillMatch)
            if (active.isNotBlank()) {
                appendLine(active)
                appendLine()
            }
        }.trim()

    private fun factsBlock(ruleAnswer: SmritiAnswer, events: List<PhysicalEvent>): String =
        buildString {
            // SCREEN_OCR first so truncation / attention keep the latest clip.
            appendScreenOcr()
            appendLine("EVIDENCE_STATE: ${ruleAnswer.evidenceState}")
            appendLine("CANONICAL_ANSWER:")
            appendLine(ruleAnswer.text.trim())
            appendLine()
            val screenFirst = events.sortedByDescending { e ->
                when {
                    e.id == "screen-ocr-latest" -> 3
                    e.source.equals("SCREENMIND", true) ||
                        e.source.equals("SMRITI_PLAY", true) ||
                        e.source.equals("OCR", true) -> 2
                    else -> 0
                }
            }
            appendLine("MEMORY_EVENTS (${screenFirst.size}):")
            if (screenFirst.isEmpty()) {
                appendLine("- (none)")
            } else {
                screenFirst.take(4).forEach { e ->
                    appendLine(
                        "- ${fmt.format(Date(e.timestampMs))} at ${e.locationLabel} [${e.source}]: " +
                            eventFact(e)
                    )
                }
            }
            if (ruleAnswer.suggestedActions.isNotEmpty()) {
                appendLine()
                appendLine("SUGGESTED_ACTIONS: ${ruleAnswer.suggestedActions.joinToString("; ")}")
            }
        }

    private fun StringBuilder.appendScreenOcr() {
        val ocr = NeuralCoreSession.lastScreenOcr.trim()
        appendLine("SCREEN_OCR:")
        if (ocr.isBlank()) {
            appendLine("(none)")
        } else {
            appendLine(ocr.take(2_000))
            NeuralCoreSession.lastScreenOcrPath?.let { path ->
                appendLine("(ocr file: ${path.substringAfterLast('/')})")
            }
        }
        appendLine()
    }

    private fun eventFact(e: PhysicalEvent): String {
        val raw = e.summary.trim()
        val cleaned = AskAnswerCleaner.userFacingSummary(raw)
        // Keep more of on-screen OCR / clip text for Qwen.
        val limit = when {
            e.source.equals("SMRITI_PLAY", true) ||
                e.source.equals("SCREENMIND", true) ||
                e.source.equals("OCR", true) ||
                e.source.equals("NEURAL_CORE", true) ||
                raw.contains("Seen on screen", ignoreCase = true) ||
                raw.contains("ScreenMind", ignoreCase = true) ||
                raw.contains("Game/App opened", ignoreCase = true) -> 1_200
            else -> 400
        }
        return cleaned.take(limit)
    }
}
