package com.aquascope.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.aquascope.R
import com.aquascope.databinding.ActivityLocalModelBinding
import com.aquascope.databinding.ItemModelDownloadBinding
import com.aquascope.halo.SmritiLightState
import com.aquascope.smriti.SmritiCore
import com.aquascope.smriti.llm.DownloadState
import com.aquascope.smriti.llm.LocalModelCatalog
import com.aquascope.smriti.llm.LocalModelDownloadPolicy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class LocalModelActivity : SmritiScreenActivity() {

    private lateinit var binding: ActivityLocalModelBinding
    private lateinit var smriti: SmritiCore
    private lateinit var haloBind: HaloBinding
    private val handler = Handler(Looper.getMainLooper())
    private var previewStep = 0
    private val downloadCards = linkedMapOf<String, ItemModelDownloadBinding>()

    private val pickModel = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
        }
        try {
            val file = smriti.modelStore.importFromUri(this, uri)
            lifecycleScope.launch {
                val status = withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
                Toast.makeText(
                    this@LocalModelActivity,
                    if (status.ready) "Loaded ${file.name}" else "Imported ${file.name} but load failed",
                    Toast.LENGTH_LONG
                ).show()
                refreshUi(reloadModel = false)
            }
        } catch (t: Throwable) {
            Toast.makeText(this, t.message ?: "Import failed", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLocalModelBinding.inflate(layoutInflater)
        setContentView(binding.root)
        smriti = SmritiCore.get(this)
        SmritiNav.bind(this, binding.bottomNav, SmritiNav.TAB_SYSTEM)
        haloBind = HaloBinding(this, binding.haloIndicator)
        binding.btnOpenNeuralCore.setOnClickListener {
            SmritiNav.openNeuralCore(this)
        }

        val halo = haloBind.controller()
        binding.switchHaloEnabled.isChecked = halo.prefs.enabled
        binding.switchHaloNight.isChecked = halo.prefs.nightMode
        binding.switchHaloSim.isChecked = halo.prefs.showSimulator
        when (halo.prefs.brightnessLevel) {
            0 -> binding.toggleBrightness.check(R.id.btnBrightLow)
            2 -> binding.toggleBrightness.check(R.id.btnBrightHigh)
            else -> binding.toggleBrightness.check(R.id.btnBrightMed)
        }

        binding.switchHaloEnabled.setOnCheckedChangeListener { _, checked ->
            halo.prefs.enabled = checked
            halo.refresh()
        }
        binding.switchHaloNight.setOnCheckedChangeListener { _, checked ->
            halo.prefs.nightMode = checked
            halo.refresh()
        }
        binding.switchHaloSim.setOnCheckedChangeListener { _, checked ->
            halo.prefs.showSimulator = checked
            halo.refresh()
        }
        binding.toggleBrightness.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            halo.prefs.brightnessLevel = when (checkedId) {
                R.id.btnBrightLow -> 0
                R.id.btnBrightHigh -> 2
                else -> 1
            }
            halo.refresh()
        }
        binding.btnPreviewHalo.setOnClickListener { previewCycle() }
        binding.btnOpenHaloSettings.setOnClickListener { openRearLightSettings() }
        binding.btnTestHalo.setOnClickListener {
            if (!halo.prefs.enabled || halo.prefs.nightMode) {
                Toast.makeText(this, "Turn Monster Halo ON and disable night mode first", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            Toast.makeText(this, "Testing Monster Halo…", Toast.LENGTH_SHORT).show()
            halo.testSolidCyan(this)
            handler.postDelayed({
                refreshHaloStatus()
                Toast.makeText(
                    this@LocalModelActivity,
                    if (halo.lastHardwareOk) "CONNECTED · effect sent (check phone back)"
                    else "Not lighting — open rear light settings and enable effects",
                    Toast.LENGTH_LONG
                ).show()
            }, 900L)
        }

        binding.switchUseLocal.isChecked = smriti.llmPrefs.enabled
        binding.switchUseLocal.setOnCheckedChangeListener { _, checked ->
            smriti.llmPrefs.enabled = checked
            refreshUi()
        }
        binding.btnImportModel.setOnClickListener { pickModel.launch(arrayOf("*/*")) }
        binding.btnReload.setOnClickListener {
            binding.textModelStatus.text = "Loading local model…"
            lifecycleScope.launch {
                val status = withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
                bindModelStatus(status)
                Toast.makeText(
                    this@LocalModelActivity,
                    if (smriti.isLocalLlmReady()) "Local model ready" else status.message,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        binding.btnRemove.setOnClickListener {
            smriti.modelDownloader.cancel()
            smriti.modelStore.deleteAll()
            lifecycleScope.launch {
                withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
                refreshUi(reloadModel = false)
                Toast.makeText(this@LocalModelActivity, "Model removed — Ask uses rules only", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSaveToken.setOnClickListener { saveTokenFromField() }
        binding.btnGetToken.setOnClickListener { openUrl(LocalModelCatalog.HF_TOKEN_URL) }
        binding.btnAcceptLicense.setOnClickListener {
            openUrl(LocalModelCatalog.gemma3_1b.licenseUrl ?: LocalModelCatalog.HF_TOKEN_URL)
        }
        binding.inputHfToken.setOnEditorActionListener { _, _, _ ->
            saveTokenFromField()
            true
        }

        inflateDownloadCards()
        observeDownloads()
        refreshUi()
        refreshHaloStatus()
        halo.setState(SmritiLightState.NORMAL)
    }

    override fun onResume() {
        super.onResume()
        haloBind.start()
        refreshHaloStatus()
        applyDownloadState(smriti.modelDownloader.state.value)
    }

    override fun onPause() {
        haloBind.stop()
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun inflateDownloadCards() {
        val inflater = LayoutInflater.from(this)
        binding.containerDownloadModels.removeAllViews()
        downloadCards.clear()
        LocalModelCatalog.downloadable.forEach { entry ->
            val card = ItemModelDownloadBinding.inflate(inflater, binding.containerDownloadModels, true)
            downloadCards[entry.id] = card
            card.btnDownload.setOnClickListener { startDownload(entry) }
            card.btnCancelDownload.setOnClickListener { smriti.modelDownloader.cancel() }
        }
    }

    private fun observeDownloads() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                smriti.modelDownloader.state.collect { state ->
                    applyDownloadState(state)
                    when (state) {
                        is DownloadState.Succeeded -> {
                            val status = withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
                            Toast.makeText(
                                this@LocalModelActivity,
                                if (smriti.isLocalLlmReady()) "Ready: ${state.fileName}"
                                else "Downloaded ${state.fileName} — ${status.message}",
                                Toast.LENGTH_LONG
                            ).show()
                            smriti.modelDownloader.consumeTerminal()
                            refreshUi(reloadModel = false)
                        }
                        is DownloadState.Failed -> {
                            if (state.needsToken) promptForToken(state.message)
                            else Toast.makeText(this@LocalModelActivity, state.message, Toast.LENGTH_LONG).show()
                            smriti.modelDownloader.consumeTerminal()
                            applyDownloadState(smriti.modelDownloader.state.value)
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun startDownload(entry: LocalModelCatalog.Entry) {
        val typed = binding.inputHfToken.text?.toString()
        if (!typed.isNullOrBlank()) {
            smriti.llmPrefs.hfAccessToken = typed
            binding.inputHfToken.text = null
            refreshTokenHint()
        }
        smriti.modelDownloader.start(entry, smriti.llmPrefs.hfAccessToken)
        applyDownloadState(smriti.modelDownloader.state.value)
    }

    private fun applyDownloadState(state: DownloadState) {
        val busyId = (state as? DownloadState.Running)?.entryId
        LocalModelCatalog.downloadable.forEach { entry ->
            val card = downloadCards[entry.id] ?: return@forEach
            val installed = smriti.modelStore.isPresent(entry.fileName)
            val running = state is DownloadState.Running && state.entryId == entry.id
            card.textModelName.text = entry.displayName
            card.textModelMeta.text = buildString {
                append(entry.notes)
                append("\n")
                if (entry.sizeBytes > 0) {
                    append(LocalModelDownloadPolicy.formatBytes(entry.sizeBytes))
                    append(" · ")
                }
                append(String.format(Locale.US, "~%.1f GB RAM", entry.approxRamGb))
                if (entry.requiresAccessToken) append(" · Hugging Face token")
            }
            card.progressDownload.visibility = if (running) View.VISIBLE else View.GONE
            card.textDownloadProgress.visibility = if (running) View.VISIBLE else View.GONE
            card.btnCancelDownload.visibility = if (running) View.VISIBLE else View.GONE
            card.btnDownload.isEnabled = busyId == null
            when {
                running && state is DownloadState.Running -> {
                    val total = state.total
                    val pct = if (total > 0) ((state.bytes * 100) / total).toInt().coerceIn(0, 100) else 0
                    card.progressDownload.isIndeterminate = total <= 0
                    if (total > 0) card.progressDownload.progress = pct
                    card.btnDownload.text = getString(R.string.local_model_downloading)
                    card.textDownloadProgress.text = if (total > 0) {
                        "${LocalModelDownloadPolicy.formatBytes(state.bytes)} / ${LocalModelDownloadPolicy.formatBytes(total)}"
                    } else {
                        LocalModelDownloadPolicy.formatBytes(state.bytes)
                    }
                }
                installed -> card.btnDownload.text = getString(R.string.local_model_redownload)
                else -> card.btnDownload.text = getString(R.string.local_model_download)
            }
        }
    }

    private fun saveTokenFromField() {
        val typed = binding.inputHfToken.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) {
            if (smriti.llmPrefs.hasHfAccessToken()) {
                smriti.llmPrefs.hfAccessToken = ""
                Toast.makeText(this, R.string.local_model_token_cleared, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.local_model_token_empty, Toast.LENGTH_SHORT).show()
            }
        } else {
            smriti.llmPrefs.hfAccessToken = typed
            binding.inputHfToken.text = null
            Toast.makeText(this, R.string.local_model_token_saved, Toast.LENGTH_SHORT).show()
        }
        refreshTokenHint()
    }

    private fun refreshTokenHint() {
        binding.inputHfTokenLayout.hint = if (smriti.llmPrefs.hasHfAccessToken()) {
            getString(R.string.local_model_token_saved)
        } else {
            getString(R.string.local_model_hf_token_hint)
        }
    }

    private fun promptForToken(message: String) {
        binding.inputHfTokenLayout.requestFocus()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_model_hf_token_hint)
            .setMessage(message)
            .setPositiveButton(R.string.local_model_get_token) { _, _ ->
                openUrl(LocalModelCatalog.HF_TOKEN_URL)
            }
            .setNeutralButton(R.string.local_model_accept_license) { _, _ ->
                openUrl(LocalModelCatalog.gemma3_1b.licenseUrl ?: LocalModelCatalog.HF_TOKEN_URL)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun openRearLightSettings() {
        val attempts = listOf(
            com.aquascope.halo.VivoLightServiceClient.rearLightSettingsIntent(),
            com.aquascope.halo.VivoLightServiceClient.rearAtmosphereSettingsIntent()
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(
            this,
            "Open Settings → search “rear light” / Dynamic Light and turn effects ON",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun refreshHaloStatus() {
        val halo = haloBind.controller()
        binding.textHaloHardware.text = buildString {
            append(if (halo.hardwareAvailable) "Hardware: CONNECTED to vivo_light_service" else "Hardware: NOT CONNECTED")
            append("\n")
            append(halo.diagnose())
            if (halo.lastStatus.isNotBlank()) {
                append("\n")
                append(halo.lastStatus)
            }
            if (halo.hardwareAvailable) {
                append("\n\nIf the camera ring stays dark:")
                append("\n• Open OriginOS rear light settings → enable effects")
                append("\n• Then Test physical light again")
                append("\n(hasLight=false is normal until OS toggle is on)")
            }
        }
    }

    private fun previewCycle() {
        val halo = haloBind.controller()
        if (!halo.prefs.enabled || halo.prefs.nightMode) {
            Toast.makeText(this, "Enable Monster Halo and turn night mode off", Toast.LENGTH_LONG).show()
            return
        }
        val states = listOf(
            SmritiLightState.NORMAL,
            SmritiLightState.SCANNING,
            SmritiLightState.PROCESSING,
            SmritiLightState.NEW_MEMORY,
            SmritiLightState.ANOMALY,
            SmritiLightState.HIGH_ANOMALY,
            SmritiLightState.PERSISTENT_ANOMALY,
            SmritiLightState.CONFIRMED,
            SmritiLightState.MEMORY_RECALL,
            SmritiLightState.UNKNOWN
        )
        previewStep = 0
        handler.removeCallbacksAndMessages(null)
        fun step() {
            if (previewStep >= states.size) {
                halo.setState(SmritiLightState.NORMAL)
                return
            }
            val s = states[previewStep]
            halo.setState(s)
            previewStep++
            handler.postDelayed({ step() }, 1800L)
        }
        step()
    }

    private fun refreshUi(reloadModel: Boolean = true) {
        if (!reloadModel) {
            bindModelStatus(smriti.modelStatusLight())
            return
        }
        binding.textModelStatus.text = "Loading local model…"
        lifecycleScope.launch {
            val status = withContext(Dispatchers.Default) { smriti.refreshLocalLlm() }
            bindModelStatus(status)
        }
    }

    private fun bindModelStatus(status: com.aquascope.smriti.llm.LocalModelStatus) {
        val sizeMb = if (status.sizeBytes > 0) {
            String.format(Locale.US, "%.0f MB", status.sizeBytes / (1024.0 * 1024.0))
        } else "—"
        val rec = LocalModelCatalog.gemma3_1b
        binding.textModelStatus.text = buildString {
            append(status.message)
            append("\n\n")
            append("Path: ${status.path ?: smriti.modelStore.rootDir().absolutePath}")
            append("\nSize: $sizeMb")
            append("\nRephrase: ${if (smriti.llmPrefs.enabled && smriti.isLocalLlmReady()) "ON" else "OFF (rules only)"}")
            append("\n\nRecommended for iQOO 15 (12 GB): ${rec.displayName}")
            append("\nFile name: ${rec.fileName}")
        }
        refreshTokenHint()
        applyDownloadState(smriti.modelDownloader.state.value)
    }
}
