package com.smriti.brain

import android.app.Application
import android.os.Build
import com.smriti.brain.database.EpisodeStore
import com.smriti.brain.database.SmritiDatabase
import com.smriti.brain.hardware.HaloActuator
import com.smriti.brain.hardware.HapticActuator
import com.smriti.brain.hardware.IrBlaster
import com.smriti.brain.memory.RetrievalEngine
import com.smriti.brain.memory.VoiceLoop
import com.smriti.brain.telemetry.TelemetryMonitor
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.File

class SmritiBrainApp : Application() {
    lateinit var graph: Graph
        private set

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (_: Throwable) {
            }
        }
        graph = Graph(this)
    }

    class Graph(app: Application) {
        val db = SmritiDatabase.create(app)
        val store = EpisodeStore(db)
        val retrieval = RetrievalEngine(store)
        val telemetry = TelemetryMonitor(app)
        val halo = HaloActuator(app.packageName)
        val haptics = HapticActuator(app)
        val ir = IrBlaster(app)
        val voice = VoiceLoop(File(app.filesDir, "models"))
    }
}
