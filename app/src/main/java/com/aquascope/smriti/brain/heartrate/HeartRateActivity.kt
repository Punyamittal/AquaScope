package com.aquascope.smriti.brain.heartrate

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.view.PreviewView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.smriti.brain.SmritiBrainTheme
import com.aquascope.ui.SmritiScreenActivity
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class HeartRateActivity : SmritiScreenActivity() {

    private val vm: HeartRateViewModel by viewModels()
    private var camera: HeartRateCameraController? = null
    private var previewView: PreviewView? = null
    private var voice: HeartRateVoiceCoach? = null

    private val cameraPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginMeasurement()
        else {
            vm.onCameraError(getString(R.string.heart_rate_cam_denied))
            voice?.onPhase(MeasurementPhase.ERROR)
            Toast.makeText(this, R.string.heart_rate_cam_denied, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.navy_deep)
        voice = HeartRateVoiceCoach(this)

        lifecycleScope.launch {
            vm.state
                .map { it.phase to it.bpm }
                .distinctUntilChanged()
                .collect { (phase, bpm) ->
                    if (phase == MeasurementPhase.COMPLETE || phase == MeasurementPhase.ERROR) {
                        releaseCameraOnly()
                    }
                    voice?.onPhase(phase, bpm ?: vm.state.value.liveBpm)
                }
        }

        setContent {
            SmritiBrainTheme {
                val state by vm.state.collectAsState()
                HeartRateScreen(
                    state = state,
                    onStart = { requestStart() },
                    onStop = { stopMeasurement(userStop = true) },
                    onClose = {
                        stopMeasurement(userStop = true)
                        finish()
                    },
                    onPreviewReady = { view ->
                        previewView = view
                        camera?.attachPreview(view)
                    }
                )
            }
        }
    }

    private fun releaseCameraOnly() {
        camera?.stop()
        camera = null
    }

    private fun requestStart() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED -> beginMeasurement()
            else -> cameraPerm.launch(Manifest.permission.CAMERA)
        }
    }

    private fun beginMeasurement() {
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) &&
            !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        ) {
            vm.onCameraError("No camera available on this device.")
            voice?.onPhase(MeasurementPhase.ERROR)
            return
        }
        stopMeasurement(userStop = false)
        vm.onMeasuringStarted()
        voice?.speakIntro()
        val controller = HeartRateCameraController(
            context = this,
            onFrame = { frame -> runOnUiThread { vm.onFrame(frame) } },
            onError = { msg ->
                runOnUiThread {
                    stopMeasurement(userStop = false)
                    vm.onCameraError(msg)
                    voice?.onPhase(MeasurementPhase.ERROR)
                }
            }
        )
        camera = controller
        controller.start(this, previewView)
    }

    private fun stopMeasurement(userStop: Boolean) {
        camera?.stop()
        camera = null
        if (userStop) {
            voice?.stop()
            vm.onStopped()
        }
    }

    override fun onStop() {
        stopMeasurement(userStop = true)
        super.onStop()
    }

    override fun onDestroy() {
        stopMeasurement(userStop = false)
        voice?.shutdown()
        voice = null
        super.onDestroy()
    }
}
