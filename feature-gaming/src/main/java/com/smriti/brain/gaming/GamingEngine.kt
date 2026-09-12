package com.smriti.brain.gaming

import android.graphics.Bitmap
import com.smriti.brain.capture.ScreenCaptureSession
import com.smriti.brain.hardware.HaloActuator
import com.smriti.brain.hardware.HaloColor
import com.smriti.brain.hardware.HapticActuator
import java.io.File

class GamingEngine(
    private val haptics: HapticActuator,
    private val halo: HaloActuator
) {
    @Volatile var lastScore: Float = 0f
        private set

    fun considerFrame(
        frame: Bitmap,
        template: Bitmap,
        capture: ScreenCaptureSession,
        threshold: Float = 0.72f
    ): File? {
        if (!KillFeedJni.loaded) return null
        val fg = toGray(frame)
        val tg = toGray(template)
        val roiH = (frame.height * 0.22f).toInt().coerceAtLeast(32)
        val score = KillFeedJni.matchSad(
            fg.data, fg.w, fg.h,
            tg.data, tg.w, tg.h,
            0, 0, frame.width, roiH
        )
        lastScore = score
        if (score < threshold) return null
        val clip = capture.flushClip() ?: return null
        halo.pulse(HaloColor.CRIMSON, strobe = true)
        haptics.thud()
        return clip
    }

    private data class Gray(val data: ByteArray, val w: Int, val h: Int)

    private fun toGray(bmp: Bitmap): Gray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            out[i] = ((r * 30 + g * 59 + b * 11) / 100).toByte()
        }
        return Gray(out, w, h)
    }
}
