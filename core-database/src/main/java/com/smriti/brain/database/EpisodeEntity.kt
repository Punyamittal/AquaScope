package com.smriti.brain.database

import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.PrimaryKey

@Entity(tableName = "episodes")
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
    val taxonomy: String,
    val title: String,
    val body: String,
    val source: String,
    val embedding: ByteArray
)

@Fts4(contentEntity = EpisodeEntity::class)
@Entity(tableName = "episodes_fts")
data class EpisodeFts(
    val title: String,
    val body: String,
    val taxonomy: String
)
