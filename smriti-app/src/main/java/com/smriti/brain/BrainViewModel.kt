package com.smriti.brain

import android.app.Application
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smriti.brain.capture.CaptureForegroundService
import com.smriti.brain.database.MemoryTaxonomy
import com.smriti.brain.guardian.FamilySms
import com.smriti.brain.guardian.GuardianForegroundService
import com.smriti.brain.hardware.HaloColor
import com.smriti.brain.telemetry.TelemetrySnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MemoryCard(
    val id: Long,
    val whenText: String,
    val taxonomy: String,
    val title: String,
    val body: String
)

data class BrainUiState(
    val telemetry: TelemetrySnapshot? = null,
    val memories: List<MemoryCard> = emptyList(),
    val answer: String = "",
    val voiceStatus: String = "",
    val tiltX: Float = 0f,
    val tiltY: Float = 0f,
    val guardianEvent: String = "idle"
)

class BrainViewModel(app: Application) : AndroidViewModel(app), SensorEventListener {
    private val graph = (app as SmritiBrainApp).graph
    private val fmt = SimpleDateFormat("h:mm a", Locale.US)
    private val _state = MutableStateFlow(BrainUiState())
    val state = _state.asStateFlow()
    private val sensors = app.getSystemService(SensorManager::class.java)

    init {
        sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(1000)
            }
        }
    }

    private suspend fun refresh() {
        val latest = graph.store.latest(40)
        _state.value = _state.value.copy(
            telemetry = graph.telemetry.snapshot(),
            memories = latest.map {
                MemoryCard(it.id, fmt.format(Date(it.createdAt)), it.taxonomy, it.title, it.body)
            },
            voiceStatus = graph.voice.status(),
            guardianEvent = GuardianForegroundService.lastEvent
        )
    }

    fun ask(q: String) {
        viewModelScope.launch {
            val a = graph.retrieval.recall(q)
            graph.telemetry.noteTokens(a.split(Regex("\\s+")).size)
            _state.value = _state.value.copy(answer = a)
        }
    }

    fun ask904() {
        viewModelScope.launch {
            _state.value = _state.value.copy(answer = graph.retrieval.recallMedicationAt904())
        }
    }

    fun logHealth(text: String) {
        viewModelScope.launch {
            graph.halo.pulse(HaloColor.AMBER)
            graph.store.ingest("Health note", text, "ui", MemoryTaxonomy.HEALTH)
            refresh()
        }
    }

    fun ingestOcr(text: String) {
        viewModelScope.launch {
            graph.halo.pulse(HaloColor.CYAN)
            graph.store.ingest("OCR", text, "ocr")
            refresh()
        }
    }

    fun startGuardian() {
        val ctx = getApplication<Application>()
        ctx.startForegroundService(Intent(ctx, GuardianForegroundService::class.java))
    }

    fun startCapture(code: Int, data: Intent) {
        val ctx = getApplication<Application>()
        val i = Intent(ctx, CaptureForegroundService::class.java)
            .putExtra(CaptureForegroundService.EXTRA_CODE, code)
            .putExtra(CaptureForegroundService.EXTRA_DATA, data)
        ctx.startForegroundService(i)
    }

    fun acToggle() {
        graph.ir.acPowerToggle()
        viewModelScope.launch {
            graph.store.ingest("IR", "AC power toggle", "ir", MemoryTaxonomy.TOOLS)
        }
    }

    fun smsFamily(number: String) {
        FamilySms(getApplication()).compose(
            number,
            "SMRITI daily: ${_state.value.memories.size} memories. Last: ${_state.value.guardianEvent}"
        )
    }

    fun openAirplaneSettings() {
        val ctx = getApplication<Application>()
        ctx.startActivity(Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        _state.value = _state.value.copy(
            tiltX = event.values.getOrNull(1) ?: 0f,
            tiltY = event.values.getOrNull(2) ?: 0f
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onCleared() {
        sensors.unregisterListener(this)
        super.onCleared()
    }
}
