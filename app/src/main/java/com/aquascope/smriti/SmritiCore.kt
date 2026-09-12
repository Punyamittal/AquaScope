package com.aquascope.smriti

import android.content.Context
import android.util.Log
import com.aquascope.dsp.AcousticFeatures
import com.aquascope.smriti.engine.EvidenceEngine
import com.aquascope.smriti.engine.EventNormalizer
import com.aquascope.smriti.engine.ReasoningEngine
import com.aquascope.smriti.engine.RetrievalEngine
import com.aquascope.smriti.llm.IsolatedLlmClient
import com.aquascope.smriti.llm.LocalLlmPreferences
import com.aquascope.smriti.llm.LocalModelDownloader
import com.aquascope.smriti.llm.LocalModelStatus
import com.aquascope.smriti.llm.LocalModelStore
import com.aquascope.smriti.llm.SmritiAnswerComposer
import com.aquascope.smriti.memory.EpisodicMemoryStore
import com.aquascope.smriti.model.EventStatus
import com.aquascope.smriti.model.EventType
import com.aquascope.smriti.model.HomeMemorySnapshot
import com.aquascope.smriti.model.HomeModel
import com.aquascope.smriti.model.MemoryNodeState
import com.aquascope.smriti.model.PhysicalEvent
import com.aquascope.smriti.model.SmritiAnswer
import java.util.Calendar

/**
 * Facade for the SMRITI intelligence layer above AquaScope sensing.
 * On-device first: capture → normalize → store → retrieve → reason → optional local LLM rephrase.
 */
class SmritiCore(context: Context) {

    private val appContext = context.applicationContext
    val store = EpisodicMemoryStore(appContext)
    val modelStore = LocalModelStore(appContext)
    val llmPrefs = LocalLlmPreferences(appContext)
    val modelDownloader = LocalModelDownloader(appContext, modelStore)

    private val normalizer = EventNormalizer(store)
    private val retrieval = RetrievalEngine(store)
    private val evidence = EvidenceEngine()
    private val reasoning = ReasoningEngine(evidence)

    private val isolatedLlm = IsolatedLlmClient(appContext)
    @Volatile private var llmLoadAttempted = false

    fun modelStatus(): LocalModelStatus = modelStore.status()

    /** Soft status without forcing a heavy MediaPipe load in the UI process. */
    fun modelStatusLight(): LocalModelStatus {
        val fileStatus = modelStore.status()
        return when {
            isolatedLlm.crashedNative -> fileStatus.copy(
                ready = false,
                message = "Local model crashed in sandbox — Ask is using rules. Reload from System to retry."
            )
            isolatedLlm.isReady -> fileStatus.copy(
                ready = true,
                message = "Local model ready: ${isolatedLlm.modelLabel ?: fileStatus.displayName}"
            )
            !fileStatus.ready -> fileStatus
            llmLoadAttempted -> fileStatus.copy(
                ready = false,
                message = isolatedLlm.lastFailure()
                    ?: "Model file found (${fileStatus.displayName}). Sandbox not ready — Ask uses rules until it loads."
            )
            else -> fileStatus.copy(
                ready = false,
                message = "Model file found (${fileStatus.displayName}). Open Ask or tap Reload to load it."
            )
        }
    }

    fun refreshLocalLlm(): LocalModelStatus {
        llmLoadAttempted = true
        return try {
            if (!llmPrefs.enabled) {
                isolatedLlm.close()
                return modelStatusLight()
            }
            isolatedLlm.reload()
            modelStatusLight()
        } catch (t: Throwable) {
            modelStatusLight().copy(
                ready = false,
                message = "Local model failed to load: ${t.message ?: t.javaClass.simpleName}"
            )
        }
    }

    /** Warm the sandbox process. Never loads MediaPipe in the UI process. */
    fun ensureLocalLlm() {
        if (!llmPrefs.enabled) return
        if (modelStore.findInstalled() == null) {
            llmLoadAttempted = true
            return
        }
        llmLoadAttempted = true
        try {
            isolatedLlm.ensureReady()
        } catch (t: Throwable) {
            Log.w("SmritiCore", "sandbox warmup failed", t)
        }
    }

    fun isLocalLlmReady(): Boolean = isolatedLlm.isReady

    fun rememberScan(
        locationId: String,
        locationLabel: String,
        features: AcousticFeatures,
        anomalyScore: Double,
        hasBaseline: Boolean
    ): PhysicalEvent {
        val event = normalizer.fromScan(
            locationId, locationLabel, features, anomalyScore, hasBaseline
        )
        store.appendEvent(event)
        return event
    }

    fun rememberBaseline(
        locationId: String,
        locationLabel: String,
        features: AcousticFeatures,
        sampleCount: Int
    ): PhysicalEvent {
        val event = normalizer.fromBaselineSaved(
            locationId, locationLabel, features, sampleCount
        )
        store.appendEvent(event)
        return event
    }

    fun timeline(limit: Int = 50): List<PhysicalEvent> =
        store.loadEvents().sortedByDescending { it.timestampMs }.take(limit)

    fun home(): HomeModel = store.loadHome()

    fun homeStatusSummary(): Pair<String, List<Pair<String, String>>> {
        val events = store.loadEvents()
        val home = store.loadHome()
        val rows = home.locations.map { loc ->
            val recent = events.filter { it.locationId == loc.id }.maxByOrNull { it.timestampMs }
            val status = when {
                recent == null -> "No scans yet"
                recent.eventType.name.contains("NORMAL") ||
                    recent.eventType.name.contains("BASELINE") -> "Normal"
                else -> "1 anomaly under observation"
            }
            loc.label to status
        }
        val anyAnomaly = events.takeLast(20).any {
            it.eventType.name.contains("ANOMALY") ||
                it.eventType.name.contains("DEVIATION") ||
                it.eventType.name.contains("LEAK")
        }
        val overall = if (anyAnomaly) "UNDER OBSERVATION" else "NORMAL"
        return overall to rows
    }

    fun homeMemorySnapshot(): HomeMemorySnapshot {
        val events = store.loadEvents()
        val home = store.loadHome()
        val startToday = startOfDayMs()
        val memoriesToday = events.count { it.timestampMs >= startToday }
        val anomalies = events.filter { isAnomaly(it.eventType) }
        val nodes = if (home.locations.isEmpty()) {
            emptyList()
        } else {
            val n = home.locations.size
            home.locations.mapIndexed { index, loc ->
                val recent = events.filter { it.locationId == loc.id }.maxByOrNull { it.timestampMs }
                val pulse = when {
                    recent == null -> MemoryNodeState.PULSE_NORMAL
                    isAnomaly(recent.eventType) -> MemoryNodeState.PULSE_ANOMALY
                    recent.timestampMs >= startToday -> MemoryNodeState.PULSE_ACTIVE
                    else -> MemoryNodeState.PULSE_NORMAL
                }
                val (x, y) = nodePosition(index, n)
                MemoryNodeState(
                    locationId = loc.id,
                    label = loc.label,
                    objectLabel = loc.objects.firstOrNull()?.label ?: loc.label,
                    pulse = pulse,
                    latest = recent,
                    xFrac = x,
                    yFrac = y
                )
            }
        }
        val density = (events.size / 18f).coerceIn(0.2f, 1f)
        val disturbance = if (anomalies.isEmpty()) 0.08f else (anomalies.size / 6f).coerceIn(0.25f, 1f)
        val statusLine = when {
            events.isEmpty() -> "AWAITING FIRST MEMORY"
            anomalies.any { it.timestampMs >= startToday } -> "UNDER OBSERVATION"
            else -> "HOME IS NORMAL"
        }
        return HomeMemorySnapshot(
            statusLine = statusLine,
            memoriesToday = memoriesToday,
            anomaliesObserved = anomalies.size,
            nodes = nodes,
            disturbance = disturbance,
            density = density
        )
    }

    fun firstAnomalyAt(locationId: String): PhysicalEvent? =
        store.eventsForLocation(locationId)
            .filter { isAnomaly(it.eventType) }
            .minByOrNull { it.timestampMs }

    fun eventsSince(sinceMs: Long): List<PhysicalEvent> =
        store.loadEvents().filter { it.timestampMs >= sinceMs }.sortedByDescending { it.timestampMs }

    private fun isAnomaly(type: EventType): Boolean = when (type) {
        EventType.ANOMALY, EventType.ACOUSTIC_DEVIATION, EventType.REPEATED_ANOMALY,
        EventType.POSSIBLE_LEAK -> true
        else -> false
    }

    private fun startOfDayMs(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun nodePosition(index: Int, count: Int): Pair<Float, Float> {
        val presets = listOf(
            0.26f to 0.40f,
            0.74f to 0.36f,
            0.50f to 0.50f,
            0.22f to 0.56f,
            0.78f to 0.54f,
            0.40f to 0.32f,
            0.62f to 0.44f
        )
        return presets.getOrElse(index) {
            val t = index / count.toFloat()
            (0.22f + 0.56f * t) to (0.42f + 0.18f * ((index % 3) - 1))
        }
    }

    fun ask(question: String, allowLocalLlm: Boolean = true): SmritiAnswer {
        val query = retrieval.parse(question)
        val retrieved = retrieval.retrieve(query)
        val ruleAnswer = reasoning.answer(query, retrieved)
        if (!allowLocalLlm || !llmPrefs.enabled) {
            return ruleAnswer.copy(usedLocalModel = false, modelName = null)
        }
        // Wait for sandbox MediaPipe (separate process) so free-form Ask can use Qwen/Gemma.
        val ready = try {
            isolatedLlm.ensureReady()
        } catch (t: Throwable) {
            Log.w("SmritiCore", "LLM ensureReady failed", t)
            false
        }
        if (!ready) {
            return ruleAnswer.copy(usedLocalModel = false, modelName = null)
        }
        return try {
            SmritiAnswerComposer(isolatedLlm, llmPrefs)
                .compose(question, ruleAnswer, query.intent)
        } catch (t: Throwable) {
            Log.w("SmritiCore", "LLM compose failed", t)
            ruleAnswer.copy(usedLocalModel = false, modelName = null)
        }
    }

    fun llmFailureHint(): String? = isolatedLlm.lastFailure()

    fun evidenceFor(eventId: String) =
        store.getEvent(eventId)?.let { evidence.forEvent(it) }

    fun applyUserCorrection(eventId: String, note: String, status: EventStatus) {
        val event = store.getEvent(eventId) ?: return
        store.updateEvent(
            event.copy(
                userCorrection = note,
                status = status,
                summary = event.summary + " User note: $note"
            )
        )
    }

    companion object {
        @Volatile private var instance: SmritiCore? = null

        fun get(context: Context): SmritiCore {
            return instance ?: synchronized(this) {
                instance ?: SmritiCore(context.applicationContext).also { instance = it }
            }
        }
    }
}
