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
 * Builds a grounded Ask prompt: Qwen answers USER_QUESTION using only APP_DATA.
 * Supports multilingual directives, on-screen OCR, guardian/home context, and skills.
 */
object GroundedPromptBuilder {

    private val fmt = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US)

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

    /**
     * Primary Ask prompt: Qwen fully answers from app data (not a rewrite of rules).
     */
    fun buildAsk(
        question: String,
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = "",
        language: String = "auto",
        appContext: String = ""
    ): String {
        val target = detectLanguage(question, language)
        val facts = factsBlock(ruleAnswer, events, target, appContext)
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
        val hasTool = skillMatch?.toolResult != null

        return """
You are SMRITI, the on-device home memory assistant for AquaScope.
You completely handle Ask chat. Answer USER_QUESTION yourself using ONLY APP_DATA below.
Do not rewrite or quote a canned rules answer — speak as SMRITI from the facts.

TARGET LANGUAGE: $target
$langRule

$examples

HARD RULES:
- You own the entire Ask answer. Never say "according to the rules" or paste internal labels.
- Use ONLY APP_DATA (HOME_STATUS, GUARDIAN, SCAN_LOCATIONS, SCREEN_OCR, MEMORY_EVENTS, NEURAL_MEMORY, APP_HINT${if (hasTool) ", SKILL_TOOL_RESULT" else ""}).
- Prefer SCREEN_OCR for "what was on screen / game / clip" questions.
- Prefer GUARDIAN / MEMORY_EVENTS / NEURAL_MEMORY for sound, listening, IR, and kitchen alerts.
- Prefer HOME_STATUS / SCAN_LOCATIONS / MEMORY_EVENTS / NEURAL_MEMORY for scans, locations, moisture, and baselines.
- Do NOT invent events, leaks, times, places, scores, or confirmations.
- Never claim a confirmed leak unless APP_HINT or MEMORY_EVENTS already say so.
- If APP_DATA is empty or insufficient, say you do not have that in memory yet and suggest what to capture next.
- Be concise (under 140 words). Spoken sentences only. No markdown. No "As an AI".
- Do not repeat labels like EVIDENCE_STATE, APP_HINT, MEMORY_EVENTS, NEURAL_MEMORY, SCREEN_OCR, HOME_STATUS, GUARDIAN, file paths, or RMS numbers.
- If ACTIVE_SKILL is present, follow it when it does not conflict with the hard rules above.

USER_QUESTION: $question

$skills
$facts

ANSWER:
""".trimIndent()
    }

    /** @deprecated Prefer [buildAsk]. Kept for older call sites / tests. */
    fun build(
        question: String,
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = "",
        language: String = "auto"
    ): String = buildAsk(question, ruleAnswer, events, skillMatch, skillCatalogBlurb, language)

    /** @deprecated Prefer [buildAsk]. */
    fun buildChat(
        question: String,
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        skillMatch: SkillMatch? = null,
        skillCatalogBlurb: String = "",
        language: String = "auto"
    ): String = buildAsk(question, ruleAnswer, events, skillMatch, skillCatalogBlurb, language)

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
- Do NOT copy names, numbers, or dates from the example. Use ONLY the data given in APP_DATA.
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
- Do NOT copy names, numbers, or dates from the example. Use ONLY the data given in APP_DATA.
""".trimIndent()
                val ex = """
FEW-SHOT GUIDANCE (STYLE ONLY):
Question: Kya kitchen me koi problem hai?
Answer: Kitchen me raat ko 74% abnormal sound dekha gaya hai. Abhi koi pakka leak nahi hai, par ek baar tap aur pipe check kar lijiye.
""".trimIndent()
                Pair(rule, ex)
            }
            "es" -> Pair("- You MUST answer COMPLETELY in natural Spanish (Español).", "")
            "fr" -> Pair("- You MUST answer COMPLETELY in natural French (Français).", "")
            "de" -> Pair("- You MUST answer COMPLETELY in natural German (Deutsch).", "")
            else -> Pair("- Respond in clear, calm spoken English.", "")
        }
    }

    private fun factsBlock(
        ruleAnswer: SmritiAnswer,
        events: List<PhysicalEvent>,
        target: String = "en",
        appContext: String = ""
    ): String = buildString {
        appendLine("APP_DATA:")
        appendLine()

        if (appContext.isNotBlank()) {
            appendLine(appContext.trim())
            appendLine()
        }

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

        appendLine("EVIDENCE_STATE: $stateTranslated")
        appendLine("APP_HINT (optional retrieval notes — do not copy; answer the user yourself from all APP_DATA):")
        appendLine(ruleAnswer.text.trim().ifBlank { "(none)" })
        appendLine()

        val screenFirst = events.sortedByDescending { e ->
            when {
                e.id == "screen-ocr-latest" -> 3
                e.source.equals("SCREENMIND", true) ||
                    e.source.equals("SMRITI_PLAY", true) ||
                    e.source.equals("OCR", true) ||
                    e.source.equals("NEURAL_CORE", true) ||
                    e.source.equals("GUARDIAN", true) -> 2
                else -> 0
            }
        }

        appendLine("MEMORY_EVENTS (${screenFirst.size}):")
        if (screenFirst.isEmpty()) {
            appendLine("- (none)")
        } else {
            screenFirst.take(12).forEach { e ->
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
                e.source.equals("GUARDIAN", true) ||
                raw.contains("Seen on screen", ignoreCase = true) ||
                raw.contains("ScreenMind", ignoreCase = true) ||
                raw.contains("Game/App opened", ignoreCase = true) -> 1_200
            else -> 400
        }
        return cleaned.take(limit)
    }
}
