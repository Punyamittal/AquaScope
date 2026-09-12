package com.smriti.core.ui

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Live UI telemetry snapshot (SPEC §2.5).
 */
data class Telemetry(
    val ramUsedGb: Float,
    val ramTotalGb: Float,
    val tokensPerSec: Float,
    val thermalC: Float?,
    val npuLabel: String,
)

/**
 * Token-rate registry for the on-device LLM path (SPEC §3). The future
 * MediaPipe GenAI callback updates [tokensPerSec] from its inference thread;
 * the telemetry poller reads it once per second. Real wiring — a plain
 * volatile hand-off, no fake data source.
 */
object TokenMeter {
    @Volatile
    var tokensPerSec: Float = 0f
}

/**
 * 1 Hz telemetry sampler (SPEC §3):
 *  - RAM used/total from ActivityManager.MemoryInfo (GB).
 *  - tokens/sec from [TokenMeter].
 *  - SoC thermals by scanning /sys/class/thermal/thermal_zone*&#47;type for
 *    cpu|soc|gpu and reading temp/1000 (nullable — SELinux may deny reads).
 *  - Static label "Hexagon NPU ready" for the Snapdragon 8 Elite NPU path.
 */
class TelemetryMonitor(private val context: Context) {

    fun telemetry(): Flow<Telemetry> = flow {
        while (true) {
            emit(sample())
            delay(POLL_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    private fun sample(): Telemetry {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalGb = info.totalMem / BYTES_PER_GB
        val usedGb = (info.totalMem - info.availMem) / BYTES_PER_GB
        return Telemetry(
            ramUsedGb = usedGb,
            ramTotalGb = totalGb,
            tokensPerSec = TokenMeter.tokensPerSec,
            thermalC = readSocThermalC(),
            npuLabel = NPU_LABEL,
        )
    }

    /**
     * First thermal zone whose type matches cpu|soc|gpu, temp millidegC -> degC.
     * Returns null when sysfs is unreadable (never crashes, SPEC §4).
     */
    private fun readSocThermalC(): Float? = runCatching {
        val root = File(THERMAL_ROOT)
        val zones = root.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
            ?: return null
        val typeRe = Regex("cpu|soc|gpu", RegexOption.IGNORE_CASE)
        zones.firstNotNullOfOrNull { zone ->
            val type = File(zone, "type").takeIf { it.canRead() }?.readText()?.trim()
                ?: return@firstNotNullOfOrNull null
            if (!typeRe.containsMatchIn(type)) return@firstNotNullOfOrNull null
            File(zone, "temp").takeIf { it.canRead() }?.readText()?.trim()?.toFloatOrNull()
                ?.div(1000f)
        }
    }.getOrNull()

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val BYTES_PER_GB = 1024f * 1024f * 1024f
        const val THERMAL_ROOT = "/sys/class/thermal"
        const val NPU_LABEL = "Hexagon NPU ready"
    }
}
