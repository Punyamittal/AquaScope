package com.smriti.core.memory

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteQuery

/**
 * Data access for the `episodes` table plus FTS5 external-content search.
 *
 * The FTS5 virtual table `episodes_fts` and its sync triggers are NOT Room-managed;
 * they are created with raw SQL in [SmritiDatabase.FTS_CALLBACK]. Because Room cannot
 * verify compile-time SQL against tables it does not know, the FTS MATCH lookup is a
 * [RawQuery] (verification skipped at compile time, executed as-is at runtime).
 */
@Dao
abstract class EpisodeDao {

    /** Insert one episode; returns the new rowid (AUTOINCREMENT id). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract fun insert(entity: EpisodeEntity): Long

    /** Fetch episodes by primary key. Order of returned rows is NOT guaranteed. */
    @Query("SELECT * FROM episodes WHERE id IN (:ids)")
    abstract fun byIds(ids: List<Long>): List<EpisodeEntity>

    /** Most recent episodes first. */
    @Query("SELECT * FROM episodes ORDER BY timestamp DESC LIMIT :limit")
    abstract fun recent(limit: Int): List<EpisodeEntity>

    /** Ids only — used by trimToCap to avoid materializing full rows. */
    @Query("SELECT id FROM episodes ORDER BY timestamp DESC LIMIT :capRows")
    abstract fun recentIds(capRows: Int): List<Long>

    @Query("SELECT COUNT(*) FROM episodes")
    abstract fun count(): Long

    /**
     * Storage cap: keep the [capRows] most recent episodes, delete the rest.
     * The AFTER DELETE trigger removes their FTS rows automatically.
     */
    @Query("DELETE FROM episodes WHERE id NOT IN (SELECT id FROM episodes ORDER BY timestamp DESC LIMIT :capRows)")
    abstract fun deleteOldestBeyond(capRows: Int = 200_000): Int

    /**
     * Low-level raw query hook. Prefer [ftsMatch], which builds a safe MATCH expression.
     * Public because Room's generated _Impl must override it; treat as module-internal.
     */
    @RawQuery
    abstract fun ftsMatchRaw(query: SupportSQLiteQuery): List<Long>

    /**
     * FTS5 keyword search over episode content. Returns up to 200 matching rowids.
     *
     * The user string is sanitized into a safe MATCH expression: tokens are lowercased,
     * split on non-alphanumerics and individually double-quoted, joined with OR, so
     * arbitrary user input can never break FTS5 MATCH syntax. Empty/blank queries
     * (no usable tokens) return an empty list without touching SQLite.
     */
    fun ftsMatch(query: String): List<Long> {
        val tokens = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        val matchExpr = tokens.joinToString(" OR ") { "\"$it\"" }
        return ftsMatchRaw(
            SimpleSQLiteQuery(
                "SELECT rowid FROM episodes_fts WHERE episodes_fts MATCH ? LIMIT 200",
                arrayOf(matchExpr)
            )
        )
    }
}

/**
 * Room database "smriti.db" v1 (SPEC section 2.1).
 *
 * FTS5 external-content table + AFTER INSERT / AFTER DELETE / AFTER UPDATE triggers
 * are created in [FTS_CALLBACK.onCreate] (runs AFTER Room creates `episodes`).
 * Standard external-content pattern: UPDATE/DELETE go through the special
 * 'delete' command against the FTS table; INSERT writes (rowid, content) directly.
 */
@Database(entities = [EpisodeEntity::class], version = 1, exportSchema = false)
abstract class SmritiDatabase : RoomDatabase() {

    abstract fun episodeDao(): EpisodeDao

    companion object {
        private const val DB_NAME = "smriti.db"

        /** Exact DDL verified against python3 sqlite3 (FTS5 bundled) — see module notes. */
        internal const val SQL_CREATE_FTS =
            "CREATE VIRTUAL TABLE episodes_fts USING fts5(content, content='episodes', content_rowid='id')"

        internal const val SQL_TRIGGER_AI =
            "CREATE TRIGGER episodes_ai AFTER INSERT ON episodes BEGIN " +
                "INSERT INTO episodes_fts(rowid, content) VALUES (new.id, new.content); " +
                "END"

        internal const val SQL_TRIGGER_AD =
            "CREATE TRIGGER episodes_ad AFTER DELETE ON episodes BEGIN " +
                "INSERT INTO episodes_fts(episodes_fts, rowid, content) VALUES ('delete', old.id, old.content); " +
                "END"

        internal const val SQL_TRIGGER_AU =
            "CREATE TRIGGER episodes_au AFTER UPDATE ON episodes BEGIN " +
                "INSERT INTO episodes_fts(episodes_fts, rowid, content) VALUES ('delete', old.id, old.content); " +
                "INSERT INTO episodes_fts(rowid, content) VALUES (new.id, new.content); " +
                "END"

        private val FTS_CALLBACK = object : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                db.execSQL(SQL_CREATE_FTS)
                db.execSQL(SQL_TRIGGER_AI)
                db.execSQL(SQL_TRIGGER_AD)
                db.execSQL(SQL_TRIGGER_AU)
            }
        }

        @Volatile
        private var INSTANCE: SmritiDatabase? = null

        /** Process-wide singleton (double-checked locking). */
        fun get(context: Context): SmritiDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    SmritiDatabase::class.java,
                    DB_NAME
                )
                    .addCallback(FTS_CALLBACK)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
