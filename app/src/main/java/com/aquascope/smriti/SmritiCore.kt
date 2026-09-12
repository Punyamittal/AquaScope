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
import com.aquascope.smriti.llm.OllamaClient
import com.aquascope.smriti.llm.OllamaPreferences
import com.aquascope.smriti.llm.SmritiAnswerComposer
import com.aquascope.smriti.memory.EpisodicMemoryStore
import com.aquascope.smriti.model.EventStatus
import com.aquascope.smriti.model.EventType
import com.aquascope.smriti.model.EvidenceState
import com.aquascope.smriti.model.HomeMemorySnapshot
import com.aquascope.smriti.model.HomeModel
import com.aquascope.smriti.model.MemoryNodeState
import com.aquascope.smriti.model.PhysicalEvent
import com.aquascope.smriti.model.SmritiAnswer
import com.aquascope.smriti.skills.SkillCatalog
import com.aquascope.smriti.skills.SkillMatch
import com.aquascope.smriti.skills.SkillMatcher
import com.aquascope.smriti.skills.SkillPreferences
import com.aquascope.smriti.skills.SkillPromptInjector
import com.aquascope.smriti.skills.SkillToolRunner
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
    val ollamaPrefs = OllamaPreferences(appContext)
    val ollamaClient = OllamaClient(ollamaPrefs)
    val modelDownloader = LocalModelDownloader(appContext, modelStore)
    val skillCatalog = SkillCatalog(appContext)
    val skillPrefs = SkillPreferences(appContext)
    private val skillTools = SkillToolRunner(appContext)

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

    fun rememberObservation(
        summary: String,
        source: String,
        locationLabel: String = "This phone",
        eventType: EventType = EventType.UNKNOWN,
        anomalyScore: Double = 0.0,
        evidenceNotes: List<String> = emptyList()
    ): PhysicalEvent {
        val event = normalizer.fromObservation(
            locationLabel = locationLabel,
            summary = summary,
            source = source,
            eventType = eventType,
            anomalyScore = anomalyScore,
            evidenceNotes = evidenceNotes
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

    fun ask(
        question: String,
        allowLocalLlm: Boolean = true,
        neuralEpisodes: List<com.aquascope.smriti.brain.EpisodeRecord> = emptyList()
    ): SmritiAnswer {
        val query = retrieval.parse(question)
        var retrieved = retrieval.retrieve(query)
        retrieved = boostWithLatestScreenOcr(query, retrieved)
        retrieved = boostWithNeuralEpisodes(neuralEpisodes, retrieved)
        var ruleAnswer = reasoning.answer(query, retrieved)
        // Neural Core SQLite hits must always ground the rule answer so Qwen sees them.
        if (neuralEpisodes.isNotEmpty() &&
            (ruleAnswer.evidenceState == EvidenceState.UNKNOWN || ruleAnswer.relatedEvents.isEmpty())
        ) {
            ruleAnswer = neuralMemoryRuleAnswer(neuralEpisodes)
        } else if (neuralEpisodes.isNotEmpty()) {
            val neuralEvents = neuralEpisodesToEvents(neuralEpisodes)
            ruleAnswer = ruleAnswer.copy(
                relatedEvents = (neuralEvents + ruleAnswer.relatedEvents)
                    .distinctBy { it.id }
                    .take(8),
                evidenceState = EvidenceState.OBSERVED
            )
        }

        val enabledSkills = skillCatalog.enabled(skillPrefs)
        val matched = SkillMatcher.match(question, enabledSkills)
        val toolResult = matched?.let { runCatching { skillTools.run(it, question) }.getOrNull() }
        val skillMatch = matched?.let { SkillMatch(it, toolResult) }
        val catalogBlurb = SkillPromptInjector.catalogBlurb(enabledSkills.take(8))

        if (!allowLocalLlm || !llmPrefs.enabled) {
            if (!toolResult.isNullOrBlank()) {
                return ruleAnswer.copy(
                    text = toolResult,
                    usedLocalModel = false,
                    modelName = "skill:${matched!!.name}"
                )
            }
            return ruleAnswer.copy(usedLocalModel = false, modelName = null)
        }
        // Wait for sandbox (MediaPipe .task or LiteRT-LM .litertlm).
        val ready = try {
            isolatedLlm.ensureReady()
        } catch (t: Throwable) {
            Log.w("SmritiCore", "LLM ensureReady failed", t)
            false
        }
        if (!ready) {
            if (!toolResult.isNullOrBlank()) {
                return ruleAnswer.copy(
                    text = toolResult,
                    usedLocalModel = false,
                    modelName = "skill:${matched!!.name}"
                )
            }
            return ruleAnswer.copy(usedLocalModel = false, modelName = null)
        }
        return try {
            SmritiAnswerComposer(isolatedLlm, llmPrefs)
                .compose(question, ruleAnswer, query.intent, skillMatch, catalogBlurb)
        } catch (t: Throwable) {
            Log.w("SmritiCore", "LLM compose failed", t)
            if (!toolResult.isNullOrBlank()) {
                ruleAnswer.copy(
                    text = toolResult,
                    usedLocalModel = false,
                    modelName = "skill:${matched!!.name}"
                )
            } else {
                ruleAnswer.copy(usedLocalModel = false, modelName = null)
            }
        }
    }

    fun llmFailureHint(): String? = isolatedLlm.lastFailure()

    /**
     * Prefer the freshest Stop/Clip / gallery OCR so Qwen can answer about on-screen text.
     * Inject whenever OCR is present — not only when the question says “screen”.
     */
    private fun boostWithLatestScreenOcr(
        @Suppress("UNUSED_PARAMETER") query: com.aquascope.smriti.model.MemoryQuery,
        retrieved: List<PhysicalEvent>
    ): List<PhysicalEvent> {
        val ocr = com.aquascope.smriti.brain.NeuralCoreSession.lastScreenOcr.trim()
        if (ocr.isBlank()) return retrieved
        val already = retrieved.any {
            it.source.equals("SMRITI_PLAY", true) ||
                it.source.equals("OCR", true) ||
                it.summary.contains(ocr.take(80), ignoreCase = true) ||
                it.summary.contains("Seen on screen", ignoreCase = true)
        }
        if (already) return retrieved
        val synthetic = PhysicalEvent(
            id = "screen-ocr-latest",
            timestampMs = System.currentTimeMillis(),
            locationId = "screen",
            locationLabel = "Screen / OCR",
            objectId = "screen",
            objectLabel = "Latest OCR",
            eventType = EventType.UNKNOWN,
            anomalyScore = 0.0,
            confidence = 1.0,
            baselineId = null,
            summary = "Latest OCR\nSeen on screen:\n${ocr.take(1_500)}",
            source = "SMRITI_PLAY",
            evidenceNotes = listOfNotNull(
                com.aquascope.smriti.brain.NeuralCoreSession.lastScreenOcrPath?.let { "OCR file: $it" }
            )
        )
        return listOf(synthetic) + retrieved
    }

    /** Feed Neural Core SQLite hits into retrieval so Qwen rephrases them. */
    private fun boostWithNeuralEpisodes(
        episodes: List<com.aquascope.smriti.brain.EpisodeRecord>,
        retrieved: List<PhysicalEvent>
    ): List<PhysicalEvent> {
        if (episodes.isEmpty()) return retrieved
        val neural = neuralEpisodesToEvents(episodes)
        val known = retrieved.map { it.id }.toHashSet()
        return neural.filter { it.id !in known } + retrieved
    }

    private fun neuralEpisodesToEvents(
        episodes: List<com.aquascope.smriti.brain.EpisodeRecord>
    ): List<PhysicalEvent> =
        episodes.take(8).map { ep ->
            PhysicalEvent(
                id = "neural-${ep.id}",
                timestampMs = ep.timestampMs,
                locationId = "neural",
                locationLabel = ep.kind.name.lowercase().replace('_', ' '),
                objectId = ep.kind.name,
                objectLabel = ep.title.ifBlank { "Neural memory" },
                eventType = EventType.UNKNOWN,
                anomalyScore = 0.0,
                confidence = ep.score.toDouble().coerceIn(0.0, 1.0),
                baselineId = null,
                summary = buildString {
                    append(ep.title.trim())
                    val body = ep.body.trim()
                    if (body.isNotBlank() && !body.equals(ep.title.trim(), ignoreCase = true)) {
                        append('\n')
                        append(body.take(1_200))
                    }
                },
                source = "NEURAL_CORE",
                evidenceNotes = listOfNotNull(ep.evidencePath?.let { "Evidence: $it" })
            )
        }

    private fun neuralMemoryRuleAnswer(
        episodes: List<com.aquascope.smriti.brain.EpisodeRecord>
    ): SmritiAnswer {
        val events = neuralEpisodesToEvents(episodes)
        val fmt = java.text.SimpleDateFormat("d MMM yyyy, h:mm a", java.util.Locale.US)
        val preview = events.take(6).joinToString("\n") { e ->
            "• ${fmt.format(java.util.Date(e.timestampMs))} — ${e.objectLabel}: " +
                com.aquascope.smriti.llm.AskAnswerCleaner.userFacingSummary(e.summary).take(400)
        }
        val text = "From neural memory (${episodes.size} match${if (episodes.size == 1) "" else "es"}):\n$preview"
        return SmritiAnswer(
            text = text,
            evidenceState = EvidenceState.OBSERVED,
            relatedEvents = events,
            evidence = evidence.forEvents(events, "Neural memory hits", EvidenceState.OBSERVED),
            suggestedActions = listOf("Ask a follow-up about this memory")
        )
    }

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
