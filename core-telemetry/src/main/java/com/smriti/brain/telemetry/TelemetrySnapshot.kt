package com.smriti.brain.telemetry

data class TelemetrySnapshot(
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val ramAvailMb: Long,
    val batteryPct: Int,
    val discharging: Boolean,
    val tokensPerSec: Float,
    val airGapped: Boolean,
    val airplaneMode: Boolean
)
