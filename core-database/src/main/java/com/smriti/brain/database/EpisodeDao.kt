package com.smriti.brain.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface EpisodeDao {
    @Insert
    suspend fun insert(entity: EpisodeEntity): Long

    @Query("SELECT * FROM episodes ORDER BY createdAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<EpisodeEntity>

    @Query(
        """
        SELECT * FROM episodes
        WHERE createdAt BETWEEN :startMs AND :endMs
        ORDER BY createdAt ASC
        """
    )
    suspend fun inRange(startMs: Long, endMs: Long): List<EpisodeEntity>

    @Query(
        """
        SELECT episodes.* FROM episodes
        JOIN episodes_fts ON episodes.id = episodes_fts.rowid
        WHERE episodes_fts MATCH :query
        ORDER BY episodes.createdAt DESC
        LIMIT :limit
        """
    )
    suspend fun fts(query: String, limit: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE taxonomy = :taxonomy ORDER BY createdAt DESC LIMIT :limit")
    suspend fun byTaxonomy(taxonomy: String, limit: Int): List<EpisodeEntity>
}
