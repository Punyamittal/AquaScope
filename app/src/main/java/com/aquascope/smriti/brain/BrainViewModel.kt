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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
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
    val screenMindOn: Boolean = true,
    val screenMindPcOn: Boolean = false,
    val swipeOcrOn: Boolean = false,
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
    private val screenMindPrefs =
        com.aquascope.smriti.brain.screenmind.ScreenMindPreferences(app)
    private val screenMindPc =
        com.aquascope.smriti.brain.screenmind.ScreenMindPcClient(screenMindPrefs)
    private val indicTtsClient = com.aquascope.smriti.tts.IndicTtsClient(screenMindPrefs)
    private val indicTtsPlayer = com.aquascope.smriti.tts.IndicTtsPlayer(app)
    private val am = app.getSystemService(ActivityManager::class.java)
    private val sensors = app.getSystemService(SensorManager::class.java)
    private var tts: TextToSpeech? = null
    private var lastIngestMs = 1L

    private val _state = MutableStateFlow(BrainUiState())
    val state: StateFlow<BrainUiState> = _state
    private val _toasts = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val toasts: SharedFlow<String> = _toasts

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
                    screenMindOn = screenMindPrefs.enabled,
                    screenMindPcOn = screenMindPrefs.pcEnabled,
                    swipeOcrOn = OcrGesturePreferences(getApplication()).swipeEnabled,
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
                _state.update { st ->
                    when {
                        !on -> st.copy(playArmed = false)
                        // Keep showing Stop→Capture transition while flush+OCR runs.
                        st.status.startsWith("Stopping") -> st.copy(playArmed = false)
                        else -> st.copy(playArmed = true)
                    }
                }
            }
        }
        viewModelScope.launch {
            NeuralCoreSession.clipTick.collect {
                if (it > 0L) runCatching { reloadTimeline() }
            }
        }
        viewModelScope.launch {
            NeuralCoreSession.clipSaved.collect { result ->
                if (result is ClipFlushResult.Saved) {
                    runCatching { reloadTimeline() }
                    // Stop path updates UI in disarmPlay; skip duplicate "Clip saved" toast-style message.
                    if (!result.reason.equals("stop", ignoreCase = true)) {
                        applyClipResult(result)
                    }
                }
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
                    recallMessage = "Type a question first, then tap Ask.",
                    orb = OrbState.IDLE,
                    status = "Waiting for input"
                )
            }
            return
        }
        viewModelScope.launch {
            val smriti = SmritiCore.get(getApplication())
            val willWarmLlm = smriti.llmPrefs.enabled &&
                smriti.modelStore.findInstalled() != null &&
                !smriti.isLocalLlmReady()
            _state.update {
                it.copy(
                    orb = OrbState.COMPUTING,
                    status = if (willWarmLlm) "Loading Qwen…" else "Thinking…",
                    recallMessage = if (willWarmLlm) {
                        "Loading the local model, then answering: $q"
                    } else {
                        "Working on: $q"
                    },
                    recallFound = false
                )
            }
            runCatching { actuators.setHalo(SmritiLightState.MEMORY_RECALL) }

            val memResult = runCatching { memory.recall(q) }.getOrElse { t ->
                Log.e(TAG, "brain recall failed", t)
                RecallResult(false, "Recall failed: ${t.message ?: t.javaClass.simpleName}", emptyList())
            }

            if (memResult.found) {
                _state.update {
                    it.copy(status = "Neural memory → Qwen…")
                }
            }

            // Optional: desktop ScreenMind — show separately; never pollute phone clip OCR.
            val pcBit = if (screenMindPrefs.pcEnabled) {
                _state.update { it.copy(status = "ScreenMind PC…") }
                screenMindPc.search(q).getOrElse {
                    screenMindPc.chat(q).getOrNull().orEmpty()
                }
            } else ""

            // Always allow Qwen when enabled — neural hits are passed in so Qwen rephrases them.
            val askAnswer = runCatching {
                withContext(Dispatchers.Default) {
                    smriti.ask(
                        question = q,
                        allowLocalLlm = true,
                        neuralEpisodes = if (memResult.found) memResult.matches else emptyList()
                    )
                }
            }.onFailure { t -> Log.e(TAG, "SmritiCore.ask failed", t) }.getOrNull()

            val askBit = com.aquascope.smriti.llm.AskAnswerCleaner.cleanForDisplay(
                askAnswer?.text.orEmpty()
            )
            val memBit = com.aquascope.smriti.llm.AskAnswerCleaner.cleanForDisplay(memResult.message)
            // Prefer Qwen (or rules) over raw neural dump; raw memory only if ask produced nothing.
            val answerText = when {
                askBit.isNotBlank() && pcBit.isNotBlank() ->
                    "$askBit\n\n—\n$pcBit"
                askBit.isNotBlank() -> askBit
                pcBit.isNotBlank() -> pcBit
                memResult.found && memBit.isNotBlank() -> memBit
                else -> "No record found yet. Type a note and tap Save, run OCR on a screenshot, or Capture + Stop a moment first."
            }
            val found = memResult.found ||
                pcBit.isNotBlank() ||
                (askAnswer?.relatedEvents?.isNotEmpty() == true) ||
                !askAnswer?.text.isNullOrBlank()

            _state.update {
                it.copy(
                    recallFound = found,
                    recallMessage = answerText,
                    timeline = if (memResult.found) memResult.matches else it.timeline,
                    orb = if (found) OrbState.VERIFIED else OrbState.IDLE,
                    status = when {
                        pcBit.isNotBlank() && askAnswer?.usedLocalModel == true ->
                            "ScreenMind PC + Qwen"
                        pcBit.isNotBlank() ->
                            "ScreenMind PC"
                        memResult.found && askAnswer?.usedLocalModel == true ->
                            "Neural memory → Qwen"
                        askAnswer?.usedLocalModel == true ->
                            "Qwen · ${askAnswer.modelName ?: "local"}"
                        memResult.found && willWarmLlm ->
                            "Neural memory (Qwen unavailable)"
                        willWarmLlm && askAnswer?.usedLocalModel != true ->
                            "Rules (Qwen unavailable)"
                        memResult.found ->
                            "Neural memory → rules"
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

    private suspend fun answerGuardianIr(q: String) {
        val mem = runCatching { memory.recall(q) }.getOrNull()
        val memoryBits = buildList {
            mem?.matches.orEmpty().take(5).forEach { ep ->
                add(
                    com.aquascope.smriti.llm.AskAnswerCleaner.cleanForDisplay(
                        ep.body.ifBlank { ep.title }
                    ).take(220)
                )
            }
            if (isEmpty()) {
                val smriti = SmritiCore.get(getApplication())
                val ask = runCatching {
                    withContext(Dispatchers.Default) {
                        smriti.ask(q, allowLocalLlm = false)
                    }
                }.getOrNull()
                ask?.relatedEvents.orEmpty().take(5).forEach { ev ->
                    add(
                        com.aquascope.smriti.llm.AskAnswerCleaner.userFacingSummary(ev.summary).take(220)
                    )
                }
            }
        }
        val answer = GuardianSense.statusAnswer(
            question = q,
            guardianOn = GuardianService.isRunning(),
            irArmed = prefs.irArmed,
            snapLine = GuardianService.snapshotLine.value,
            memoryBits = memoryBits
        )
        _state.update {
            it.copy(
                recallFound = true,
                recallMessage = answer,
                timeline = mem?.matches?.takeIf { rows -> rows.isNotEmpty() } ?: it.timeline,
                orb = OrbState.VERIFIED,
                status = "Guardian / IR"
            )
        }
        runCatching { actuators.haptic(HardwareActuatorService.HAPTIC_CONFIRM) }
        speak(answer.take(280))
    }

    companion object {
        private const val TAG = "BrainViewModel"
    }

    fun ingestText(raw: String, source: String = "USER") {
        viewModelScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                _state.update { it.copy(orb = OrbState.COMPUTING, status = "Saving…") }
                runCatching { actuators.setHalo(SmritiLightState.EXTRACTION) }
                val rec = NeuralCoreMemory.remember(getApplication(), raw, source)
                lastIngestMs = (System.currentTimeMillis() - t0).coerceAtLeast(1)
                runCatching { actuators.setHalo(rec.kind.toHalo()) }
                runCatching { actuators.haptic(HardwareActuatorService.HAPTIC_TICK) }
                reloadTimeline()
                val preview = raw.trim().take(180)
                _state.update {
                    it.copy(
                        orb = OrbState.VERIFIED,
                        recallFound = true,
                        recallMessage = when (source) {
                            "OCR" -> "OCR saved · ${rec.title}\n\n$preview"
                            "NOTE" -> "Note saved · ${rec.title}\n\n$preview"
                            else -> "Stored · ${rec.title}\n\n$preview"
                        },
                        status = "In memory · ${rec.kind.name}"
                    )
                }
                refreshTelemetry()
            } catch (t: Throwable) {
                Log.e(TAG, "ingestText failed", t)
                _state.update {
                    it.copy(
                        orb = OrbState.IDLE,
                        recallFound = false,
                        recallMessage = "Could not save: ${t.message ?: "unknown error"}",
                        status = "Save failed"
                    )
                }
            }
        }
    }

    fun ingestUri(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    orb = OrbState.COMPUTING,
                    status = "Reading image…",
                    recallMessage = "Running OCR on the selected image…",
                    recallFound = false
                )
            }
            runCatching { actuators.setHalo(SmritiLightState.EXTRACTION) }
            try {
                Log.i(TAG, "OCR start uri=$uri")
                val text = LocalOcr.read(getApplication(), uri)
                finishOcrText(text)
            } catch (t: Throwable) {
                Log.e(TAG, "OCR failed", t)
                val msg = when {
                    t is kotlinx.coroutines.TimeoutCancellationException ->
                        "timed out — try again, or turn off Gemma OCR in System → Local model"
                    t.message.isNullOrBlank() -> "unknown error"
                    else -> t.message!!
                }
                reportOcrFailure(msg)
            }
        }
    }

    /** Preferred path: Activity already copied the picker URI into a local cache file. */
    fun ingestOcrFile(file: File) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    orb = OrbState.COMPUTING,
                    status = "Reading image…",
                    recallMessage = "Running OCR on the selected image…",
                    recallFound = false
                )
            }
            runCatching { actuators.setHalo(SmritiLightState.EXTRACTION) }
            try {
                val bytes = file.length()
                Log.i(TAG, "OCR start file=${file.absolutePath} bytes=$bytes")
                val libraryImage = runCatching {
                    com.aquascope.smriti.brain.library.ScreenLibraryStore.saveImageFile(
                        getApplication(),
                        file
                    )
                }.getOrNull()
                if (screenMindPrefs.enabled) {
                    val bmp = LocalOcr.decodeFile(file)
                    if (bmp != null) {
                        try {
                            val record = com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer.analyze(
                                context = getApplication(),
                                bitmap = bmp,
                                reason = "gallery"
                            )
                            finishScreenMind(record, libraryImage)
                            return@launch
                        } catch (t: Throwable) {
                            // Don't surface as hard OCR failure — fall through to ML Kit text path.
                            Log.w(TAG, "ScreenMind path failed, falling back to OCR: ${t.message}")
                        } finally {
                            if (!bmp.isRecycled) bmp.recycle()
                        }
                    }
                }
                val text = LocalOcr.readFile(file, getApplication())
                finishOcrText(text, libraryImage)
            } catch (t: Throwable) {
                Log.e(TAG, "OCR file failed", t)
                // If Ask already has screen text from a partial success, don't scare the user.
                if (NeuralCoreSession.lastScreenOcr.isNotBlank()) {
                    _state.update {
                        it.copy(
                            orb = OrbState.VERIFIED,
                            recallFound = true,
                            recallMessage = "OCR text is ready for Ask (save step had a hiccup).\n\n" +
                                NeuralCoreSession.lastScreenOcr.take(400),
                            status = "OCR ready"
                        )
                    }
                    _toasts.tryEmit("OCR ready — ask about the screen")
                    return@launch
                }
                val msg = when {
                    t is kotlinx.coroutines.TimeoutCancellationException ->
                        "timed out — try again, or turn off Gemma OCR in System → Local model"
                    t.message.isNullOrBlank() -> "unknown error"
                    else -> t.message!!
                }
                reportOcrFailure(msg)
            } finally {
                runCatching { file.delete() }
            }
        }
    }

    fun reportOcrFailure(message: String) {
        _state.update {
            it.copy(
                orb = OrbState.IDLE,
                recallFound = false,
                recallMessage = "OCR failed: $message. Try a PNG/JPG screenshot from Gallery.",
                status = "OCR failed"
            )
        }
        _toasts.tryEmit("OCR failed: $message")
    }

    private suspend fun finishScreenMind(
        record: com.aquascope.smriti.brain.screenmind.ScreenMindRecord,
        imageFile: File? = null
    ) {
        val cleaned = record.visibleText.ifBlank { record.sceneDescription }.trim()
        val payload = record.askContext().ifBlank { cleaned }
        if (payload.isBlank()) {
            _state.update {
                it.copy(
                    orb = OrbState.IDLE,
                    recallFound = false,
                    recallMessage = "ScreenMind found little readable content. Try a clearer screenshot.",
                    status = "ScreenMind empty"
                )
            }
            _toasts.tryEmit("No readable text in that image")
            return
        }
        val t0 = System.currentTimeMillis()
        _state.update { it.copy(status = "Saving ScreenMind…") }
        val ocrFile = runCatching {
            val dir = File(getApplication<Application>().filesDir, "smriti_clips").also { it.mkdirs() }
            File(dir, "screenmind_${System.currentTimeMillis()}.ocr.txt").also {
                it.writeText(payload)
            }
        }.getOrNull()
        // Publish for Ask before any secondary persistence that might throw.
        NeuralCoreSession.lastScreenOcr = payload
        NeuralCoreSession.lastScreenOcrPath = ocrFile?.absolutePath
        runCatching {
            com.aquascope.smriti.brain.library.ScreenLibraryStore.ingest(
                ctx = getApplication(),
                ocrText = cleaned.ifBlank { payload },
                imageFile = imageFile,
                mind = record,
                foreground = com.aquascope.smriti.brain.screenmind.ForegroundAppResolver.current(getApplication())
            )
        }
        val rec = runCatching {
            NeuralCoreMemory.remember(
                context = getApplication(),
                raw = record.memoryText("gallery"),
                source = "SCREENMIND",
                evidencePath = ocrFile?.absolutePath
            )
        }.getOrElse {
            Log.w(TAG, "ScreenMind remember failed: ${it.message}")
            null
        }
        lastIngestMs = (System.currentTimeMillis() - t0).coerceAtLeast(1)
        if (rec != null) {
            runCatching { actuators.setHalo(rec.kind.toHalo()) }
            runCatching { actuators.haptic(HardwareActuatorService.HAPTIC_TICK) }
            reloadTimeline()
        }
        val preview = record.summary.ifBlank { cleaned.take(400) }.ifBlank { payload.take(400) }
        _state.update {
            it.copy(
                orb = OrbState.VERIFIED,
                recallFound = true,
                recallMessage = "ScreenMind · ${record.appName} · ${record.category}\n\n$preview\n\nAsk: what was on my screen?",
                status = "OCR ready for Ask"
            )
        }
        refreshTelemetry()
        _toasts.tryEmit("OCR ready — ask about the screen")
    }

    private suspend fun finishOcrText(text: String, imageFile: File? = null) {
        if (text.isBlank()) {
            _state.update {
                it.copy(
                    orb = OrbState.IDLE,
                    recallFound = false,
                    recallMessage = "OCR found no readable text in that image. Try a clearer screenshot or photo.",
                    status = "OCR empty"
                )
            }
            _toasts.tryEmit("No text found in image")
            return
        }
        val t0 = System.currentTimeMillis()
        _state.update { it.copy(status = "Saving OCR…") }
        val cleaned = text.trim()
        // Make gallery OCR available to Ask / Qwen the same way Capture→Stop does.
        val ocrFile = runCatching {
            val dir = File(getApplication<Application>().filesDir, "smriti_clips").also { it.mkdirs() }
            File(dir, "gallery_${System.currentTimeMillis()}.ocr.txt").also { it.writeText(cleaned) }
        }.getOrNull()
        NeuralCoreSession.lastScreenOcr = cleaned
        NeuralCoreSession.lastScreenOcrPath = ocrFile?.absolutePath
        runCatching {
            com.aquascope.smriti.brain.library.ScreenLibraryStore.ingest(
                ctx = getApplication(),
                ocrText = cleaned,
                imageFile = imageFile,
                mind = null,
                foreground = com.aquascope.smriti.brain.screenmind.ForegroundAppResolver.current(getApplication())
            )
        }
        val rec = runCatching {
            NeuralCoreMemory.remember(
                context = getApplication(),
                raw = "Gallery OCR\nSeen on screen:\n${cleaned.take(1_500)}",
                source = "OCR",
                evidencePath = ocrFile?.absolutePath
            )
        }.getOrElse {
            Log.w(TAG, "OCR remember failed: ${it.message}")
            null
        }
        lastIngestMs = (System.currentTimeMillis() - t0).coerceAtLeast(1)
        if (rec != null) {
            runCatching { actuators.setHalo(rec.kind.toHalo()) }
            runCatching { actuators.haptic(HardwareActuatorService.HAPTIC_TICK) }
            reloadTimeline()
        }
        val preview = cleaned.take(400)
        _state.update {
            it.copy(
                orb = OrbState.VERIFIED,
                recallFound = true,
                recallMessage = "OCR saved for Ask · ${rec?.title ?: "screen"}\n\n$preview\n\nAsk: what was on my screen?",
                status = "OCR ready for Qwen"
            )
        }
        refreshTelemetry()
        _toasts.tryEmit("OCR ready (${cleaned.length} chars) — ask about it")
        Log.i(TAG, "OCR saved chars=${cleaned.length} path=${ocrFile?.absolutePath}")
    }

    /** Save typed note, or the current answer panel if the field is empty. */
    fun saveNoteOrAnswer() {
        val q = _state.value.query.trim()
        val answer = _state.value.recallMessage.trim()
        when {
            q.isNotBlank() -> {
                ingestText(q, "NOTE")
                setQuery("")
            }
            answer.isNotBlank() &&
                answer != "Ask what this phone remembers." &&
                !answer.startsWith("OCR found no") &&
                !answer.startsWith("Type a") -> {
                ingestText(answer, "NOTE")
            }
            else -> {
                _state.update {
                    it.copy(
                        recallFound = false,
                        recallMessage = "Type a note in the field below, or Ask first and tap Save to keep the answer.",
                        status = "Nothing to save"
                    )
                }
            }
        }
    }

    fun toggleGuardian(on: Boolean) {
        if (on) {
            GuardianService.start(getApplication())
            _state.update {
                it.copy(
                    guardianOn = true,
                    orb = OrbState.LISTENING,
                    recallFound = true,
                    recallMessage = "Guardian is listening on-device. It learns a quiet baseline, " +
                        "labels sounds (kitchen alert, cough, smoke, glass, fall), stores them in memory, " +
                        "and — if IR is armed — can send an IR pulse when evidence supports it.",
                    status = "Guardian on"
                )
            }
        } else {
            GuardianService.stop(getApplication())
            _state.update {
                it.copy(
                    guardianOn = false,
                    orb = OrbState.IDLE,
                    amplitude = 0f,
                    status = "Guardian off",
                    recallMessage = "Guardian stopped. Ask “what did Guardian hear?” to review stored detections."
                )
            }
        }
    }

    fun armPlay(resultCode: Int, data: Intent, w: Int, h: Int, dpi: Int) {
        CaptureProjectionService.start(getApplication(), resultCode, data, w, h, dpi)
        _state.update {
            it.copy(
                playArmed = true,
                recallFound = true,
                recallMessage = "Capture on — PEACE saves only the highlight (≈ kill −5s…+5s), not the whole buffer. " +
                    "Manual Clip keeps the last ~12s. Stop when done.",
                status = "Recording · PEACE watching"
            )
        }
        actuators.setHalo(SmritiLightState.SCREEN_RECORDING)
    }

    fun disarmPlay() {
        _state.update {
            it.copy(
                playArmed = false,
                orb = OrbState.COMPUTING,
                status = "Stopping · OCR…",
                recallMessage = "Saving the recording and reading on-screen text…"
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = CaptureProjectionService.flushAndStop(getApplication())
            withContext(Dispatchers.Main) {
                applyStopResult(result)
                actuators.setHalo(
                    if (GuardianService.isRunning()) SmritiLightState.VOICE_RECORDING
                    else SmritiLightState.NORMAL
                )
            }
        }
    }

    private fun applyStopResult(result: ClipFlushResult) {
        when (result) {
            is ClipFlushResult.Saved -> {
                viewModelScope.launch {
                    reloadTimeline()
                    val seen = result.sceneText.trim()
                    _state.update {
                        it.copy(
                            playArmed = false,
                            orb = OrbState.VERIFIED,
                            recallFound = true,
                            recallMessage = if (seen.isNotBlank()) {
                                "Recording stopped. OCR saved" +
                                    (result.ocrPath?.let { " (${File(it).name})" } ?: "") +
                                    " and sent to memory for Qwen.\n\n" +
                                    "On screen I saw:\n${seen.take(500)}\n\n" +
                                    "Ask e.g. “what was on my screen?” or any question about that recording."
                            } else {
                                "Recording stopped and clip saved, but no readable text was found. " +
                                    "Tap the Play row to watch it, or Ask about the recording anyway."
                            },
                            status = "Stopped · OCR ready for Ask"
                        )
                    }
                }
            }
            ClipFlushResult.Empty, ClipFlushResult.NotRecording -> {
                _state.update {
                    it.copy(
                        playArmed = false,
                        orb = OrbState.IDLE,
                        recallFound = false,
                        status = "Capture stopped",
                        recallMessage = "Capture stopped before enough screen was buffered. " +
                            "Start Capture again, wait a few seconds, then tap Stop."
                    )
                }
            }
            is ClipFlushResult.Failed -> {
                _state.update {
                    it.copy(
                        playArmed = false,
                        orb = OrbState.IDLE,
                        recallFound = false,
                        status = "Stop failed",
                        recallMessage = "Capture stopped, but OCR/save failed: ${result.message}"
                    )
                }
            }
        }
    }

    fun manualClip() {
        if (!CaptureProjectionService.isRunning()) {
            _state.update {
                it.copy(
                    recallFound = false,
                    recallMessage = "Start Capture first — Clip saves the last ~30 seconds of screen into memory.",
                    status = "Capture off"
                )
            }
            return
        }
        if (!CaptureProjectionService.hasBufferedFrames()) {
            _state.update {
                it.copy(
                    recallFound = false,
                    recallMessage = "Still buffering video. Keep Capture on for a few seconds, then tap Clip again.",
                    status = "Buffer empty"
                )
            }
            return
        }
        _state.update { it.copy(status = "Saving clip…", orb = OrbState.COMPUTING) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = CaptureProjectionService.captureNow(getApplication())
            withContext(Dispatchers.Main) { applyClipResult(result) }
        }
    }

    private fun applyClipResult(result: ClipFlushResult) {
        when (result) {
            is ClipFlushResult.Saved -> {
                viewModelScope.launch {
                    reloadTimeline()
                    val seen = result.sceneText.trim()
                    _state.update {
                        it.copy(
                            orb = OrbState.VERIFIED,
                            recallFound = true,
                            recallMessage = if (seen.isNotBlank()) {
                                "Clip in MEMORY + Library\n\n$seen".take(900) +
                                    "\n\nScroll to CLIPS · tap to play · Ask anytime."
                            } else {
                                "Clip saved, but summary was thin. Enable Gemma OCR in System → Local model."
                            },
                            status = "Clip · ${File(result.path).name}"
                        )
                    }
                }
            }
            ClipFlushResult.Empty -> {
                _state.update {
                    it.copy(
                        orb = OrbState.IDLE,
                        recallFound = false,
                        recallMessage = "Still buffering video. Keep Capture on for a few seconds, then tap Clip again.",
                        status = "Buffer empty"
                    )
                }
            }
            ClipFlushResult.NotRecording -> {
                _state.update {
                    it.copy(
                        orb = OrbState.IDLE,
                        recallFound = false,
                        recallMessage = "Start Capture first — Clip saves the last ~30 seconds of screen into memory.",
                        status = "Capture off"
                    )
                }
            }
            is ClipFlushResult.Failed -> {
                _state.update {
                    it.copy(
                        orb = OrbState.IDLE,
                        recallFound = false,
                        recallMessage = "Could not save clip: ${result.message}",
                        status = "Clip failed"
                    )
                }
            }
        }
    }

    fun setScreenMind(on: Boolean) {
        screenMindPrefs.enabled = on
        _state.update { it.copy(screenMindOn = on) }
        _toasts.tryEmit(if (on) "ScreenMind ON — Capture/OCR analyze screens" else "ScreenMind OFF")
    }

    fun setSwipeOcr(on: Boolean) {
        val ctx = getApplication<Application>()
        runCatching { OcrSwipeOverlayService.stop(ctx) }
        SmritiOcrAccessService.setArmed(ctx, on)
        _state.update { it.copy(swipeOcrOn = on) }
        if (on) {
            _toasts.tryEmit(
                if (SmritiOcrAccessService.isConnected()) {
                    "Swipe OCR on — swipe up from the bottom edge (1 or 2 fingers)"
                } else {
                    "Enable Accessibility → SMRITI OCR Capture, then turn Swipe OCR on again"
                }
            )
        } else {
            _toasts.tryEmit("Swipe OCR off")
        }
    }

    fun setScreenMindPc(on: Boolean) {
        screenMindPrefs.pcEnabled = on
        _state.update { it.copy(screenMindPcOn = on) }
        if (on) {
            viewModelScope.launch {
                val msg = screenMindPc.ping().fold(
                    onSuccess = { it },
                    onFailure = {
                        "PC ScreenMind offline — run desktop app, set URL (${screenMindPrefs.pcBaseUrl})"
                    }
                )
                _toasts.tryEmit(msg)
            }
        } else {
            _toasts.tryEmit("PC ScreenMind OFF")
        }
    }

    fun setIr(enabled: Boolean) {
        prefs.irArmed = enabled
        _state.update { it.copy(irEnabled = enabled) }
        if (enabled) {
            val cap = actuators.irCapability()
            actuators.haptic(HardwareActuatorService.HAPTIC_TICK)
            val msg = GuardianSense.armIrMessage(cap)
            NeuralCoreMemory.rememberAsync(
                getApplication(),
                raw = msg,
                source = "IR",
                kind = TaxonomyParser.Kind.ACOUSTIC,
                throttleMs = 0L
            )
            _state.update {
                it.copy(
                    status = cap,
                    recallFound = true,
                    recallMessage = msg
                )
            }
        } else {
            val msg = GuardianSense.disarmIrMessage()
            NeuralCoreMemory.rememberAsync(
                getApplication(),
                raw = msg,
                source = "IR",
                throttleMs = 0L
            )
            _state.update {
                it.copy(
                    status = "IR disarmed",
                    recallFound = true,
                    recallMessage = msg
                )
            }
        }
    }

    fun setListening() {
        _state.update { it.copy(orb = OrbState.LISTENING, status = "Whisper listening…") }
        actuators.setHalo(SmritiLightState.VOICE_RECORDING)
    }

    fun setListeningIdle() {
        _state.update {
            it.copy(
                orb = if (GuardianService.isRunning()) OrbState.LISTENING else OrbState.IDLE,
                status = "On-device · SMRITI AQUA"
            )
        }
        if (!GuardianService.isRunning()) {
            actuators.setHalo(SmritiLightState.NORMAL)
        }
    }

    private suspend fun reloadTimeline() {
        val rows = memory.timeline(40)
        _state.update { it.copy(timeline = rows) }
    }

    private fun speak(text: String) {
        _state.update { it.copy(orb = OrbState.SPEAKING) }
        val smriti = SmritiCore.get(getApplication())
        val configured = smriti.llmPrefs.targetLanguage.lowercase()
        val target = if (configured == "auto") {
            com.aquascope.smriti.llm.GroundedPromptBuilder.detectLanguage(text)
        } else {
            configured
        }
        val useIndicTts = target in com.aquascope.smriti.llm.SarvamPreferences.INDIAN_LANGUAGE_TARGETS &&
            screenMindPrefs.pcEnabled && screenMindPrefs.ttsEnabled
        if (useIndicTts) {
            viewModelScope.launch {
                val bytes = withTimeoutOrNull(20_000L) { indicTtsClient.speak(text, target) }
                    ?.getOrNull()
                if (bytes != null) {
                    indicTtsPlayer.play(wavBytes = bytes, onError = { speakWithAndroidTts(text) })
                } else {
                    speakWithAndroidTts(text)
                }
            }
            return
        }
        speakWithAndroidTts(text)
    }

    /** Fallback / default path — Android's built-in TextToSpeech, unchanged from before. */
    private fun speakWithAndroidTts(text: String) {
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
        indicTtsPlayer.stop()
        super.onCleared()
    }
}
