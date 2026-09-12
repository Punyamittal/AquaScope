package com.smriti.aqua.memory

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * A grounded answer to a recall query.
 *
 * @param text human-readable answer, built ONLY from stored rows.
 * @param evidenceIds ids of the events the answer was derived from (empty for refusals-without-data).
 * @param grounded always true for this engine: it never answers from anything but the log.
 */
data class RecallAnswer(
    val text: String,
    val evidenceIds: List<Long>,
    val grounded: Boolean
)

/**
 * RecallEngine — deterministic, grounded Q&A over the episodic log.
 *
 * Intent parsing is pure regex/keyword matching (case-insensitive, en + basic hi).
 * Every number in every answer is computed from real stored rows. When the log
 * holds nothing relevant, the engine honestly refuses instead of guessing.
 */
class RecallEngine(private val store: EpisodicStore) {

    companion object {
        private val TIME_FMT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
        private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)

        // Intent patterns (case-insensitive). Order = priority.
        private val INTENT_START = Regex("when did it start|first detected|start", RegexOption.IGNORE_CASE)
        private val INTENT_BEFORE = Regex("happened before|before|previous|similar", RegexOption.IGNORE_CASE)
        private val INTENT_CONFIRM = Regex("sure|confirm|is it a leak|broken", RegexOption.IGNORE_CASE)
        private val INTENT_LAST_NORMAL = Regex("last normal|when.*normal", RegexOption.IGNORE_CASE)
        private val INTENT_STATUS = Regex("status|what.*wrong|summary", RegexOption.IGNORE_CASE)

        private val DEVANAGARI = Regex("[\\u0900-\\u097F]")
        private val HINGLISH = Regex("\\b(kya|hai|nahi|kab|paas)\\b", RegexOption.IGNORE_CASE)

        private const val NO_RECORD_EN = "I have no record of that."
        private const val NO_RECORD_HI = "Mere paas koi record nahi hai."
    }

    fun answer(query: String): RecallAnswer {
        val noRecord = noRecord(query)

        // Object resolution: a known object id mentioned in the query wins;
        // otherwise fall back to the object of the most recent event.
        val knownIds = store.allObjectIds()
        if (knownIds.isEmpty()) return noRecord
        val lowerQuery = query.lowercase(Locale.US)
        val objectId = knownIds.firstOrNull { lowerQuery.contains(it.lowercase(Locale.US)) }
            ?: knownIds.first() // allObjectIds() is ordered by most recent activity

        val events = store.eventsFor(objectId)
        if (events.isEmpty()) return noRecord

        return when {
            INTENT_START.containsMatchIn(query) -> answerWhenStarted(objectId, events, noRecord)
            INTENT_BEFORE.containsMatchIn(query) -> answerHappenedBefore(objectId, events, noRecord)
            INTENT_CONFIRM.containsMatchIn(query) -> answerConfirm(objectId, noRecord)
            INTENT_LAST_NORMAL.containsMatchIn(query) -> answerLastNormal(objectId, noRecord)
            INTENT_STATUS.containsMatchIn(query) -> answerStatus(objectId, events, noRecord)
            else -> noRecord
        }
    }

    // ---------- Intent 1: when did it start ----------

    /** Earliest ANOMALY in the current (most recent) burst of consecutive anomalies. */
    private fun answerWhenStarted(objectId: String, events: List<Event>, noRecord: RecallAnswer): RecallAnswer {
        val burst = currentBurst(events)
        if (burst.isEmpty()) return noRecord
        val first = burst.last() // burst is newest-first; last element is the earliest
        return RecallAnswer(
            text = "The anomaly was first observed at ${formatTime(first.timestamp)}.",
            evidenceIds = burst.map { it.id },
            grounded = true
        )
    }

    /** Consecutive ANOMALY run (newest-first) that contains the most recent anomaly. */
    private fun currentBurst(events: List<Event>): List<Event> {
        val firstAnomalyIdx = events.indexOfFirst { it.status == EventStatus.ANOMALY }
        if (firstAnomalyIdx < 0) return emptyList()
        val burst = ArrayList<Event>()
        for (i in firstAnomalyIdx until events.size) {
            if (events[i].status != EventStatus.ANOMALY) break
            burst.add(events[i])
        }
        return burst
    }

    // ---------- Intent 2: has this happened before ----------

    private fun answerHappenedBefore(objectId: String, events: List<Event>, noRecord: RecallAnswer): RecallAnswer {
        val burstIds = currentBurst(events).map { it.id }.toSet()
        val prior = store.anomaliesFor(objectId).filter { it.id !in burstIds }
        return if (prior.isEmpty()) {
            RecallAnswer(
                text = "No, I have no earlier anomalies on record for $objectId before the current one.",
                evidenceIds = emptyList(),
                grounded = true
            )
        } else {
            val last = prior.first() // anomaliesFor is newest-first
            RecallAnswer(
                text = "Yes. I have ${prior.size} earlier anomal${if (prior.size == 1) "y" else "ies"} " +
                    "on record for $objectId. The last one was observed at ${formatTime(last.timestamp)}.",
                evidenceIds = prior.map { it.id },
                grounded = true
            )
        }
    }

    // ---------- Intent 3: are you sure / is it a leak -> THE REFUSAL ----------

    private fun answerConfirm(objectId: String, noRecord: RecallAnswer): RecallAnswer {
        val latest = store.anomaliesFor(objectId).firstOrNull() ?: return noRecord
        val pct = (latest.deviation * 100f).roundToInt()
        return RecallAnswer(
            text = "No. I can confirm an abnormal acoustic signature ($pct% deviation), " +
                "but I cannot confirm a leak from the available evidence.",
            evidenceIds = listOf(latest.id),
            grounded = true
        )
    }

    // ---------- Intent 4: last normal ----------

    private fun answerLastNormal(objectId: String, noRecord: RecallAnswer): RecallAnswer {
        val lastNormal = store.lastNormalBefore(objectId, Long.MAX_VALUE) ?: return noRecord
        return RecallAnswer(
            text = "$objectId was last normal at ${formatTime(lastNormal.timestamp)}.",
            evidenceIds = listOf(lastNormal.id),
            grounded = true
        )
    }

    // ---------- Intent 5: status / summary ----------

    private fun answerStatus(objectId: String, events: List<Event>, noRecord: RecallAnswer): RecallAnswer {
        val latest = events.first() // eventsFor is newest-first
        val window = events.take(3).asReversed() // chronological, up to 3
        val trend = trendOf(window.map { it.deviation })
        val pct = (latest.deviation * 100f).roundToInt()
        return RecallAnswer(
            text = "Current status of $objectId: ${latest.status.name}. " +
                "Latest deviation: $pct% at ${formatTime(latest.timestamp)}. " +
                "Trend over the last ${window.size} event${if (window.size == 1) "" else "s"}: $trend.",
            evidenceIds = window.map { it.id },
            grounded = true
        )
    }

    /** "rising" / "stable" / "falling" from up-to-3 deviations in chronological order. */
    private fun trendOf(deviations: List<Float>): String {
        if (deviations.size < 2) return "stable"
        val eps = 0.02f
        var rising = true
        var falling = true
        for (i in 1 until deviations.size) {
            if (deviations[i] <= deviations[i - 1] + eps) rising = false
            if (deviations[i] >= deviations[i - 1] - eps) falling = false
        }
        return when {
            rising -> "rising"
            falling -> "falling"
            else -> "stable"
        }
    }

    // ---------- Formatting & fallback ----------

    /** "h:mm a" today; "h:mm a, d MMM yyyy" on any other day. */
    private fun formatTime(ts: Long): String {
        val zone = ZoneId.systemDefault()
        val dt = Instant.ofEpochMilli(ts).atZone(zone)
        val time = TIME_FMT.format(dt)
        return if (dt.toLocalDate() == LocalDate.now(zone)) {
            time
        } else {
            "$time, ${DATE_FMT.format(dt)}"
        }
    }

    private fun noRecord(query: String): RecallAnswer {
        val hindi = DEVANAGARI.containsMatchIn(query) || HINGLISH.containsMatchIn(query)
        return RecallAnswer(
            text = if (hindi) NO_RECORD_HI else NO_RECORD_EN,
            evidenceIds = emptyList(),
            grounded = true
        )
    }
}
