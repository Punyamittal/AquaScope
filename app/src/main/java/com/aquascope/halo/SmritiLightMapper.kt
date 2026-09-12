package com.aquascope.halo

import com.aquascope.baseline.AnomalyThresholds
import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.EventStatus
import com.aquascope.smriti.model.EventType
import com.aquascope.smriti.model.PhysicalEvent

/** Maps SMRITI domain outcomes → semantic Monster Halo states. */
object SmritiLightMapper {

    fun fromHomeStatus(statusLine: String, anomalyCount: Int, maxScore: Double = 0.0): SmritiLightState {
        val s = statusLine.uppercase()
        return when {
            s.contains("CONFIRM") -> SmritiLightState.CONFIRMED
            s.contains("REPEATED") || anomalyCount >= 3 -> SmritiLightState.PERSISTENT_ANOMALY
            s.contains("ANOMALY") || s.contains("OBSERVATION") || anomalyCount > 0 ->
                if (maxScore < AnomalyThresholds.GREEN_MAX) SmritiLightState.ANOMALY
                else fromScore(maxScore)
            else -> SmritiLightState.NORMAL
        }
    }

    fun fromScanEvent(event: PhysicalEvent?): SmritiLightState {
        if (event == null) return SmritiLightState.NORMAL
        if (event.status == EventStatus.USER_CONFIRMED) return SmritiLightState.CONFIRMED
        return when (event.eventType) {
            EventType.POSSIBLE_LEAK, EventType.REPEATED_ANOMALY -> SmritiLightState.PERSISTENT_ANOMALY
            EventType.BASELINE_ESTABLISHED, EventType.NORMAL -> SmritiLightState.NORMAL
            EventType.ANOMALY, EventType.ACOUSTIC_DEVIATION ->
                fromScore(event.anomalyScore, event.previousOccurrenceCount)
            else -> SmritiLightState.UNKNOWN
        }
    }

    fun fromScore(score: Double, priorAnomalies: Int = 0): SmritiLightState = when {
        score < AnomalyThresholds.GREEN_MAX -> SmritiLightState.NORMAL
        score < AnomalyThresholds.HALO_HIGH -> SmritiLightState.ANOMALY
        priorAnomalies >= 1 -> SmritiLightState.PERSISTENT_ANOMALY
        else -> SmritiLightState.HIGH_ANOMALY
    }

    fun fromAsk(evidence: EvidenceState, relatedCount: Int, maxScore: Double = 0.0): SmritiLightState = when {
        evidence == EvidenceState.UNKNOWN || relatedCount == 0 -> SmritiLightState.UNKNOWN
        evidence == EvidenceState.CONFIRMED -> SmritiLightState.CONFIRMED
        evidence == EvidenceState.POSSIBLE || evidence == EvidenceState.INFERRED -> {
            val byScore = fromScore(maxScore)
            if (byScore == SmritiLightState.NORMAL) SmritiLightState.ANOMALY else byScore
        }
        else -> SmritiLightState.NORMAL
    }
}
