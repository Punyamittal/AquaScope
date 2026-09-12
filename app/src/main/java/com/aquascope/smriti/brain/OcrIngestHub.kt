package com.aquascope.smriti.brain

import android.content.Context
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Delivers swipe-captured screenshot files into Neural Core OCR,
 * even if Brain UI is not currently foreground.
 */
object OcrIngestHub {
    fun interface Listener {
        fun onOcrImage(file: File)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    var pendingPath: String? = null
        private set

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
        pendingPath?.let { path ->
            val f = File(path)
            if (f.exists()) {
                pendingPath = null
                listener.onOcrImage(f)
            }
        }
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    fun offer(file: File) {
        if (!file.exists() || file.length() == 0L) return
        val copy = listeners.toList()
        if (copy.isEmpty()) {
            pendingPath = file.absolutePath
            return
        }
        pendingPath = null
        copy.forEach { runCatching { it.onOcrImage(file) } }
    }
}

class OcrGesturePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ocr_gesture", Context.MODE_PRIVATE)

    var swipeEnabled: Boolean
        get() = prefs.getBoolean("swipe_enabled", false)
        set(value) = prefs.edit().putBoolean("swipe_enabled", value).apply()

    /** Cleared blue-strip overlay once after upgrade. */
    var stripRemovedV2: Boolean
        get() = prefs.getBoolean("strip_removed_v2", false)
        set(value) = prefs.edit().putBoolean("strip_removed_v2", value).apply()

    /** One-shot: kill touch-exploration path that slowed the phone. */
    var touchExploreKilledV3: Boolean
        get() = prefs.getBoolean("touch_explore_killed_v3", false)
        set(value) = prefs.edit().putBoolean("touch_explore_killed_v3", value).apply()
}
