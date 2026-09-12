package com.smriti.brain.database

class EpisodeStore(private val db: SmritiDatabase) {
    suspend fun ingest(
        title: String,
        body: String,
        source: String,
        taxonomy: MemoryTaxonomy = TaxonomyRouter.route("$title $body"),
        at: Long = System.currentTimeMillis()
    ): Long {
        val text = "$title\n$body"
        val entity = EpisodeEntity(
            createdAt = at,
            taxonomy = taxonomy.wire,
            title = title,
            body = body,
            source = source,
            embedding = LocalEmbedder.toBlob(LocalEmbedder.embed(text))
        )
        return db.episodes().insert(entity)
    }

    suspend fun latest(limit: Int = 50) = db.episodes().latest(limit)

    suspend fun inRange(startMs: Long, endMs: Long) = db.episodes().inRange(startMs, endMs)

    suspend fun search(query: String, limit: Int = 20): List<ScoredEpisode> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val ftsQuery = q.split(Regex("\\s+")).joinToString(" OR ") { "\"$it\"" }
        val ftsHits = try {
            db.episodes().fts(ftsQuery, limit * 3)
        } catch (_: Throwable) {
            emptyList()
        }
        val vec = LocalEmbedder.embed(q)
        val pool = (ftsHits + db.episodes().latest(80)).distinctBy { it.id }
        return pool.map {
            ScoredEpisode(
                episode = it,
                fts = ftsHits.any { h -> h.id == it.id },
                cosine = LocalEmbedder.cosine(vec, LocalEmbedder.fromBlob(it.embedding))
            )
        }.sortedByDescending { it.cosine + if (it.fts) 0.15f else 0f }
            .take(limit)
    }
}

data class ScoredEpisode(
    val episode: EpisodeEntity,
    val fts: Boolean,
    val cosine: Float
)
