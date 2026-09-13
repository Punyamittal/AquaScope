package com.aquascope.smriti.brain.heartrate

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.view.ViewGroup
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * CameraX rear-camera + torch acquisition for fingertip PPG.
 * Binds live Preview feed with ImageAnalysis (YUV ROI, no Bitmaps).
 */
class HeartRateCameraController(
    private val context: Context,
    private val onFrame: (FrameAnalysis) -> Unit,
    private val onError: (String) -> Unit
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var analysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var executor: ExecutorService? = null
    private val active = AtomicBoolean(false)
    private val aeLocked = AtomicBoolean(false)
    private var coveredStreak = 0
    private var previewView: PreviewView? = null

    fun attachPreview(view: PreviewView) {
        previewView = view
        preview?.setSurfaceProvider(view.surfaceProvider)
    }

    fun start(lifecycleOwner: LifecycleOwner, previewTarget: PreviewView? = null) {
        if (previewTarget != null) previewView = previewTarget
        if (active.getAndSet(true)) {
            // Already running — just (re)attach preview if needed.
            previewView?.let { preview?.setSurfaceProvider(it.surfaceProvider) }
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                bind(lifecycleOwner, provider)
            } catch (t: Throwable) {
                active.set(false)
                Log.e(TAG, "camera provider failed", t)
                onError("Camera unavailable: ${t.message ?: "unknown error"}")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        active.set(false)
        aeLocked.set(false)
        coveredStreak = 0
        runCatching { camera?.cameraControl?.enableTorch(false) }
        runCatching { cameraProvider?.unbindAll() }
        camera = null
        analysis = null
        preview = null
        executor?.shutdown()
        executor = null
    }

    fun isTorchSupported(): Boolean =
        camera?.cameraInfo?.hasFlashUnit() == true

    @SuppressLint("UnsafeOptInUsageError")
    private fun bind(owner: LifecycleOwner, provider: ProcessCameraProvider) {
        provider.unbindAll()
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        if (!provider.hasCamera(selector)) {
            active.set(false)
            onError("No rear camera available on this device.")
            return
        }

        val exec = Executors.newSingleThreadExecutor()
        executor = exec

        val previewUseCase = Preview.Builder().build().also { preview = it }
        previewView?.let { previewUseCase.setSurfaceProvider(it.surfaceProvider) }

        val builder = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)

        Camera2Interop.Extender(builder)
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )

        val analysisUseCase = builder.build().also { analysis = it }
        analysisUseCase.setAnalyzer(exec) { image ->
            if (!active.get()) {
                image.close()
                return@setAnalyzer
            }
            try {
                val frame = extractRoi(image)
                if (frame.fingerCovered) {
                    coveredStreak++
                    if (coveredStreak > 12 && !aeLocked.get()) {
                        lockExposure()
                    }
                } else {
                    coveredStreak = 0
                }
                onFrame(frame)
            } catch (t: Throwable) {
                Log.w(TAG, "frame analyze: ${t.message}")
            } finally {
                image.close()
            }
        }

        try {
            camera = provider.bindToLifecycle(owner, selector, previewUseCase, analysisUseCase)
            val cam = camera ?: return
            if (!cam.cameraInfo.hasFlashUnit()) {
                stop()
                onError("This device does not support flashlight / torch mode.")
                return
            }
            cam.cameraControl.enableTorch(true)
        } catch (t: Throwable) {
            stop()
            Log.e(TAG, "bind failed", t)
            onError("Could not start camera: ${t.message ?: "bind failed"}")
        }
    }

    private fun lockExposure() {
        val cam = camera ?: return
        aeLocked.set(true)
        runCatching {
            cam.cameraControl.setExposureCompensationIndex(0)
        }
    }

    private fun extractRoi(image: ImageProxy): FrameAnalysis {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuf = yPlane.buffer
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val yRow = yPlane.rowStride
        val yPix = yPlane.pixelStride
        val uRow = uPlane.rowStride
        val uPix = uPlane.pixelStride
        val vRow = vPlane.rowStride
        val vPix = vPlane.pixelStride

        val w = image.width
        val h = image.height
        val side = (min(w, h) * 0.28f).toInt().coerceAtLeast(24)
        val x0 = (w - side) / 2
        val y0 = (h - side) / 2
        val step = (side / 24).coerceAtLeast(2)

        var rSum = 0.0
        var gSum = 0.0
        var bSum = 0.0
        var ySum = 0.0
        var n = 0

        var yy = y0
        while (yy < y0 + side) {
            var xx = x0
            while (xx < x0 + side) {
                val yIndex = yy * yRow + xx * yPix
                val Y = (yBuf.get(yIndex).toInt() and 0xFF)

                val ux = xx / 2
                val uy = yy / 2
                val uIndex = uy * uRow + ux * uPix
                val vIndex = uy * vRow + ux * vPix
                val U = (uBuf.get(uIndex).toInt() and 0xFF) - 128
                val V = (vBuf.get(vIndex).toInt() and 0xFF) - 128

                val r = (Y + 1.402 * V).toFloat().coerceIn(0f, 255f)
                val g = (Y - 0.344136 * U - 0.714136 * V).toFloat().coerceIn(0f, 255f)
                val b = (Y + 1.772 * U).toFloat().coerceIn(0f, 255f)

                rSum += r
                gSum += g
                bSum += b
                ySum += Y
                n++
                xx += step
            }
            yy += step
        }

        val inv = if (n == 0) 0f else 1f / n
        val red = (rSum * inv).toFloat()
        val green = (gSum * inv).toFloat()
        val blue = (bSum * inv).toFloat()
        val brightness = (ySum * inv).toFloat()
        val (covered, score) = FingerDetector.analyze(red, green, blue, brightness)

        return FrameAnalysis(
            timestampNs = image.imageInfo.timestamp.takeIf { it > 0 }
                ?: System.nanoTime(),
            redMean = red,
            greenMean = green,
            blueMean = blue,
            brightness = brightness,
            fingerCovered = covered,
            coverageScore = score
        )
    }

    companion object {
        private const val TAG = "HeartRateCam"

        fun createPreviewView(context: Context): PreviewView =
            PreviewView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
    }
}
