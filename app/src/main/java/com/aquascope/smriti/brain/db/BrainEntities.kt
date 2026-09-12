package com.aquascope.smriti.brain.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val kind: String,
    val title: String,
    val body: String,
    val fieldsJson: String,
    val evidencePath: String?,
    val embedding: ByteArray,
    val source: String
)

@Dao
interface EpisodeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EpisodeEntity)

    @Query("SELECT * FROM episodes ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun byId(id: String): EpisodeEntity?

    @Query(
        """
        SELECT * FROM episodes
        WHERE title LIKE '%' || :needle || '%' OR body LIKE '%' || :needle || '%'
        ORDER BY timestampMs DESC
        LIMIT :limit
        """
    )
    suspend fun like(needle: String, limit: Int): List<EpisodeEntity>

    @Query("SELECT COUNT(*) FROM episodes")
    suspend fun count(): Int

    @Query("SELECT * FROM episodes")
    suspend fun all(): List<EpisodeEntity>

    @Query("DELETE FROM episodes WHERE timestampMs < :cutoffMs")
    suspend fun trimOlderThan(cutoffMs: Long): Int
}

@Database(entities = [EpisodeEntity::class], version = 1, exportSchema = false)
abstract class SmritiBrainDatabase : RoomDatabase() {
    abstract fun episodes(): EpisodeDao

    companion object {
        @Volatile private var instance: SmritiBrainDatabase? = null

        fun get(context: Context): SmritiBrainDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    SmritiBrainDatabase::class.java,
                    "smriti_brain.db"
                ).addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        runCatching {
                            db.execSQL(
                                "CREATE VIRTUAL TABLE IF NOT EXISTS episodes_fts USING fts5(id UNINDEXED, title, body)"
                            )
                        }
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        runCatching {
                            db.execSQL(
                                "CREATE VIRTUAL TABLE IF NOT EXISTS episodes_fts USING fts5(id UNINDEXED, title, body)"
                            )
                        }
                    }
                }).fallbackToDestructiveMigration().build().also { instance = it }
            }
        }
    }
}
