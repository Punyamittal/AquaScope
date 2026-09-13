package com.aquascope.smriti.brain.heartrate

enum class SignalQuality {
    HIGH,
    MEDIUM,
    LOW,
    INVALID
}

enum class MeasurementPhase {
    IDLE,
    PLACE_FINGER,
    DETECTING_PULSE,
    MEASURING,
    POOR_SIGNAL,
    COMPLETE,
    ERROR
}

data class HeartRateMeasurement(
    val bpm: Int,
    val confidence: Float,
    val signalQuality: SignalQuality,
    val durationSeconds: Int,
    val timestamp: Long
)

data class HeartRateUiState(
    val phase: MeasurementPhase = MeasurementPhase.IDLE,
    val bpm: Int? = null,
    val liveBpm: Int? = null,
    val confidence: Float = 0f,
    val signalQuality: SignalQuality = SignalQuality.INVALID,
    val progress: Float = 0f,
    val elapsedSeconds: Int = 0,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val measuring: Boolean = false,
    val fingerCovered: Boolean = false,
    /** Downsampled filtered PPG samples for the live / final waveform graph. */
    val waveform: List<Float> = emptyList()
)

data class PpgSample(
    val timestampNs: Long,
    val red: Float
)

data class FrameAnalysis(
    val timestampNs: Long,
    val redMean: Float,
    val greenMean: Float,
    val blueMean: Float,
    val brightness: Float,
    val fingerCovered: Boolean,
    val coverageScore: Float
)
