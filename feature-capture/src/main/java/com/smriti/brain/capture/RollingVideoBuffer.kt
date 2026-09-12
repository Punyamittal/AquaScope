package com.smriti.brain.capture

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.util.ArrayDeque

/**
 * 30s / 60fps H.264 GOP ring in RAM. Encoded access units are kept until flush to UFS.
 */
class RollingVideoBuffer(
    private val width: Int = 1280,
    private val height: Int = 720,
    private val fps: Int = 60,
    private val windowSec: Int = 30
) {
    data class Au(val data: ByteArray, val flags: Int, val ptsUs: Long)

    private val maxFrames = fps * windowSec
    private val ring = ArrayDeque<Au>(maxFrames + 8)
    private var encoder: MediaCodec? = null
    var inputSurface: Surface? = null
        private set
    private val lock = Any()
    @Volatile var running = false
        private set

    fun start() {
        if (running) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 8_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
        running = true
    }

    fun drain() {
        val codec = encoder ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val ix = codec.dequeueOutputBuffer(info, 0)
            if (ix < 0) break
            val buf = codec.getOutputBuffer(ix)
            if (buf != null && info.size > 0) {
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                val copy = ByteArray(info.size)
                buf.get(copy)
                synchronized(lock) {
                    ring.addLast(Au(copy, info.flags, info.presentationTimeUs))
                    while (ring.size > maxFrames) ring.removeFirst()
                }
            }
            codec.releaseOutputBuffer(ix, false)
        }
    }

    fun flushTo(file: File): Boolean {
        drain()
        val frames: List<Au>
        synchronized(lock) {
            if (ring.isEmpty()) return false
            frames = ring.toList()
        }
        val mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        return try {
            val fmt = encoder?.outputFormat ?: MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC, width, height
            )
            val track = mux.addTrack(fmt)
            mux.start()
            val info = MediaCodec.BufferInfo()
            for (au in frames) {
                info.offset = 0
                info.size = au.data.size
                info.flags = au.flags
                info.presentationTimeUs = au.ptsUs
                mux.writeSampleData(track, ByteBuffer.wrap(au.data), info)
            }
            true
        } catch (_: Throwable) {
            false
        } finally {
            try {
                mux.stop()
            } catch (_: Throwable) {
            }
            mux.release()
        }
    }

    fun stop() {
        running = false
        try {
            encoder?.stop()
        } catch (_: Throwable) {
        }
        encoder?.release()
        encoder = null
        inputSurface?.release()
        inputSurface = null
        synchronized(lock) { ring.clear() }
    }
}
