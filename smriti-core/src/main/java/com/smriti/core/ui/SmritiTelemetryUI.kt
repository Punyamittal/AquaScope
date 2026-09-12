package com.smriti.core.ui

import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smriti.core.actuators.HaloState
import com.smriti.core.guardian.GuardianAlert
import com.smriti.core.memory.EntityType
import com.smriti.core.memory.RecallVerdict
import com.smriti.core.memory.SearchResult
import com.smriti.core.play.ScreenBufferRecorder
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ============================================================================
// SMRITI telemetry UI — Cyber-Tactile Glassmorphism, dark only (SPEC §3).
// ============================================================================

private val MonoFont = FontFamily.Monospace

/** Root screen: header, orb, recall bar, play control, timeline, guardian banner. */
@Composable
fun SmritiRoot(viewModel: SmritiViewModel) {
    val telemetry by viewModel.telemetry.collectAsState()
    val orbState by viewModel.orbState.collectAsState()
    val halo by viewModel.halo.collectAsState()
    val verdict by viewModel.verdict.collectAsState()
    val episodes by viewModel.episodes.collectAsState()
    val recorderState by viewModel.recorderState.collectAsState()
    val guardianAlert by viewModel.guardianAlerts.collectAsState()

    Box(
        Modifier
            .fillMaxSize()
            .background(SmritiBg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            TelemetryHeader(telemetry)
            NeuralOrb(
                state = orbState,
                halo = halo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(168.dp),
            )
            MicWaveform(
                state = orbState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp),
            )
            VoiceRecallBar(verdict = verdict, onQuery = viewModel::onVoiceQuery)
            RecorderControl(state = recorderState, viewModel = viewModel)
            Text(
                "MEMORY TIMELINE",
                color = SmritiOnBgDim,
                fontSize = 11.sp,
                fontFamily = MonoFont,
                letterSpacing = 2.sp,
            )
            MemoryTimeline(episodes = episodes, modifier = Modifier.weight(1f))
        }
        GuardianAlertBanner(
            alert = guardianAlert,
            onDismiss = viewModel::clearGuardianAlert,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
        )
    }
}

/** Glassmorphism card chrome (SPEC §3): #14FFFFFF bg, 1 dp #33FFFFFF border, 24 dp corner. */
fun Modifier.glass(): Modifier = this
    .clip(GlassShape)
    .background(GlassBg)
    .border(1.dp, GlassBorder, GlassShape)

// ----------------------------------------------------------------------------
// TelemetryHeader — animated RAM bar, tok/s chip, thermal chip, NPU label.
// ----------------------------------------------------------------------------

@Composable
fun TelemetryHeader(t: Telemetry) {
    val ramFrac by animateFloatAsState(
        targetValue = if (t.ramTotalGb > 0f) (t.ramUsedGb / t.ramTotalGb).coerceIn(0f, 1f) else 0f,
        animationSpec = tween(600, easing = FastOutSlowInEasing),
        label = "ramFrac",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .glass()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SMRITI CORE",
                color = SmritiCyan,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = MonoFont,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(t.npuLabel, color = SmritiOnBgDim, fontSize = 10.sp, fontFamily = MonoFont)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(Color(0x22FFFFFF)),
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ramFrac)
                        .background(
                            Brush.horizontalGradient(listOf(SmritiCyan, SmritiAmber)),
                        ),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                "%.1f / %.0f GB".format(t.ramUsedGb, t.ramTotalGb),
                color = SmritiOnBg,
                fontSize = 12.sp,
                fontFamily = MonoFont,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TelemetryChip("%.1f tok/s".format(t.tokensPerSec), SmritiCyan)
            TelemetryChip(
                t.thermalC?.let { "%.1f°C".format(it) } ?: "therm N/A",
                if ((t.thermalC ?: 0f) > 70f) SmritiCrimson else SmritiAmber,
            )
            TelemetryChip("RAM LIVE", SmritiEmerald)
        }
    }
}

@Composable
private fun TelemetryChip(label: String, accent: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.10f))
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(label, color = accent, fontSize = 10.sp, fontFamily = MonoFont)
    }
}

// ----------------------------------------------------------------------------
// NeuralOrb — Canvas orb morphing per OrbState (SPEC §3) + halo edge ring (ACT).
// ----------------------------------------------------------------------------

@Composable
fun NeuralOrb(state: OrbState, halo: HaloState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breath",
    )
    val spin by transition.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    val ring by transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing), RepeatMode.Restart),
        label = "ring",
    )
    val wave by transition.animateFloat(
        0f, (2f * PI).toFloat(),
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
        label = "wave",
    )
    val glow by transition.animateFloat(
        0.35f, 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "glow",
    )

    Canvas(modifier) {
        val c = center
        val baseR = size.minDimension * 0.24f
        when (state) {
            OrbState.IDLE -> {
                // Dim cyan breathing orb.
                val r = baseR * (0.92f + 0.08f * breath)
                drawCircle(SmritiCyan.copy(alpha = 0.06f + 0.04f * breath), radius = r * 1.7f)
                drawCircle(SmritiCyan.copy(alpha = 0.45f), radius = r, style = Stroke(2.5.dp.toPx()))
                drawCircle(SmritiCyan.copy(alpha = 0.55f), radius = r * 0.35f)
            }
            OrbState.LISTENING -> {
                // Expanding cyan rings.
                repeat(3) { i ->
                    val p = (ring + i / 3f) % 1f
                    drawCircle(
                        SmritiCyan.copy(alpha = (1f - p) * 0.5f),
                        radius = baseR * (0.5f + 2.2f * p),
                        style = Stroke(2.dp.toPx()),
                    )
                }
                drawCircle(SmritiCyan, radius = baseR * 0.42f)
            }
            OrbState.COMPUTING -> {
                // Rotating amber arcs around a cyan core.
                drawCircle(SmritiCyan.copy(alpha = 0.5f), radius = baseR * 0.4f)
                rotate(spin, pivot = c) {
                    drawArc(
                        SmritiAmber, startAngle = 0f, sweepAngle = 110f, useCenter = false,
                        topLeft = Offset(c.x - baseR, c.y - baseR), size = Size(baseR * 2, baseR * 2),
                        style = Stroke(5.dp.toPx(), cap = StrokeCap.Round),
                    )
                    drawArc(
                        SmritiAmber.copy(alpha = 0.45f), startAngle = 200f, sweepAngle = 60f,
                        useCenter = false,
                        topLeft = Offset(c.x - baseR * 0.7f, c.y - baseR * 0.7f),
                        size = Size(baseR * 1.4f, baseR * 1.4f),
                        style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
            OrbState.SPEAKING -> {
                // Emerald waveform blob — radius modulated by a travelling sine.
                val path = Path()
                val steps = 72
                for (i in 0..steps) {
                    val a = (i / steps.toFloat()) * 2f * PI
                    val r = baseR * (1f + 0.20f * sin(6f * a + wave).toFloat())
                    val x = c.x + (r * cos(a)).toFloat()
                    val y = c.y + (r * sin(a)).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, SmritiEmerald.copy(alpha = 0.22f))
                drawPath(path, SmritiEmerald, style = Stroke(2.5.dp.toPx()))
            }
            OrbState.VERIFIED -> {
                // Cyan check glow.
                drawCircle(SmritiCyan.copy(alpha = 0.18f * glow), radius = baseR * 1.8f)
                drawCircle(SmritiCyan.copy(alpha = 0.35f * glow), radius = baseR * 1.25f)
                drawCircle(SmritiCyan, radius = baseR * 0.85f, style = Stroke(3.dp.toPx()))
                val check = Path().apply {
                    moveTo(c.x - baseR * 0.40f, c.y + baseR * 0.02f)
                    lineTo(c.x - baseR * 0.08f, c.y + baseR * 0.32f)
                    lineTo(c.x + baseR * 0.46f, c.y - baseR * 0.34f)
                }
                drawPath(
                    check, SmritiCyan,
                    style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        // Actuator halo state reflected as an outer edge ring (ACT haloState flow).
        drawCircle(
            haloEdgeColor(halo).copy(alpha = 0.10f + 0.12f * glow),
            radius = size.minDimension * 0.48f,
            style = Stroke(1.5.dp.toPx()),
        )
    }
}

/** Maps ACT HaloState enum entries (contract names only) to edge-ring colors. */
private fun haloEdgeColor(halo: HaloState): Color = when (halo) {
    HaloState.STANDBY_EMERALD -> SmritiEmerald
    HaloState.EXTRACTION_CYAN -> SmritiCyan
    HaloState.GAME_CRIMSON -> SmritiCrimson
    HaloState.CONFIRM_AMBER -> SmritiAmber
    HaloState.EMERGENCY_STROBE -> Color.White
}

// ----------------------------------------------------------------------------
// MicWaveform — PROCEDURAL (SPEC §3 note): no live AudioRecord amplitude flow
// is exposed by guardian/VoskStt in the §2 contracts, so amplitude is
// synthesized from orbState. Swap in a real amplitude StateFlow when GRD
// exposes one — clearly marked here per SPEC.
// ----------------------------------------------------------------------------

@Composable
fun MicWaveform(state: OrbState, modifier: Modifier = Modifier) {
    val active = state == OrbState.LISTENING || state == OrbState.SPEAKING
    val transition = rememberInfiniteTransition(label = "micWave")
    val phase by transition.animateFloat(
        0f, (2f * PI).toFloat(),
        infiniteRepeatable(tween(if (active) 650 else 2400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val color = if (state == OrbState.SPEAKING) SmritiEmerald else SmritiCyan
    Canvas(modifier) {
        val midY = size.height / 2f
        val amp = if (active) size.height * 0.36f else size.height * 0.10f
        val alpha = if (active) 0.9f else 0.3f
        // Three layered detuned sines for depth.
        for (layer in 0..2) {
            val layerAmp = amp * (1f - layer * 0.28f)
            val path = Path()
            val n = 96
            for (i in 0..n) {
                val x = size.width * i / n
                val env = sin(PI * i / n).toFloat()
                val y = midY + layerAmp * env * sin(i * 0.30f + phase * (1f + layer * 0.35f) + layer).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color.copy(alpha = alpha * (1f - layer * 0.3f)), style = Stroke(1.6.dp.toPx()))
        }
    }
}

// ----------------------------------------------------------------------------
// MemoryTimeline — LazyColumn + Canvas connector spine, gyro-tilt glass cards,
// tap -> expanded detail dialog (SPEC §3).
// ----------------------------------------------------------------------------

@Composable
fun MemoryTimeline(episodes: List<SearchResult>, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf<SearchResult?>(null) }
    val tilt by rememberGyroTilt()

    if (episodes.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "No episodes yet — capture memory to begin.",
                color = SmritiOnBgDim,
                fontSize = 12.sp,
                fontFamily = MonoFont,
            )
        }
    } else {
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            itemsIndexed(episodes, key = { _, e -> e.id }) { index, episode ->
                TimelineRow(
                    episode = episode,
                    isFirst = index == 0,
                    isLast = index == episodes.lastIndex,
                    tilt = tilt,
                    onClick = { expanded = episode },
                )
            }
        }
    }

    expanded?.let { episode ->
        EpisodeDetailDialog(episode = episode, onDismiss = { expanded = null })
    }
}

@Composable
private fun TimelineRow(
    episode: SearchResult,
    isFirst: Boolean,
    isLast: Boolean,
    tilt: Pair<Float, Float>,
    onClick: () -> Unit,
) {
    val nodeColor = entityColor(episode.type)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        // Connector spine: vertical segment + node dot colored by EntityType.
        Canvas(
            Modifier
                .width(26.dp)
                .fillMaxHeight(),
        ) {
            val x = size.width / 2f
            val y = size.height / 2f
            val top = if (isFirst) y else 0f
            val bottom = if (isLast) y else size.height
            drawLine(
                color = Color(0x33FFFFFF),
                start = Offset(x, top),
                end = Offset(x, bottom),
                strokeWidth = 2.dp.toPx(),
            )
            drawCircle(nodeColor.copy(alpha = 0.25f), radius = 8.dp.toPx(), center = Offset(x, y))
            drawCircle(nodeColor, radius = 4.dp.toPx(), center = Offset(x, y))
        }
        Spacer(Modifier.width(6.dp))
        // Gyro-tilted glass card (rotation vector -> pitch/roll clamped ±6°).
        Column(
            Modifier
                .weight(1f)
                .graphicsLayer {
                    rotationX = -tilt.first
                    rotationY = tilt.second
                    cameraDistance = 18f * density
                }
                .glass()
                .clickable(onClick = onClick)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    episode.type.name,
                    color = nodeColor,
                    fontSize = 10.sp,
                    fontFamily = MonoFont,
                    letterSpacing = 1.5.sp,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    formatTimestamp(episode.timestamp),
                    color = SmritiOnBgDim,
                    fontSize = 10.sp,
                    fontFamily = MonoFont,
                )
            }
            Text(
                episode.summary,
                color = SmritiOnBg,
                fontSize = 13.sp,
                maxLines = 2,
            )
        }
    }
}

@Composable
private fun EpisodeDetailDialog(episode: SearchResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SmritiSurfaceHigh,
        titleContentColor = entityColor(episode.type),
        textContentColor = SmritiOnBg,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = SmritiCyan) }
        },
        title = {
            Text(
                episode.type.name,
                fontFamily = MonoFont,
                letterSpacing = 2.sp,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(episode.summary, fontSize = 14.sp)
                Text(
                    formatTimestamp(episode.timestamp),
                    color = SmritiOnBgDim,
                    fontSize = 11.sp,
                    fontFamily = MonoFont,
                )
                Text(
                    "score %.3f".format(episode.score),
                    color = SmritiAmber,
                    fontSize = 11.sp,
                    fontFamily = MonoFont,
                )
            }
        },
    )
}

/** EntityType -> spine node / accent color. */
private fun entityColor(type: EntityType): Color = when (type) {
    EntityType.TOOL -> SmritiCyan
    EntityType.HEALTH -> SmritiEmerald
    EntityType.PLACE -> Color(0xFF4D9FFF)
    EntityType.LOCATION -> Color(0xFFB388FF)
    EntityType.MONEY -> SmritiAmber
    EntityType.PEOPLE -> Color(0xFFFF7AB8)
    EntityType.ACCESS -> SmritiCrimson
    EntityType.EPISODE -> Color(0xFF9AA7AD)
}

private fun formatTimestamp(ts: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ts))

// ----------------------------------------------------------------------------
// Gyro tilt — Sensor.TYPE_ROTATION_VECTOR -> rotation matrix -> pitch/roll,
// clamped to ±6° for graphicsLayer rotationX/rotationY (SPEC §3).
// ----------------------------------------------------------------------------

@Composable
private fun rememberGyroTilt(): State<Pair<Float, Float>> {
    val context = LocalContext.current
    val tilt = remember { mutableStateOf(0f to 0f) }
    DisposableEffect(Unit) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val rot = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(rot, event.values)
                val orient = FloatArray(3)
                SensorManager.getOrientation(rot, orient)
                val pitch = Math.toDegrees(orient[1].toDouble()).toFloat().coerceIn(-6f, 6f)
                val roll = Math.toDegrees(orient[2].toDouble()).toFloat().coerceIn(-6f, 6f)
                tilt.value = pitch to roll
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) {
            sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { sm.unregisterListener(listener) }
    }
    return tilt
}

// ----------------------------------------------------------------------------
// VoiceRecallBar — query field + mic button; verdict card below (SPEC §3).
// ----------------------------------------------------------------------------

@Composable
fun VoiceRecallBar(verdict: RecallVerdict?, onQuery: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("Ask SMRITI to recall…", color = SmritiOnBgDim, fontSize = 13.sp)
            },
            trailingIcon = {
                IconButton(onClick = { onQuery(text) }) {
                    MicGlyph(tint = SmritiCyan, modifier = Modifier.size(22.dp))
                }
            },
            shape = GlassShape,
            colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SmritiCyan,
                unfocusedBorderColor = GlassBorder,
                focusedContainerColor = GlassBg,
                unfocusedContainerColor = GlassBg,
                cursorColor = SmritiCyan,
                focusedTextColor = SmritiOnBg,
                unfocusedTextColor = SmritiOnBg,
            ),
        )
        VerdictCard(verdict)
    }
}

@Composable
private fun VerdictCard(verdict: RecallVerdict?) {
    when (verdict) {
        null -> Unit
        is RecallVerdict.Found -> {
            // Found citation — glass card with amber left border.
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassBg)
                    .border(1.dp, GlassBorder, RoundedCornerShape(12.dp)),
            ) {
                Box(
                    Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .background(SmritiAmber),
                )
                Text(
                    verdict.citation,
                    color = SmritiOnBg,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        RecallVerdict.NoRecord -> {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SmritiCrimson.copy(alpha = 0.08f))
                    .border(1.dp, SmritiCrimson.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text("No record found", color = SmritiCrimson, fontSize = 13.sp)
            }
        }
    }
}

/** Mic glyph drawn by hand — material-icons-extended is NOT allowed (SPEC §3). */
@Composable
private fun MicGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // Capsule head.
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.36f, h * 0.06f),
            size = Size(w * 0.28f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.14f, w * 0.14f),
        )
        // Cradle arc.
        drawArc(
            color = tint,
            startAngle = 15f,
            sweepAngle = 150f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.26f),
            size = Size(w * 0.60f, h * 0.46f),
            style = Stroke(width = w * 0.07f, cap = StrokeCap.Round),
        )
        // Stem + base.
        drawLine(tint, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.88f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.32f, h * 0.92f), Offset(w * 0.68f, h * 0.92f), strokeWidth = w * 0.07f, cap = StrokeCap.Round)
    }
}

// ----------------------------------------------------------------------------
// RecorderControl — SMRITI Play start/stop with MediaProjection consent
// (rememberLauncherForActivityResult + createScreenCaptureIntent, SPEC §3) and
// state chip incl. Saved(file.name) confirmation.
// ----------------------------------------------------------------------------

@Composable
fun RecorderControl(state: ScreenBufferRecorder.State, viewModel: SmritiViewModel) {
    val context = LocalContext.current
    val projectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.startRecording(result.resultCode, result.data!!)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .glass()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val recording = state is ScreenBufferRecorder.State.Recording
        Button(
            onClick = { consentLauncher.launch(projectionManager.createScreenCaptureIntent()) },
            enabled = !recording,
            colors = ButtonDefaults.buttonColors(
                containerColor = SmritiCrimson,
                contentColor = Color.White,
                disabledContainerColor = Color(0x22FFFFFF),
                disabledContentColor = SmritiOnBgDim,
            ),
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("PLAY", fontSize = 12.sp, fontFamily = MonoFont)
        }
        OutlinedButton(
            onClick = { viewModel.stopRecording() },
            enabled = recording,
        ) {
            Text("STOP", fontSize = 12.sp, fontFamily = MonoFont, color = SmritiCyan)
        }
        Spacer(Modifier.weight(1f))
        val (chipLabel, chipColor) = when (state) {
            is ScreenBufferRecorder.State.Idle -> "REC IDLE" to SmritiOnBgDim
            is ScreenBufferRecorder.State.Recording -> "● REC" to SmritiCrimson
            is ScreenBufferRecorder.State.Saved -> "SAVED ${state.file.name}" to SmritiEmerald
        }
        TelemetryChip(chipLabel, chipColor)
    }
}

// ----------------------------------------------------------------------------
// GuardianAlertBanner — toast-like banner on GRD alerts; auto-dismisses.
// ----------------------------------------------------------------------------

@Composable
fun GuardianAlertBanner(alert: GuardianAlert?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    LaunchedEffect(alert) {
        if (alert != null) {
            delay(6_000)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = alert != null,
        modifier = modifier,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
    ) {
        if (alert != null) {
            val (label, accent) = when (alert) {
                is GuardianAlert.Fall ->
                    "FALL DETECTED — emergency strobe engaged" to SmritiCrimson
                is GuardianAlert.Sound ->
                    "SOUND: ${alert.label} (${(alert.confidence * 100).toInt()}%)" to SmritiAmber
                is GuardianAlert.Speech ->
                    "HEARD: \"${alert.text}\"" to SmritiAmber
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(accent.copy(alpha = 0.12f))
                    .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    color = accent,
                    fontSize = 12.sp,
                    fontFamily = MonoFont,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = accent, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}
