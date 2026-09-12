package com.smriti.core

import android.app.Application
import com.smriti.core.actuators.HardwareActuators
import com.smriti.core.guardian.AmbientAudioSensorManager
import com.smriti.core.memory.SmritiMemoryEngine
import com.smriti.core.play.ScreenBufferRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * SMRITI application singleton hub (SPEC §3).
 *
 * Owns the app-wide singletons that every module shares; property names are
 * contractual — the actuator service binds `(application as SmritiApp).actuators`
 * and the play projection host uses `(application as SmritiApp).recorder`.
 *
 * All singletons are lazy so cold start never touches hardware, the Room db, or
 * native ML libs before first real use.
 */
class SmritiApp : Application() {

    /** App-lifetime scope: SupervisorJob so one failing child never cancels siblings. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** MEM module (SPEC §2.1) — episodic memory engine over Room "smriti.db". */
    val engine: SmritiMemoryEngine by lazy { SmritiMemoryEngine(this) }

    /** ACT module (SPEC §2.3) — Monster Halo RGB, haptics, IR blaster facade. */
    val actuators: HardwareActuators by lazy { HardwareActuators(this) }

    /** PLAY module (SPEC §2.2) — 30 s RAM-ring screen recorder / clip engine. */
    val recorder: ScreenBufferRecorder by lazy { ScreenBufferRecorder(this) }

    /** GRD module (SPEC §2.4) — YAMNet sound guardian + fall detector + Vosk STT. */
    val guardian: AmbientAudioSensorManager by lazy { AmbientAudioSensorManager(this, applicationScope) }

    override fun onCreate() {
        super.onCreate()
        // SPEC §3: enforce the 200k-row episode cap on start, off the main thread.
        applicationScope.launch(Dispatchers.IO) {
            runCatching { engine.trimToCap() }
        }
    }
}
