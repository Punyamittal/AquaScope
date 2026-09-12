package com.aquascope.smriti.brain

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.ConsumerIrManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.aquascope.R
import com.aquascope.halo.MonsterHaloController
import com.aquascope.halo.SmritiLightState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Monster Halo + dual-axis haptics + consumer IR. All local; no network.
 */
class HardwareActuatorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var actuators: HardwareActuators

    override fun onCreate() {
        super.onCreate()
        actuators = HardwareActuators.get(this)
        startForeground(NOTIF, notice("SMRITI actuators ready"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return START_STICKY
        scope.launch {
            when (action) {
                ACTION_STATE -> {
                    val name = intent.getStringExtra(EXTRA_STATE) ?: return@launch
                    val state = runCatching { SmritiLightState.valueOf(name) }.getOrNull() ?: return@launch
                    actuators.setHalo(state)
                }
                ACTION_HAPTIC -> actuators.haptic(
                    intent.getStringExtra(EXTRA_HAPTIC) ?: HAPTIC_TICK
                )
                ACTION_IR -> if (intent.getBooleanExtra(EXTRA_IR_ENABLED, false)) {
                    actuators.transmitAcToggle("service")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notice(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_nav_home)
        .setContentTitle("SMRITI")
        .setContentText(text)
        .setOngoing(true)
        .build()

    companion object {
        const val EXTRA_ACTION = "action"
        const val EXTRA_STATE = "state"
        const val EXTRA_HAPTIC = "haptic"
        const val EXTRA_IR_ENABLED = "ir"
        const val ACTION_STATE = "state"
        const val ACTION_HAPTIC = "haptic"
        const val ACTION_IR = "ir"
        const val HAPTIC_TICK = "tick"
        const val HAPTIC_THUD = "thud"
        const val HAPTIC_CONFIRM = "confirm"
        const val HAPTIC_ALARM = "alarm"
        private const val CHANNEL = "smriti_hw"
        private const val NOTIF = 7101

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "SMRITI hardware", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
}

class HardwareActuators private constructor(context: Context) {
    private val app = context.applicationContext
    private val halo = MonsterHaloController.get(app)
    private val ir = app.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    val irAvailable: Boolean get() = ir?.hasIrEmitter() == true
    val haloHardware: Boolean get() = halo.hardwareAvailable

    fun setHalo(state: SmritiLightState) {
        halo.setState(state)
    }

    fun pulseHalo(state: SmritiLightState, then: SmritiLightState, ms: Long = 900L) {
        halo.setStateBrief(state, then, ms)
    }

    fun haptic(kind: String) {
        val vib = vibrator ?: return
        if (Build.VERSION.SDK_INT < 26) {
            @Suppress("DEPRECATION")
            vib.vibrate(40)
            return
        }
        val effect = when (kind) {
            HardwareActuatorService.HAPTIC_THUD ->
                VibrationEffect.createWaveform(longArrayOf(0, 70, 40, 120), intArrayOf(0, 255, 0, 180), -1)
            HardwareActuatorService.HAPTIC_CONFIRM ->
                VibrationEffect.createWaveform(longArrayOf(0, 35, 70, 35), intArrayOf(0, 160, 0, 160), -1)
            HardwareActuatorService.HAPTIC_ALARM ->
                VibrationEffect.createWaveform(longArrayOf(0, 50, 50, 50, 50, 50), intArrayOf(0, 255, 0, 255, 0, 255), -1)
            else ->
                VibrationEffect.createOneShot(18, 90)
        }
        vib.vibrate(effect)
    }

    fun irCapability(): String {
        val mgr = ir ?: return "No IR emitter on this device"
        if (!mgr.hasIrEmitter()) return "No IR emitter on this device"
        val ranges = runCatching {
            mgr.carrierFrequencies?.joinToString { "${it.minFrequency}-${it.maxFrequency}Hz" }
        }.getOrNull().orEmpty()
        return if (ranges.isBlank()) "IR emitter present (carrier list unavailable)"
        else "IR emitter present · carriers $ranges"
    }

    fun transmitAcToggle(reason: String = "manual"): IrTransmitRecord {
        val mgr = ir ?: return IrTransmitRecord(false, 0, 0, irCapability(), reason)
        if (!mgr.hasIrEmitter()) return IrTransmitRecord(false, 0, 0, irCapability(), reason)
        val carrier = pickCarrierHz(mgr, 38_000)
        return try {
            mgr.transmit(carrier, NEC_POWER_TOGGLE)
            IrTransmitRecord(true, carrier, NEC_POWER_TOGGLE.size, irCapability(), reason)
        } catch (t: Throwable) {
            IrTransmitRecord(false, carrier, NEC_POWER_TOGGLE.size, t.message ?: irCapability(), reason)
        }
    }

    private fun pickCarrierHz(mgr: ConsumerIrManager, preferred: Int): Int {
        val ranges = runCatching { mgr.carrierFrequencies }.getOrNull() ?: return preferred
        if (ranges.any { preferred in it.minFrequency..it.maxFrequency }) return preferred
        val first = ranges.firstOrNull() ?: return preferred
        return ((first.minFrequency + first.maxFrequency) / 2).coerceAtLeast(first.minFrequency)
    }

    companion object {
        // µs mark/space pairs approximating NEC address 0x00 command 0x40 (power).
        private val NEC_POWER_TOGGLE = intArrayOf(
            9000, 4500,
            560, 560, 560, 1690, 560, 560, 560, 560,
            560, 560, 560, 560, 560, 560, 560, 560,
            560, 1690, 560, 560, 560, 560, 560, 560,
            560, 560, 560, 560, 560, 560, 560, 1690,
            560
        )

        @Volatile private var instance: HardwareActuators? = null

        fun get(context: Context): HardwareActuators {
            return instance ?: synchronized(this) {
                instance ?: HardwareActuators(context.applicationContext).also { instance = it }
            }
        }
    }
}
