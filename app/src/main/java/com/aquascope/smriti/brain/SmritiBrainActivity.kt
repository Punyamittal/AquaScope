package com.aquascope.smriti.brain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognizerIntent
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import com.aquascope.R
import com.aquascope.databinding.ActivitySmritiBrainBinding
import com.aquascope.ui.SmritiNav
import com.aquascope.ui.SmritiScreenActivity

class SmritiBrainActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivitySmritiBrainBinding
    private val vm: BrainViewModel by viewModels()

    private val micPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) vm.toggleGuardian(true) }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val ocrPick = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { vm.ingestUri(it) } }

    private val projection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult
        val metrics = resources.displayMetrics
        vm.armPlay(result.resultCode, result.data!!, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    private val voice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            vm.setQuery(spoken)
            vm.recall()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivitySmritiBrainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            window.statusBarColor = getColor(R.color.navy_deep)
            SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_NONE)
            runCatching { HardwareActuatorService.ensureChannel(this) }
            binding.composeBrain.setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
            )
            binding.composeBrain.setContent {
                SmritiBrainTheme {
                    val state by vm.state.collectAsState()
                    SmritiTelemetryUI(
                        state = state,
                        onQuery = vm::setQuery,
                        onRecall = {
                            // Send typed query into on-device memory recall
                            vm.recall()
                        },
                        onVoiceRecall = {
                            vm.setListening()
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(
                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                )
                                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask SMRITI")
                            }
                            try {
                                voice.launch(intent)
                            } catch (_: Exception) {
                                Toast.makeText(
                                    this@SmritiBrainActivity,
                                    "Voice unavailable — type and tap Send",
                                    Toast.LENGTH_SHORT
                                ).show()
                                vm.recall()
                            }
                        },
                        onGuardian = { on ->
                            if (on) ensureMic { vm.toggleGuardian(true) }
                            else vm.toggleGuardian(false)
                        },
                        onIr = vm::setIr,
                        onArmPlay = {
                            ensureMic {
                                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                                projection.launch(mgr.createScreenCaptureIntent())
                            }
                        },
                        onDisarmPlay = vm::disarmPlay,
                        onManualClip = vm::manualClip,
                        onOcr = { ocrPick.launch(arrayOf("image/*")) },
                        onIngestNote = {
                            val q = vm.state.value.query.trim()
                            if (q.isBlank()) {
                                Toast.makeText(this@SmritiBrainActivity, "Type a note first", Toast.LENGTH_SHORT).show()
                            } else {
                                vm.ingestText(q, "NOTE")
                                vm.setQuery("")
                            }
                        },
                        onClose = { finish() }
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                binding.composeBrain.post {
                    if (!isFinishing) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Neural core failed to open", t)
            Toast.makeText(this, "Neural core failed: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun ensureMic(then: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) then() else micPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    companion object {
        private const val TAG = "SmritiBrain"
        const val ACTION_OPEN = "com.aquascope.OPEN_NEURAL_CORE"
    }
}
