package com.aquascope.smriti.brain

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.aquascope.smriti.SmritiCore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * MediaProjection → MediaCodec ring of the last ~30s of encoded video in RAM.
 * On flush: write MP4, OCR recent stills, store readable scene text in memory.
 */
class ScreenBufferRecorder(
    context: Context,
    private val memory: SmritiMemoryEngine,
    private val actuators: HardwareActuators,
    private val onClipSaved: (File) -> Unit = {}
) {
    private val app = context.applicationContext
    private val trigger = KillFeedTrigger { reason, score -> flushClip(reason, score) }
    private val running = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var sampleDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var muxThread: HandlerThread? = null
    private var sampleThread: HandlerThread? = null
    private val gop = ArrayDeque<EncodedUnit>()
    private val stills = ArrayDeque<Bitmap>()
    private var lastStillAtMs = 0L
    @Volatile private var trackFormat: MediaFormat? = null
    private var lastPtsUs = 0L
    private var width = 720
    private var height = 1280

    fun hasBufferedFrames(): Boolean = synchronized(gop) { gop.isNotEmpty() }

    fun start(resultCode: Int, data: Intent, screenWidth: Int, screenHeight: Int, dpi: Int) {
        if (!running.compareAndSet(false, true)) return
        width = 720
        height = max(1280, (720f * screenHeight / screenWidth).toInt() and 1.inv())
        val mgr = app.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mgr.getMediaProjection(resultCode, data)
        if (proj == null) {
            running.set(false)
            return
        }
        val mux = HandlerThread("smriti-mux").also { it.start() }
        muxThread = mux
        sampleThread = HandlerThread("smriti-killfeed").also { it.start() }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                running.set(false)
                CaptureProjectionService.notifyStopped()
            }
        }, Handler(mux.looper))
        projection = proj
        startEncoder()
        val surface = inputSurface
        if (surface == null) {
            running.set(false)
            return
        }
        virtualDisplay = projection?.createVirtualDisplay(
            "smriti-play",
            width,
            height,
            dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            Handler(muxThread!!.looper)
        )
        startSampler()
        drainEncoder()
    }

    fun stop() {
        running.set(false)
        try {
            sampleDisplay?.release()
            virtualDisplay?.release()
            imageReader?.close()
            inputSurface?.release()
            encoder?.stop()
            encoder?.release()
            projection?.stop()
        } catch (_: Throwable) {
        }
        sampleDisplay = null
        virtualDisplay = null
        encoder = null
        projection = null
        muxThread?.quitSafely()
        sampleThread?.quitSafely()
        synchronized(gop) { gop.clear() }
        clearStills()
    }

    fun captureNow(reason: String = "manual"): ClipFlushResult = flushClip(reason, 1f)

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        codec.start()
        encoder = codec
        trackFormat = null
    }

    private fun drainEncoder() {
        val handler = Handler(muxThread!!.looper)
        handler.post(object : Runnable {
            override fun run() {
                if (!running.get()) return
                val codec = encoder ?: return
                val info = MediaCodec.BufferInfo()
                try {
                    var idx = codec.dequeueOutputBuffer(info, 4_000)
                    while (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || idx >= 0) {
                        if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            trackFormat = codec.outputFormat
                            idx = codec.dequeueOutputBuffer(info, 0)
                            continue
                        }
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val copy = ByteArray(info.size)
                                buf.position(info.offset)
                                buf.get(copy)
                                offer(EncodedUnit(copy, info.presentationTimeUs, info.flags))
                                lastPtsUs = info.presentationTimeUs
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        idx = codec.dequeueOutputBuffer(info, 0)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "drain: ${t.message}")
                }
                if (running.get()) handler.postDelayed(this, 33L)
            }
        })
    }

    private fun startSampler() {
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val reader = imageReader!!
        try {
            sampleDisplay = projection?.createVirtualDisplay(
                "smriti-kill-roi",
                width,
                height,
                160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                Handler(sampleThread!!.looper)
            )
        } catch (t: Throwable) {
            Log.w(TAG, "kill-feed sampler unavailable: ${t.message}")
        }
        reader.setOnImageAvailableListener({
            if (!running.get()) return@setOnImageAvailableListener
            val image = try {
                reader.acquireLatestImage()
            } catch (_: Throwable) {
                null
            } ?: return@setOnImageAvailableListener
            image.use { img ->
                val plane = img.planes[0]
                val buf = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                if (pixelStride == 4 && rowStride == width * 4) {
                    buf.rewind()
                    bmp.copyPixelsFromBuffer(buf)
                } else {
                    val row = ByteArray(rowStride)
                    val px = IntArray(width * height)
                    var i = 0
                    for (y in 0 until height) {
                        buf.position(y * rowStride)
                        buf.get(row, 0, rowStride)
                        var x = 0
                        while (x < width) {
                            val o = x * pixelStride
                            val r = row[o].toInt() and 0xFF
                            val g = row[o + 1].toInt() and 0xFF
                            val b = row[o + 2].toInt() and 0xFF
                            val a = row[o + 3].toInt() and 0xFF
                            px[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            x++
                        }
                    }
                    bmp.setPixels(px, 0, width, 0, 0, width, height)
                }
                offerStill(bmp)
                trigger.inspect(bmp)
                bmp.recycle()
            }
        }, Handler(sampleThread!!.looper))
    }

    private fun offerStill(full: Bitmap) {
        val now = System.currentTimeMillis()
        if (now - lastStillAtMs < STILL_INTERVAL_MS) return
        lastStillAtMs = now
        // Keep near-full resolution for ML Kit OCR (half-res was often empty).
        val tw = width.coerceAtMost(1080).coerceAtLeast(320)
        val th = ((height.toFloat() / width) * tw).toInt().coerceAtLeast(320) and 1.inv()
        val scaled = try {
            if (full.width == tw && full.height == th) {
                full.copy(Bitmap.Config.ARGB_8888, false) ?: return
            } else {
                Bitmap.createScaledBitmap(full, tw, th, true)
            }
        } catch (_: Throwable) {
            return
        }
        synchronized(stills) {
            stills.addLast(scaled)
            while (stills.size > MAX_STILLS) {
                stills.removeFirst().recycle()
            }
        }
    }

    private fun snapshotStills(): List<Bitmap> = synchronized(stills) {
        stills.mapNotNull { src ->
            try {
                src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun clearStills() {
        synchronized(stills) {
            stills.forEach { it.recycle() }
            stills.clear()
        }
        lastStillAtMs = 0L
    }

    private fun offer(unit: EncodedUnit) {
        synchronized(gop) {
            gop.addLast(unit)
            val horizon = unit.ptsUs - WINDOW_US
            while (gop.isNotEmpty() && gop.first().ptsUs < horizon) {
                gop.removeFirst()
            }
        }
    }

    private fun flushClip(reason: String, score: Float): ClipFlushResult {
        val snapshot: List<EncodedUnit>
        synchronized(gop) {
            if (gop.isEmpty()) return ClipFlushResult.Empty
            snapshot = gop.toList()
        }
        val format = trackFormat ?: encoder?.outputFormat
        if (format == null) return ClipFlushResult.Empty
        val out = File(memory.clipsDir(), "clip_${System.currentTimeMillis()}.mp4")
        try {
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            val basePts = snapshot.first().ptsUs
            snapshot.forEach { unit ->
                info.offset = 0
                info.size = unit.bytes.size
                info.presentationTimeUs = (unit.ptsUs - basePts).coerceAtLeast(0L)
                info.flags = unit.flags
                muxer.writeSampleData(track, ByteBuffer.wrap(unit.bytes), info)
            }
            muxer.stop()
            muxer.release()
            memory.trimClipStorage()
            onClipSaved(out)

            val scene = runBlocking { describeScene(out, reason, score) }
            val ocrFile = writeOcrFile(out, scene.visibleText)
            val kind = when {
                reason.contains("kill", ignoreCase = true) ||
                    reason.contains("banner", ignoreCase = true) ||
                    reason.contains("template", ignoreCase = true) -> TaxonomyParser.Kind.GAME
                else -> null
            }
            val memoryRaw = if (ocrFile != null) {
                scene.memoryText + "\nOCR file=${ocrFile.name}"
            } else {
                scene.memoryText
            }
            runBlocking {
                NeuralCoreMemory.remember(
                    context = app,
                    raw = memoryRaw,
                    source = "SMRITI_PLAY",
                    evidencePath = out.absolutePath,
                    kind = kind,
                    eventType = com.aquascope.smriti.model.EventType.UNKNOWN,
                    anomalyScore = 0.0
                )
            }
            NeuralCoreSession.lastScreenOcr = scene.visibleText
            NeuralCoreSession.lastScreenOcrPath = ocrFile?.absolutePath
            val saved = ClipFlushResult.Saved(
                out.absolutePath,
                reason,
                scene.visibleText,
                ocrFile?.absolutePath
            )
            NeuralCoreSession.clipSaved.value = saved
            actuators.pulseHalo(
                com.aquascope.halo.SmritiLightState.GAME_KILL,
                com.aquascope.halo.SmritiLightState.SCREEN_RECORDING,
                1200
            )
            actuators.haptic(HardwareActuatorService.HAPTIC_THUD)
            return saved
        } catch (t: Throwable) {
            Log.w(TAG, "flush failed: ${t.message}", t)
            out.delete()
            return ClipFlushResult.Failed(t.message ?: "Could not write clip")
        }
    }

    private suspend fun describeScene(clipFile: File, reason: String, score: Float): SceneDescription {
        val frames = snapshotStills().ifEmpty { framesFromMp4(clipFile) }
        val useGemma = runCatching {
            SmritiCore.get(app).ollamaPrefs.ocrEnabled
        }.getOrDefault(false)
        // Gemma vision is slow — send at most 2 frames; ML Kit can scan more.
        val toRead = if (useGemma && frames.size > 2) frames.takeLast(2) else frames
        if (toRead.size < frames.size) {
            frames.dropLast(toRead.size).forEach { runCatching { it.recycle() } }
        }
        val chunks = ArrayList<String>()
        for (bmp in toRead) {
            try {
                val text = LocalOcr.readBitmap(app, bmp).trim()
                if (text.isNotBlank()) chunks.add(text)
            } catch (t: Throwable) {
                Log.w(TAG, "clip OCR: ${t.message}")
            } finally {
                bmp.recycle()
            }
        }
        val visible = mergeOcr(chunks)
        val memoryText = buildString {
            append("Screen clip ($reason)")
            append(" · score=${"%.2f".format(score)}")
            append(" · file=${clipFile.name}")
            if (visible.isNotBlank()) {
                append("\nSeen on screen:\n")
                append(visible.take(1_500))
            } else {
                append("\nNo readable on-screen text was found in this clip.")
            }
        }
        return SceneDescription(memoryText = memoryText, visibleText = visible)
    }

    private fun writeOcrFile(clipFile: File, visibleText: String): File? {
        if (visibleText.isBlank()) return null
        return try {
            val ocr = File(clipFile.parentFile, clipFile.nameWithoutExtension + ".ocr.txt")
            ocr.writeText(visibleText)
            ocr
        } catch (t: Throwable) {
            Log.w(TAG, "OCR file write failed: ${t.message}")
            null
        }
    }

    private fun framesFromMp4(file: File): List<Bitmap> {
        val out = ArrayList<Bitmap>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(0L)
                ?: 0L
            val stampsUs = if (durMs <= 0L) {
                listOf(0L)
            } else {
                listOf(0L, durMs / 3, (durMs * 2) / 3, (durMs * 9) / 10).map { it * 1_000L }
            }
            for (tUs in stampsUs.distinct()) {
                val frame = retriever.getFrameAtTime(tUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: continue
                val scaled = try {
                    Bitmap.createScaledBitmap(
                        frame,
                        frame.width.coerceAtMost(1080).coerceAtLeast(320),
                        ((frame.height.toFloat() / frame.width) *
                            frame.width.coerceAtMost(1080).coerceAtLeast(320)).toInt()
                            .coerceAtLeast(320) and 1.inv(),
                        true
                    )
                } catch (_: Throwable) {
                    frame
                }
                if (scaled !== frame) frame.recycle()
                out.add(scaled)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "frame extract: ${t.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Throwable) {
            }
        }
        return out
    }

    private fun mergeOcr(chunks: List<String>): String {
        if (chunks.isEmpty()) return ""
        val seen = LinkedHashSet<String>()
        for (chunk in chunks) {
            chunk.lineSequence()
                .map { it.trim() }
                .filter { it.length >= 2 }
                .forEach { line -> seen.add(line) }
        }
        return seen.joinToString("\n").trim()
    }

    data class EncodedUnit(val bytes: ByteArray, val ptsUs: Long, val flags: Int)
    private data class SceneDescription(val memoryText: String, val visibleText: String)

    companion object {
        private const val TAG = "SmritiPlay"
        private const val WINDOW_US = 30_000_000L
        private const val STILL_INTERVAL_MS = 1_000L
        private const val MAX_STILLS = 8

        fun projectionIntent(activity: Activity): Intent {
            val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return mgr.createScreenCaptureIntent()
        }
    }
}

sealed class ClipFlushResult {
    data class Saved(
        val path: String,
        val reason: String,
        val sceneText: String = "",
        val ocrPath: String? = null
    ) : ClipFlushResult()
    data class Failed(val message: String) : ClipFlushResult()
    data object Empty : ClipFlushResult()
    data object NotRecording : ClipFlushResult()
}
