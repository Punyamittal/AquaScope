package com.smriti.aqua.memory

/**
 * Status lifecycle of an episodic event.
 *
 * BASELINE  – a calibration/baseline capture (not an anomaly verdict).
 * NORMAL    – inspected, matched the baseline.
 * ANOMALY   – inspected, deviated from the baseline (NOT a confirmed leak).
 * CONFIRMED – anomaly later confirmed by an external human/plumber verdict.
 */
enum class EventStatus { BASELINE, NORMAL, ANOMALY, CONFIRMED }

/**
 * One episodic memory row. Immutable; the SQLite log is the single source of truth.
 */
data class Event(
    val id: Long = 0,
    val objectId: String,
    val location: String,
    val timestamp: Long,
    val sensor: String,
    val baselineId: Long?,
    val deviation: Float,
    val durationMs: Long,
    val coherence: Float,
    val status: EventStatus,
    val note: String
)

/**
 * A stored acoustic baseline for an object: per-dimension mean and variance
 * of the 72-dim fingerprint vectors observed during calibration.
 *
 * FloatArray does not get structural equality from `data class`, so equals/hashCode
 * are overridden with contentEquals/contentHashCode to keep data-class sanity.
 */
data class Baseline(
    val id: Long,
    val objectId: String,
    val createdAt: Long,
    val mean: FloatArray,
    val variance: FloatArray,
    val samples: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Baseline) return false
        return id == other.id &&
            objectId == other.objectId &&
            createdAt == other.createdAt &&
            mean.contentEquals(other.mean) &&
            variance.contentEquals(other.variance) &&
            samples == other.samples
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + objectId.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + mean.contentHashCode()
        result = 31 * result + variance.contentHashCode()
        result = 31 * result + samples
        return result
    }
}
