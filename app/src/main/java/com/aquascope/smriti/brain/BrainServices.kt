package com.aquascope.smriti.brain

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aquascope.R
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.model.EventType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class CaptureProjectionService : Service() {

    private var wake: PowerManager.WakeLock? = null
    private var recorder: ScreenBufferRecorder? = null

    override fun onCreate() {
        super.onCreate()
        HardwareActuatorService.ensureChannel(this)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_scan)
            .setContentTitle("SMRITI Play")
            .setContentText("Recording last 30s as memory")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF, n)
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smriti:play")
            .apply { acquire(6 * 60 * 60 * 1000L) }
        instance = this
        running.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent
        if (cmd?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (cmd?.action == ACTION_CLIP) {
            recorder?.captureNow("manual")
            return START_STICKY
        }
        val data = projectionData(cmd)
        if (cmd == null || data == null) {
            if (recorder == null) stopSelf()
            return START_NOT_STICKY
        }
        val resultCode = cmd.getIntExtra(EXTRA_RESULT_CODE, 0)
        val w = cmd.getIntExtra(EXTRA_WIDTH, 720)
        val h = cmd.getIntExtra(EXTRA_HEIGHT, 1280)
        val dpi = cmd.getIntExtra(EXTRA_DPI, 420)
        if (recorder == null) {
            val memory = SmritiMemoryEngine.get(this)
            val actuators = HardwareActuators.get(this)
            recorder = ScreenBufferRecorder(this, memory, actuators) {
                NeuralCoreSession.clipTick.value = System.currentTimeMillis()
            }
            recorder?.start(resultCode, data, w, h, dpi)
        }
        running.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        try {
            recorder?.stop()
        } catch (_: Throwable) {
        }
        recorder = null
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (_: Throwable) {
        }
        wake = null
        instance = null
        running.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "smriti_hw"
        private const val NOTIF = 7102
        const val ACTION_STOP = "com.aquascope.PLAY_STOP"
        const val ACTION_CLIP = "com.aquascope.PLAY_CLIP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val EXTRA_WIDTH = "w"
        const val EXTRA_HEIGHT = "h"
        const val EXTRA_DPI = "dpi"

        val running = MutableStateFlow(false)
        @Volatile private var instance: CaptureProjectionService? = null

        fun isRunning(): Boolean = running.value

        fun notifyStopped() {
            instance?.stopSelf()
        }

        fun start(
            context: Context,
            resultCode: Int,
            data: Intent,
            width: Int,
            height: Int,
            dpi: Int
        ) {
            HardwareActuatorService.ensureChannel(context)
            val i = Intent(context, CaptureProjectionService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_WIDTH, width)
                putExtra(EXTRA_HEIGHT, height)
                putExtra(EXTRA_DPI, dpi)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun captureNow(context: Context) {
            val live = instance
            if (live != null) {
                live.recorder?.captureNow("manual")
                return
            }
            context.startService(
                Intent(context, CaptureProjectionService::class.java).setAction(ACTION_CLIP)
            )
        }

        fun stop(context: Context) {
            val i = Intent(context, CaptureProjectionService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(i)
            } catch (_: Throwable) {
            }
            context.stopService(Intent(context, CaptureProjectionService::class.java))
        }

        @Suppress("DEPRECATION")
        private fun projectionData(intent: Intent?): Intent? {
            if (intent == null) return null
            return if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            } else {
                intent.getParcelableExtra(EXTRA_DATA)
            }
        }
    }
}

class GuardianService : Service() {

    private var wake: PowerManager.WakeLock? = null
    private var ambient: AmbientAudioSensorManager? = null
    private lateinit var prefs: NeuralCorePrefs
    private val ledger = GuardianLedger()
    private val pulse = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() {
            val snap = ledger.snapshot()
            snapshotLine.value = snap.summary
            NeuralCoreMemory.rememberAsync(
                context = this@GuardianService,
                raw = "Guardian 10m digest: ${snap.summary}",
                source = "GUARDIAN",
                kind = TaxonomyParser.Kind.ACOUSTIC,
                eventType = EventType.UNKNOWN,
                anomalyScore = 0.0,
                throttleMs = 0L
            )
            pulse.postDelayed(this, HEARTBEAT_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = NeuralCorePrefs(this)
        HardwareActuatorService.ensureChannel(this)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_home)
            .setContentTitle("SMRITI Guardian")
            .setContentText("Listening on-device — stays on until you turn it off")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF, n)
        }
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smriti:guardian")
            .apply { acquire(12 * 60 * 60 * 1000L) }
        startListening()
        running.value = true
        prefs.guardianOn = true
        pulse.postDelayed(heartbeat, HEARTBEAT_MS)
        NeuralCoreMemory.rememberAsync(
            context = this,
            raw = ledger.sessionSummary(started = true),
            source = "GUARDIAN",
            kind = TaxonomyParser.Kind.ACOUSTIC,
            throttleMs = 0L
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.guardianOn = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (ambient == null) startListening()
        running.value = true
        prefs.guardianOn = true
        return START_STICKY
    }

    override fun onDestroy() {
        pulse.removeCallbacks(heartbeat)
        NeuralCoreMemory.rememberAsync(
            context = this,
            raw = ledger.sessionSummary(started = false),
            source = "GUARDIAN",
            kind = TaxonomyParser.Kind.ACOUSTIC,
            throttleMs = 0L
        )
        stopListening()
        try {
            if (wake?.isHeld == true) wake?.release()
        } catch (_: Throwable) {
        }
        wake = null
        running.value = false
        amplitude.value = 0f
        snapshotLine.value = "Guardian off"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startListening() {
        if (ambient != null) return
        val actuators = HardwareActuators.get(this)
        ambient = AmbientAudioSensorManager(
            this,
            onAcoustic = { ev -> onWindow(ev, actuators) },
            onFall = {
                val mag = ambient?.lastAccelMag ?: 0f
                val ev = AcousticEvent("fall", 1f, amplitude.value, 0f, 0f, 0f)
                onWindow(ev, actuators, accelMag = mag)
            },
            onAmplitude = { rms ->
                amplitude.value = rms
                ledger.finishPostIrIfDue()?.let { follow ->
                    NeuralCoreMemory.rememberAsync(
                        context = this,
                        raw = follow,
                        source = "IR",
                        kind = TaxonomyParser.Kind.ACOUSTIC,
                        eventType = EventType.UNKNOWN,
                        throttleMs = 0L
                    ) {
                        NeuralCoreSession.clipTick.value = System.currentTimeMillis()
                    }
                    snapshotLine.value = ledger.snapshot().summary
                }
            }
        )
        ambient?.start()
        actuators.setHalo(SmritiLightState.GUARDIAN)
        snapshotLine.value = ledger.snapshot().summary
        Log.i(TAG, "Guardian listening")
    }

    private fun onWindow(
        ev: AcousticEvent,
        actuators: HardwareActuators,
        accelMag: Float = ambient?.lastAccelMag ?: 9.8f
    ) {
        val window = GuardianWindow(
            rms = ev.rms,
            highFrac = ev.highFrac,
            midFrac = ev.midFrac,
            lowFrac = ev.lowFrac,
            label = ev.label,
            confidence = ev.confidence,
            accelMag = accelMag,
            timestampMs = System.currentTimeMillis()
        )
        ledger.ingest(window)
        snapshotLine.value = ledger.snapshot().summary
        val label = ev.label ?: return
        val emergency = AmbientAudioSensorManager.haloFor(label) == SmritiLightState.EMERGENCY
        NeuralCoreMemory.rememberAsync(
            context = this,
            raw = "Guardian ${label} rms=${"%.3f".format(ev.rms)} " +
                "bands h/m/l=${"%.2f".format(ev.highFrac)}/${"%.2f".format(ev.midFrac)}/${"%.2f".format(ev.lowFrac)} " +
                "vs ${ledger.snapshot().summary}",
            source = "GUARDIAN",
            kind = if (label == "fall") TaxonomyParser.Kind.HEALTH else TaxonomyParser.Kind.ACOUSTIC,
            eventType = if (emergency) EventType.ANOMALY else EventType.ACOUSTIC_DEVIATION,
            anomalyScore = ledger.scoreFor(label, ev.rms),
            throttleMs = if (emergency) 4_000L else 20_000L
        ) {
            NeuralCoreSession.clipTick.value = System.currentTimeMillis()
        }
        actuators.pulseHalo(
            AmbientAudioSensorManager.haloFor(label),
            SmritiLightState.GUARDIAN,
            1600
        )
        if (emergency) actuators.haptic(HardwareActuatorService.HAPTIC_ALARM)
        maybeFireIr(label, actuators)
    }

    private fun maybeFireIr(label: String, actuators: HardwareActuators) {
        val decision = ledger.shouldFireIr(label, NeuralCorePrefs(this).irArmed)
        if (!decision.fire) return
        val tx = actuators.transmitAcToggle(decision.reason)
        ledger.noteIrFired(tx)
        NeuralCoreMemory.rememberAsync(
            context = this,
            raw = if (tx.ok) {
                "IR blast ${tx.carrierHz}Hz ${tx.pulseCount} pulses because ${tx.reason}. ${tx.carrierHint}"
            } else {
                "IR blast skipped/failed (${tx.carrierHint}) for ${tx.reason}"
            },
            source = "IR",
            kind = TaxonomyParser.Kind.ACOUSTIC,
            eventType = EventType.UNKNOWN,
            throttleMs = 0L
        ) {
            NeuralCoreSession.clipTick.value = System.currentTimeMillis()
        }
        snapshotLine.value = ledger.snapshot().summary
    }

    private fun stopListening() {
        ambient?.stop()
        ambient = null
        HardwareActuators.get(this).setHalo(SmritiLightState.NORMAL)
    }

    companion object {
        private const val TAG = "GuardianService"
        private const val CHANNEL = "smriti_hw"
        private const val NOTIF = 7103
        private const val HEARTBEAT_MS = 10 * 60 * 1000L
        const val ACTION_STOP = "com.aquascope.GUARDIAN_STOP"

        val running = MutableStateFlow(false)
        val amplitude = MutableStateFlow(0f)
        val snapshotLine = MutableStateFlow("Guardian off")
        val runningFlow: StateFlow<Boolean> = running

        fun isRunning(): Boolean = running.value

        fun start(context: Context) {
            HardwareActuatorService.ensureChannel(context)
            NeuralCorePrefs(context).guardianOn = true
            val i = Intent(context, GuardianService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            NeuralCorePrefs(context).guardianOn = false
            val i = Intent(context, GuardianService::class.java).setAction(ACTION_STOP)
            try {
                context.startService(i)
            } catch (_: Throwable) {
            }
            context.stopService(Intent(context, GuardianService::class.java))
        }
    }
}

object NeuralCoreSession {
    val clipTick = MutableStateFlow(0L)
}
