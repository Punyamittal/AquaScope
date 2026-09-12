package com.smriti.brain.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.view.Surface
import java.io.File

class ScreenCaptureSession(private val context: Context) {
    private val mgr = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    val buffer = RollingVideoBuffer()

    fun createConsentIntent(): Intent = mgr.createScreenCaptureIntent()

    fun attach(resultCode: Int, data: Intent): Boolean {
        if (resultCode != Activity.RESULT_OK) return false
        projection = mgr.getMediaProjection(resultCode, data)
        buffer.start()
        val surface: Surface = buffer.inputSurface ?: return false
        display = projection?.createVirtualDisplay(
            "smriti-play",
            1280,
            720,
            context.resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            surface,
            null,
            null
        )
        return display != null
    }

    fun tick() = buffer.drain()

    fun flushClip(): File? {
        val dir = File(context.filesDir, "clips").apply { mkdirs() }
        val out = File(dir, "clip_${System.currentTimeMillis()}.mp4")
        return if (buffer.flushTo(out)) out else null
    }

    fun stop() {
        display?.release()
        display = null
        projection?.stop()
        projection = null
        buffer.stop()
    }
}
