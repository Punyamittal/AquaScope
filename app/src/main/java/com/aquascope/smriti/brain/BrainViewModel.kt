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
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.SmritiCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val guardianLine: String = "",
    val query: String = "",
    val recallMessage: String = "Ask what this phone remembers.",
    val recallFound: Boolean = false,
    val timeline: List<EpisodeRecord> = emptyList(),
    val haloHardware: Boolean = false,
    val irHardware: Boolean = false,
    val status: String = "On-device · SMRITI AQUA"
)

class BrainViewModel(app: Application) : AndroidViewModel(app), SensorEventListener {

    private val memory = SmritiMemoryEngine.get(app)
    private val actuators = HardwareActuators.get(app)
    private val prefs = NeuralCorePrefs(app)
    private val am = app.getSystemService(ActivityManager::class.java)
    private val sensors = app.getSystemService(SensorManager::class.java)
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
                    irHardware = actuators.irAvailable,
                    guardianOn = GuardianService.isRunning(),
                    playArmed = CaptureProjectionService.isRunning(),
                    irEnabled = prefs.irArmed && actuators.irAvailable,
                    orb = if (GuardianService.isRunning()) OrbState.LISTENING else it.orb
                )
            }
        }
        viewModelScope.launch {
            GuardianService.running.collect { on ->
                _state.update {
                    it.copy(
                        guardianOn = on,
                        orb = if (on) OrbState.LISTENING else if (it.orb == OrbState.LISTENING) OrbState.IDLE else it.orb,
                        amplitude = if (on) it.amplitude else 0f
                    )
                }
            }
        }
        viewModelScope.launch {
            GuardianService.amplitude.collect { rms ->
                _state.update { it.copy(amplitude = rms) }
            }
        }
        viewModelScope.launch {
            GuardianService.snapshotLine.collect { line ->
                _state.update { it.copy(guardianLine = line) }
            }
        }
        viewModelScope.launch {
            CaptureProjectionService.running.collect { on ->
                _state.update { it.copy(playArmed = on) }
            }
        }
        viewModelScope.launch {
            NeuralCoreSession.clipTick.collect {
                if (it > 0L) runCatching { reloadTimeline() }
            }
        }
        if (prefs.guardianOn && !GuardianService.isRunning()) {
            runCatching { GuardianService.start(app) }
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
                    orb = OrbState.IDLE,
                    status = "Waiting for input"
                )
            }
            return
        }
        viewModelScope.launch {
            _state.update {
                it.copy(
                    orb = OrbState.COMPUTING,
                    status = "Thinking…",
                    recallMessage = "Working on: $q",
                    recallFound = false
                )
            }
            runCatching { actuators.setHalo(SmritiLightState.MEMORY_RECALL) }

            val memResult = runCatching { memory.recall(q) }.getOrElse { t ->
                Log.e(TAG, "brain recall failed", t)
                RecallResult(false, "Recall failed: ${t.message ?: t.javaClass.simpleName}", emptyList())
            }

            // Same grounded Ask path as the Ask tab — so Neural Core always produces text.
            // Only use the local LLM if already warm; never block Send on model load.
            val smriti = SmritiCore.get(getApplication())
            val askAnswer = runCatching {
                withContext(Dispatchers.Default) {
                    smriti.ask(q, allowLocalLlm = smriti.isLocalLlmReady())
                }
            }.onFailure { t -> Log.e(TAG, "SmritiCore.ask failed", t) }.getOrNull()

            val answerText = when {
                memResult.found -> {
                    val askBit = askAnswer?.text?.trim().orEmpty()
                    if (askBit.isNotEmpty() && !askBit.equals(memResult.message, ignoreCase = true)) {
                        "${memResult.message}\n\n$askBit"
                    } else {
                        memResult.message
                    }
                }
                !askAnswer?.text.isNullOrBlank() -> askAnswer!!.text.trim()
                else -> "No record found yet. Tap Store note to save this, or run a Scan first."
            }
            val found = memResult.found ||
                (askAnswer?.relatedEvents?.isNotEmpty() == true) ||
                !askAnswer?.text.isNullOrBlank()

            _state.update {
                it.copy(
                    recallFound = found,
                    recallMessage = answerText,
                    timeline = if (memResult.found) memResult.matches else it.timeline,
                    orb = if (found) OrbState.VERIFIED else OrbState.IDLE,
                    status = when {
                        memResult.found -> "Neural memory hit"
                        askAnswer?.usedLocalModel == true -> "Local model · ${askAnswer.modelName ?: "on-device"}"
                        !askAnswer?.text.isNullOrBlank() -> "Grounded answer"
                        else -> "No match in memory"
                    }
                )
            }
            if (found) {
                runCatching { actuators.haptic(HardwareActuatorService.HAPTIC_CONFIRM) }
                speak(answerText.take(280))
            } else {
                runCatching { actuators.setHalo(SmritiLightState.UNKNOWN) }
            }
        }
    }

    companion object {
        private const val TAG = "BrainViewModel"
    }

    fun ingestText(raw: String, source: String = "USER") {
        viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            _state.update { it.copy(orb = OrbState.COMPUTING) }
            actuators.setHalo(SmritiLightState.EXTRACTION)
            val rec = NeuralCoreMemory.remember(getApplication(), raw, source)
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
            _state.update { it.copy(guardianOn = true, orb = OrbState.LISTENING) }
        } else {
            GuardianService.stop(getApplication())
            _state.update { it.copy(guardianOn = false, orb = OrbState.IDLE, amplitude = 0f) }
        }
    }

    fun armPlay(resultCode: Int, data: Intent, w: Int, h: Int, dpi: Int) {
        CaptureProjectionService.start(getApplication(), resultCode, data, w, h, dpi)
        _state.update { it.copy(playArmed = true, status = "Recording last 30s into memory") }
        actuators.setHalo(SmritiLightState.GUARDIAN)
    }

    fun disarmPlay() {
        CaptureProjectionService.stop(getApplication())
        _state.update { it.copy(playArmed = false) }
    }

    fun manualClip() {
        CaptureProjectionService.captureNow(getApplication())
    }

    fun setIr(enabled: Boolean) {
        prefs.irArmed = enabled
        _state.update { it.copy(irEnabled = enabled) }
        if (enabled) {
            val cap = actuators.irCapability()
            actuators.haptic(HardwareActuatorService.HAPTIC_TICK)
            NeuralCoreMemory.rememberAsync(
                getApplication(),
                raw = "IR blaster armed. $cap. Blast only if Guardian evidence supports it; follow-up RMS is stored.",
                source = "IR",
                kind = TaxonomyParser.Kind.ACOUSTIC,
                throttleMs = 0L
            )
            _state.update { it.copy(status = cap) }
        } else {
            NeuralCoreMemory.rememberAsync(
                getApplication(),
                raw = "IR blaster disarmed. No further blasts until armed again.",
                source = "IR",
                throttleMs = 0L
            )
        }
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
        tts?.shutdown()
        super.onCleared()
    }
}
