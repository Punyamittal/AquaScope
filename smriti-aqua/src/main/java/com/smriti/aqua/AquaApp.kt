package com.smriti.aqua

import android.app.Application
import com.smriti.aqua.memory.EpisodicStore

/**
 * Application entry point. Holds the process-wide [EpisodicStore] singleton;
 * all persistence is local SQLite (smriti.db) — nothing ever leaves the device.
 */
class AquaApp : Application() {
    val store: EpisodicStore by lazy { EpisodicStore(this) }
}
