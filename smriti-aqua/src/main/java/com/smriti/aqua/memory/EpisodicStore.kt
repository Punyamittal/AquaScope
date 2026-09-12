package com.smriti.aqua.memory

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * EpisodicStore — the SQLite-backed episodic memory log.
 *
 * This database is the SINGLE SOURCE OF TRUTH for SMRITI AQUA. The recall
 * engine answers only from rows stored here; nothing is ever fabricated.
 *
 * Uses only android.database.sqlite APIs (+ android.content.Context).
 * FloatArray <-> BLOB encoding: raw little-endian IEEE-754 floats via ByteBuffer.
 */
class EpisodicStore(context: Context) :
    SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "smriti.db"
        private const val DB_VERSION = 1

        // Exact schema from SPEC section 5 — do not alter.
        private const val SQL_CREATE_EVENTS =
            "CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, object_id TEXT NOT NULL, location TEXT," +
                " timestamp INTEGER NOT NULL, sensor TEXT NOT NULL, baseline_id INTEGER, deviation REAL," +
                " duration_ms INTEGER, coherence REAL, status TEXT NOT NULL, fingerprint BLOB, note TEXT);"
        private const val SQL_CREATE_BASELINES =
            "CREATE TABLE baselines(id INTEGER PRIMARY KEY AUTOINCREMENT, object_id TEXT NOT NULL," +
                " created_at INTEGER NOT NULL, mean BLOB NOT NULL, var BLOB NOT NULL, samples INTEGER NOT NULL);"

        /** Lower clamp for variance so diagonal Mahalanobis distance stays finite. */
        private const val VAR_EPSILON = 1e-6f

        private const val SQL_INSERT_EVENT =
            "INSERT INTO events(object_id, location, timestamp, sensor, baseline_id, deviation," +
                " duration_ms, coherence, status, fingerprint, note) VALUES(?,?,?,?,?,?,?,?,?,NULL,?)"

        private const val SQL_INSERT_BASELINE =
            "INSERT INTO baselines(object_id, created_at, mean, var, samples) VALUES(?,?,?,?,?)"

        private const val EVENT_COLS =
            "id, object_id, location, timestamp, sensor, baseline_id, deviation," +
                " duration_ms, coherence, status, note"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SQL_CREATE_EVENTS)
        db.execSQL(SQL_CREATE_BASELINES)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1: no upgrade path yet. Future migrations go here.
    }

    // ---------- FloatArray <-> BLOB (little-endian) ----------

    private fun floatsToBlob(values: FloatArray): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buf.putFloat(v)
        return buf.array()
    }

    private fun blobToFloats(blob: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(blob.size / 4)
        for (i in out.indices) out[i] = buf.getFloat()
        return out
    }

    // ---------- Events ----------

    fun insertEvent(e: Event): Long {
        val stmt = writableDatabase.compileStatement(SQL_INSERT_EVENT)
        try {
            stmt.bindString(1, e.objectId)
            stmt.bindString(2, e.location)
            stmt.bindLong(3, e.timestamp)
            stmt.bindString(4, e.sensor)
            if (e.baselineId != null) stmt.bindLong(5, e.baselineId) else stmt.bindNull(5)
            stmt.bindDouble(6, e.deviation.toDouble())
            stmt.bindLong(7, e.durationMs)
            stmt.bindDouble(8, e.coherence.toDouble())
            stmt.bindString(9, e.status.name)
            stmt.bindString(10, e.note)
            return stmt.executeInsert()
        } finally {
            stmt.close()
        }
    }

    /** Newest first, capped by [limit]. */
    fun eventsFor(objectId: String, limit: Int = 200): List<Event> = queryEvents(
        "SELECT $EVENT_COLS FROM events WHERE object_id = ?" +
            " ORDER BY timestamp DESC, id DESC LIMIT ?",
        arrayOf(objectId, limit.toString())
    )

    /** All ANOMALY-status events for an object, newest first. */
    fun anomaliesFor(objectId: String): List<Event> = queryEvents(
        "SELECT $EVENT_COLS FROM events WHERE object_id = ? AND status = ?" +
            " ORDER BY timestamp DESC, id DESC",
        arrayOf(objectId, EventStatus.ANOMALY.name)
    )

    /** Newest NORMAL event for [objectId] with timestamp strictly before [beforeTs]. */
    fun lastNormalBefore(objectId: String, beforeTs: Long): Event? = queryEvents(
        "SELECT $EVENT_COLS FROM events WHERE object_id = ? AND status = ? AND timestamp < ?" +
            " ORDER BY timestamp DESC, id DESC LIMIT 1",
        arrayOf(objectId, EventStatus.NORMAL.name, beforeTs.toString())
    ).firstOrNull()

    /** Distinct object ids ever seen, most recently active first. */
    fun allObjectIds(): List<String> {
        val out = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT object_id FROM events GROUP BY object_id ORDER BY MAX(timestamp) DESC, MAX(id) DESC",
            emptyArray()
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }

    fun eventCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events", emptyArray()).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /**
     * Runs a SELECT whose column order matches [EVENT_COLS] and maps every row
     * to an [Event]. Column order: id, object_id, location, timestamp, sensor,
     * baseline_id, deviation, duration_ms, coherence, status, note.
     */
    private fun queryEvents(sql: String, args: Array<String>): List<Event> {
        val out = ArrayList<Event>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Event(
                        id = c.getLong(0),
                        objectId = c.getString(1),
                        location = if (c.isNull(2)) "" else c.getString(2),
                        timestamp = c.getLong(3),
                        sensor = c.getString(4),
                        baselineId = if (c.isNull(5)) null else c.getLong(5),
                        deviation = if (c.isNull(6)) 0f else c.getFloat(6),
                        durationMs = if (c.isNull(7)) 0L else c.getLong(7),
                        coherence = if (c.isNull(8)) 0f else c.getFloat(8),
                        status = EventStatus.valueOf(c.getString(9)),
                        note = if (c.isNull(10)) "" else c.getString(10)
                    )
                )
            }
        }
        return out
    }

    // ---------- Baselines ----------

    /**
     * Computes per-dimension mean and population variance (Welford accumulation)
     * across [fps], clamps variance to >= 1e-6 so diagonal Mahalanobis stays
     * stable, and inserts the baseline row. Returns the new baseline id.
     */
    fun saveBaseline(objectId: String, fps: List<FloatArray>): Long {
        require(fps.isNotEmpty()) { "saveBaseline requires at least one fingerprint" }
        val dim = fps[0].size
        require(fps.all { it.size == dim }) { "all fingerprints must have the same dimension" }

        val n = fps.size
        val mean = FloatArray(dim)
        val m2 = FloatArray(dim)
        // Welford's online algorithm, accumulated per dimension.
        for ((count, fp) in fps.withIndex()) {
            for (d in 0 until dim) {
                val delta = fp[d] - mean[d]
                mean[d] += delta / (count + 1)
                m2[d] += delta * (fp[d] - mean[d])
            }
        }
        val variance = FloatArray(dim) { d ->
            val v = m2[d] / n // population variance
            if (v < VAR_EPSILON) VAR_EPSILON else v
        }

        val stmt = writableDatabase.compileStatement(SQL_INSERT_BASELINE)
        try {
            stmt.bindString(1, objectId)
            stmt.bindLong(2, System.currentTimeMillis())
            stmt.bindBlob(3, floatsToBlob(mean))
            stmt.bindBlob(4, floatsToBlob(variance))
            stmt.bindLong(5, n.toLong())
            return stmt.executeInsert()
        } finally {
            stmt.close()
        }
    }

    /** Most recently created baseline for [objectId], or null. */
    fun latestBaseline(objectId: String): Baseline? {
        readableDatabase.rawQuery(
            "SELECT id, object_id, created_at, mean, var, samples FROM baselines" +
                " WHERE object_id = ? ORDER BY created_at DESC, id DESC LIMIT 1",
            arrayOf(objectId)
        ).use { c ->
            if (!c.moveToFirst()) return null
            return Baseline(
                id = c.getLong(0),
                objectId = c.getString(1),
                createdAt = c.getLong(2),
                mean = blobToFloats(c.getBlob(3)),
                variance = blobToFloats(c.getBlob(4)),
                samples = c.getInt(5)
            )
        }
    }
}
