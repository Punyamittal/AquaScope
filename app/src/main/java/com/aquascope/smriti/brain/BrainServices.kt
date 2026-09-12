package com.aquascope.smriti.brain

import android.app.PendingIntent
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
        val n = buildRecordingNotification()
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

    private fun buildRecordingNotification(
        statusLine: String = "PEACE watching · tap Clip anytime"
    ): android.app.Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, SmritiBrainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val clip = PendingIntent.getService(
            this, 1,
            Intent(this, CaptureProjectionService::class.java).setAction(ACTION_CLIP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, CaptureProjectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_nav_scan)
            .setContentTitle("SMRITI Capture")
            .setContentText(statusLine)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Clip", clip)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun publishClipResult(result: ClipFlushResult) {
        NeuralCoreSession.clipSaved.value = result
        when (result) {
            is ClipFlushResult.Saved -> {
                NeuralCoreSession.clipTick.value = System.currentTimeMillis()
                ClipSaveNotifier.notifySaved(this, result)
                runCatching {
                    getSystemService(android.app.NotificationManager::class.java)
                        ?.notify(NOTIF, buildRecordingNotification("Last clip saved · still recording"))
                }
            }
            is ClipFlushResult.Failed -> ClipSaveNotifier.notifyFailed(this, result.message)
            ClipFlushResult.Empty -> ClipSaveNotifier.notifyFailed(
                this,
                "Still buffering — keep Capture on a few seconds, then Clip again"
            )
            ClipFlushResult.NotRecording -> { }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent
        if (cmd?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (cmd?.action == ACTION_CLIP) {
            Thread {
                val result = recorder?.captureNow("manual") ?: ClipFlushResult.NotRecording
                publishClipResult(result)
            }.start()
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
            recorder = ScreenBufferRecorder(this, memory, actuators) { file ->
                NeuralCoreSession.clipTick.value = System.currentTimeMillis()
                // Auto PEACE flush already posts ClipFlushResult via NeuralCoreSession in recorder;
                // refresh ongoing shade text.
                runCatching {
                    getSystemService(android.app.NotificationManager::class.java)
                        ?.notify(
                            NOTIF,
                            buildRecordingNotification("PEACE clipped ${file.name} · still on")
                        )
                }
            }
            recorder?.start(resultCode, data, w, h, dpi)
            actuators.setHalo(SmritiLightState.SCREEN_RECORDING)
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
        HardwareActuators.get(this).setHalo(
            if (GuardianService.isRunning()) SmritiLightState.VOICE_RECORDING
            else SmritiLightState.NORMAL
        )
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

        fun captureNow(context: Context, reason: String = "manual"): ClipFlushResult {
            val live = instance
            val recorder = live?.recorder
            if (live == null || recorder == null) {
                return ClipFlushResult.NotRecording
            }
            return recorder.captureNow(reason)
        }

        fun hasBufferedFrames(): Boolean = instance?.recorder?.hasBufferedFrames() == true

        /** PNG of the latest Capture still, or null if Capture is off / no frame yet. */
        fun exportLatestStill(context: Context): java.io.File? {
            val recorder = instance?.recorder ?: return null
            val dir = java.io.File(context.cacheDir, "ocr_capture").also { it.mkdirs() }
            val out = java.io.File(dir, "capture_${System.currentTimeMillis()}.png")
            return if (recorder.exportLatestStill(out)) out else null
        }

        /**
         * OCR + save the rolling buffer, then tear down projection.
         * Call from a background thread — flush runs OCR synchronously.
         */
        fun flushAndStop(context: Context): ClipFlushResult {
            val result = if (hasBufferedFrames()) {
                captureNow(context, reason = "stop")
            } else {
                ClipFlushResult.Empty
            }
            stop(context)
            return result
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
            val spoken = GuardianSense.spokenSnapshot(snap, guardianOn = true, irArmed = prefs.irArmed)
            snapshotLine.value = spoken
            NeuralCoreMemory.rememberAsync(
                context = this@GuardianService,
                raw = "Guardian 10m digest: $spoken",
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
        snapshotLine.value = GuardianSense.spokenSnapshot(
            ledger.snapshot(),
            guardianOn = true,
            irArmed = prefs.irArmed
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
                    val spoken = GuardianSense.followUpNarrative(follow)
                    NeuralCoreMemory.rememberAsync(
                        context = this,
                        raw = spoken,
                        source = "IR",
                        kind = TaxonomyParser.Kind.ACOUSTIC,
                        eventType = EventType.UNKNOWN,
                        throttleMs = 0L
                    ) {
                        NeuralCoreSession.clipTick.value = System.currentTimeMillis()
                    }
                    snapshotLine.value = GuardianSense.spokenSnapshot(
                        ledger.snapshot(),
                        guardianOn = true,
                        irArmed = prefs.irArmed
                    )
                }
            }
        )
        ambient?.start()
        actuators.setHalo(
            if (CaptureProjectionService.isRunning()) SmritiLightState.SCREEN_RECORDING
            else SmritiLightState.VOICE_RECORDING
        )
        snapshotLine.value = GuardianSense.spokenSnapshot(
            ledger.snapshot(),
            guardianOn = true,
            irArmed = prefs.irArmed
        )
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
        snapshotLine.value = GuardianSense.spokenSnapshot(
            ledger.snapshot(),
            guardianOn = true,
            irArmed = prefs.irArmed
        )
        val label = ev.label ?: return
        val emergency = AmbientAudioSensorManager.haloFor(label) == SmritiLightState.EMERGENCY
        val irDecision = ledger.shouldFireIr(label, prefs.irArmed)
        val narrative = GuardianSense.eventNarrative(
            label = label,
            rms = ev.rms,
            highFrac = ev.highFrac,
            midFrac = ev.midFrac,
            lowFrac = ev.lowFrac,
            snap = ledger.snapshot(),
            irArmed = prefs.irArmed,
            irDecision = irDecision
        )
        NeuralCoreMemory.rememberAsync(
            context = this,
            raw = narrative,
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
            if (CaptureProjectionService.isRunning()) SmritiLightState.SCREEN_RECORDING
            else SmritiLightState.VOICE_RECORDING,
            1600
        )
        if (emergency) actuators.haptic(HardwareActuatorService.HAPTIC_ALARM)
        maybeFireIr(actuators, irDecision)
    }

    private fun maybeFireIr(actuators: HardwareActuators, decision: IrDecision) {
        if (!decision.fire) return
        val tx = actuators.transmitAcToggle(decision.reason)
        ledger.noteIrFired(tx)
        NeuralCoreMemory.rememberAsync(
            context = this,
            raw = GuardianSense.irOutcomeNarrative(tx),
            source = "IR",
            kind = TaxonomyParser.Kind.ACOUSTIC,
            eventType = EventType.UNKNOWN,
            throttleMs = 0L
        ) {
            NeuralCoreSession.clipTick.value = System.currentTimeMillis()
        }
        snapshotLine.value = GuardianSense.spokenSnapshot(
            ledger.snapshot(),
            guardianOn = true,
            irArmed = prefs.irArmed
        )
    }

    private fun stopListening() {
        ambient?.stop()
        ambient = null
        HardwareActuators.get(this).setHalo(
            if (CaptureProjectionService.isRunning()) SmritiLightState.SCREEN_RECORDING
            else SmritiLightState.NORMAL
        )
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
    val clipSaved = MutableStateFlow<ClipFlushResult?>(null)
    /** Latest OCR text from a saved/stopped screen recording (for Ask / Qwen). */
    @Volatile var lastScreenOcr: String = ""
    @Volatile var lastScreenOcrPath: String? = null
}
