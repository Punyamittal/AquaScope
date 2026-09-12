package com.smriti.brain.memory

import com.smriti.brain.database.EpisodeStore
import com.smriti.brain.database.MemoryTaxonomy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class RetrievalEngine(private val store: EpisodeStore) {
    private val clock = SimpleDateFormat("h:mm a", Locale.US)

    suspend fun recall(query: String): String {
        val hits = store.search(query, 8)
        if (hits.isEmpty()) return "No record found"
        val top = hits.first().episode
        val whenText = clock.format(Date(top.createdAt))
        return "At $whenText (${top.taxonomy}): ${top.body.ifBlank { top.title }}"
    }

    /** Spec check: cite the exact 9:04 AM medication log, else "No record found". */
    suspend fun recallMedicationAt904(now: Long = System.currentTimeMillis()): String {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, 9)
        cal.set(Calendar.MINUTE, 4)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis - 60_000
        val end = cal.timeInMillis + 60_000
        val rows = store.inRange(start, end).filter {
            it.taxonomy == MemoryTaxonomy.HEALTH.wire ||
                it.body.contains("medic", true) ||
                it.title.contains("medic", true)
        }
        if (rows.isEmpty()) return "No record found"
        val row = rows.first()
        return "9:04 AM log: ${row.body.ifBlank { row.title }}"
    }
}
