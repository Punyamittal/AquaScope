package com.aquascope.smriti.brain

/**
 * Turns Guardian / IR telemetry into plain-language meaning and next steps.
 * Numbers stay available in memory; answers and UI should speak this layer.
 */
object GuardianSense {

    fun labelMeaning(label: String): String = when (label.lowercase()) {
        "smoke_alarm" -> "a high-pitched alarm (possible smoke / alert tone)"
        "glass_break" -> "a sharp crash that may be glass breaking"
        "kitchen_alert" -> "busy kitchen noise (fan, sizzle, or appliance hum)"
        "cough" -> "a mid-band sound consistent with coughing"
        "fall" -> "a sudden impact that may indicate a fall"
        else -> label.replace('_', ' ')
    }

    fun irPolicyFor(label: String): String = when (label.lowercase()) {
        "smoke_alarm", "glass_break", "fall" ->
            "If IR is armed, SMRITI may send an IR pulse for safety context and keep listening."
        "kitchen_alert" ->
            "If IR is armed and this repeats above the ambient baseline, SMRITI may IR-toggle an appliance (often AC) and check whether the room gets quieter."
        else ->
            "This label alone does not trigger IR."
    }

    fun spokenLabel(label: String): String = label.replace('_', ' ')

    fun eventNarrative(
        label: String,
        rms: Float,
        highFrac: Float,
        midFrac: Float,
        lowFrac: Float,
        snap: GuardianSnapshot,
        irArmed: Boolean,
        irDecision: IrDecision?
    ): String = buildString {
        append("Guardian heard ${labelMeaning(label)}")
        append(" (loudness ${levelWord(rms)}, bands high/mid/low ")
        append("${pct(highFrac)}/${pct(midFrac)}/${pct(lowFrac)})")
        append(". Ambient baseline ${fmt(snap.baselineRms)}; recent peak ${fmt(snap.peakRms)}.")
        append(' ')
        append(irPolicyFor(label))
        when {
            irDecision == null -> Unit
            irDecision.fire -> append(" IR decision: send pulse — ${irDecision.reason}.")
            irArmed -> append(" IR decision: hold — ${irDecision.reason}.")
            else -> append(" IR is disarmed, so no pulse was sent.")
        }
        if (snap.labeledCounts.isNotEmpty()) {
            append(" Recent detections: ")
            append(snap.labeledCounts.entries.joinToString { "${spokenLabel(it.key)}×${it.value}" })
            append('.')
        }
    }

    fun irOutcomeNarrative(tx: IrTransmitRecord): String = buildString {
        if (tx.ok) {
            append("IR pulse sent (${tx.carrierHz} Hz, ${tx.pulseCount} pulses) because ${tx.reason}. ")
            append(tx.carrierHint)
            append(" SMRITI will keep listening for a few seconds to see if the room gets quieter.")
        } else {
            append("IR pulse was not sent (${tx.carrierHint}) for ${tx.reason}.")
        }
    }

    fun followUpNarrative(raw: String): String = when {
        raw.contains("quieter", ignoreCase = true) ->
            "After the IR pulse the room got quieter — that supports an appliance/AC change."
        raw.contains("not quieter", ignoreCase = true) ->
            "After the IR pulse the room was not quieter — the appliance change is unconfirmed."
        raw.contains("no clear", ignoreCase = true) ->
            "After the IR pulse there was no clear loudness change."
        raw.contains("not enough", ignoreCase = true) ->
            "IR follow-up did not collect enough audio samples to judge the effect."
        else -> raw
    }

    fun spokenSnapshot(
        snap: GuardianSnapshot,
        guardianOn: Boolean,
        irArmed: Boolean
    ): String {
        if (!guardianOn) return "Guardian is off."
        return buildString {
            append("Guardian listening")
            append(" · room ${levelWord(snap.meanRms)}")
            if (snap.baselineRms > 1e-4f) {
                append(" (baseline ${fmt(snap.baselineRms)})")
            }
            if (snap.labeledCounts.isNotEmpty()) {
                append(" · heard ")
                append(
                    snap.labeledCounts.entries.joinToString(", ") {
                        val name = spokenLabel(it.key)
                        if (it.value == 1) name else "$name ${it.value}×"
                    }
                )
            } else {
                append(" · no labeled events yet")
            }
            append(if (irArmed) " · IR armed" else " · IR disarmed")
            snap.lastIrReason?.let { append(" · last IR: $it") }
            snap.lastIrFollowUp?.let { append(" · ${followUpNarrative(it)}") }
        }
    }

    fun armIrMessage(capability: String): String =
        "IR armed. $capability When Guardian hears a repeated kitchen alert above baseline, " +
            "or an emergency sound (smoke alarm, glass break, fall), SMRITI can send an IR pulse " +
            "and then check whether the room got quieter."

    fun disarmIrMessage(): String =
        "IR disarmed. Guardian can still listen and remember sounds, but no IR pulses will be sent."

    fun statusAnswer(
        question: String,
        guardianOn: Boolean,
        irArmed: Boolean,
        snapLine: String,
        memoryBits: List<String>
    ): String {
        val q = question.lowercase()
        val aboutIr = q.contains("ir") || q.contains("blaster") || q.contains("infrared")
        return buildString {
            if (aboutIr) {
                append(if (irArmed) "IR is armed. " else "IR is disarmed. ")
                append(
                    if (guardianOn) {
                        "Guardian is on and can request an IR pulse when evidence supports it. "
                    } else {
                        "Guardian is off, so IR will not fire from ambient detections. "
                    }
                )
            } else {
                append(if (guardianOn) "Guardian is on. " else "Guardian is off. ")
                append(if (irArmed) "IR is armed. " else "IR is disarmed. ")
            }
            if (snapLine.isNotBlank() && snapLine != "Guardian off") {
                append(snapLine.trim())
                append(' ')
            }
            if (memoryBits.isNotEmpty()) {
                append("\n\nFrom memory:\n")
                memoryBits.take(5).forEach { append("• ").append(it).append('\n') }
            } else if (!guardianOn) {
                append("Turn Guardian on in Neural Core to start collecting ambient readings.")
            } else {
                append("No labeled Guardian/IR events stored yet — leave it listening for a bit.")
            }
        }.trim()
    }

    fun isGuardianIrQuestion(raw: String): Boolean {
        val q = raw.lowercase()
        if (q.contains("guardian") ||
            q.contains("ir blaster") ||
            q.contains("infrared") ||
            q.contains("kitchen alert") ||
            q.contains("smoke alarm") ||
            q.contains("glass break")
        ) {
            return true
        }
        val mentionsIr = Regex("""\bir\b""").containsMatchIn(q)
        if (mentionsIr && (
                q.contains("arm") || q.contains("blast") || q.contains("pulse") ||
                    q.contains("send") || q.contains("fire") || q.contains("status") ||
                    q.contains("what") || q.contains("did")
                )
        ) {
            return true
        }
        return q.contains("what did") && (q.contains("hear") || q.contains("listen"))
    }

    private fun levelWord(rms: Float): String = when {
        rms < 0.02f -> "quiet"
        rms < 0.06f -> "soft"
        rms < 0.12f -> "moderate"
        rms < 0.2f -> "loud"
        else -> "very loud"
    }

    private fun pct(v: Float): String = "${(v * 100).toInt()}%"
    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)
}
