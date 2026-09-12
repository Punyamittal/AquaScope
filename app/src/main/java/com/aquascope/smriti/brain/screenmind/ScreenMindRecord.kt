package com.aquascope.smriti.brain.screenmind

/**
 * ScreenMind-style activity record (inspired by
 * https://github.com/ayushh0110/ScreenMind).
 */
data class ScreenMindRecord(
    val appName: String = "unknown",
    val category: String = "other",
    val summary: String = "",
    val detailedContext: String = "",
    val mood: String = "neutral",
    val confidence: Double = 0.0,
    val sceneDescription: String = "",
    val visibleText: String = "",
    val snippets: List<String> = emptyList()
) {
    fun memoryText(reason: String = "screen"): String = buildString {
        append("ScreenMind · $reason")
        append("\nApp: $appName")
        append(" · Category: $category")
        append(" · Mood: $mood")
        if (confidence > 0) append(" · conf=${"%.2f".format(confidence)}")
        if (summary.isNotBlank()) {
            append("\nSummary: ")
            append(summary)
        }
        if (detailedContext.isNotBlank()) {
            append("\nContext: ")
            append(detailedContext.take(800))
        }
        if (sceneDescription.isNotBlank()) {
            append("\nScene: ")
            append(sceneDescription.take(1_000))
        }
        if (visibleText.isNotBlank()) {
            append("\nSeen on screen:\n")
            append(visibleText.take(1_500))
        } else if (snippets.isNotEmpty()) {
            append("\nSeen on screen:\n")
            append(snippets.joinToString("\n").take(1_500))
        }
    }

    fun askContext(): String = buildString {
        append("SCREENMIND: app=$appName category=$category mood=$mood")
        if (category == "gaming" || appName !in listOf("Phone UI", "unknown", "")) {
            append("\nGame/App opened: $appName")
        }
        if (summary.isNotBlank()) append("\nSummary: $summary")
        if (visibleText.isNotBlank()) {
            append("\nSeen on screen:\n")
            append(visibleText.take(2_000))
        } else if (sceneDescription.isNotBlank()) {
            append("\nScene:\n")
            append(sceneDescription.take(2_000))
        }
    }
}
