package com.smriti.core.ui

import android.app.Application
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smriti.core.SmritiApp
import com.smriti.core.actuators.HapticEvent
import com.smriti.core.actuators.HaloState
import com.smriti.core.guardian.GuardianAlert
import com.smriti.core.memory.RecallVerdict
import com.smriti.core.memory.SearchResult
import com.smriti.core.play.ScreenBufferRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Neural orb visual states (SPEC §2.5). */
enum class OrbState { IDLE, LISTENING, COMPUTING, SPEAKING, VERIFIED }

/**
 * UI wiring hub (SPEC §2.5) — consumes the SmritiApp singletons directly:
 * engine (MEM), actuators (ACT), recorder (PLAY), guardian (GRD). No stubs.
 *
 * Voice recall pipeline: LISTENING -> COMPUTING (engine.recall on
 * Dispatchers.Default) -> haptic RECALL_CONFIRM + halo CONFIRM_AMBER on Found
 * -> SPEAKING (TextToSpeech of the citation, or "No record found")
 * -> VERIFIED (Found) / IDLE (NoRecord).
 *
 * Guardian alerts: Fall -> halo EMERGENCY_STROBE + FALL_ALARM haptic;
 * Sound/Speech -> halo CONFIRM_AMBER + RECALL_CONFIRM haptic.
 */
class SmritiViewModel(app: Application) : AndroidViewModel(app) {

    private val smritiApp = app as SmritiApp
    private val engine = smritiApp.engine
    private val actuators = smritiApp.actuators
    private val recorder = smritiApp.recorder
    private val guardian = smritiApp.guardian

    private val telemetryMonitor = TelemetryMonitor(app)

    // ---- SPEC §2.5 exposed surface ----

    val telemetry: StateFlow<Telemetry> = telemetryMonitor.telemetry()
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            Telemetry(0f, 0f, 0f, null, "Hexagon NPU ready"),
        )

    private val _episodes = MutableStateFlow<List<SearchResult>>(emptyList())
    val episodes: StateFlow<List<SearchResult>> = _episodes.asStateFlow()

    private val _verdict = MutableStateFlow<RecallVerdict?>(null)
    val verdict: StateFlow<RecallVerdict?> = _verdict.asStateFlow()

    private val _guardianAlerts = MutableStateFlow<GuardianAlert?>(null)
    val guardianAlerts: StateFlow<GuardianAlert?> = _guardianAlerts.asStateFlow()

    private val _orbState = MutableStateFlow(OrbState.IDLE)
    val orbState: StateFlow<OrbState> = _orbState.asStateFlow()

    /** ACT passthrough — drives the in-app halo edge glow. */
    val halo: StateFlow<HaloState> = actuators.haloState

    /** PLAY passthrough — Idle / Recording / Saved(file). */
    val recorderState: StateFlow<ScreenBufferRecorder.State> = recorder.state

    // ---- TextToSpeech (OnInitListener-guarded; degrade to silent when unavailable) ----

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    /** Verdict currently being spoken, so the utterance callback knows the landing orb state. */
    @Volatile private var pendingFoundLanding = false

    init {
        tts = TextToSpeech(app, TextToSpeech.OnInitListener { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _orbState.value = OrbState.SPEAKING
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _orbState.value = if (pendingFoundLanding) OrbState.VERIFIED else OrbState.IDLE
                    }

                    override fun onDone(utteranceId: String?) {
                        _orbState.value = if (pendingFoundLanding) OrbState.VERIFIED else OrbState.IDLE
                    }
                })
            }
        })

        refreshTimeline()

        // GRD alert wiring (SPEC §2.5).
        viewModelScope.launch {
            guardian.alerts.collect { alert ->
                _guardianAlerts.value = alert
                when (alert) {
                    is GuardianAlert.Fall -> {
                        actuators.setHalo(HaloState.EMERGENCY_STROBE)
                        actuators.haptic(HapticEvent.FALL_ALARM)
                    }
                    is GuardianAlert.Sound -> {
                        // SPEC §2.5 + user color spec: emergency acoustics (smoke/fire/glass/siren)
                        // trip the white strobe + alarm haptic; informational sounds only confirm.
                        if (EMERGENCY_SOUNDS.any { alert.label.contains(it, ignoreCase = true) }) {
                            actuators.setHalo(HaloState.EMERGENCY_STROBE)
                            actuators.haptic(HapticEvent.FALL_ALARM)
                        } else {
                            actuators.setHalo(HaloState.CONFIRM_AMBER)
                            actuators.haptic(HapticEvent.RECALL_CONFIRM)
                        }
                    }
                    is GuardianAlert.Speech -> {
                        actuators.setHalo(HaloState.CONFIRM_AMBER)
                        actuators.haptic(HapticEvent.RECALL_CONFIRM)
                    }
                }
            }
        }
    }

    // ---- Queries ----

    /** Voice/text recall (SPEC §2.5): the full orb + actuator + TTS choreography. */
    fun onVoiceQuery(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return
        viewModelScope.launch {
            _orbState.value = OrbState.LISTENING
            _orbState.value = OrbState.COMPUTING
            val result = withContext(Dispatchers.Default) { engine.recall(query) }
            _verdict.value = result
            val found = result as? RecallVerdict.Found
            if (found != null) {
                actuators.haptic(HapticEvent.RECALL_CONFIRM)
                actuators.setHalo(HaloState.CONFIRM_AMBER)
            }
            pendingFoundLanding = found != null
            speak(found?.citation ?: "No record found")
        }
    }

    /** Timeline refresh — Room reads on Dispatchers.IO (SPEC §4 dispatcher discipline). */
    fun refreshTimeline() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { engine.recentEpisodes() }
                .onSuccess { _episodes.value = it }
        }
    }

    /** Dismiss the guardian banner after the user has seen it. */
    fun clearGuardianAlert() {
        _guardianAlerts.value = null
    }

    // ---- SMRITI Play control (routed through PlayProjectionService, which owns
    //      recorder.start/stop once the mediaProjection FGS is up — SPEC §3) ----

    /** Consent granted: hand resultCode/data to the projection foreground service. */
    fun startRecording(resultCode: Int, data: Intent) {
        val ctx = getApplication<Application>()
        runCatching {
            ContextCompat.startForegroundService(
                ctx,
                PlayProjectionService.startIntent(ctx, resultCode, data),
            )
        }
    }

    fun stopRecording() {
        val ctx = getApplication<Application>()
        runCatching { ctx.startService(PlayProjectionService.stopIntent(ctx)) }
    }

    // ---- Speech ----

    private fun speak(text: String) {
        val engine = tts
        if (engine != null && ttsReady) {
            // OnInitListener guard: utterance listener moves SPEAKING -> VERIFIED/IDLE.
            _orbState.value = OrbState.SPEAKING
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID_RECALL)
        } else {
            // TTS unavailable on this device: settle without audio (SPEC §4 graceful degrade).
            _orbState.value = if (pendingFoundLanding) OrbState.VERIFIED else OrbState.IDLE
        }
    }

    override fun onCleared() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onCleared()
    }

    private companion object {
        const val UTTERANCE_ID_RECALL = "smriti.recall"
        /** YAMNet label keywords that count as emergency acoustics (SPEC §2.5). */
        val EMERGENCY_SOUNDS = listOf("smoke", "fire", "glass", "shatter", "siren", "smash", "breaking", "alarm")
    }
}
