package com.aquascope.smriti.brain

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
import com.aquascope.smriti.brain.peace.PeaceClipEngine
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
    private val trigger = KillFeedTrigger { reason, score, killCount, clipStartMs, clipEndMs ->
        val runFlush = Runnable {
            val result = flushClip(
                reason = reason,
                score = score.coerceAtLeast(0.01f * killCount),
                highlightStartMs = clipStartMs,
                highlightEndMs = clipEndMs
            )
            Log.i(TAG, "auto flush result=$result reason=$reason")
        }
        val mux = muxThread
        if (mux != null) Handler(mux.looper).post(runFlush) else runFlush.run()
    }
    private val running = AtomicBoolean(false)

    private var projection: MediaProjection? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var muxThread: HandlerThread? = null
    private var sampleThread: HandlerThread? = null
    private val gop = ArrayDeque<EncodedUnit>()
    private val stills = ArrayDeque<Bitmap>()
    private var lastStillAtMs = 0L
    private var lastEncodeAtMs = 0L
    private var lastGopLogAtMs = 0L
    private var framesPushed = 0
    private val encodePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val encodeDst = Rect()
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
            Log.e(TAG, "getMediaProjection returned null")
            running.set(false)
            return
        }
        val mux = HandlerThread("smriti-mux").also { it.start() }
        muxThread = mux
        sampleThread = HandlerThread("smriti-killfeed").also { it.start() }
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                running.set(false)
                CaptureProjectionService.notifyStopped()
            }
        }, Handler(mux.looper))
        projection = proj
        loadPeaceTemplates()
        startEncoder()
        if (inputSurface == null) {
            Log.e(TAG, "encoder input surface missing")
            running.set(false)
            return
        }
        // One VirtualDisplay only (vivo/OriginOS often breaks a 2nd VD → empty GOP / no MP4).
        // Mirror → ImageReader → PEACE + Canvas-push into the encoder surface.
        if (!startSingleDisplayPipeline(dpi)) {
            Log.e(TAG, "VirtualDisplay failed — Capture cannot record")
            running.set(false)
            return
        }
        drainEncoder()
        Log.i(TAG, "Capture pipeline up ${width}x$height (single VD)")
    }

    private fun loadPeaceTemplates() {
        runCatching {
            val bitmaps = com.aquascope.smriti.brain.peace.PeaceClipEngine.loadTemplatesFromAssets(app)
            if (bitmaps.isNotEmpty()) {
                trigger.setTemplates(bitmaps)
            }
        }.onFailure { Log.w(TAG, "PEACE templates: ${it.message}") }
    }

    fun stop() {
        running.set(false)
        runCatching { trigger.flushPending() }
        runCatching { trigger.release() }
        try {
            virtualDisplay?.release()
            imageReader?.close()
            inputSurface?.release()
            encoder?.stop()
            encoder?.release()
            projection?.stop()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        imageReader = null
        encoder = null
        inputSurface = null
        projection = null
        muxThread?.quitSafely()
        sampleThread?.quitSafely()
        synchronized(gop) { gop.clear() }
        clearStills()
    }

    fun captureNow(reason: String = "manual"): ClipFlushResult {
        val end = System.currentTimeMillis()
        // Manual / Stop: keep only the recent highlight tail — not the full 30s ring.
        val start = end - MANUAL_HIGHLIGHT_MS
        return flushClip(reason, 1f, highlightStartMs = start, highlightEndMs = end)
    }

    private fun startEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 24)
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
                                offer(
                                    EncodedUnit(
                                        copy,
                                        info.presentationTimeUs,
                                        info.flags,
                                        System.currentTimeMillis()
                                    )
                                )
                                lastPtsUs = info.presentationTimeUs
                            }
                        }
                        codec.releaseOutputBuffer(idx, false)
                        idx = codec.dequeueOutputBuffer(info, 0)
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "drain: ${t.message}")
                }
                val now = System.currentTimeMillis()
                if (now - lastGopLogAtMs > 2_500L) {
                    lastGopLogAtMs = now
                    val n = synchronized(gop) { gop.size }
                    Log.i(TAG, "ring frames=$n pushed=$framesPushed format=${trackFormat != null}")
                }
                if (running.get()) handler.postDelayed(this, 33L)
            }
        })
    }

    /**
     * Single MediaProjection VirtualDisplay → ImageReader.
     * Same frames drive PEACE detection and H.264 ring (via Canvas → encoder Surface).
     */
    private fun startSingleDisplayPipeline(dpi: Int): Boolean {
        val attempts = listOf(
            Triple(width, height, dpi),
            Triple(540, max(960, (540f * height / width).toInt() and 1.inv()), dpi.coerceAtMost(320)),
            Triple(360, max(640, (360f * height / width).toInt() and 1.inv()), 160)
        )
        for ((w, h, d) in attempts) {
            try {
                runCatching { imageReader?.close() }
                runCatching { virtualDisplay?.release() }
                imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
                virtualDisplay = projection?.createVirtualDisplay(
                    "smriti-play",
                    w,
                    h,
                    d,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader!!.surface,
                    null,
                    Handler(sampleThread!!.looper)
                )
                if (virtualDisplay != null) {
                    // Encoder stays at nominal width/height; bitmaps are scaled when drawn.
                    Log.i(TAG, "VirtualDisplay up ${w}x$h dpi=$d")
                    attachReader(imageReader!!)
                    return true
                }
            } catch (t: Throwable) {
                Log.w(TAG, "VD attempt ${w}x$h failed: ${t.message}")
                virtualDisplay = null
            }
        }
        return false
    }

    private fun attachReader(reader: ImageReader) {
        val sampleW = reader.width
        val sampleH = reader.height
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
                val bmp = Bitmap.createBitmap(sampleW, sampleH, Bitmap.Config.ARGB_8888)
                if (pixelStride == 4 && rowStride == sampleW * 4) {
                    buf.rewind()
                    bmp.copyPixelsFromBuffer(buf)
                } else {
                    val row = ByteArray(rowStride)
                    val px = IntArray(sampleW * sampleH)
                    var i = 0
                    for (y in 0 until sampleH) {
                        buf.position(y * rowStride)
                        buf.get(row, 0, minOf(rowStride, row.size))
                        var x = 0
                        while (x < sampleW) {
                            val o = x * pixelStride
                            if (o + 3 >= row.size) break
                            val r = row[o].toInt() and 0xFF
                            val g = row[o + 1].toInt() and 0xFF
                            val b = row[o + 2].toInt() and 0xFF
                            val a = row[o + 3].toInt() and 0xFF
                            px[i++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            x++
                        }
                    }
                    bmp.setPixels(px, 0, sampleW, 0, 0, sampleW, sampleH)
                }
                offerStill(bmp)
                trigger.inspect(bmp)
                pushFrameToEncoder(bmp)
                bmp.recycle()
            }
        }, Handler(sampleThread!!.looper))
    }

    private fun pushFrameToEncoder(frame: Bitmap) {
        val surface = inputSurface ?: return
        val now = System.currentTimeMillis()
        if (now - lastEncodeAtMs < ENCODE_MIN_INTERVAL_MS) return
        lastEncodeAtMs = now
        try {
            val canvas: Canvas = try {
                surface.lockHardwareCanvas()
            } catch (_: Throwable) {
                surface.lockCanvas(null) ?: return
            }
            try {
                encodeDst.set(0, 0, width, height)
                canvas.drawColor(android.graphics.Color.BLACK)
                canvas.drawBitmap(frame, null, encodeDst, encodePaint)
            } finally {
                surface.unlockCanvasAndPost(canvas)
            }
            framesPushed++
        } catch (t: Throwable) {
            Log.w(TAG, "encode push: ${t.message}")
        }
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

    /** Latest still for in-app OCR without stopping Capture. */
    fun exportLatestStill(outFile: File): Boolean {
        val bmp = synchronized(stills) {
            val src = stills.lastOrNull() ?: return false
            try {
                src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
            } catch (_: Throwable) {
                null
            }
        } ?: return false
        return try {
            outFile.parentFile?.mkdirs()
            java.io.FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 92, fos)
            }
            outFile.exists() && outFile.length() > 0L
        } catch (t: Throwable) {
            Log.w(TAG, "export still: ${t.message}")
            false
        } finally {
            if (!bmp.isRecycled) bmp.recycle()
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

    private fun flushClip(
        reason: String,
        score: Float,
        highlightStartMs: Long? = null,
        highlightEndMs: Long? = null
    ): ClipFlushResult {
        val snapshot: List<EncodedUnit>
        synchronized(gop) {
            if (gop.isEmpty()) {
                Log.w(TAG, "flushClip empty gop reason=$reason")
                if (reason != "stop") {
                    ClipSaveNotifier.notifyFailed(
                        app,
                        "Still buffering — keep Capture on a few seconds, then Clip again"
                    )
                }
                return ClipFlushResult.Empty
            }
            val raw = gop.toList()
            val sliced = sliceHighlight(raw, reason, highlightStartMs, highlightEndMs)
            // Mux must start on a keyframe or many devices write a broken/empty file.
            val keyIdx = sliced.indexOfFirst {
                (it.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            }
            snapshot = if (keyIdx >= 0) sliced.drop(keyIdx) else sliced
            if (snapshot.isEmpty()) {
                Log.w(TAG, "flushClip no keyframe units reason=$reason")
                return ClipFlushResult.Empty
            }
            val durMs = ((snapshot.last().ptsUs - snapshot.first().ptsUs) / 1000L).coerceAtLeast(0L)
            Log.i(
                TAG,
                "flushClip slice reason=$reason units=${snapshot.size}/${raw.size} ~${durMs}ms " +
                    "wall=${highlightStartMs}..${highlightEndMs}"
            )
        }
        val format = trackFormat ?: encoder?.outputFormat
        if (format == null) {
            Log.w(TAG, "flushClip no track format reason=$reason")
            return ClipFlushResult.Empty
        }
        val out = File(memory.clipsDir(), "clip_${System.currentTimeMillis()}.mp4")
        Log.i(TAG, "flushClip start reason=$reason units=${snapshot.size} → ${out.name}")
        var muxedOk = false
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
            if (!out.exists() || out.length() < 1024L) {
                Log.w(TAG, "flushClip tiny/missing file len=${out.length()}")
                out.delete()
                return ClipFlushResult.Failed("Clip file too small")
            }
            muxedOk = true
            // Never trim away the clip we just wrote.
            memory.trimClipStorage(keepNewest = out)
            onClipSaved(out)
            Log.i(TAG, "flushClip mux ok bytes=${out.length()} reason=$reason")

            val frames = snapshotStills().ifEmpty { framesFromMp4(out) }
            val peace = runBlocking {
                com.aquascope.smriti.brain.peace.PeaceClipMemory.summarizeAndCategorize(
                    context = app,
                    frames = frames,
                    clipFile = out,
                    triggerReason = reason,
                    score = score
                )
            }
            val thumbFile = runCatching {
                val src = frames.lastOrNull() ?: return@runCatching null
                val f = File(app.cacheDir, "peace_thumb_${System.currentTimeMillis()}.jpg")
                java.io.FileOutputStream(f).use { src.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                f.takeIf { it.exists() && it.length() > 0L }
            }.getOrNull()
            runCatching {
                com.aquascope.smriti.brain.library.ScreenLibraryStore.ingest(
                    ctx = app,
                    ocrText = peace.record.visibleText.ifBlank { peace.askContext },
                    imageFile = thumbFile,
                    mind = peace.record,
                    foreground = com.aquascope.smriti.brain.screenmind.ForegroundAppResolver.current(app),
                    videoFile = out
                )
            }.onFailure { Log.w(TAG, "library ingest: ${it.message}") }
            frames.forEach { runCatching { if (!it.isRecycled) it.recycle() } }
            runCatching { thumbFile?.delete() }

            val askText = buildString {
                append(peace.record.summary)
                if (peace.record.detailedContext.isNotBlank()) {
                    append("\n\n")
                    append(peace.record.detailedContext.take(400))
                }
            }.ifBlank { peace.askContext.ifBlank { peace.record.visibleText } }
            val ocrFile = writeOcrFile(out, askText.ifBlank { peace.askContext })
            val memoryRaw = buildString {
                append(peace.memoryRaw)
                if (ocrFile != null) append("\nOCR file=${ocrFile.name}")
            }
            runCatching {
                runBlocking {
                    NeuralCoreMemory.remember(
                        context = app,
                        raw = memoryRaw,
                        source = "PEACE",
                        evidencePath = out.absolutePath,
                        kind = peace.kind,
                        eventType = com.aquascope.smriti.model.EventType.UNKNOWN,
                        anomalyScore = score.toDouble().coerceIn(0.0, 1.0)
                    )
                }
            }.onFailure { Log.w(TAG, "remember after clip: ${it.message}") }
            NeuralCoreSession.lastScreenOcr = askText
            NeuralCoreSession.lastScreenOcrPath = ocrFile?.absolutePath
            val saved = ClipFlushResult.Saved(
                out.absolutePath,
                reason,
                askText,
                ocrFile?.absolutePath
            )
            NeuralCoreSession.clipSaved.value = saved
            ClipSaveNotifier.notifySaved(app, saved)
            Log.i(
                TAG,
                "PEACE clip memorized gemma=${peace.usedGemma} cat=${peace.record.category} app=${peace.record.appName}"
            )
            runCatching {
                actuators.pulseHalo(
                    com.aquascope.halo.SmritiLightState.GAME_KILL,
                    com.aquascope.halo.SmritiLightState.SCREEN_RECORDING,
                    1200
                )
                actuators.haptic(HardwareActuatorService.HAPTIC_THUD)
            }
            // Also keep a durable copy under app Movies (survives internal cleanup / easier to find).
            runCatching { mirrorToPublicClips(out) }
            return saved
        } catch (t: Throwable) {
            Log.w(TAG, "flush failed: ${t.message}", t)
            // If mux already succeeded, keep the MP4 — only delete broken partials.
            if (!muxedOk) {
                out.delete()
                val failed = ClipFlushResult.Failed(t.message ?: "Could not write clip")
                ClipSaveNotifier.notifyFailed(app, failed.message)
                return failed
            }
            val askText = "Clip video saved (${out.name}) but post-process failed: ${t.message}"
            writeOcrFile(out, askText)
            val saved = ClipFlushResult.Saved(out.absolutePath, reason, askText, null)
            NeuralCoreSession.clipSaved.value = saved
            ClipSaveNotifier.notifySaved(app, saved)
            runCatching { mirrorToPublicClips(out) }
            return saved
        }
    }

    /**
     * Keep only the PEACE highlight window (first kill −5s … last +5s), not the full 30s ring.
     * Manual/Stop fall back to the last [MANUAL_HIGHLIGHT_MS].
     */
    private fun sliceHighlight(
        raw: List<EncodedUnit>,
        reason: String,
        highlightStartMs: Long?,
        highlightEndMs: Long?
    ): List<EncodedUnit> {
        if (raw.isEmpty()) return raw
        val endWall = highlightEndMs ?: raw.last().wallMs
        val startWall = when {
            highlightStartMs != null -> highlightStartMs
            reason.contains("manual", true) || reason.contains("stop", true) ->
                endWall - MANUAL_HIGHLIGHT_MS
            else -> endWall - PeaceClipEngine.PRE_ROLL_MS - PeaceClipEngine.POST_ROLL_MS
        }
        // Pad slightly so keyframe seek still lands near the action.
        val from = startWall - 500L
        val to = endWall + 250L
        val byWall = raw.filter { it.wallMs in from..to }
        val window = when {
            byWall.size >= 8 -> byWall
            else -> {
                // Fallback: last N seconds by PTS if wall clocks are sparse.
                val lastPts = raw.last().ptsUs
                val keepUs = (to - from).coerceIn(4_000L, 20_000L) * 1_000L
                raw.filter { it.ptsUs >= lastPts - keepUs }
            }
        }
        return window.ifEmpty { raw.takeLast((raw.size / 3).coerceAtLeast(8).coerceAtMost(raw.size)) }
    }

    private suspend fun describeScene(clipFile: File, reason: String, score: Float): SceneDescription {
        val frames = snapshotStills().ifEmpty { framesFromMp4(clipFile) }
        val foreground = runCatching {
            com.aquascope.smriti.brain.screenmind.ForegroundAppResolver.current(app)?.label
        }.getOrNull()
        val prefs = runCatching {
            com.aquascope.smriti.brain.screenmind.ScreenMindPreferences(app)
        }.getOrNull()
        val useScreenMind = prefs?.enabled != false
        if (useScreenMind && frames.isNotEmpty()) {
            val toAnalyze = frames.takeLast(3)
            frames.dropLast(toAnalyze.size).forEach { runCatching { it.recycle() } }
            return try {
                var record = com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer.analyzeFrames(
                    app, toAnalyze, reason
                )
                record = com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer.withForegroundHint(
                    record, foreground
                )
                toAnalyze.forEach { runCatching { it.recycle() } }
                toSceneDescription(record, reason, score, clipFile)
            } catch (t: Throwable) {
                Log.w(TAG, "ScreenMind analyze failed: ${t.message}")
                // Keep frames for OCR fallback (do not recycle before fallback).
                fallbackDescribeScene(clipFile, reason, score, toAnalyze, foreground)
            }
        }
        return fallbackDescribeScene(clipFile, reason, score, frames, foreground)
    }

    private fun toSceneDescription(
        record: com.aquascope.smriti.brain.screenmind.ScreenMindRecord,
        reason: String,
        score: Float,
        clipFile: File
    ): SceneDescription {
        val memoryText = buildString {
            append(record.memoryText(reason))
            append(" · score=${"%.2f".format(score)}")
            append(" · file=${clipFile.name}")
            if (record.category == "gaming" || record.appName != "Phone UI") {
                append("\nGame/App opened: ${record.appName}")
            }
        }
        return SceneDescription(
            memoryText = memoryText,
            visibleText = record.visibleText.ifBlank { record.sceneDescription },
            askContext = record.askContext(),
            appName = record.appName,
            category = record.category
        )
    }

    private suspend fun fallbackDescribeScene(
        clipFile: File,
        reason: String,
        score: Float,
        frames: List<Bitmap>,
        foreground: String? = null
    ): SceneDescription {
        var workFrames = frames
        if (workFrames.isEmpty()) {
            workFrames = framesFromMp4(clipFile)
        }
        val useGemma = runCatching {
            SmritiCore.get(app).ollamaPrefs.ocrEnabled
        }.getOrDefault(false)
        val toRead = if (useGemma && workFrames.size > 3) workFrames.takeLast(3) else workFrames
        if (toRead.size < workFrames.size) {
            workFrames.dropLast(toRead.size).forEach { runCatching { it.recycle() } }
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
        var record = com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer.fromOcrOnly(visible, reason)
        record = com.aquascope.smriti.brain.screenmind.ScreenMindAnalyzer.withForegroundHint(
            record, foreground
        )
        return toSceneDescription(record, reason, score, clipFile)
    }

    private fun mirrorToPublicClips(src: File) {
        val dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?: return
        val peace = File(dir, "peace_clips").also { it.mkdirs() }
        val dest = File(peace, src.name)
        src.copyTo(dest, overwrite = true)
        Log.i(TAG, "mirrored clip → ${dest.absolutePath} bytes=${dest.length()}")
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

    data class EncodedUnit(
        val bytes: ByteArray,
        val ptsUs: Long,
        val flags: Int,
        val wallMs: Long = System.currentTimeMillis()
    )
    private data class SceneDescription(
        val memoryText: String,
        val visibleText: String,
        val askContext: String = visibleText,
        val appName: String = "",
        val category: String = ""
    )

    companion object {
        private const val TAG = "SmritiPlay"
        private const val WINDOW_US = 30_000_000L
        private const val STILL_INTERVAL_MS = 1_000L
        private const val ENCODE_MIN_INTERVAL_MS = 42L
        private const val MAX_STILLS = 8
        /** Manual Clip / Stop: keep only this recent highlight, not the full ring. */
        private const val MANUAL_HIGHLIGHT_MS = 12_000L

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
