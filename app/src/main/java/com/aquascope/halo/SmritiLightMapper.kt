package com.aquascope.halo

import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.EventStatus
import com.aquascope.smriti.model.EventType
import com.aquascope.smriti.model.PhysicalEvent

/** Maps SMRITI domain outcomes → semantic Monster Halo states. */
object SmritiLightMapper {

    fun fromHomeStatus(statusLine: String, anomalyCount: Int): SmritiLightState {
        val s = statusLine.uppercase()
        return when {
            s.contains("CONFIRM") -> SmritiLightState.CONFIRMED
            s.contains("REPEATED") || anomalyCount >= 3 -> SmritiLightState.PERSISTENT_ANOMALY
            s.contains("ANOMALY") || s.contains("OBSERVATION") || anomalyCount > 0 ->
                SmritiLightState.ANOMALY
            else -> SmritiLightState.NORMAL
        }
    }

    fun fromScanEvent(event: PhysicalEvent?): SmritiLightState {
        if (event == null) return SmritiLightState.NORMAL
        if (event.status == EventStatus.USER_CONFIRMED) return SmritiLightState.CONFIRMED
        return when (event.eventType) {
            EventType.POSSIBLE_LEAK, EventType.REPEATED_ANOMALY -> SmritiLightState.PERSISTENT_ANOMALY
            EventType.ANOMALY, EventType.ACOUSTIC_DEVIATION -> SmritiLightState.ANOMALY
            EventType.BASELINE_ESTABLISHED, EventType.NORMAL -> SmritiLightState.NORMAL
            else -> SmritiLightState.UNKNOWN
        }
    }

    fun fromScore(score: Double, priorAnomalies: Int = 0): SmritiLightState = when {
        score >= 70 && priorAnomalies >= 1 -> SmritiLightState.PERSISTENT_ANOMALY
        score >= 70 -> SmritiLightState.ANOMALY
        score >= 30 -> SmritiLightState.ANOMALY
        else -> SmritiLightState.NORMAL
    }

    fun fromAsk(evidence: EvidenceState, relatedCount: Int): SmritiLightState = when {
        evidence == EvidenceState.UNKNOWN || relatedCount == 0 -> SmritiLightState.UNKNOWN
        evidence == EvidenceState.POSSIBLE || evidence == EvidenceState.INFERRED ->
            SmritiLightState.ANOMALY
        evidence == EvidenceState.CONFIRMED -> SmritiLightState.CONFIRMED
        else -> SmritiLightState.NORMAL
    }
}
