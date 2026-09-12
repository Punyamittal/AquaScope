package com.smriti.core.guardian

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import org.tensorflow.lite.support.common.FileUtil

/** Guardian alert contract — SPEC §2.4. Consumed by SmritiViewModel (UI owns callers). */
sealed interface GuardianAlert {
    data class Sound(val label: String, val confidence: Float, val timestamp: Long) : GuardianAlert
    data class Fall(val timestamp: Long) : GuardianAlert
    data class Speech(val text: String, val timestamp: Long) : GuardianAlert
}

/**
 * AmbientAudioSensorManager — SPEC §2.4.
 *
 * Two independent pipelines, both fully offline:
 *
 * 1. Fall detection: SensorEventListener on TYPE_ACCELEROMETER (SENSOR_DELAY_GAME) feeding
 *    [FallDetector]; a confirmed sequence emits [GuardianAlert.Fall].
 *
 * 2. Ambient audio classification: background coroutine (Dispatchers.IO) reading 0.975 s
 *    windows ([WINDOW_SAMPLES] = 15600 samples @ 16 kHz mono PCM float) from AudioRecord
 *    (VOICE_RECOGNITION source, falls back to MIC) and running YAMNet
 *    (assets/yamnet.tflite, loaded via FileUtil.loadMappedFile) with the NNAPI delegate
 *    (try/catch fallback to CPU, 4 threads). Output is [521] class scores; the top class is
 *    mapped to a label from assets/yamnet_labels.csv if present (line index = class index;
 *    a 3-column "index,mid,display_name" CSV with header is also tolerated), else a built-in
 *    map ([BUILTIN_LABELS]). A [GuardianAlert.Sound] is emitted when the top confidence
 *    exceeds [CONF_THRESHOLD] (0.45) AND the label is in [TARGETS].
 *
 * Graceful degradation: if assets/yamnet.tflite is missing, classification is disabled
 * (logged, mic loop exits) and fall detection keeps working. Nothing here ever throws
 * across the public API.
 *
 * RECORD_AUDIO is a runtime permission requested by MainActivity; the mic path simply
 * fails-and-exits gracefully if it was denied, hence @SuppressLint("MissingPermission").
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO is requested at runtime by MainActivity.
class AmbientAudioSensorManager(
    private val context: Context,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "Guardian"
        const val SAMPLE_RATE = 16000
        /** YAMNet window: 0.975 s * 16 kHz. */
        const val WINDOW_SAMPLES = 15600
        /** Minimum top-class confidence for a Sound alert. */
        const val CONF_THRESHOLD = 0.45f
        const val MODEL_ASSET = "yamnet.tflite"
        const val LABELS_ASSET = "yamnet_labels.csv"
        const val NUM_CLASSES = 521

        /**
         * Alert-worthy YAMNet display names (lowercase, matched exactly against the parsed
         * label). Tunable: smoke alarm, glass breaking/shatter, cough, dog bark, baby cry,
         * running water/tap, knock, generic alarm/buzzer.
         */
        val TARGETS: Set<String> = setOf(
            "smoke detector, smoke alarm",
            "fire alarm",
            "alarm",
            "alarm clock",
            "siren",
            "buzzer",
            "shatter",
            "glass",
            "smash, crash",
            "breaking",
            "cough",
            "bark",
            "baby cry, infant cry",
            "water tap, faucet",
            "sink (filling or washing)",
            "knock"
        )

        /**
         * Fallback label map (real YAMNet/AudioSet class indices, best-effort — TUNABLE).
         * Used only when assets/yamnet_labels.csv is absent. Indices verified against the
         * published yamnet_class_map.csv: 20 Baby cry, 42 Cough, 70 Bark, 282 Water,
         * 286 Stream, 353 Knock, 364 Water tap/faucet, 365 Sink, 366 Bathtub, 382 Alarm,
         * 389 Alarm clock, 390 Siren, 392 Buzzer, 393 Smoke detector/smoke alarm,
         * 394 Fire alarm, 435 Glass, 437 Shatter, 443 Pour, 463 Smash/crash, 464 Breaking.
         */
        val BUILTIN_LABELS: Map<Int, String> = mapOf(
            0 to "Speech",
            20 to "Baby cry, infant cry",
            42 to "Cough",
            70 to "Bark",
            282 to "Water",
            286 to "Stream",
            287 to "Waterfall",
            353 to "Knock",
            354 to "Tap",
            364 to "Water tap, faucet",
            365 to "Sink (filling or washing)",
            366 to "Bathtub (filling or washing)",
            382 to "Alarm",
            389 to "Alarm clock",
            390 to "Siren",
            392 to "Buzzer",
            393 to "Smoke detector, smoke alarm",
            394 to "Fire alarm",
            435 to "Glass",
            437 to "Shatter",
            443 to "Pour",
            463 to "Smash, crash",
            464 to "Breaking"
        )
    }

    private val _alerts = MutableSharedFlow<GuardianAlert>(
        extraBufferCapacity = 16,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val alerts: SharedFlow<GuardianAlert> = _alerts

    private val fallDetector = FallDetector()
    private var audioJob: Job? = null
    private var sensorListener: SensorEventListener? = null
    @Volatile private var running = false

    /** Start accelerometer fall detection + (if the model asset exists) YAMNet mic loop. */
    fun start() {
        if (running) return
        running = true
        startFallDetection()
        startAudioPipeline()
    }

    /** Stop everything; safe to call twice. */
    fun stop() {
        running = false
        sensorListener?.let { listener ->
            try {
                (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                    ?.unregisterListener(listener)
            } catch (t: Throwable) {
                Log.w(TAG, "sensor unregister failed", t)
            }
        }
        sensorListener = null
        audioJob?.cancel()
        audioJob = null
    }

    // ------------------------------------------------------------------ fall detection

    private fun startFallDetection() {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: run {
            Log.w(TAG, "no SensorManager; fall detection disabled")
            return
        }
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            Log.w(TAG, "no accelerometer; fall detection disabled")
            return
        }
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // SensorEvent.timestamp is nanoseconds on a monotonic clock -> ms for FallDetector.
                val tsMs = event.timestamp / 1_000_000L
                if (fallDetector.onAccel(event.values[0], event.values[1], event.values[2], tsMs)) {
                    fallDetector.reset()
                    _alerts.tryEmit(GuardianAlert.Fall(System.currentTimeMillis()))
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorListener = listener
        sm.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
    }

    // ------------------------------------------------------------- audio classification

    private fun startAudioPipeline() {
        if (!assetExists(MODEL_ASSET)) {
            Log.w(TAG, "$MODEL_ASSET missing from assets; ambient audio classification disabled")
            return
        }
        val labels = loadLabels()
        audioJob = scope.launch(Dispatchers.IO) {
            var recorder: AudioRecord? = null
            var interpreter: Interpreter? = null
            var nnapi: NnApiDelegate? = null
            try {
                interpreter = buildInterpreter().also { pair ->
                    nnapi = pair.second
                }.first
                recorder = buildRecorder()
                recorder.startRecording()
                Log.i(TAG, "YAMNet loop started (nnapi=${nnapi != null})")

                val window = FloatArray(WINDOW_SAMPLES)
                val scores = Array(1) { FloatArray(NUM_CLASSES) }
                while (isActive && running) {
                    // Fill one 0.975 s window (blocking reads, may arrive in chunks).
                    var off = 0
                    while (off < WINDOW_SAMPLES && isActive && running) {
                        val n = recorder.read(
                            window, off, WINDOW_SAMPLES - off, AudioRecord.READ_BLOCKING
                        )
                        if (n <= 0) break // ERROR_* or interrupted; re-try outer loop
                        off += n
                    }
                    if (off < WINDOW_SAMPLES) continue

                    scores[0].fill(0f)
                    interpreter.run(window, scores)

                    var topIdx = 0
                    var topConf = scores[0][0]
                    for (i in 1 until NUM_CLASSES) {
                        if (scores[0][i] > topConf) {
                            topConf = scores[0][i]
                            topIdx = i
                        }
                    }
                    val label = labelFor(topIdx, labels)
                    if (topConf > CONF_THRESHOLD && label != null && label.lowercase() in TARGETS) {
                        _alerts.tryEmit(
                            GuardianAlert.Sound(label, topConf, System.currentTimeMillis())
                        )
                    }
                }
            } catch (t: Throwable) {
                // Permission denied, no mic, model load failure — degrade, never crash.
                Log.w(TAG, "audio pipeline stopped: ${t.message}", t)
            } finally {
                try {
                    recorder?.stop()
                } catch (t: Throwable) {
                    Log.w(TAG, "recorder stop failed", t)
                }
                recorder?.release()
                interpreter?.close()
                nnapi?.close()
            }
        }
    }

    /** NNAPI delegate preferred; any failure falls back to 4-thread CPU. */
    private fun buildInterpreter(): Pair<Interpreter, NnApiDelegate?> {
        val model = FileUtil.loadMappedFile(context, MODEL_ASSET)
        return try {
            val delegate = NnApiDelegate()
            val opts = Interpreter.Options().apply { addDelegate(delegate) }
            Interpreter(model, opts) to delegate
        } catch (t: Throwable) {
            Log.w(TAG, "NNAPI unavailable, using CPU x4", t)
            Interpreter(model, Interpreter.Options().apply { setNumThreads(4) }) to null
        }
    }

    /** 16 kHz mono PCM float; VOICE_RECOGNITION source, falls back to MIC. */
    private fun buildRecorder(): AudioRecord {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufBytes = maxOf(minBuf, WINDOW_SAMPLES * 4)
        return try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufBytes
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VOICE_RECOGNITION source failed, using MIC", t)
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufBytes
            )
        }
    }

    /**
     * Labels from assets/yamnet_labels.csv if present: line index = class index.
     * Tolerates the official 3-column format ("index,mid,display_name" + header) by taking
     * the last comma-separated field and skipping a header line. Returns null if absent.
     */
    private fun loadLabels(): List<String>? {
        if (!assetExists(LABELS_ASSET)) {
            Log.i(TAG, "$LABELS_ASSET absent; using built-in label map (${BUILTIN_LABELS.size} classes)")
            return null
        }
        return try {
            context.assets.open(LABELS_ASSET).bufferedReader().use { reader ->
                reader.readLines()
                    .filter { it.isNotBlank() }
                    .map { line -> line.substringAfterLast(',').trim().removeSurrounding("\"") }
                    .let { lines ->
                        if (lines.firstOrNull()?.equals("display_name", ignoreCase = true) == true)
                            lines.drop(1)
                        else lines
                    }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to parse $LABELS_ASSET; using built-in label map", t)
            null
        }
    }

    private fun labelFor(index: Int, labels: List<String>?): String? =
        if (labels != null) labels.getOrNull(index) else BUILTIN_LABELS[index]

    private fun assetExists(name: String): Boolean = try {
        context.assets.open(name).use { true }
    } catch (t: Throwable) {
        false
    }
}
