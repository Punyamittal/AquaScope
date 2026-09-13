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
 * Supports multilingual directives (Hindi, Hinglish, Spanish, French, German),
 * on-screen OCR grounding, and Edge Gallery skill injections.
 */
object GroundedPromptBuilder {

    private val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)
    private val currentDateTimeFmt = SimpleDateFormat("EEEE, d MMM yyyy, h:mm a", Locale.US)

    fun detectLanguage(question: String, selectedPref: String = "auto"): String {
        if (selectedPref.isNotBlank() && selectedPref.lowercase() != "auto") {
            return selectedPref.lowercase()
        }
        val q = question.lowercase(Locale.getDefault())
        if (question.any { it in '\u0900'..'\u097F' }) return "hi"
        val hinglishPattern = Regex("""\b(kya|hai|hain|yeh|ye|mujhe|batao|ho|raha|rahi|kab|shuru|nahi|pehle|aaj|kaun|kaise|pani|paani|kitna|gusal|rasoi|futa|badh|theek|sahi|saboot|praman|sunao|kuch)\b""")
        if (hinglishPattern.containsMatchIn(q)) return "hinglish"
        val spanishPattern = Regex("""\b(qu[eé]|est[aá]|fuga|agua|cu[aá]ndo|hoy|ayer|semana|por\s+qu[eé])\b""")
        if (spanishPattern.containsMatchIn(q)) return "es"
        val frenchPattern = Regex("""\b(fuite|eau|quand|aujourd'hui|hier|pourquoi|est-ce)\b""")
        if (frenchPattern.containsMatchIn(q)) return "fr"
        return "en"
    }

    fun build(
        question: String,
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = "",
        language: String = "auto"
    ): String {
        val target = detectLanguage(question, language)
        val facts = factsBlock(ruleAnswer, events, target)
        val skills = skillSections(skillMatch, skillCatalogBlurb)
        val (langRule, examples) = getLanguageDirectives(target)

        return """
You are SMRITI, an on-device home acoustic-memory assistant.
Rewrite the FACTS below into a clear, natural, human response.

TARGET LANGUAGE: $target
$langRule

$examples

Speak naturally and conversationally, like a knowledgeable assistant — elaborate, explain, add helpful general context or advice where it fits. Don't just restate the facts tersely.

SAFETY RULE (the only hard constraint):
- Never say a leak is confirmed, and never state a specific anomaly score, timestamp, or event that contradicts CANONICAL_ANSWER / MEMORY_EVENTS, unless CANONICAL_ANSWER already says so. Everything else is fair game — use your own words, general knowledge, and judgment freely.
- Use CURRENT_DATE_TIME below for anything involving today's date, current time, or "how long ago" — never guess or make one up.
- Do not repeat internal labels such as CURRENT_DATE_TIME, EVIDENCE_STATE, CANONICAL_ANSWER, MEMORY_EVENTS, SCREEN_OCR, file paths, or RMS numbers.
- If ACTIVE_SKILL is present, follow its instructions when they do not conflict with the safety rule above.

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
        skillCatalogBlurb: String = "",
        language: String = "auto"
    ): String {
        val target = detectLanguage(question, language)
        val facts = factsBlock(ruleAnswer, events, target)
        val skills = skillSections(skillMatch, skillCatalogBlurb)
        val adventure = skillMatch?.skill?.id == "kitchen-adventure"

        if (adventure) {
            return """
You are running an Edge Gallery skill.

$skills

USER_QUESTION: $question

Follow ACTIVE_SKILL instructions exactly. Keep replies short.

ANSWER:
""".trimIndent()
        }

        val (langRule, examples) = getLanguageDirectives(target)

        return """
You are SMRITI, an on-device home memory assistant.
Answer USER_QUESTION, grounded in the facts below (CANONICAL_ANSWER + MEMORY_EVENTS + SCREEN_OCR${if (skillMatch?.toolResult != null) " + SKILL_TOOL_RESULT" else ""}) but not limited to reciting them.

TARGET LANGUAGE: $target
$langRule

$examples

Speak naturally and conversationally — elaborate, explain, add helpful general knowledge or advice where it fits, like a real assistant would. If SCREEN_OCR names an app/game, that's the answer for "what game/app" questions.

SAFETY RULE (the only hard constraint):
- Never claim a confirmed leak, and never state a specific anomaly score, time, or event that contradicts CANONICAL_ANSWER / MEMORY_EVENTS, unless CANONICAL_ANSWER already does. Everything else is fair game.
- If facts are genuinely insufficient (EVIDENCE_STATE is UNKNOWN and there is no SCREEN_OCR / MEMORY text), say you don't have that in memory yet — unless SKILL_TOOL_RESULT has the answer.
- Use CURRENT_DATE_TIME below for anything involving today's date, current time, or "how long ago" — never guess or make one up.
- Do not repeat internal labels such as CURRENT_DATE_TIME, EVIDENCE_STATE, CANONICAL_ANSWER, MEMORY_EVENTS, SCREEN_OCR, file paths, or RMS numbers.
- If ACTIVE_SKILL is present, follow its instructions when they do not conflict with the safety rule above.

USER_QUESTION: $question

$skills
$facts

ANSWER:
""".trimIndent()
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

    private fun getLanguageDirectives(target: String): Pair<String, String> {
        return when (target) {
            "hi" -> {
                val rule = """
LANGUAGE DIRECTIVE (HINDI):
- You MUST answer COMPLETELY in natural, conversational Hindi using Devanagari script (हिन्दी).
- Do NOT output English sentences.
- Avoid robotic literal words; speak naturally like a helpful family assistant.
- Do NOT copy names, numbers, or dates from the example. Use ONLY the data given in MEMORY_EVENTS.
""".trimIndent()
                val ex = """
FEW-SHOT GUIDANCE (STYLE ONLY):
प्रश्न: क्या किचन में कोई समस्या है?
उत्तर: किचन में रात को 74% असामान्य ध्वनि स्तर देखा गया है। अभी कोई पक्का लीक नहीं है, लेकिन एक बार नल और पाइप की जांच ज़रूर कर लें।
""".trimIndent()
                Pair(rule, ex)
            }
            "hinglish" -> {
                val rule = """
LANGUAGE DIRECTIVE (HINGLISH):
- You MUST answer COMPLETELY in conversational Hinglish (Hindi written using the English alphabet).
- Do NOT answer in pure English. Speak like how Indians chat on WhatsApp.
- Example phrasing: "record hui hai", "abnormal sound dekha gaya hai", "koi leak confirm nahi hua hai".
- Do NOT copy names, numbers, or dates from the example. Use ONLY the data given in MEMORY_EVENTS.
""".trimIndent()
                val ex = """
FEW-SHOT GUIDANCE (STYLE ONLY):
Question: Kya kitchen me koi problem hai?
Answer: Kitchen me raat ko 74% abnormal sound dekha gaya hai. Abhi koi pakka leak nahi hai, par ek baar tap aur pipe check kar lijiye.
""".trimIndent()
                Pair(rule, ex)
            }
            "es" -> {
                val rule = "- You MUST answer COMPLETELY in natural Spanish (Español)."
                Pair(rule, "")
            }
            "fr" -> {
                val rule = "- You MUST answer COMPLETELY in natural French (Français)."
                Pair(rule, "")
            }
            "de" -> {
                val rule = "- You MUST answer COMPLETELY in natural German (Deutsch)."
                Pair(rule, "")
            }
            else -> {
                val rule = "- Respond in clear, calm spoken English."
                Pair(rule, "")
            }
        }
    }

    private fun factsBlock(
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        target: String = "en"
    ): String = buildString {
        appendScreenOcr()

        val stateTranslated = when (target) {
            "hi" -> when (ruleAnswer.evidenceState.name) {
                "OBSERVED" -> "OBSERVED (देखा गया / मिला है)"
                "POSSIBLE" -> "POSSIBLE (संभावित / पक्का नहीं)"
                "CONFIRMED" -> "CONFIRMED (पुष्ट / पक्का लीक)"
                else -> "UNKNOWN (कोई रिकॉर्ड नहीं / अज्ञात)"
            }
            "hinglish" -> when (ruleAnswer.evidenceState.name) {
                "OBSERVED" -> "OBSERVED (record mila hai)"
                "POSSIBLE" -> "POSSIBLE (sambhavit hai, pakka nahi)"
                "CONFIRMED" -> "CONFIRMED (confirmed leak)"
                else -> "UNKNOWN (koi record nahi mila)"
            }
            else -> ruleAnswer.evidenceState.name
        }

        appendLine("CURRENT_DATE_TIME: ${currentDateTimeFmt.format(Date())}")
        appendLine("EVIDENCE_STATE: $stateTranslated")
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
            screenFirst.take(6).forEach { e ->
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
