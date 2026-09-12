package com.smriti.aqua.sensors

/**
 * Fuses the acoustic anomaly score with the mic/accelerometer coherence score
 * into a routing decision (SPEC section 4).
 *
 * Rationale: a real structural/plumbing change rings in the air AND couples
 * into the pipe/phone body. A purely ambient acoustic event (door slam, voices,
 * another device) produces a high anomaly score but does NOT correlate with
 * the accelerometer — so high anomaly + very low coherence is treated as
 * ambient and discarded rather than stored as a plumbing anomaly.
 */
object FusionGate {

    enum class Verdict { PROCESS, DISCARD_AMBIENT }

    /** Above this, the capture deviates enough from baseline to matter. */
    const val ANOMALY_THRESHOLD = 0.45f

    /** Below this, mic/accel correlation is too weak to be structural. */
    const val COHERENCE_THRESHOLD = 0.15f

    /**
     * anomaly > 0.45 && coherence < 0.15  -> DISCARD_AMBIENT
     * otherwise                             -> PROCESS
     */
    fun decide(anomaly: Float, coherence: Float): Verdict =
        if (anomaly > ANOMALY_THRESHOLD && coherence < COHERENCE_THRESHOLD) {
            Verdict.DISCARD_AMBIENT
        } else {
            Verdict.PROCESS
        }
}
