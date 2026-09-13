package com.aquascope.smriti.brain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.databinding.ActivitySmritiBrainBinding
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.llm.SpeechLanguage
import com.aquascope.smriti.llm.WhisperVoiceSession
import com.aquascope.ui.LocalModelActivity
import com.aquascope.ui.SmritiNav
import com.aquascope.ui.SmritiScreenActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class SmritiBrainActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivitySmritiBrainBinding
    private val vm: BrainViewModel by viewModels()
    private lateinit var smriti: SmritiCore

    private fun openClipEvidence(path: String) {
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(this, "Clip file missing — capture again", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (path.endsWith(".mp4", true)) "video/mp4" else "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Play clip"))
        } catch (t: Throwable) {
            Log.w(TAG, "open clip: ${t.message}")
            Toast.makeText(this, "Could not open clip: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
    private val micPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) vm.toggleGuardian(true) }

    private val speakMicPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) startWhisperOnlySpeak() }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    /**
     * Classic GET_CONTENT — most reliable across OEMs for one-shot image access.
     * We copy bytes to cache **inside this callback** before the URI grant expires.
     */
    private val ocrPick = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            Toast.makeText(this, "OCR cancelled", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        val uri = result.data?.data
        if (uri == null) {
            Toast.makeText(this, "No image selected", Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        startOcrFromPickerUri(uri)
    }

    private val projection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK || result.data == null) return@registerForActivityResult
        val metrics = resources.displayMetrics
        vm.armPlay(result.resultCode, result.data!!, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivitySmritiBrainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            smriti = SmritiCore.get(this)
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
                            vm.recall()
                        },
                        onVoiceRecall = { requestSpeak() },
                        onGuardian = { on ->
                            if (on) ensureMic { vm.toggleGuardian(true) }
                            else vm.toggleGuardian(false)
                        },
                        onIr = vm::setIr,
                        onScreenMind = vm::setScreenMind,
                        onScreenMindPc = vm::setScreenMindPc,
                        onSwipeOcr = { on -> ensureSwipeOcr(on) },
                        onOpenLibrary = {
                            startActivity(Intent(this@SmritiBrainActivity, ScreenLibraryActivity::class.java))
                        },
                        onOpenHeartRate = {
                            startActivity(
                                Intent(
                                    this@SmritiBrainActivity,
                                    com.aquascope.smriti.brain.heartrate.HeartRateActivity::class.java
                                )
                            )
                        },
                        onArmPlay = {
                            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            projection.launch(mgr.createScreenCaptureIntent())
                        },
                        onDisarmPlay = vm::disarmPlay,
                        onManualClip = vm::manualClip,
                        onOcr = { launchOcrPicker() },
                        onIngestNote = {
                            val q = vm.state.value.query.trim()
                            if (q.isBlank()) {
                                Toast.makeText(this@SmritiBrainActivity, "Type a note first", Toast.LENGTH_SHORT).show()
                            } else {
                                vm.ingestText(q, "NOTE")
                                vm.setQuery("")
                            }
                        },
                        onOpenEvidence = { path -> openClipEvidence(path) },
                        onClose = { finish() }
                    )
                }
            }
            if (Build.VERSION.SDK_INT >= 33) {
                binding.composeBrain.post {
                    if (!isFinishing) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            lifecycleScope.launch {
                vm.toasts.collect { msg ->
                    if (!isFinishing) Toast.makeText(this@SmritiBrainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Neural core failed to open", t)
            Toast.makeText(this, "Neural core failed: ${t.message ?: t.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onDestroy() {
        WhisperVoiceSession.release()
        super.onDestroy()
    }

    /** Speak is Whisper-only — never Google / system speech recognition. */
    private fun requestSpeak() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startWhisperOnlySpeak() else speakMicPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startWhisperOnlySpeak() {
        val whisper = smriti.modelStore.findWhisperInstalled()
        if (whisper == null) {
            promptInstallWhisper()
            return
        }
        vm.setListening()
        Toast.makeText(this, "Whisper listening… speak now (6s)", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WhisperVoiceSession.transcribe(
                    context = this@SmritiBrainActivity,
                    modelFile = whisper,
                    seconds = 6,
                    languageTag = Locale.getDefault().toLanguageTag(),
                    onRecordingFinished = {
                        lifecycleScope.launch(Dispatchers.Main) {
                            Toast.makeText(
                                this@SmritiBrainActivity,
                                "Whisper transcribing…",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
            result.fold(
                onSuccess = { spoken ->
                    Toast.makeText(this@SmritiBrainActivity, "Whisper: $spoken", Toast.LENGTH_SHORT).show()
                    handleSpokenQuery(spoken)
                },
                onFailure = { err ->
                    Log.e(TAG, "Whisper Speak failed", err)
                    vm.setListeningIdle()
                    Toast.makeText(
                        this@SmritiBrainActivity,
                        "Whisper failed: ${err.message ?: err.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun promptInstallWhisper() {
        AlertDialog.Builder(this)
            .setTitle("Whisper required")
            .setMessage(
                "Speak uses on-device Whisper only (not Google speech).\n\n" +
                    "Open System and download Whisper Tiny, then try Speak again."
            )
            .setPositiveButton("Open System") { _, _ ->
                startActivity(Intent(this, LocalModelActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun ensureSwipeOcr(on: Boolean) {
        if (!on) {
            vm.setSwipeOcr(false)
            return
        }
        if (!SmritiOcrAccessService.isEnabled(this)) {
            Toast.makeText(
                this,
                "Enable Accessibility → SMRITI OCR Capture",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }
        if (!SmritiOcrAccessService.isConnected()) {
            Toast.makeText(
                this,
                "Toggle SMRITI OCR Capture OFF then ON, then retry Swipe OCR",
                Toast.LENGTH_LONG
            ).show()
            runCatching {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            return
        }
        vm.setSwipeOcr(true)
    }

    private fun launchOcrPicker() {
        Toast.makeText(this, "Pick a screenshot or photo…", Toast.LENGTH_SHORT).show()
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "image/jpeg", "image/webp", "image/*"))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ocrPick.launch(Intent.createChooser(intent, "Select image for OCR"))
        } catch (t: Throwable) {
            Log.e(TAG, "OCR picker failed", t)
            Toast.makeText(this, "Could not open gallery: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun startOcrFromPickerUri(uri: Uri) {
        val cached: File = try {
            LocalOcr.copyPickerUriToCache(this, uri)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to copy picker uri=$uri", t)
            Toast.makeText(
                this,
                "Could not read image: ${t.message ?: "permission denied"}",
                Toast.LENGTH_LONG
            ).show()
            vm.reportOcrFailure(t.message ?: "Could not read selected image")
            return
        }
        Toast.makeText(this, "Running OCR…", Toast.LENGTH_SHORT).show()
        vm.ingestOcrFile(cached)
    }

    private fun handleSpokenQuery(spoken: String) {
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { maybeTranslateSpeech(spoken) }
            vm.setQuery(text)
            vm.recall()
        }
    }

    private suspend fun maybeTranslateSpeech(spoken: String): String {
        if (!smriti.ollamaPrefs.enabled) return spoken
        if (SpeechLanguage.isLikelyEnglish(spoken)) return spoken
        return smriti.ollamaClient.translateToEnglish(spoken).getOrElse { err ->
            Log.w(TAG, "Ollama translate failed", err)
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@SmritiBrainActivity,
                    "Ollama translate failed — using original speech",
                    Toast.LENGTH_SHORT
                ).show()
            }
            spoken
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
