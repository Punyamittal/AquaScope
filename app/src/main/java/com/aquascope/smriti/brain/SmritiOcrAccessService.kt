package com.aquascope.smriti.brain

import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Swipe OCR without slowing the phone.
 *
 * Never enables touch-exploration. When armed: transparent **bottom-edge** band only
 * (TYPE_ACCESSIBILITY_OVERLAY) — rest of the screen is normal.
 * Swipe up on that band (1 or 2 fingers) → screenshot → library.
 */
class SmritiOcrAccessService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastGestureFireElapsed = 0L
    private var edgeView: View? = null
    private var windowManager: WindowManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // Always clear touch-exploration — never leave the phone in that slow mode.
        clearTouchExplorationFlags()
        val prefs = OcrGesturePreferences(this)
        if (!prefs.touchExploreKilledV3) {
            prefs.swipeEnabled = false
            prefs.touchExploreKilledV3 = true
        }
        applyArmedState(prefs.swipeEnabled, announce = true)
        Log.i(TAG, "OCR accessibility connected (no touch-exploration)")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        removeEdgeStrip()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun setSwipeArmed(armed: Boolean) {
        OcrGesturePreferences(this).swipeEnabled = armed
        clearTouchExplorationFlags()
        applyArmedState(armed, announce = true)
    }

    private fun clearTouchExplorationFlags() {
        runCatching {
            val info = serviceInfo ?: return
            val cleared = info.flags and
                AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            if (cleared != info.flags) {
                info.flags = cleared
                serviceInfo = info
                Log.i(TAG, "cleared touch-exploration flags")
            }
        }
    }

    private fun applyArmedState(armed: Boolean, announce: Boolean) {
        clearTouchExplorationFlags()
        mainHandler.post {
            if (armed) attachBottomStrip() else removeBottomStrip()
            if (announce) {
                Toast.makeText(
                    this,
                    if (armed) {
                        "Swipe OCR on — swipe up from the bottom edge (1 or 2 fingers)"
                    } else {
                        "Swipe OCR off"
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun attachBottomStrip() {
        removeBottomStrip()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm
        val density = resources.displayMetrics.density
        // Full-width bottom band so two fingers can both land on it.
        val stripH = (56f * density).toInt().coerceIn(48, 72)
        val gestureInset = (18f * density).toInt()
        val minUp = 56f * density

        val view = object : View(this) {
            private val startX = FloatArray(2)
            private val startY = FloatArray(2)
            private var twoFinger = false
            private var fired = false
            private var oneStartX = 0f
            private var oneStartY = 0f

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        twoFinger = false
                        fired = false
                        oneStartX = event.x
                        oneStartY = event.y
                        startX[0] = event.x
                        startY[0] = event.y
                        Log.d(TAG, "strip DOWN")
                        return true
                    }
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        if (event.pointerCount >= 2) {
                            twoFinger = true
                            fired = false
                            for (i in 0 until minOf(2, event.pointerCount)) {
                                startX[i] = event.getX(i)
                                startY[i] = event.getY(i)
                            }
                            Log.d(TAG, "strip 2-finger down")
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (fired) return true
                        if (twoFinger && event.pointerCount >= 2) {
                            val dy0 = startY[0] - event.getY(0)
                            val dy1 = startY[1] - event.getY(1)
                            val dx0 = abs(event.getX(0) - startX[0])
                            val dx1 = abs(event.getX(1) - startX[1])
                            val avgUp = (dy0 + dy1) / 2f
                            val avgHoriz = (dx0 + dx1) / 2f
                            if (avgUp > minUp && avgUp > avgHoriz * 1.05f) {
                                fired = true
                                fireSwipeCapture("bottom_2finger")
                            }
                        } else if (!twoFinger && event.pointerCount == 1) {
                            val up = oneStartY - event.y
                            val horiz = abs(event.x - oneStartX)
                            // 1-finger swipe up also works — easier to hit on the edge.
                            if (up > minUp && up > horiz * 1.2f) {
                                fired = true
                                fireSwipeCapture("bottom_1finger")
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        twoFinger = false
                        return true
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        if (event.pointerCount - 1 < 2) twoFinger = false
                        return true
                    }
                }
                return true
            }
        }.apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false
            isFocusable = false
        }

        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            stripH,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = gestureInset
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        fun tryAdd(overlayType: Int): Boolean {
            params.type = overlayType
            return runCatching {
                wm.addView(view, params)
                edgeView = view
                true
            }.onFailure {
                Log.e(TAG, "addView type=$overlayType failed: ${it.message}")
            }.getOrDefault(false)
        }

        val ok = tryAdd(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) ||
            (Settings.canDrawOverlays(this) && tryAdd(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY))
        if (ok) {
            Log.i(TAG, "bottom swipe strip attached h=$stripH")
        } else {
            toast("Swipe strip failed — re-toggle Accessibility, or allow Display over other apps")
        }
    }

    private fun removeBottomStrip() {
        val v = edgeView ?: return
        runCatching { windowManager?.removeView(v) }
        edgeView = null
        windowManager = null
    }

    private fun removeEdgeStrip() = removeBottomStrip()

    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        // Soft fallback only — never rely on touch-exploration.
        if (!OcrGesturePreferences(this).swipeEnabled) return false
        if (Build.VERSION.SDK_INT < 30) return false
        if (gestureEvent.gestureId != GESTURE_2_FINGER_SWIPE_UP) return false
        fireSwipeCapture("a11y_gesture")
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onGesture(gestureId: Int): Boolean {
        if (!OcrGesturePreferences(this).swipeEnabled) return false
        if (gestureId != GESTURE_2_FINGER_SWIPE_UP) return false
        fireSwipeCapture("a11y_legacy")
        return true
    }

    private fun fireSwipeCapture(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastGestureFireElapsed < COOLDOWN_MS) return
        lastGestureFireElapsed = now
        Log.i(TAG, "Swipe OCR fire reason=$reason")
        toast("Capturing screen…")
        captureScreenshotForOcr()
    }

    fun captureScreenshotForOcr(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toast("Screenshot OCR needs Android 11+")
            return false
        }
        if (!capturing.compareAndSet(false, true)) {
            Log.i(TAG, "capture already in progress")
            return true
        }
        return try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                Executors.newSingleThreadExecutor(),
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        try {
                            val file = persistScreenshot(screenshot)
                            if (file == null) {
                                toast("Could not save screenshot")
                                return
                            }
                            Log.i(TAG, "Screenshot saved ${file.length()} bytes → ${file.name}")
                            toast("Screenshot OK — running OCR…")
                            OcrIngestHub.offer(file)
                        } finally {
                            capturing.set(false)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        capturing.set(false)
                        Log.w(TAG, "takeScreenshot failed code=$errorCode")
                        toast("Screenshot blocked (code $errorCode). Re-toggle Accessibility.")
                    }
                }
            )
            true
        } catch (t: Throwable) {
            capturing.set(false)
            Log.e(TAG, "takeScreenshot threw", t)
            toast("Screenshot error: ${t.message}")
            false
        }
    }

    private fun persistScreenshot(screenshot: ScreenshotResult): File? {
        val buffer: HardwareBuffer = screenshot.hardwareBuffer
        return try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            val bmp = hw.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            if (hw !== bmp && !hw.isRecycled) hw.recycle()
            val dir = File(filesDir, "ocr_swipe").also { it.mkdirs() }
            val out = File(dir, "swipe_${System.currentTimeMillis()}.png")
            FileOutputStream(out).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 90, fos)
            }
            bmp.recycle()
            out
        } catch (t: Throwable) {
            Log.w(TAG, "persist screenshot: ${t.message}")
            null
        } finally {
            runCatching { buffer.close() }
        }
    }

    private fun toast(msg: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "SmritiOcrAccess"
        private const val COOLDOWN_MS = 1800L
        private val capturing = AtomicBoolean(false)

        @Volatile
        private var instance: SmritiOcrAccessService? = null

        fun isConnected(): Boolean = instance != null

        fun isEnabled(context: Context): Boolean {
            if (instance != null) return true
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ).orEmpty()
            val pkg = context.packageName
            val needle = SmritiOcrAccessService::class.java.name
            if (enabled.contains(needle, ignoreCase = true)) return true
            if (enabled.contains("$pkg/.smriti.brain.SmritiOcrAccessService", true)) return true
            val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
            return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any {
                    val n = it.resolveInfo?.serviceInfo?.name.orEmpty()
                    n == needle || n.endsWith(".SmritiOcrAccessService")
                }
        }

        fun setArmed(context: Context, armed: Boolean) {
            OcrGesturePreferences(context).swipeEnabled = armed
            instance?.setSwipeArmed(armed) ?: run {
                // Service not connected yet — prefs only; strip attaches on connect.
            }
        }

        fun requestCapture(): Boolean {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "Accessibility OCR service not connected")
                return false
            }
            return svc.captureScreenshotForOcr()
        }
    }
}
