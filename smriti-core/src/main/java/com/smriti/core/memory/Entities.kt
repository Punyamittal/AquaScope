package com.smriti.core.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Episode taxonomy. Classification rules live in [TaxonomyParser].
 * Sacred contract — SPEC section 2.1. Do not change ordering/names.
 */
enum class EntityType { TOOL, HEALTH, PLACE, LOCATION, MONEY, PEOPLE, ACCESS, EPISODE }

/** One scored memory hit returned by [SmritiMemoryEngine.search] / [SmritiMemoryEngine.recentEpisodes]. */
data class SearchResult(
    val id: Long,
    val type: EntityType,
    val timestamp: Long,
    val summary: String,
    val score: Float
)

/** Zero-hallucination recall verdict: either a cited answer or an honest "no record". */
sealed interface RecallVerdict {
    data class Found(val citation: String, val evidenceIds: List<Long>) : RecallVerdict
    data object NoRecord : RecallVerdict
}

/**
 * Room entity for the `episodes` table (SPEC section 2.1).
 * Columns exactly: id INTEGER PK AUTOINCREMENT, type TEXT, timestamp INTEGER,
 * source TEXT, content TEXT, summary TEXT, embedding BLOB.
 * `embedding` is a FloatArray(128) encoded little-endian (see helpers below).
 */
@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,
    @ColumnInfo(name = "type") val type: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "summary") val summary: String,
    @ColumnInfo(name = "embedding") val embedding: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EpisodeEntity) return false
        return id == other.id &&
            type == other.type &&
            timestamp == other.timestamp &&
            source == other.source &&
            content == other.content &&
            summary == other.summary &&
            embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + source.hashCode()
        result = 31 * result + content.hashCode()
        result = 31 * result + summary.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}

/** Encode a FloatArray as a little-endian BLOB. Internal to the memory module. */
internal fun floatArrayToBlob(values: FloatArray): ByteArray {
    val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
    for (v in values) buffer.putFloat(v)
    return buffer.array()
}

/** Decode a little-endian BLOB produced by [floatArrayToBlob] back to a FloatArray. */
internal fun blobToFloatArray(blob: ByteArray): FloatArray {
    val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(blob.size / 4)
    for (i in out.indices) out[i] = buffer.float
    return out
}
