package com.smriti.core

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smriti.core.ui.SmritiRoot
import com.smriti.core.ui.SmritiTheme
import com.smriti.core.ui.SmritiViewModel

/**
 * Single-activity host (SPEC §3).
 *
 * Requests RECORD_AUDIO + POST_NOTIFICATIONS (API 33+) at runtime, starts the
 * ambient guardian once mic access is granted, and renders the Compose tree:
 * `SmritiTheme { SmritiRoot(viewModel()) }`.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true) startGuardian()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            startGuardian()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }

        setContent {
            SmritiTheme {
                SmritiRoot(viewModel())
            }
        }
    }

    /** Mic-gated guardian start; AmbientAudioSensorManager degrades safely without assets. */
    private fun startGuardian() {
        runCatching { (application as SmritiApp).guardian.start() }
    }
}
