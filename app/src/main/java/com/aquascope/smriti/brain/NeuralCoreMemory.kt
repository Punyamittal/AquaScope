package com.aquascope.smriti.brain

import android.content.Context
import android.util.Log
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.model.EventType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes Neural Core observations into both brain SQLite and AquaScope Ask memory.
 */
object NeuralCoreMemory {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastByKey = ConcurrentHashMap<String, Long>()

    fun rememberAsync(
        context: Context,
        raw: String,
        source: String,
        evidencePath: String? = null,
        kind: TaxonomyParser.Kind? = null,
        eventType: EventType = EventType.UNKNOWN,
        anomalyScore: Double = 0.0,
        throttleMs: Long = 0L,
        onStored: ((EpisodeRecord) -> Unit)? = null
    ) {
        if (throttled("$source:${raw.take(64)}", throttleMs)) return
        scope.launch {
            val rec = runCatching {
                remember(context, raw, source, evidencePath, kind, eventType, anomalyScore)
            }.onFailure { t ->
                Log.w(TAG, "remember failed: ${t.message}")
            }.getOrNull() ?: return@launch
            onStored?.invoke(rec)
        }
    }

    suspend fun remember(
        context: Context,
        raw: String,
        source: String,
        evidencePath: String? = null,
        kind: TaxonomyParser.Kind? = null,
        eventType: EventType = EventType.UNKNOWN,
        anomalyScore: Double = 0.0
    ): EpisodeRecord {
        val rec = SmritiMemoryEngine.get(context).ingest(raw, source, evidencePath, kind)
        val location = when (source) {
            "SMRITI_PLAY" -> "Screen capture"
            "IR" -> "IR blaster"
            "GUARDIAN" -> "Guardian"
            else -> "This phone"
        }
        SmritiCore.get(context).rememberObservation(
            summary = raw,
            source = source,
            locationLabel = location,
            eventType = eventType,
            anomalyScore = anomalyScore,
            evidenceNotes = listOfNotNull(
                evidencePath?.let { "Recording saved: $it" }
            )
        )
        return rec
    }

    private fun throttled(key: String, throttleMs: Long): Boolean {
        if (throttleMs <= 0L) return false
        val now = System.currentTimeMillis()
        val prev = lastByKey[key] ?: 0L
        if (now - prev < throttleMs) return true
        lastByKey[key] = now
        return false
    }

    private const val TAG = "NeuralCoreMemory"
}
