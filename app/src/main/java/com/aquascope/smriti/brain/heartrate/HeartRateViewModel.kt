package com.aquascope.smriti.brain.heartrate

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aquascope.smriti.brain.NeuralCoreMemory
import com.aquascope.smriti.brain.TaxonomyParser
import com.aquascope.smriti.model.EventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HeartRateViewModel(app: Application) : AndroidViewModel(app) {

    private val processor = PpgSignalProcessor()
    private val _state = MutableStateFlow(HeartRateUiState())
    val state: StateFlow<HeartRateUiState> = _state.asStateFlow()

    private var completed = false

    fun resetToIdle() {
        processor.reset()
        completed = false
        _state.value = HeartRateUiState(
            phase = MeasurementPhase.IDLE,
            statusMessage = "Ready to measure"
        )
    }

    fun onMeasuringStarted() {
        processor.reset()
        completed = false
        _state.value = HeartRateUiState(
            phase = MeasurementPhase.PLACE_FINGER,
            measuring = true,
            statusMessage = "Place finger",
            waveform = emptyList()
        )
    }

    fun onCameraError(message: String) {
        processor.reset()
        completed = false
        _state.value = HeartRateUiState(
            phase = MeasurementPhase.ERROR,
            measuring = false,
            errorMessage = message,
            statusMessage = message,
            waveform = _state.value.waveform
        )
    }

    fun onStopped() {
        if (_state.value.phase == MeasurementPhase.COMPLETE) {
            _state.update { it.copy(measuring = false) }
            return
        }
        val wave = _state.value.waveform
        processor.reset()
        completed = false
        _state.update {
            it.copy(
                measuring = false,
                phase = if (it.phase == MeasurementPhase.ERROR) it.phase else MeasurementPhase.IDLE,
                liveBpm = null,
                progress = 0f,
                statusMessage = it.errorMessage ?: "Stopped",
                waveform = wave
            )
        }
    }

    fun onFrame(frame: FrameAnalysis) {
        if (completed) return
        if (!_state.value.measuring) return

        val snap = processor.onFrame(frame)
        val phase = snap.phase
        _state.update {
            it.copy(
                phase = phase,
                liveBpm = snap.liveBpm,
                bpm = snap.finalBpm ?: it.bpm,
                confidence = snap.confidence,
                signalQuality = snap.quality,
                progress = snap.progress,
                elapsedSeconds = snap.elapsedSeconds,
                statusMessage = snap.status,
                fingerCovered = snap.fingerCovered,
                measuring = phase != MeasurementPhase.COMPLETE && phase != MeasurementPhase.ERROR,
                waveform = snap.waveform.ifEmpty { it.waveform }
            )
        }

        if (phase == MeasurementPhase.COMPLETE && snap.finalBpm != null) {
            completed = true
            persist(snap)
        }
    }

    private fun persist(snap: PpgSignalProcessor.Snapshot) {
        val bpm = snap.finalBpm ?: return
        val measurement = HeartRateMeasurement(
            bpm = bpm,
            confidence = snap.confidence,
            signalQuality = snap.quality,
            durationSeconds = snap.elapsedSeconds.coerceAtLeast(1),
            timestamp = System.currentTimeMillis()
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                HeartRateStore.save(getApplication(), measurement)
                NeuralCoreMemory.remember(
                    context = getApplication(),
                    raw = "Heart rate ${measurement.bpm} BPM " +
                        "(${measurement.signalQuality.name.lowercase()} confidence, " +
                        "${measurement.durationSeconds}s wellness reading)",
                    source = "HEART_RATE",
                    kind = TaxonomyParser.Kind.HEALTH,
                    eventType = EventType.UNKNOWN,
                    anomalyScore = (1.0 - measurement.confidence).coerceIn(0.0, 1.0)
                )
            }
        }
    }
}
