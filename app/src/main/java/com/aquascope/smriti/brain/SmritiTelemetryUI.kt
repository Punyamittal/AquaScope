package com.aquascope.smriti.brain

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF071A2B)
private val Surface = Color(0xCC0C2A43)
private val Hairline = Color(0xFF1E3F55)
private val Cyan = Color(0xFF4AAFC2)
private val Ocean = Color(0xFF126A8A)
private val Ink = Color(0xFFF5F5F0)
private val Mute = Color(0xFFB7C9C8)
private val Faint = Color(0xFF7A9398)
private val Amber = Color(0xFFD99A3A)
private val Panel = RoundedCornerShape(24.dp)

@Composable
fun SmritiBrainTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cyan,
            onPrimary = Navy,
            secondary = Amber,
            background = Navy,
            surface = Color(0xFF0C2A43),
            onBackground = Ink,
            onSurface = Ink,
            outline = Ocean
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
    onOpenEvidence: (String) -> Unit = {},
    onClose: () -> Unit
) {
    val focus = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
            .imePadding()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "SMRITI",
                    color = Ink,
                    fontSize = 20.sp,
                    letterSpacing = 4.sp,
                    fontWeight = FontWeight.Light
                )
                Text(
                    "NEURAL CORE",
                    color = Cyan,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            TextButton(onClick = onClose) {
                Text("Home", color = Cyan, fontSize = 13.sp)
            }
        }

        // Answer is the primary surface — keep it above the fold.
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 14.dp)
                .background(Surface, Panel)
                .border(
                    1.dp,
                    if (state.recallFound) Cyan.copy(alpha = 0.55f) else Hairline,
                    Panel
                )
                .padding(18.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                orbCaption(state.orb).uppercase(Locale.getDefault()),
                color = Cyan,
                fontSize = 10.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Medium
            )
            Text(
                state.recallMessage,
                color = Ink,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                state.status,
                color = Faint,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 10.dp)
            )
            if (state.timeline.isNotEmpty()) {
                Text(
                    "MEMORY",
                    color = Cyan,
                    fontSize = 10.sp,
                    letterSpacing = 1.6.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(top = 18.dp, bottom = 8.dp)
                )
                state.timeline.take(6).forEach { ep ->
                    MemoryRow(ep, onOpenEvidence)
                    Spacer(Modifier.padding(bottom = 8.dp))
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QuietSwitch("Guardian", state.guardianOn, on = onGuardian)
            QuietSwitch(
                label = "IR",
                checked = state.irEnabled,
                enabled = state.irHardware,
                on = onIr
            )
        }
        if (state.guardianOn) {
            QuietWave(state.amplitude)
            if (state.guardianLine.isNotBlank()) {
                Text(
                    state.guardianLine,
                    color = Mute,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SecondaryAction(
                if (state.playArmed) "Stop" else "Capture",
                Modifier.weight(1f)
            ) { if (state.playArmed) onDisarmPlay() else onArmPlay() }
            SecondaryAction("Clip", Modifier.weight(1f), onManualClip)
            Button(
                onClick = onOcr,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Ocean,
                    contentColor = Ink
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) { Text("OCR", fontSize = 13.sp) }
            SecondaryAction("Save", Modifier.weight(1f), onIngestNote)
        }

        OutlinedTextField(
            value = state.query,
            onValueChange = onQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            placeholder = { Text("Ask what this phone remembers…", color = Faint) },
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
            shape = RoundedCornerShape(28.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Ink,
                unfocusedTextColor = Ink,
                cursorColor = Cyan,
                focusedBorderColor = Cyan,
                unfocusedBorderColor = Ocean
            )
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    focus.clearFocus()
                    onRecall()
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cyan,
                    contentColor = Navy
                ),
                elevation = ButtonDefaults.buttonElevation(0.dp)
            ) { Text("Ask", fontSize = 14.sp) }
            OutlinedButton(
                onClick = onVoiceRecall,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                border = androidx.compose.foundation.BorderStroke(1.dp, Ocean)
            ) { Text("Speak", fontSize = 14.sp) }
        }
    }
}

@Composable
private fun QuietWave(amp: Float) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .height(36.dp)
            .background(Surface, RoundedCornerShape(16.dp))
    ) {
        val mid = size.height / 2f
        val n = 64
        var prev = Offset(0f, mid)
        for (i in 0 until n) {
            val x = size.width * i / (n - 1).coerceAtLeast(1)
            val y = mid - amp * (size.height * 0.38f) *
                kotlin.math.sin(i * 0.42f + amp * 6f)
            val next = Offset(x, y)
            if (i > 0) {
                drawLine(Cyan.copy(alpha = 0.85f), prev, next, strokeWidth = 2.2f, cap = StrokeCap.Round)
            }
            prev = next
        }
    }
}

@Composable
private fun MemoryRow(ep: EpisodeRecord, onOpenEvidence: (String) -> Unit) {
    val whenText = SimpleDateFormat("d MMM · HH:mm", Locale.getDefault()).format(Date(ep.timestampMs))
    val kind = when (ep.kind) {
        TaxonomyParser.Kind.GAME -> "Play"
        TaxonomyParser.Kind.HEALTH -> "Health"
        TaxonomyParser.Kind.ACOUSTIC -> "Acoustic"
        else -> "Note"
    }
    val clipPath = ep.evidencePath
    val hasClip = !clipPath.isNullOrBlank()
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface, Panel)
            .border(1.dp, if (hasClip) Cyan.copy(alpha = 0.45f) else Hairline, Panel)
            .then(
                if (hasClip) Modifier.clickable { onOpenEvidence(clipPath!!) }
                else Modifier
            )
            .padding(16.dp)
    ) {
        Text(
            kind.uppercase(Locale.getDefault()),
            color = Cyan,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            ep.title,
            color = Ink,
            fontSize = 16.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        Text(
            "$whenText  ·  ${ep.source}",
            color = Faint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
        )
        if (ep.body.isNotBlank()) {
            Text(
                ep.body.take(160),
                color = Mute,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (hasClip) {
            Text(
                "Tap to play clip",
                color = Cyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun QuietSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    on: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Ink, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
        Switch(
            checked = checked,
            onCheckedChange = on,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = Cyan,
                checkedThumbColor = Navy,
                uncheckedTrackColor = Hairline,
                uncheckedThumbColor = Mute
            )
        )
    }
}

@Composable
private fun SecondaryAction(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
        border = androidx.compose.foundation.BorderStroke(1.dp, Ocean)
    ) { Text(label, fontSize = 13.sp) }
}

private fun orbCaption(orb: OrbState): String = when (orb) {
    OrbState.IDLE -> "Idle"
    OrbState.LISTENING -> "Listening"
    OrbState.COMPUTING -> "Looking in memory"
    OrbState.SPEAKING -> "Speaking"
    OrbState.VERIFIED -> "Found in memory"
}
