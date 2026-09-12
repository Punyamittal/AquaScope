package com.aquascope

import android.app.Application
import android.os.Build
import android.util.Log
import com.aquascope.baseline.UltrasonicModelWeights
import com.aquascope.smriti.brain.OcrBackgroundProcessor
import com.aquascope.smriti.brain.OcrGesturePreferences
import com.aquascope.smriti.brain.OcrSwipeOverlayService
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Unlocks non-SDK ServiceManager access so Monster Halo (`vivo_light_service`)
 * can be reached on OriginOS / Android 9+.
 */
class AquaScopeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        unlockHiddenApis()
        UltrasonicModelWeights.ensureLoaded(this)
        OcrBackgroundProcessor.install(this)
        runCatching { OcrSwipeOverlayService.stop(this) }
        val prefs = OcrGesturePreferences(this)
        if (!prefs.stripRemovedV2) prefs.stripRemovedV2 = true
        // Kill the slow touch-exploration swipe path from a prior build.
        if (!prefs.touchExploreKilledV3) {
            prefs.swipeEnabled = false
            prefs.touchExploreKilledV3 = true
            Log.i(TAG, "Disabled swipe OCR after touch-exploration slowdown")
        }
    }

    companion object {
        private const val TAG = "AquaScopeApp"

        fun unlockHiddenApis() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
                Log.i(TAG, "Hidden API exemptions applied")
            } catch (t: Throwable) {
                Log.w(TAG, "Hidden API bypass failed: ${t.message}")
            }
        }
    }
}
