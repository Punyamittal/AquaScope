package com.smriti.brain.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EpisodeEntity::class, EpisodeFts::class],
    version = 1,
    exportSchema = false
)
abstract class SmritiDatabase : RoomDatabase() {
    abstract fun episodes(): EpisodeDao

    companion object {
        fun create(context: Context): SmritiDatabase =
            Room.databaseBuilder(context, SmritiDatabase::class.java, "smriti-brain.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
