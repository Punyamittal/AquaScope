package com.aquascope.smriti.engine

import com.aquascope.smriti.memory.EpisodicMemoryStore
import com.aquascope.smriti.model.EventType
import com.aquascope.smriti.model.MemoryQuery
import com.aquascope.smriti.model.PhysicalEvent
import com.aquascope.smriti.model.QueryIntent
import java.util.Calendar
import java.util.Locale

/**
 * Structured + temporal retrieval over local episodic memory.
 * Does NOT dump the whole DB into an LLM.
 */
class RetrievalEngine(
    private val store: EpisodicMemoryStore
) {

    fun parse(question: String): MemoryQuery {
        val q = question.lowercase(Locale.getDefault())
        val intent = when {
            q.contains("definitely a leak") || q.contains("is it a leak") ||
                q.contains("confirm") && q.contains("leak") -> QueryIntent.IS_DEFINITE_LEAK
            q.contains("first") || q.contains("when did") || q.contains("start") ->
                QueryIntent.FIRST_OCCURRENCE
            q.contains("before") || q.contains("happened before") || q.contains("similar") ->
                QueryIntent.HAS_HAPPENED_BEFORE
            q.contains("this week") || q.contains("how many") -> QueryIntent.COUNT_THIS_WEEK
            q.contains("today") || q.contains("changed today") || q.contains("what changed") ->
                QueryIntent.WHAT_CHANGED_TODAY
            q.contains("worse") || q.contains("getting worse") || q.contains("increasing") ->
                QueryIntent.IS_GETTING_WORSE
            q.contains("evidence") || q.contains("why") || q.contains("show me") && q.contains("record") ->
                QueryIntent.SHOW_EVIDENCE
            q.contains("normal") || q.contains("baseline") -> QueryIntent.WHAT_IS_NORMAL
            else -> QueryIntent.GENERAL
        }

        val locationHint = when {
            q.contains("kitchen") && !q.contains("alert") -> "kitchen"
            q.contains("bathroom") -> "bathroom"
            q.contains("pump") -> "pump"
            q.contains("pipe") -> "pipe"
            q.contains("guardian") || q.contains("kitchen alert") ||
                q.contains("smoke") || q.contains("glass") -> "guardian"
            q.contains("ir blaster") || q.contains("infrared") ||
                (q.contains("ir") && (q.contains("blast") || q.contains("pulse") || q.contains("arm"))) -> "ir"
            q.contains("recording") || q.contains("clip") || q.contains("on screen") ||
                q.contains("screen capture") || q.contains("screen recording") ||
                q.contains("what was on") || q.contains("from the screen") ||
                q.contains("what did i see") || q.contains("what did you see") ||
                (q.contains("screen") && (q.contains("see") || q.contains("saw") ||
                    q.contains("show") || q.contains("text") || q.contains("read") ||
                    q.contains("ocr") || q.contains("watch") || q.contains("record"))) -> "screen"
            else -> null
        }

        val since = when {
            intent == QueryIntent.WHAT_CHANGED_TODAY || q.contains("today") -> startOfDayMs()
            intent == QueryIntent.COUNT_THIS_WEEK || q.contains("week") -> startOfWeekMs()
            q.contains("yesterday") -> startOfDayMs() - 24L * 60 * 60 * 1000
            else -> null
        }
        val until = if (q.contains("yesterday")) startOfDayMs() else null

        return MemoryQuery(
            raw = question,
            locationHint = locationHint,
            sinceMs = since,
            untilMs = until,
            intent = intent
        )
    }

    fun retrieve(query: MemoryQuery, limit: Int = 12): List<PhysicalEvent> {
        var events: List<PhysicalEvent> = store.loadEvents()

        query.locationHint?.let { hint ->
            events = events.filter { ev ->
                matchesHint(ev, hint)
            }
        }
        query.sinceMs?.let { since -> events = events.filter { it.timestampMs >= since } }
        query.untilMs?.let { until -> events = events.filter { it.timestampMs < until } }

        if (query.eventTypes.isNotEmpty()) {
            events = events.filter { it.eventType in query.eventTypes }
        }

        return when (query.intent) {
            QueryIntent.FIRST_OCCURRENCE ->
                events.filter { isDeviation(it) }.sortedBy { it.timestampMs }.take(limit)
            QueryIntent.HAS_HAPPENED_BEFORE, QueryIntent.IS_GETTING_WORSE,
            QueryIntent.SHOW_EVIDENCE, QueryIntent.IS_DEFINITE_LEAK ->
                events.filter { isDeviation(it) }.sortedByDescending { it.timestampMs }.take(limit)
            QueryIntent.WHAT_IS_NORMAL ->
                events.filter {
                    it.eventType == EventType.NORMAL || it.eventType == EventType.BASELINE_ESTABLISHED
                }.sortedByDescending { it.timestampMs }.take(limit)
            else ->
                events.sortedByDescending { it.timestampMs }.take(limit)
        }
    }

    fun anomalousForObject(objectId: String): List<PhysicalEvent> =
        store.eventsForObject(objectId).filter { isDeviation(it) }

    private fun matchesHint(ev: PhysicalEvent, hint: String): Boolean {
        val h = hint.lowercase(Locale.getDefault())
        if (ev.locationLabel.contains(h, ignoreCase = true) ||
            ev.objectLabel.contains(h, ignoreCase = true) ||
            ev.summary.contains(h, ignoreCase = true) ||
            ev.source.contains(h, ignoreCase = true)
        ) {
            return true
        }
        return when (h) {
            "guardian" ->
                ev.source.equals("GUARDIAN", true) ||
                    ev.summary.contains("Guardian", ignoreCase = true)
            "ir" ->
                ev.source.equals("IR", true) ||
                    ev.summary.contains("IR pulse", ignoreCase = true) ||
                    ev.summary.contains("IR armed", ignoreCase = true)
            "screen" ->
                ev.source.equals("SMRITI_PLAY", true) ||
                    ev.summary.contains("Screen clip", ignoreCase = true) ||
                    ev.summary.contains("Seen on screen", ignoreCase = true)
            else -> false
        }
    }

    private fun isDeviation(e: PhysicalEvent) = e.eventType in setOf(
        EventType.ACOUSTIC_DEVIATION,
        EventType.ANOMALY,
        EventType.REPEATED_ANOMALY,
        EventType.POSSIBLE_LEAK
    )

    private fun startOfDayMs(): Long {
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun startOfWeekMs(): Long {
        val c = Calendar.getInstance()
        c.firstDayOfWeek = Calendar.MONDAY
        c.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
