package com.smriti.core.memory

import android.content.Context
import androidx.annotation.WorkerThread
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * SMRITI episodic memory engine (SPEC section 2.1 — sacred public API).
 *
 * Pipeline:
 *  - insertRaw: taxonomy-parse -> insert row (+ FTS via trigger) -> cache embedding.
 *  - search:    FTS5 keyword candidates -> cosine re-rank in memory ->
 *               top-k by 0.6*cosine + 0.4*ftsRank.
 *  - recall:    zero-hallucination — top hit must score >= [RECALL_THRESHOLD],
 *               otherwise an honest [RecallVerdict.NoRecord].
 *
 * Threading policy (consistent across the whole engine): every public method is
 * BLOCKING and annotated @WorkerThread; callers must invoke off the main thread.
 * The UI layer calls from Dispatchers.Default/IO per SPEC section 2.5, which
 * satisfies the SPEC section 4 dispatcher discipline without the engine
 * re-dispatching internally. All mutable state ([vectorCache]) is thread-safe.
 */
class SmritiMemoryEngine(context: Context) {

    private val dao: EpisodeDao = SmritiDatabase.get(context).episodeDao()

    /** Default deterministic embedder; swap for a MediaPipe/TFLite [EmbeddingProvider] if desired. */
    private val embedder: EmbeddingProvider = HashEmbeddingProvider()

    /** Hot vector cache: episode id -> 128-dim L2-normalized embedding (lazily warmed from db BLOBs). */
    private val vectorCache = ConcurrentHashMap<Long, FloatArray>()

    /**
     * Parse [text], insert one row per (type, summary) pair with its embedding,
     * and return the new row ids. FTS index is updated by the AFTER INSERT trigger.
     */
    @WorkerThread
    fun insertRaw(text: String, source: String): List<Long> {
        if (text.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        val embedding = embedder.embed(text)
        val blob = floatArrayToBlob(embedding)
        val ids = ArrayList<Long>()
        for ((type, summary) in TaxonomyParser.parse(text)) {
            val id = dao.insert(
                EpisodeEntity(
                    id = 0,
                    type = type.name,
                    timestamp = now,
                    source = source,
                    content = text,
                    summary = summary,
                    embedding = blob
                )
            )
            vectorCache[id] = embedding
            ids += id
        }
        return ids
    }

    /**
     * FTS5 keyword candidates -> vector re-rank.
     * Score = 0.6 * cosine(query, episode) + 0.4 * ftsRank, where ftsRank is the
     * candidate's normalized position in the FTS result list (1.0 = first hit).
     */
    @WorkerThread
    fun search(query: String, k: Int = 10): List<SearchResult> {
        if (query.isBlank() || k <= 0) return emptyList()
        val ftsIds = dao.ftsMatch(query)
        if (ftsIds.isEmpty()) return emptyList()
        val rowsById = dao.byIds(ftsIds).associateBy { it.id }
        val queryEmbedding = embedder.embed(query)
        val total = ftsIds.size.toFloat()
        val scored = ArrayList<SearchResult>(ftsIds.size)
        ftsIds.forEachIndexed { index, id ->
            val row = rowsById[id] ?: return@forEachIndexed
            val embedding = vectorCache.getOrPut(id) { blobToFloatArray(row.embedding) }
            val cosine = cosine(queryEmbedding, embedding)
            val ftsRank = if (total <= 1f) 1f else 1f - (index / total)
            val score = 0.6f * cosine + 0.4f * ftsRank
            scored += SearchResult(
                id = row.id,
                type = safeType(row.type),
                timestamp = row.timestamp,
                summary = row.summary,
                score = score
            )
        }
        return scored.sortedByDescending { it.score }.take(k)
    }

    /**
     * Zero-hallucination recall: search top-3; if the best score >= 0.25 answer with a
     * citation "{summary} — recorded {h:mm a, d MMM yyyy}" + all evidence ids,
     * else [RecallVerdict.NoRecord]. Never invents content.
     */
    @WorkerThread
    fun recall(question: String): RecallVerdict {
        val results = search(question, 3)
        val best = results.firstOrNull() ?: return RecallVerdict.NoRecord
        if (best.score < RECALL_THRESHOLD) return RecallVerdict.NoRecord
        val recordedAt = SimpleDateFormat("h:mm a, d MMM yyyy", Locale.US).format(Date(best.timestamp))
        return RecallVerdict.Found(
            citation = "${best.summary} — recorded $recordedAt",
            evidenceIds = results.map { it.id }
        )
    }

    /** Most recent episodes (score fixed at 1.0 — no query to score against). */
    @WorkerThread
    fun recentEpisodes(limit: Int = 50): List<SearchResult> =
        dao.recent(limit).map {
            SearchResult(
                id = it.id,
                type = safeType(it.type),
                timestamp = it.timestamp,
                summary = it.summary,
                score = 1f
            )
        }

    @WorkerThread
    fun count(): Long = dao.count()

    /**
     * Storage cap: keep the [capRows] most recent episodes, delete older rows
     * (FTS rows are removed by the AFTER DELETE trigger). Called by SmritiApp.onCreate
     * on Dispatchers.IO. Evicted ids are also dropped from the hot vector cache.
     */
    @WorkerThread
    fun trimToCap(capRows: Int = 200_000) {
        val removed = dao.deleteOldestBeyond(capRows)
        // Drop cache entries whose rows no longer exist (lazily re-warmed on demand).
        if (removed > 0 && vectorCache.isNotEmpty()) {
            val alive = dao.recentIds(capRows).toHashSet()   // ids only — never materialize 200k full rows
            vectorCache.keys.retainAll(alive)
        }
    }

    private fun safeType(name: String): EntityType =
        runCatching { EntityType.valueOf(name) }.getOrDefault(EntityType.EPISODE)

    /** Cosine similarity; both inputs are normally L2-normalized but stay safe either way. */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom == 0f) 0f else dot / denom
    }

    companion object {
        /** Minimum top-hit score for a [RecallVerdict.Found] (SPEC section 2.1). */
        private const val RECALL_THRESHOLD = 0.25f
    }
}
