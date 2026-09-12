package com.smriti.core.memory

/**
 * Deterministic rule-based episode classifier (SPEC section 2.1).
 *
 * Ordered rule priority — FIRST match wins:
 *   ACCESS -> MONEY -> HEALTH -> TOOL -> LOCATION -> PLACE -> PEOPLE, else EPISODE.
 *
 * Returns a List (contract) currently holding a single (type, summary) pair;
 * the List shape keeps the door open for future multi-label classification
 * without breaking the [SmritiMemoryEngine] insert loop.
 *
 * Summary = first 80 chars of the trimmed text, cut back to the last word
 * boundary so we never split a word mid-way.
 */
object TaxonomyParser {

    // wifi / password / gate-code style secrets -> ACCESS
    private val accessRe = Regex("wifi|wi-fi|password|pass:|gate code", RegexOption.IGNORE_CASE)

    // due / invoice / amount / rupee / dollar -> MONEY
    private val moneyRe = Regex("\\b(due|invoice|amount)\\b|[₹$]", RegexOption.IGNORE_CASE)

    // dosage like "500mg" / "5 ml" -> HEALTH (case-sensitive: avoids "12 MG Road" address false-positives)
    private val healthRe = Regex("(\\d+)\\s?(mg|ml)")

    // URLs, .apk package names, or explicit tool/app words -> TOOL
    private val toolRe = Regex(
        "(https?://\\S+|www\\.\\S+)|\\b[\\w.-]+\\.apk\\b|\\b(apk|app|installer|tool)\\b",
        RegexOption.IGNORE_CASE
    )

    // parked vehicle / floor-level / slot style breadcrumbs -> LOCATION
    private val locationRe = Regex("\\b(park|parked|parking|level|slot)\\b", RegexOption.IGNORE_CASE)

    // address patterns: Indian 6-digit pincode or street words -> PLACE
    private val placeRe = Regex(
        "\\b\\d{6}\\b|\\b(street|st\\.|road|rd\\.|lane|nagar|avenue|ave\\.)\\b",
        RegexOption.IGNORE_CASE
    )

    // social/meeting verbs (typically followed by a person name) -> PEOPLE
    private val peopleRe = Regex(
        "\\b(comes|coming|visit|visits|visiting|call|calls|called|calling|meeting|meet)\\b",
        RegexOption.IGNORE_CASE
    )

    private const val SUMMARY_MAX = 80

    /**
     * Classify [text] and build its summary.
     * @return list of (EntityType, summary) pairs — highest-priority matching rule,
     *         or a single EPISODE pair when no rule fires.
     */
    fun parse(text: String): List<Pair<EntityType, String>> {
        val type = when {
            accessRe.containsMatchIn(text) -> EntityType.ACCESS
            moneyRe.containsMatchIn(text) -> EntityType.MONEY
            healthRe.containsMatchIn(text) -> EntityType.HEALTH
            toolRe.containsMatchIn(text) -> EntityType.TOOL
            locationRe.containsMatchIn(text) -> EntityType.LOCATION
            placeRe.containsMatchIn(text) -> EntityType.PLACE
            peopleRe.containsMatchIn(text) -> EntityType.PEOPLE
            else -> EntityType.EPISODE
        }
        return listOf(type to summarize(text))
    }

    /** First [maxLen] chars of the trimmed text, trimmed back to a word boundary. */
    fun summarize(text: String, maxLen: Int = SUMMARY_MAX): String {
        val trimmed = text.trim()
        if (trimmed.length <= maxLen) return trimmed
        val window = trimmed.substring(0, maxLen)
        val lastSpace = window.lastIndexOf(' ')
        return if (lastSpace > 0) window.substring(0, lastSpace).trim() else window
    }
}
