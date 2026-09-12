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
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * MediaProjection → MediaCodec ring of the last ~30s of encoded video in RAM.
 * Disk write happens only on kill-feed trigger.
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
    private var imageReader: ImageReader? = null
    private var muxThread: HandlerThread? = null
    private var sampleThread: HandlerThread? = null
    private val gop = ArrayDeque<EncodedUnit>()
    private var lastPtsUs = 0L
    private var width = 720
    private var height = 1280

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
            virtualDisplay?.release()
            imageReader?.close()
            inputSurface?.release()
            encoder?.stop()
            encoder?.release()
            projection?.stop()
        } catch (_: Throwable) {
        }
        virtualDisplay = null
        encoder = null
        projection = null
        muxThread?.quitSafely()
        sampleThread?.quitSafely()
        synchronized(gop) { gop.clear() }
    }

    fun captureNow(reason: String = "manual") = flushClip(reason, 1f)

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
                    while (idx >= 0) {
                        val buf = codec.getOutputBuffer(idx)
                        if (buf != null && info.size > 0) {
                            val copy = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(copy)
                            offer(EncodedUnit(copy, info.presentationTimeUs, info.flags))
                            lastPtsUs = info.presentationTimeUs
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
        val sampleSurface = reader.surface
        try {
            projection?.createVirtualDisplay(
                "smriti-kill-roi",
                width,
                height,
                160,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                sampleSurface,
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
                trigger.inspect(bmp)
                bmp.recycle()
            }
        }, Handler(sampleThread!!.looper))
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

    private fun flushClip(reason: String, score: Float) {
        val snapshot: List<EncodedUnit>
        synchronized(gop) {
            if (gop.isEmpty()) return
            snapshot = gop.toList()
        }
        val out = File(memory.clipsDir(), "kill_${System.currentTimeMillis()}.mp4")
        try {
            val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_WIDTH, width)
                setInteger(MediaFormat.KEY_HEIGHT, height)
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            }
            val track = muxer.addTrack(format)
            muxer.start()
            val info = MediaCodec.BufferInfo()
            snapshot.forEach { unit ->
                info.offset = 0
                info.size = unit.bytes.size
                info.presentationTimeUs = unit.ptsUs - snapshot.first().ptsUs
                info.flags = unit.flags
                muxer.writeSampleData(track, ByteBuffer.wrap(unit.bytes), info)
            }
            muxer.stop()
            muxer.release()
            memory.trimClipStorage()
            onClipSaved(out)
            NeuralCoreMemory.rememberAsync(
                context = app,
                raw = "Screen recording ($reason) score=${"%.2f".format(score)} clip=${out.name}",
                source = "SMRITI_PLAY",
                evidencePath = out.absolutePath,
                kind = TaxonomyParser.Kind.GAME,
                eventType = com.aquascope.smriti.model.EventType.UNKNOWN,
                anomalyScore = 0.0,
                throttleMs = 0L
            )
            actuators.pulseHalo(com.aquascope.halo.SmritiLightState.GAME_KILL, com.aquascope.halo.SmritiLightState.GUARDIAN, 1200)
            actuators.haptic(HardwareActuatorService.HAPTIC_THUD)
        } catch (t: Throwable) {
            Log.w(TAG, "flush failed: ${t.message}", t)
            out.delete()
        }
    }

    data class EncodedUnit(val bytes: ByteArray, val ptsUs: Long, val flags: Int)

    companion object {
        private const val TAG = "SmritiPlay"
        private const val WINDOW_US = 30_000_000L

        fun projectionIntent(activity: Activity): Intent {
            val mgr = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            return mgr.createScreenCaptureIntent()
        }
    }
}
