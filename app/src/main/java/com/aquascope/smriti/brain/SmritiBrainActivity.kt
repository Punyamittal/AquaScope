package com.aquascope.smriti.brain

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import com.aquascope.R
import com.aquascope.databinding.ActivitySmritiBrainBinding
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.llm.SpeechLanguage
import com.aquascope.smriti.llm.WhisperVoiceSession
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

    private val micPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) vm.toggleGuardian(true) }

    private val speakMicPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> if (ok) startSpeakCapture() }

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

    private val voice = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            handleSpokenQuery(spoken)
        }
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
                        onArmPlay = {
                            // Screen capture / OCR does not need the mic — only MediaProjection consent.
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

    private fun requestSpeak() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startSpeakCapture() else speakMicPerm.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startSpeakCapture() {
        val whisper = smriti.modelStore.findWhisperInstalled()
        if (whisper != null) {
            startWhisperSpeak(whisper)
        } else {
            Toast.makeText(
                this,
                "Download Whisper on System for on-device Speak — using phone STT for now",
                Toast.LENGTH_LONG
            ).show()
            launchSystemVoice()
        }
    }

    private fun startWhisperSpeak(modelFile: File) {
        vm.setListening()
        Toast.makeText(this, "Listening with Whisper… speak now", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                WhisperVoiceSession.transcribe(
                    context = this@SmritiBrainActivity,
                    modelFile = modelFile,
                    seconds = 6,
                    languageTag = Locale.getDefault().toLanguageTag()
                )
            }
            result.fold(
                onSuccess = { spoken ->
                    Toast.makeText(this@SmritiBrainActivity, "Heard: $spoken", Toast.LENGTH_SHORT).show()
                    handleSpokenQuery(spoken)
                },
                onFailure = { err ->
                    Log.w(TAG, "Whisper Speak failed", err)
                    Toast.makeText(
                        this@SmritiBrainActivity,
                        "Whisper failed (${err.message ?: "error"}) — trying phone STT",
                        Toast.LENGTH_LONG
                    ).show()
                    launchSystemVoice()
                }
            )
        }
    }

    private fun launchSystemVoice() {
        vm.setListening()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask SMRITI")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        }
        try {
            voice.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(
                this@SmritiBrainActivity,
                "Voice unavailable — type and tap Ask",
                Toast.LENGTH_SHORT
            ).show()
            vm.recall()
        }
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

    /**
     * Must copy while still on the result callback — many OEMs revoke URI access
     * as soon as we return / hop to a background thread.
     */
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
