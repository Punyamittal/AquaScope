package com.aquascope.smriti.brain

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private val Bg = Color(0xFF0A0A0C)
private val Cyan = Color(0xFF00F0FF)
private val Crimson = Color(0xFFFF003C)
private val Amber = Color(0xFFFFB800)
private val Glass = Color(0x33111A22)
private val Ink = Color(0xFFE8FBFF)
private val Mute = Color(0xFF8AA4A8)

@Composable
fun SmritiBrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan,
            onPrimary = Bg,
            secondary = Amber,
            background = Bg,
            surface = Color(0xFF111A22),
            onBackground = Ink,
            onSurface = Ink
        ),
        content = content
    )
}

@Composable
fun SmritiTelemetryUI(
    state: BrainUiState,
    onQuery: (String) -> Unit,
    onRecall: () -> Unit,
    onVoiceRecall: () -> Unit,
    onGuardian: (Boolean) -> Unit,
    onIr: (Boolean) -> Unit,
    onArmPlay: () -> Unit,
    onDisarmPlay: () -> Unit,
    onManualClip: () -> Unit,
    onOcr: () -> Unit,
    onIngestNote: () -> Unit,
    onClose: () -> Unit
) {
    val focus = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .imePadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("SMRITI", color = Ink, fontSize = 22.sp, letterSpacing = 6.sp, fontWeight = FontWeight.Light)
                Text("NEURAL CORE · ON-DEVICE", color = Cyan, fontSize = 10.sp, letterSpacing = 2.sp)
            }
            TextButton(onClick = onClose) { Text("Home", color = Mute) }
        }
        Spacer(Modifier.height(10.dp))
        TelemetryHeader(state)
        Spacer(Modifier.height(12.dp))
        Waveform(state.amplitude)
        Spacer(Modifier.height(8.dp))
        NeuralOrb(state.orb, state.amplitude)
        Text(
            state.orb.name,
            color = Mute,
            fontSize = 11.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp)
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LabelSwitch("Guardian", state.guardianOn, onGuardian)
            LabelSwitch("IR", state.irEnabled && state.irHardware, onIr)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniButton(if (state.playArmed) "Disarm Play" else "Arm Play", Crimson) {
                if (state.playArmed) onDisarmPlay() else onArmPlay()
            }
            MiniButton("Save clip", Amber, onManualClip)
            MiniButton("OCR", Cyan, onOcr)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Ask or type a memory…", color = Mute) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = {
                    focus.clearFocus()
                    onRecall()
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                cursorColor = Cyan,
                focusedBorderColor = Cyan,
                unfocusedBorderColor = Color(0xFF1C3338)
            )
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MiniButton("Send", Cyan) {
                focus.clearFocus()
                onRecall()
            }
            MiniButton("Mic", Amber, onVoiceRecall)
            MiniButton("Store note", Amber, onIngestNote)
        }
        Text(
            state.recallMessage,
            color = if (state.recallFound) Cyan else Mute,
            fontSize = 13.sp,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Text(state.status, color = Mute, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Text("EPISODIC STREAM", color = Cyan, fontSize = 10.sp, letterSpacing = 2.sp)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.timeline, key = { it.id }) { ep ->
                EpisodeCard(ep, state.tiltX, state.tiltY)
            }
        }
    }
}

@Composable
private fun TelemetryHeader(state: BrainUiState) {
    val frac = if (state.ramTotalGb <= 0) 0f else (state.ramUsedGb / state.ramTotalGb).toFloat().coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Glass)
            .border(1.dp, Color(0x4400F0FF), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(
            String.format(Locale.US, "RAM  %.1f / %.1f GB   ·   %s", state.ramUsedGb, state.ramTotalGb, state.ramEffectiveLabel),
            color = Ink,
            fontSize = 12.sp
        )
        LinearProgressIndicator(
            progress = { frac },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(4.dp),
            color = Cyan,
            trackColor = Color(0xFF132028)
        )
        Text(
            String.format(
                Locale.US,
                "%.1f tok/s   ·   %s   ·   halo %s",
                state.tokensPerSec,
                state.thermalLabel,
                if (state.haloHardware) "HW" else "sim"
            ),
            color = Mute,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun NeuralOrb(orb: OrbState, amp: Float) {
    val t = rememberInfiniteTransition(label = "orb")
    val spin by t.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(6800, easing = LinearEasing), RepeatMode.Restart),
        label = "spin"
    )
    val pulse by t.animateFloat(
        0.86f, 1.08f,
        infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val color = when (orb) {
        OrbState.LISTENING -> Cyan
        OrbState.COMPUTING -> Color(0xFF7B5EA7)
        OrbState.SPEAKING -> Amber
        OrbState.VERIFIED -> Color(0xFF00C853)
        OrbState.IDLE -> Color(0xFF4AAFC2)
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(128.dp)) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color.copy(alpha = 0.12f + amp), r * pulse)
            drawCircle(color.copy(alpha = 0.85f), r * 0.72f, c, style = Stroke(width = 3.5f))
            val rad = Math.toRadians(spin.toDouble())
            for (i in 0 until 3) {
                val a = rad + i * 2.1
                val p = Offset(
                    c.x + cos(a).toFloat() * r * 0.72f,
                    c.y + sin(a).toFloat() * r * 0.72f
                )
                drawCircle(color, 5f, p)
            }
        }
    }
}

@Composable
private fun Waveform(amp: Float) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Glass)
    ) {
        val mid = size.height / 2f
        val n = 48
        for (i in 0 until n) {
            val x = size.width * i / n
            val h = (8f + amp * 90f * (0.4f + 0.6f * sin(i * 0.55f + amp * 8f)))
            drawLine(
                Cyan.copy(alpha = 0.85f),
                Offset(x, mid - h),
                Offset(x, mid + h),
                strokeWidth = 3f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun EpisodeCard(ep: EpisodeRecord, tiltX: Float, tiltY: Float) {
    val accent = when (ep.kind) {
        TaxonomyParser.Kind.GAME -> Crimson
        TaxonomyParser.Kind.HEALTH -> Amber
        TaxonomyParser.Kind.ACOUSTIC -> Color(0xFF00C853)
        else -> Cyan
    }
    val whenText = SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date(ep.timestampMs))
    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = tiltX * 12f
                rotationX = -tiltY * 8f
                cameraDistance = 12f * density
            }
            .clip(RoundedCornerShape(16.dp))
            .background(Glass)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
            .padding(12.dp)
    ) {
        Text(ep.kind.name, color = accent, fontSize = 10.sp, letterSpacing = 1.4.sp)
        Text(ep.title, color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(whenText + " · " + ep.source, color = Mute, fontSize = 11.sp)
        if (ep.body.isNotBlank()) {
            Text(ep.body.take(160), color = Mute, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun LabelSwitch(label: String, checked: Boolean, on: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Switch(
            checked = checked,
            onCheckedChange = on,
            colors = SwitchDefaults.colors(checkedTrackColor = Cyan, checkedThumbColor = Bg)
        )
    }
}

@Composable
private fun MiniButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.18f),
            contentColor = color
        ),
        shape = CircleShape,
        modifier = Modifier.padding(top = 8.dp)
    ) { Text(label, fontSize = 12.sp) }
}
