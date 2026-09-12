package com.aquascope.halo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

interface MonsterHaloDriver {
    val hardwareReachable: Boolean
    fun apply(state: SmritiLightState, brightnessPct: Int): Boolean
    fun playRaw(json: String, brightnessPct: Int = 100): Boolean
    fun stop()
    fun diagnose(): String
}

class VivoMonsterHaloDriver(
    private val packageName: String,
    private val client: VivoLightServiceClient = VivoLightServiceClient()
) : MonsterHaloDriver {

    override val hardwareReachable: Boolean
        get() = client.binderPresent()

    @Volatile private var handleId: Int = -1
    @Volatile private var breathingArmed: Boolean = false

    fun reprobe() {
        breathingArmed = false
        client.forceReprobe()
    }

    fun tryEnableToggle(context: Context): String = client.tryEnableSystemToggle(context)

    override fun apply(state: SmritiLightState, brightnessPct: Int): Boolean {
        if (!hardwareReachable) return false
        return try {
            armBreathing()
            if (state == SmritiLightState.OFF) {
                stop()
                return true
            }
            // hasLight() can be false on iQOO 15 even when the service works — still try.
            val json = HaloEffectJson.forState(state, brightnessPct)
            val id = client.startLightJson(packageName, json)
            handleId = id
            id > 0
        } catch (t: Throwable) {
            Log.w(TAG, "hardware apply failed: ${t.message}", t)
            false
        }
    }

    override fun playRaw(json: String, brightnessPct: Int): Boolean {
        if (!hardwareReachable) return false
        return try {
            armBreathing()
            val id = client.startLightJson(packageName, json)
            handleId = id
            id > 0
        } catch (t: Throwable) {
            Log.w(TAG, "playRaw failed: ${t.message}", t)
            false
        }
    }

    override fun stop() {
        try {
            if (handleId > 0) client.stopById(handleId)
            client.stopAll()
        } catch (_: Throwable) {
        }
        handleId = -1
    }

    override fun diagnose(): String = client.diagnose() + " handle=$handleId armed=$breathingArmed"

    private fun armBreathing() {
        if (breathingArmed) return
        breathingArmed = client.ensureBreathingEnabled()
    }

    companion object {
        private const val TAG = "VivoHaloDriver"
    }
}

class SimulatedHaloDriver : MonsterHaloDriver {
    override val hardwareReachable: Boolean = false
    override fun apply(state: SmritiLightState, brightnessPct: Int): Boolean = false
    override fun playRaw(json: String, brightnessPct: Int): Boolean = false
    override fun stop() = Unit
    override fun diagnose(): String = "simulated (no vivo_light_service)"
}

/**
 * App-facing facade. UI sets semantic state; this layer talks to hardware + sim listeners.
 */
class MonsterHaloController private constructor(context: Context) {

    private val app = context.applicationContext
    val prefs = HaloPreferences(app)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = CopyOnWriteArrayList<(HaloRender) -> Unit>()

    private val vivo = VivoMonsterHaloDriver(app.packageName)

    @Volatile var current: SmritiLightState = SmritiLightState.NORMAL
        private set

    @Volatile var lastRender: HaloRender =
        HaloPalette.render(SmritiLightState.NORMAL, prefs.brightnessScale())
        private set

    @Volatile var lastHardwareOk: Boolean = false
        private set

    @Volatile var lastStatus: String = "idle"
        private set

    private var pendingReturn: Runnable? = null

    val hardwareAvailable: Boolean get() = vivo.hardwareReachable

    fun addListener(listener: (HaloRender) -> Unit) {
        listeners.add(listener)
        listener(lastRender)
    }

    fun removeListener(listener: (HaloRender) -> Unit) {
        listeners.remove(listener)
    }

    fun setState(state: SmritiLightState) {
        pendingReturn?.let { main.removeCallbacks(it) }
        pendingReturn = null
        applyNow(state)
    }

    fun setStateBrief(state: SmritiLightState, then: SmritiLightState, durationMs: Long = 900L) {
        pendingReturn?.let { main.removeCallbacks(it) }
        applyNow(state)
        val r = Runnable { applyNow(then) }
        pendingReturn = r
        main.postDelayed(r, durationMs)
    }

    fun refresh() {
        applyNow(current)
    }

    /** Force a proven solid cyan for 60s — System diagnostics button. */
    fun testSolidCyan(context: Context? = null) {
        pendingReturn?.let { main.removeCallbacks(it) }
        pendingReturn = null
        current = SmritiLightState.NORMAL
        val render = HaloPalette.render(SmritiLightState.NORMAL, 1f)
        lastRender = render
        notifyListeners(render)
        io.execute {
            context?.let { vivo.tryEnableToggle(it) }
            vivo.reprobe()
            val ok = if (prefs.enabled && !prefs.nightMode && vivo.hardwareReachable) {
                vivo.playRaw(HaloEffectJson.testSolid(HaloPalette.CYAN, 100))
            } else false
            lastHardwareOk = ok
            lastStatus = if (ok) {
                "TEST cyan OK · ${vivo.diagnose()}"
            } else {
                "TEST cyan FAILED · ${vivo.diagnose()} · enabled=${prefs.enabled} night=${prefs.nightMode}"
            }
            Log.i(TAG, lastStatus)
            main.post { notifyListeners(lastRender) }
        }
    }

    fun stopPhysical() {
        vivo.stop()
    }

    fun diagnose(): String = vivo.diagnose()

    private fun applyNow(state: SmritiLightState) {
        val effective = when {
            prefs.nightMode || !prefs.enabled -> SmritiLightState.OFF
            else -> state
        }
        current = state
        val scale = prefs.brightnessScale()
        val render = HaloPalette.render(effective, scale)
        lastRender = render
        notifyListeners(render)

        val brightnessPct = (scale * 100).toInt().coerceIn(20, 100)
        io.execute {
            if (!prefs.enabled || prefs.nightMode) {
                vivo.stop()
                lastHardwareOk = false
                lastStatus = "physical off (prefs)"
                return@execute
            }
            if (!vivo.hardwareReachable) {
                lastHardwareOk = false
                lastStatus = "no vivo_light_service — on-screen only"
                return@execute
            }
            val ok = vivo.apply(effective, brightnessPct)
            lastHardwareOk = ok
            lastStatus = if (ok) {
                "HW ${effective.name} ok · ${vivo.diagnose()}"
            } else {
                "HW ${effective.name} failed · ${vivo.diagnose()}"
            }
            Log.i(TAG, lastStatus)
        }
    }

    private fun notifyListeners(render: HaloRender) {
        listeners.forEach { listener ->
            try {
                listener(render)
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "SmritiHalo"
        @Volatile private var instance: MonsterHaloController? = null

        fun get(context: Context): MonsterHaloController {
            return instance ?: synchronized(this) {
                instance ?: MonsterHaloController(context.applicationContext).also { instance = it }
            }
        }
    }
}
