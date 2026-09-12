package com.aquascope.smriti.brain

/**
 * Deterministic routing of OCR / transcripts into typed episodic entities.
 * Never invents facts — only labels what the text already contains.
 */
object TaxonomyParser {

    enum class Kind {
        TOOLS, HEALTH, PLACES, LOCATION, MONEY, PEOPLE, ACCESS, GAME, ACOUSTIC, UNKNOWN
    }

    data class Parsed(
        val kind: Kind,
        val title: String,
        val fields: Map<String, String>
    )

    fun parse(raw: String): Parsed {
        val text = raw.trim()
        if (text.isEmpty()) {
            return Parsed(Kind.UNKNOWN, "Empty input", emptyMap())
        }
        val lower = text.lowercase()
        val title = extractTitle(text)
        return when {
            matches(lower, GAME) ->
                Parsed(Kind.GAME, title, fields(text, "event" to text))
            matches(lower, HEALTH) ->
                Parsed(
                    Kind.HEALTH,
                    title,
                    fields(
                        text,
                        "medicine" to capture(lower, Regex("""(mg|ml|tablet|capsule|dose|insulin|paracetamol|ibuprofen)""")),
                        "note" to text
                    )
                )
            matches(lower, MONEY) ->
                Parsed(
                    Kind.MONEY,
                    title,
                    fields(
                        text,
                        "amount" to capture(text, Regex("""(?:₹|rs\.?|inr|usd|\$)\s*[\d,]+(?:\.\d{2})?""", RegexOption.IGNORE_CASE)),
                        "note" to text
                    )
                )
            matches(lower, ACCESS) ->
                Parsed(
                    Kind.ACCESS,
                    title,
                    fields(text, "secret_kind" to "local_code", "note" to text)
                )
            matches(lower, TOOLS) ->
                Parsed(
                    Kind.TOOLS,
                    title,
                    fields(
                        text,
                        "url" to capture(text, Regex("""https?://\S+|www\.\S+""", RegexOption.IGNORE_CASE)),
                        "purpose" to text
                    )
                )
            matches(lower, PLACES) || matches(lower, LOCATION) ->
                Parsed(
                    Kind.LOCATION,
                    title,
                    fields(text, "place" to text)
                )
            matches(lower, PEOPLE) ->
                Parsed(Kind.PEOPLE, title, fields(text, "conversation" to text))
            matches(lower, ACOUSTIC) ->
                Parsed(Kind.ACOUSTIC, title, fields(text, "signal" to text))
            else -> Parsed(Kind.UNKNOWN, title, fields(text, "raw" to text))
        }
    }

    private val GAME = listOf(
        "kill", "knock", "victory", "winner winner", "bgmi", "death cam", "clutch",
        "clip", "recording", "peace", "highlight", "gaming", "kill feed", "screenmind"
    )
    private val HEALTH = listOf("mg", "tablet", "capsule", "dose", "medicine", "insulin", "medication", "pill")
    private val MONEY = listOf("invoice", "due", "payment", "₹", "rs.", "total", "amount")
    private val ACCESS = listOf("wifi", "wi-fi", "password", "gate code", "otp", "pin ")
    private val TOOLS = listOf("http", "www.", "github", "app:", "software", "api key")
    private val PLACES = listOf("address", "street", "road", "nagar", "sector")
    private val LOCATION = listOf("parking", "level", "basement", "mall", "landmark")
    private val PEOPLE = listOf("called", "meeting", "visit", "tomorrow", "said")
    private val ACOUSTIC = listOf("alarm", "glass", "cough", "fall", "leak", "anomaly", "guardian", "smoke", "ir blast", "infrared")

    private fun matches(lower: String, keys: List<String>): Boolean =
        keys.any { lower.contains(it) }

    private fun firstLine(text: String): String =
        text.lineSequence().firstOrNull { it.isNotBlank() }?.take(80) ?: "Memory"

    /** Prefer an explicit Title: / Summary: line so PEACE clips show a real headline in MEMORY. */
    private fun extractTitle(text: String): String {
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isBlank()) continue
            val titled = Regex("""(?i)^(?:title|summary)\s*:\s*(.+)$""").find(t)?.groupValues?.getOrNull(1)
            if (!titled.isNullOrBlank()) return titled.take(100)
        }
        val first = firstLine(text)
        // Skip generic PEACE banners as the card title.
        if (first.startsWith("PEACE", true) || first.equals("highlight clip", true)) {
            val better = text.lineSequence()
                .map { it.trim() }
                .firstOrNull {
                    it.isNotBlank() &&
                        !it.startsWith("PEACE", true) &&
                        !it.startsWith("Category:", true) &&
                        !it.startsWith("File:", true) &&
                        !it.startsWith("Tags:", true)
                }
            if (!better.isNullOrBlank()) return better.take(100)
        }
        return first
    }

    private fun capture(text: String, regex: Regex): String =
        regex.find(text)?.value.orEmpty()

    private fun fields(text: String, vararg pairs: Pair<String, String>): Map<String, String> {
        val map = linkedMapOf("ingested" to text.take(400))
        pairs.forEach { (k, v) -> if (v.isNotBlank()) map[k] = v }
        return map
    }
}
