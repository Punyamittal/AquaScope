package com.smriti.core.play

import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * SMRITI Play — zero-lag gameplay highlight engine (SPEC §2.2).
 *
 * One MediaProjection feeds TWO outputs simultaneously:
 *  (a) a MediaCodec H.264 encoder (1920x1080 @ 60 fps, 10 Mbps, IDR every 1 s) whose
 *      compressed output is written to an in-RAM [CircularByteBuffer] (default 38 MiB ≈
 *      30 s at 10 Mbps) — nothing touches disk while recording;
 *  (b) a 960x540 YUV_420_888 ImageReader sampled at 10 Hz whose luma plane feeds
 *      [KillFeedTrigger]; a positive fires [triggerSave] with reason "kill-feed".
 *
 * [triggerSave] re-muxes the ring from the last IDR into clips/{name}.mp4 via MediaMuxer —
 * see the re-mux correctness note on [triggerSave].
 *
 * ## UI-module contract — foreground service declaration (this class declares NOTHING)
 *
 * On targetSdk 34 MediaProjection MUST run inside a started foreground service of type
 * `mediaProjection`, started BEFORE [start] is called, or getMediaProjection/start throws
 * SecurityException. The UI module owns the manifest and must:
 *
 *  1. Declare permissions `android.permission.FOREGROUND_SERVICE` and
 *     `android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION` (both already listed in
 *     SPEC §3 for the UI-owned manifest).
 *  2. Declare one service (any name, e.g. `com.smriti.core.ui.PlayProjectionService`):
 *     ```xml
 *     <service
 *         android:name=".ui.PlayProjectionService"
 *         android:exported="false"
 *         android:foregroundServiceType="mediaProjection" />
 *     ```
 *  3. Obtain capture consent via `MediaProjectionManager.createScreenCaptureIntent()`
 *     (`rememberLauncherForActivityResult`), then `ContextCompat.startForegroundService()`
 *     and, inside the service, `ServiceCompat.startForeground(id, notification,
 *     ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)`.
 *  4. Only then call `SmritiApp.recorder.start(resultCode, data)` from that foreground
 *     context, and call `recorder.stop()` from the service's onDestroy.
 *
 * No INTERNET permission is needed anywhere in this pipeline.
 */
class ScreenBufferRecorder(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data object Recording : State
        data class Saved(val file: File) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)

    /** Recording lifecycle state: Idle -> Recording -> Saved (still recording) -> Idle. */
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Kill-feed search region, normalized to the frame. Default matches the top-right
     * strip used by most FPS kill-feeds; UI may reassign before start().
     */
    @Volatile
    var killFeedRegion: RectF = DEFAULT_KILL_FEED_REGION

    private val ring = CircularByteBuffer(DEFAULT_CAPACITY_BYTES)

    /** Rebuilt in start() from the current [killFeedRegion]. */
    @Volatile
    private var killFeed = KillFeedTrigger(DEFAULT_KILL_FEED_REGION)

    // All guarded by lock; released idempotently by stop().
    private val lock = Any()
    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var encoderInputSurface: Surface? = null
    private var encoderDisplay: VirtualDisplay? = null
    private var lumaDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var encoderThread: HandlerThread? = null
    private var lumaThread: HandlerThread? = null

    /** Latest output format (carries csd-0/csd-1 SPS/PPS) from INFO_OUTPUT_FORMAT_CHANGED. */
    @Volatile
    private var outputFormat: MediaFormat? = null

    /** Latest raw SPS/PPS payload captured from a BUFFER_FLAG_CODEC_CONFIG output buffer. */
    @Volatile
    private var codecConfig: ByteArray? = null

    /** Serializes MediaMuxer flushes (kill-feed thread vs UI thread). */
    private val muxLock = Any()

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // User revoked capture from the system UI: tear everything down.
            stop()
        }
    }

    /**
     * Starts projection + encoder + luma analysis. Must be called from a foreground
     * context (see class KDoc contract). Returns false on any failure (already recording,
     * no consent, codec/projection error) and leaves the recorder fully released.
     */
    fun start(resultCode: Int, data: Intent): Boolean {
        synchronized(lock) {
            if (projection != null) return false
            try {
                ring.clear()
                killFeed = KillFeedTrigger(killFeedRegion)
                outputFormat = null
                codecConfig = null

                val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                    as MediaProjectionManager
                val proj = mpm.getMediaProjection(resultCode, data)
                proj.registerCallback(projectionCallback, null)
                projection = proj

                // --- Output (a): H.264 encoder pipeline -------------------------------
                val enc = MediaCodec.createEncoderByType(MIME_AVC)
                val format = MediaFormat.createVideoFormat(MIME_AVC, ENC_WIDTH, ENC_HEIGHT).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, ENC_BITRATE_BPS)
                    setInteger(MediaFormat.KEY_FRAME_RATE, ENC_FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, ENC_I_FRAME_INTERVAL_S)
                }
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

                val encThread = HandlerThread("smriti-play-encoder").apply { start() }
                encoderThread = encThread
                enc.setCallback(encoderCallback, Handler(encThread.looper))
                val inputSurface = enc.createInputSurface()
                encoderInputSurface = inputSurface
                enc.start()
                encoder = enc

                val dpi = context.resources.displayMetrics.densityDpi
                encoderDisplay = proj.createVirtualDisplay(
                    "smriti-play-enc",
                    ENC_WIDTH, ENC_HEIGHT, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface, null, null
                )

                // --- Output (b): 10 Hz luma tap for the kill-feed trigger -------------
                val reader = ImageReader.newInstance(
                    LUMA_WIDTH, LUMA_HEIGHT, android.graphics.ImageFormat.YUV_420_888, /* maxImages = */ 2
                )
                imageReader = reader
                val lt = HandlerThread("smriti-play-luma").apply { start() }
                lumaThread = lt
                reader.setOnImageAvailableListener(lumaListener, Handler(lt.looper))
                lumaDisplay = proj.createVirtualDisplay(
                    "smriti-play-luma",
                    LUMA_WIDTH, LUMA_HEIGHT, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, null
                )

                _state.value = State.Recording
                return true
            } catch (t: Throwable) {
                // SecurityException (not foreground), codec/config errors, etc.
                releaseLocked()
                return false
            }
        }
    }

    /**
     * Flushes the RAM ring to an MP4 and returns the file, or null if there is nothing
     * decodable yet (no IDR in the ring, or encoder format not seen yet) or on I/O error.
     * Recording continues uninterrupted; state becomes [State.Saved].
     *
     * ## Re-mux correctness (BufferInfo synthesis)
     *
     * MediaCodec delivers exactly one H.264 access unit per output buffer, so each stored
     * [CircularByteBuffer.Chunk] is one whole frame — chunk boundaries ARE sample
     * boundaries and each chunk is written as one muxer sample with its original byte
     * count as BufferInfo.size.
     *
     * Decodability: the snapshot starts at the most recent chunk flagged
     * BUFFER_FLAG_KEY_FRAME, i.e. an IDR access unit. An H.264 IDR invalidates the
     * decoder's reference picture buffer, so no frame at/after an IDR can reference any
     * frame before it — starting the clip exactly at the IDR therefore yields a closed
     * GOP that decodes standalone. Parameter sets are guaranteed present because the
     * muxer track is added with the encoder's latest output MediaFormat, which carries
     * SPS/PPS as csd-0/csd-1; the cached BUFFER_FLAG_CODEC_CONFIG payload (raw SPS/PPS
     * Annex-B) is additionally written as a leading sample flagged
     * BUFFER_FLAG_CODEC_CONFIG so the parameter sets also appear in-band before the IDR.
     *
     * Timestamps: real capture timing is intentionally discarded (the ring is flushed
     * long after capture). Samples get synthetic, strictly monotonic presentation
     * timestamps starting at 0 and stepping [FRAME_INTERVAL_US] (= 1_000_000/ENC_FPS) per sample —
     * monotonicity is all MediaMuxer requires; wall-clock duration is approximate by
     * design for a highlight clip. Flags: a chunk keeps BUFFER_FLAG_KEY_FRAME iff the
     * encoder marked it so (guaranteed true for the first sample, the IDR), else 0.
     */
    fun triggerSave(reason: String): File? {
        val chunks = ring.snapshotChunksFromLastKeyFrame() ?: return null
        val format = outputFormat ?: return null
        val cfg = codecConfig

        val dir = File(context.filesDir, CLIPS_DIR).apply { mkdirs() }
        val safeReason = reason.replace(Regex("[^A-Za-z0-9_-]"), "-")
        val file = File(dir, "smriti_play_${System.currentTimeMillis()}_$safeReason.mp4")

        synchronized(muxLock) {
            var muxer: MediaMuxer? = null
            try {
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val track = muxer.addTrack(format)
                muxer.start()

                val info = MediaCodec.BufferInfo()
                var sampleIndex = 0L

                // Leading in-band SPS/PPS (codec config also lives in the track format).
                if (cfg != null && cfg.isNotEmpty()) {
                    info.set(0, cfg.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                    muxer.writeSampleData(track, java.nio.ByteBuffer.wrap(cfg), info)
                    sampleIndex++
                }
                for (chunk in chunks) {
                    val flags =
                        if (chunk.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, chunk.bytes.size, sampleIndex * FRAME_INTERVAL_US, flags)
                    muxer.writeSampleData(track, java.nio.ByteBuffer.wrap(chunk.bytes), info)
                    sampleIndex++
                }
                muxer.stop()
                muxer.release()
                muxer = null
            } catch (t: Throwable) {
                try {
                    muxer?.release()
                } catch (_: Throwable) {
                }
                file.delete()
                return null
            }
        }
        _state.value = State.Saved(file)
        return file
    }

    /** Idempotent full teardown: projection, displays, codec, reader, threads. */
    fun stop() {
        synchronized(lock) {
            releaseLocked()
        }
        _state.value = State.Idle
    }

    // ---------------------------------------------------------------- internals

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            try {
                val buf = codec.getOutputBuffer(index)
                if (buf != null && info.size > 0) {
                    val bytes = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    buf.get(bytes)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        codecConfig = bytes // SPS/PPS, cached for in-band prepend on save
                    } else {
                        ring.write(bytes, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0)
                    }
                }
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: IllegalStateException) {
                    // Codec already stopped by stop(); nothing to release.
                }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format // carries csd-0/csd-1 (SPS/PPS) for muxer addTrack
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit
        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) = Unit
    }

    private var lastLumaAnalyzedTs = 0L

    private val lumaListener = ImageReader.OnImageAvailableListener { reader ->
        val image = reader.acquireLatestImage() ?: return@OnImageAvailableListener
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastLumaAnalyzedTs >= LUMA_PERIOD_MS) {
                lastLumaAnalyzedTs = now
                val luma = extractLuma(image)
                if (luma != null &&
                    killFeed.onLumaFrame(luma, image.width, image.height, now)
                ) {
                    triggerSave("kill-feed")
                }
            }
        } finally {
            image.close()
        }
    }

    /** Copies the Y plane into a tightly packed w*h ByteArray honoring row/pixel stride. */
    private fun extractLuma(image: Image): ByteArray? {
        val plane = image.planes.firstOrNull() ?: return null
        val w = image.width
        val h = image.height
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val out = ByteArray(w * h)
        return try {
            if (pixelStride == 1) {
                for (row in 0 until h) {
                    buf.position(row * rowStride)
                    buf.get(out, row * w, w)
                }
            } else {
                for (row in 0 until h) {
                    val rowBase = row * rowStride
                    val outBase = row * w
                    for (col in 0 until w) {
                        out[outBase + col] = buf.get(rowBase + col * pixelStride)
                    }
                }
            }
            out
        } catch (t: Throwable) {
            null
        }
    }

    /** Caller must hold [lock]. Safe to call repeatedly. */
    private fun releaseLocked() {
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Throwable) {
        }
        try {
            encoderDisplay?.release()
        } catch (_: Throwable) {
        }
        try {
            lumaDisplay?.release()
        } catch (_: Throwable) {
        }
        try {
            encoder?.stop()
        } catch (_: Throwable) {
        }
        try {
            encoder?.release()
        } catch (_: Throwable) {
        }
        try {
            encoderInputSurface?.release()
        } catch (_: Throwable) {
        }
        try {
            imageReader?.close()
        } catch (_: Throwable) {
        }
        try {
            projection?.stop()
        } catch (_: Throwable) {
        }
        encoderThread?.quitSafely()
        lumaThread?.quitSafely()

        projection = null
        encoderDisplay = null
        lumaDisplay = null
        encoder = null
        encoderInputSurface = null
        imageReader = null
        encoderThread = null
        lumaThread = null
        outputFormat = null
        codecConfig = null
        lastLumaAnalyzedTs = 0L
        ring.clear()
    }

    companion object {
        const val MIME_AVC = "video/avc"
        const val ENC_WIDTH = 1920
        const val ENC_HEIGHT = 1080
        const val ENC_FPS = 60
        const val ENC_BITRATE_BPS = 10_000_000
        const val ENC_I_FRAME_INTERVAL_S = 1

        const val LUMA_WIDTH = 960
        const val LUMA_HEIGHT = 540
        const val LUMA_PERIOD_MS = 100L // 10 Hz

        const val CLIPS_DIR = "clips"

        /**
         * 30 s at 10 Mbps = 37_500_000 B ≈ 37.5 MB; rounded up to 38 MiB (39_845_888 B)
         * so the full window plus bitrate spikes always fit in the RAM ring.
         */
        const val DEFAULT_CAPACITY_BYTES = 38 * 1024 * 1024

        /** Synthetic sample step used when re-muxing (see triggerSave KDoc). */
        /** Synthetic sample cadence MUST match ENC_FPS or saved clips play back at the wrong speed. */
        const val FRAME_INTERVAL_US = 1_000_000L / ENC_FPS

        /** Default kill-feed search strip: top-right quadrant of the screen. */
        val DEFAULT_KILL_FEED_REGION = RectF(0.70f, 0.02f, 1.00f, 0.40f)
    }
}
