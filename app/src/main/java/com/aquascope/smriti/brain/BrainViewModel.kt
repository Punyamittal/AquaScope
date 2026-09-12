package com.aquascope.smriti.brain

import android.app.ActivityManager
import android.app.Application
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aquascope.halo.SmritiLightState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

enum class OrbState { IDLE, LISTENING, COMPUTING, SPEAKING, VERIFIED }

data class BrainUiState(
    val ramUsedGb: Double = 0.0,
    val ramTotalGb: Double = 0.0,
    val ramEffectiveLabel: String = "16 GB + ZRAM",
    val tokensPerSec: Double = 0.0,
    val thermalLabel: String = "NPU/GPU headroom: local",
    val amplitude: Float = 0f,
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val orb: OrbState = OrbState.IDLE,
    val guardianOn: Boolean = false,
    val playArmed: Boolean = false,
    val irEnabled: Boolean = false,
    val query: String = "",
    val recallMessage: String = "Ask what this device remembers.",
    val recallFound: Boolean = false,
    val timeline: List<EpisodeRecord> = emptyList(),
    val haloHardware: Boolean = false,
    val irHardware: Boolean = false,
    val status: String = "On-device · SMRITI AQUA"
)

class BrainViewModel(app: Application) : AndroidViewModel(app), SensorEventListener {

    private val memory = SmritiMemoryEngine.get(app)
    private val actuators = HardwareActuators.get(app)
    private val am = app.getSystemService(ActivityManager::class.java)
    private val sensors = app.getSystemService(SensorManager::class.java)
    private var recorder: ScreenBufferRecorder? = null
    private var ambient: AmbientAudioSensorManager? = null
    private var tts: TextToSpeech? = null
    private var lastIngestMs = 1L

    private val _state = MutableStateFlow(BrainUiState())
    val state: StateFlow<BrainUiState> = _state

    init {
        runCatching {
            tts = TextToSpeech(app) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                }
            }
        }
        runCatching {
            sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        runCatching { refreshTelemetry() }
        viewModelScope.launch {
            runCatching { reloadTimeline() }
        }
        runCatching {
            _state.update {
                it.copy(
                    haloHardware = actuators.haloHardware,
                    irHardware = actuators.irAvailable
                )
            }
        }
    }

    fun refreshTelemetry() {
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info) ?: return
        val total = info.totalMem / 1e9
        val used = (info.totalMem - info.availMem) / 1e9
        val tps = if (lastIngestMs > 0) (32_000.0 / lastIngestMs).coerceIn(0.4, 80.0) else 0.0
        _state.update {
            it.copy(
                ramUsedGb = used,
                ramTotalGb = total,
                ramEffectiveLabel = if (total >= 20) "32 GB effective" else String.format(Locale.US, "%.0f GB + ZRAM/extended", total),
                tokensPerSec = tps,
                thermalLabel = if (info.lowMemory) "Thermal/RAM pressure" else "Headroom OK · local NPU/GPU"
            )
        }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
    }

    fun recall() {
        val q = _state.value.query.trim()
        if (q.isEmpty()) {
            _state.update {
                it.copy(
                    recallFound = false,
                    recallMessage = "Type a question first, then tap Send.",
                    orb = OrbState.IDLE
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(orb = OrbState.COMPUTING, status = "Recalling…") }
            actuators.setHalo(SmritiLightState.MEMORY_RECALL)
            val result = runCatching { memory.recall(q) }.getOrElse { t ->
                RecallResult(false, "Recall failed: ${t.message ?: t.javaClass.simpleName}", emptyList())
            }
            _state.update {
                it.copy(
                    recallFound = result.found,
                    recallMessage = result.message,
                    timeline = if (result.found) result.matches else it.timeline,
                    orb = if (result.found) OrbState.VERIFIED else OrbState.IDLE,
                    status = if (result.found) "Recall hit" else "No match in memory"
                )
            }
            if (result.found) {
                actuators.haptic(HardwareActuatorService.HAPTIC_CONFIRM)
                speak(result.message)
            } else {
                actuators.setHalo(SmritiLightState.UNKNOWN)
            }
        }
    }

    fun ingestText(raw: String, source: String = "USER") {
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            _state.update { it.copy(orb = OrbState.COMPUTING) }
            actuators.setHalo(SmritiLightState.EXTRACTION)
            val rec = memory.ingest(raw, source)
            lastIngestMs = (System.currentTimeMillis() - t0).coerceAtLeast(1)
            actuators.setHalo(rec.kind.toHalo())
            actuators.haptic(HardwareActuatorService.HAPTIC_TICK)
            reloadTimeline()
            _state.update { it.copy(orb = OrbState.VERIFIED, status = "Stored ${rec.kind.name}") }
            refreshTelemetry()
        }
    }

    fun ingestUri(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(orb = OrbState.COMPUTING, status = "OCR…") }
            actuators.setHalo(SmritiLightState.EXTRACTION)
            try {
                val text = LocalOcr.read(getApplication(), uri)
                if (text.isBlank()) {
                    _state.update { it.copy(orb = OrbState.IDLE, recallMessage = "No record found", recallFound = false) }
                } else {
                    ingestText(text, "OCR")
                }
            } catch (t: Throwable) {
                _state.update { it.copy(orb = OrbState.IDLE, status = t.message ?: "OCR failed") }
            }
        }
    }

    fun toggleGuardian(on: Boolean) {
        if (on) {
            GuardianService.start(getApplication())
            ambient?.stop()
            ambient = AmbientAudioSensorManager(
                getApplication(),
                onAcoustic = { ev ->
                    viewModelScope.launch(Dispatchers.Main) {
                        ingestText("${ev.label} rms=${"%.2f".format(ev.rms)}", "YAMNET")
                        val halo = AmbientAudioSensorManager.haloFor(ev.label)
                        actuators.pulseHalo(halo, SmritiLightState.GUARDIAN, 1600)
                        if (halo == SmritiLightState.EMERGENCY) {
                            actuators.haptic(HardwareActuatorService.HAPTIC_ALARM)
                            if (_state.value.irEnabled) actuators.transmitAcToggle()
                        }
                    }
                },
                onFall = {
                    viewModelScope.launch(Dispatchers.Main) {
                        ingestText("Fall vector: jerk then stillness", "IMU")
                        actuators.setHalo(SmritiLightState.EMERGENCY)
                        actuators.haptic(HardwareActuatorService.HAPTIC_ALARM)
                    }
                },
                onAmplitude = { rms -> _state.update { it.copy(amplitude = rms) } }
            )
            ambient?.start()
            actuators.setHalo(SmritiLightState.GUARDIAN)
            _state.update { it.copy(guardianOn = true, orb = OrbState.LISTENING) }
        } else {
            ambient?.stop()
            ambient = null
            GuardianService.stop(getApplication())
            actuators.setHalo(SmritiLightState.NORMAL)
            _state.update { it.copy(guardianOn = false, orb = OrbState.IDLE, amplitude = 0f) }
        }
    }

    fun armPlay(resultCode: Int, data: Intent, w: Int, h: Int, dpi: Int) {
        CaptureProjectionService.start(getApplication())
        recorder?.stop()
        recorder = ScreenBufferRecorder(getApplication(), memory, actuators) {
            viewModelScope.launch { reloadTimeline() }
        }
        recorder?.start(resultCode, data, w, h, dpi)
        _state.update { it.copy(playArmed = true, status = "Play ring buffer 30s") }
        actuators.setHalo(SmritiLightState.GUARDIAN)
    }

    fun disarmPlay() {
        recorder?.stop()
        recorder = null
        CaptureProjectionService.stop(getApplication())
        _state.update { it.copy(playArmed = false) }
    }

    fun manualClip() {
        recorder?.captureNow("manual")
    }

    fun setIr(enabled: Boolean) {
        _state.update { it.copy(irEnabled = enabled) }
    }

    fun setListening() {
        _state.update { it.copy(orb = OrbState.LISTENING) }
        actuators.setHalo(SmritiLightState.EXTRACTION)
    }

    private suspend fun reloadTimeline() {
        val rows = memory.timeline(40)
        _state.update { it.copy(timeline = rows) }
    }

    private fun speak(text: String) {
        _state.update { it.copy(orb = OrbState.SPEAKING) }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "smriti-recall")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val rot = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rot, event.values)
        val ori = FloatArray(3)
        SensorManager.getOrientation(rot, ori)
        _state.update {
            it.copy(
                tiltX = ori[2].coerceIn(-0.6f, 0.6f),
                tiltY = ori[1].coerceIn(-0.6f, 0.6f)
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onCleared() {
        sensors?.unregisterListener(this)
        ambient?.stop()
        recorder?.stop()
        tts?.shutdown()
        super.onCleared()
    }
}
