package com.aquascope.smriti.brain

data class GuardianWindow(
    val rms: Float,
    val highFrac: Float,
    val midFrac: Float,
    val lowFrac: Float,
    val label: String?,
    val confidence: Float,
    val accelMag: Float,
    val timestampMs: Long
)

data class GuardianSnapshot(
    val windows: Int,
    val baselineRms: Float,
    val meanRms: Float,
    val peakRms: Float,
    val meanHighFrac: Float,
    val labeledCounts: Map<String, Int>,
    val irTransmits: Int,
    val lastIrReason: String?,
    val lastIrFollowUp: String?,
    val summary: String
)

data class IrDecision(
    val fire: Boolean,
    val reason: String
)

data class IrTransmitRecord(
    val ok: Boolean,
    val carrierHz: Int,
    val pulseCount: Int,
    val carrierHint: String,
    val reason: String
)

/**
 * Rolling on-device Guardian/IR ledger. Scores events against a measured ambient
 * baseline and only recommends an IR blast when collected evidence supports it.
 */
class GuardianLedger(
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    private val windows = ArrayDeque<GuardianWindow>()
    private val labels = ArrayDeque<Pair<Long, String>>()
    private val rmsHistory = ArrayDeque<Float>()
    private var baselineRms = 0f
    private var baselineN = 0
    private var irCount = 0
    private var lastIrAt = 0L
    private var lastIrReason: String? = null
    private var lastIrFollowUp: String? = null
    private var postIrUntil = 0L
    private val postIrRms = ArrayDeque<Float>()
    private var preIrRms = 0f

    fun ingest(window: GuardianWindow) {
        windows.addLast(window)
        rmsHistory.addLast(window.rms)
        while (windows.size > MAX_WINDOWS) windows.removeFirst()
        while (rmsHistory.size > MAX_WINDOWS) rmsHistory.removeFirst()
        if (baselineN < BASELINE_N && window.rms < 0.08f && window.label == null) {
            baselineN++
            baselineRms += (window.rms - baselineRms) / baselineN
        }
        window.label?.let { label ->
            labels.addLast(window.timestampMs to label)
        }
        val horizon = window.timestampMs - LABEL_HORIZON_MS
        while (labels.isNotEmpty() && labels.first().first < horizon) {
            labels.removeFirst()
        }
        if (postIrUntil > 0L && window.timestampMs <= postIrUntil) {
            postIrRms.addLast(window.rms)
        }
    }

    fun snapshot(): GuardianSnapshot {
        val rms = if (rmsHistory.isEmpty()) 0f else rmsHistory.average().toFloat()
        val peak = rmsHistory.maxOrNull() ?: 0f
        val high = if (windows.isEmpty()) 0f else windows.map { it.highFrac }.average().toFloat()
        val counts = linkedMapOf<String, Int>()
        labels.forEach { counts[it.second] = (counts[it.second] ?: 0) + 1 }
        val vs = if (baselineRms > 1e-4f) rms / baselineRms else 0f
        val summary = buildString {
            append("Guardian rms=${fmt(rms)} peak=${fmt(peak)}")
            if (baselineN >= 5) append(" vs baseline ${fmt(baselineRms)} (${fmt(vs)}×)")
            if (counts.isNotEmpty()) {
                append(" · ")
                append(counts.entries.joinToString { "${it.key}×${it.value}" })
            }
            lastIrReason?.let { append(" · last IR: $it") }
            lastIrFollowUp?.let { append(" · $it") }
        }
        return GuardianSnapshot(
            windows = windows.size,
            baselineRms = baselineRms,
            meanRms = rms,
            peakRms = peak,
            meanHighFrac = high,
            labeledCounts = counts,
            irTransmits = irCount,
            lastIrReason = lastIrReason,
            lastIrFollowUp = lastIrFollowUp,
            summary = summary
        )
    }

    fun shouldFireIr(label: String, irArmed: Boolean): IrDecision {
        if (!irArmed) return IrDecision(false, "IR not armed")
        val t = nowMs()
        if (lastIrAt > 0L && t - lastIrAt < IR_COOLDOWN_MS) {
            return IrDecision(false, "IR cooldown ${((IR_COOLDOWN_MS - (t - lastIrAt)) / 1000)}s")
        }
        val recent = labels.count { t - it.first <= 180_000L && it.second == label }
        val rms = windows.lastOrNull()?.rms ?: 0f
        val above = baselineN >= 5 && baselineRms > 1e-4f && rms >= baselineRms * 2.5f
        return when (label) {
            "smoke_alarm", "glass_break", "fall" ->
                IrDecision(true, "$label with rms=${fmt(rms)} (safety)")
            "kitchen_alert" -> {
                if (recent >= 2 && (above || rms >= 0.12f)) {
                    IrDecision(
                        true,
                        "kitchen_alert ×$recent in 3 min, rms=${fmt(rms)} vs baseline ${fmt(baselineRms)}"
                    )
                } else {
                    IrDecision(
                        false,
                        "kitchen_alert ×$recent, rms=${fmt(rms)} — need repeat above baseline before IR"
                    )
                }
            }
            else -> IrDecision(false, "$label does not justify IR")
        }
    }

    fun noteIrFired(record: IrTransmitRecord) {
        irCount++
        lastIrAt = nowMs()
        lastIrReason = record.reason
        lastIrFollowUp = null
        preIrRms = windows.lastOrNull()?.rms ?: 0f
        postIrUntil = lastIrAt + POST_IR_MS
        postIrRms.clear()
    }

    fun finishPostIrIfDue(): String? {
        val t = nowMs()
        if (postIrUntil <= 0L || t < postIrUntil) return null
        postIrUntil = 0L
        val after = if (postIrRms.isEmpty()) 0f else postIrRms.average().toFloat()
        val delta = after - preIrRms
        val follow = when {
            postIrRms.size < 3 ->
                "IR follow-up: not enough acoustic samples after blast"
            delta <= -0.03f || (preIrRms > 0.04f && after < preIrRms * 0.7f) ->
                "IR follow-up: rms ${fmt(preIrRms)} → ${fmt(after)} (quieter after blast)"
            delta >= 0.03f ->
                "IR follow-up: rms ${fmt(preIrRms)} → ${fmt(after)} (not quieter — AC change unconfirmed)"
            else ->
                "IR follow-up: rms ${fmt(preIrRms)} → ${fmt(after)} (no clear acoustic change)"
        }
        lastIrFollowUp = follow
        return follow
    }

    fun sessionSummary(started: Boolean): String {
        val snap = snapshot()
        return if (started) {
            "Guardian session started. Collecting ambient sound until a quiet baseline exists. " +
                "Labeled events (kitchen alert, cough, smoke, glass, fall) are stored as memories; " +
                "with IR armed, repeated kitchen noise or emergencies can trigger an IR pulse."
        } else {
            "Guardian session ended. ${GuardianSense.spokenSnapshot(snap, guardianOn = false, irArmed = false)}"
        }
    }

    fun scoreFor(label: String, rms: Float): Double {
        val vs = if (baselineRms > 1e-4f) (rms / baselineRms).toDouble() else 1.0
        val base = when (label) {
            "smoke_alarm", "glass_break", "fall" -> 80.0
            "kitchen_alert" -> 55.0
            "cough" -> 35.0
            else -> 25.0
        }
        return (base + (vs - 1.0).coerceIn(0.0, 2.0) * 10.0).coerceIn(15.0, 95.0)
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    companion object {
        private const val MAX_WINDOWS = 600
        private const val BASELINE_N = 20
        private const val LABEL_HORIZON_MS = 60 * 60 * 1000L
        private const val IR_COOLDOWN_MS = 60_000L
        private const val POST_IR_MS = 8_000L
    }
}
