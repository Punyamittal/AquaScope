package com.aquascope.smriti.engine

import com.aquascope.smriti.model.EvidenceBundle
import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.PhysicalEvent

/** Builds forensic-style evidence bundles from stored events only. */
class EvidenceEngine {

    fun forEvent(event: PhysicalEvent): EvidenceBundle {
        val claim = when (event.evidenceState) {
            EvidenceState.POSSIBLE ->
                "Possible abnormal water flow at ${event.objectLabel}."
            EvidenceState.INFERRED ->
                "Inferred change in acoustic behavior at ${event.objectLabel}."
            EvidenceState.OBSERVED ->
                event.summary
            EvidenceState.CONFIRMED ->
                event.summary
            EvidenceState.UNKNOWN ->
                "Insufficient structured evidence for a firm claim."
        }
        return EvidenceBundle(
            claim = claim,
            evidenceState = event.evidenceState,
            supporting = event.evidenceNotes,
            unknown = event.unknownNotes,
            relatedEventIds = listOf(event.id)
        )
    }

    fun forEvents(events: List<PhysicalEvent>, claim: String, state: EvidenceState): EvidenceBundle {
        val supporting = events.flatMap { it.evidenceNotes }.distinct().take(8)
        val unknown = events.flatMap { it.unknownNotes }.distinct().take(6)
        return EvidenceBundle(
            claim = claim,
            evidenceState = state,
            supporting = supporting.ifEmpty {
                events.map { "${it.id}: ${it.summary}" }
            },
            unknown = unknown.ifEmpty {
                listOf("No visual confirmation.", "No external flow sensor.")
            },
            relatedEventIds = events.map { it.id }
        )
    }
}
