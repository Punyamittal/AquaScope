package com.smriti.core.play

/**
 * RAM-only ring of encoded H.264 chunks backing SMRITI Play's "last 30 seconds" buffer.
 *
 * Zero disk I/O: the [ScreenBufferRecorder] encoder callback writes every compressed access
 * unit here; nothing is ever flushed to storage until [ScreenBufferRecorder.triggerSave]
 * pulls a snapshot.
 *
 * Capacity is caller-supplied. SMRITI Play's default is 38 MiB
 * ([ScreenBufferRecorder.DEFAULT_CAPACITY_BYTES]): 30 s of 1080p60 H.264 at the configured
 * 10 Mbps is 30 * 10_000_000 / 8 = 37_500_000 bytes ~= 37.5 MB, rounded up to 38 MiB so a
 * full 30 s window plus encoder burst jitter always fits.
 *
 * Thread safety: all public entry points are synchronized. The buffer takes ownership of
 * the [ByteArray] passed to [write] (callers must not mutate it afterwards).
 */
class CircularByteBuffer(private val capacityBytes: Int) {

    init {
        require(capacityBytes > 0) { "capacityBytes must be > 0" }
    }

    /**
     * One stored access unit as delivered by MediaCodec: exactly one encoder output buffer.
     *
     * [bytes] is the raw Annex-B payload; [isKeyFrame] mirrors MediaCodec.BUFFER_FLAG_KEY_FRAME;
     * [arrivalTsMs] is a monotonic millisecond timestamp (System.nanoTime-derived) captured at
     * write time — it is only used to report [bufferedMs], never for muxing.
     */
    data class Chunk(val bytes: ByteArray, val isKeyFrame: Boolean, val arrivalTsMs: Long) {
        override fun equals(other: Any?): Boolean =
            other is Chunk && other.isKeyFrame == isKeyFrame &&
                other.arrivalTsMs == arrivalTsMs && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + arrivalTsMs.hashCode()
    }

    private val chunks = ArrayDeque<Chunk>()
    private var totalBytes = 0

    /** Milliseconds of video currently held, derived from chunk arrival timestamps. */
    val bufferedMs: Int
        @Synchronized get() {
            if (chunks.size < 2) return 0
            return (chunks.last().arrivalTsMs - chunks.first().arrivalTsMs)
                .coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        }

    /** Bytes currently held. */
    val sizeBytes: Int
        @Synchronized get() = totalBytes

    /** Number of chunks currently held. */
    val chunkCount: Int
        @Synchronized get() = chunks.size

    /**
     * Appends [chunk] and evicts oldest chunks until the total fits [capacityBytes].
     * The chunk just written is never evicted by its own write.
     */
    @Synchronized
    fun write(chunk: ByteArray, isKeyFrame: Boolean) {
        if (chunk.isEmpty()) return
        val ts = System.nanoTime() / 1_000_000L
        chunks.addLast(Chunk(chunk, isKeyFrame, ts))
        totalBytes += chunk.size
        while (totalBytes > capacityBytes && chunks.size > 1) {
            totalBytes -= chunks.removeFirst().bytes.size
        }
    }

    /**
     * Concatenated payload from the most recent keyframe chunk (inclusive) to the newest
     * chunk, or null if the ring currently holds no keyframe.
     *
     * Note: with the recorder's i-frame-interval of 1 s and ~30 s of capacity, the ring
     * holds ~30 IDRs, so this is only null during the first second after start().
     */
    @Synchronized
    fun snapshotFromLastKeyFrame(): ByteArray? {
        val from = snapshotChunksFromLastKeyFrameLocked() ?: return null
        val out = ByteArray(from.sumOf { it.bytes.size })
        var pos = 0
        for (c in from) {
            System.arraycopy(c.bytes, 0, out, pos, c.bytes.size)
            pos += c.bytes.size
        }
        return out
    }

    /**
     * Same window as [snapshotFromLastKeyFrame] but preserving per-chunk boundaries and
     * keyframe flags so [ScreenBufferRecorder] can re-mux each chunk as its own MediaMuxer
     * sample. Null if no keyframe is present.
     */
    @Synchronized
    fun snapshotChunksFromLastKeyFrame(): List<Chunk>? = snapshotChunksFromLastKeyFrameLocked()

    private fun snapshotChunksFromLastKeyFrameLocked(): List<Chunk>? {
        val idx = chunks.indexOfLast { it.isKeyFrame }
        if (idx < 0) return null
        return chunks.drop(idx)
    }

    /** Drops all buffered chunks (used on start()/stop()). */
    @Synchronized
    fun clear() {
        chunks.clear()
        totalBytes = 0
    }
}
