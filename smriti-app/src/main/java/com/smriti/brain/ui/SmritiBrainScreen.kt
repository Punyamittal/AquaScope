package com.smriti.brain.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smriti.brain.BrainViewModel

private val Bg = Color(0xFF0A0A0C)
private val Glass = Color(0x3312F0FF)
private val Neon = Color(0xFF12F0FF)
private val Ink = Color(0xFFE8F6FF)
private val Dim = Color(0xFF8AA0B3)

@Composable
fun SmritiBrainScreen(
    onCapture: () -> Unit,
    vm: BrainViewModel = viewModel()
) {
    val s by vm.state.collectAsState()
    var query by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val tel = s.telemetry

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .padding(16.dp)
    ) {
        Text("SMRITI", color = Ink, fontSize = 28.sp, letterSpacing = 6.sp)
        Text("LOCAL SECOND BRAIN", color = Neon, fontSize = 11.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF12151C), Color(0xFF0E2A33))))
                .border(1.dp, Neon.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Metric("NPU tok/s", tel?.tokensPerSec?.let { "%.1f".format(it) } ?: "—")
            Metric("RAM", tel?.let { "${it.ramUsedMb}/${it.ramTotalMb} MB" } ?: "—")
            Metric("BATT", tel?.let { "${it.batteryPct}%" } ?: "—")
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Airplane / No Network", color = Ink, modifier = Modifier.weight(1f))
            Switch(
                checked = tel?.airGapped == true,
                onCheckedChange = { vm.openAirplaneSettings() },
                colors = SwitchDefaults.colors(checkedTrackColor = Neon)
            )
        }
        Text(
            if (tel?.airGapped == true) "AIR-GAPPED · no active network" else "Network path present — enable Airplane Mode for demo",
            color = if (tel?.airGapped == true) Neon else Color(0xFFFFB000),
            fontSize = 12.sp
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ask memory") },
            colors = fieldColors()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.ask(query) }, colors = neonBtn()) { Text("Recall") }
            Button(onClick = { vm.ask904() }, colors = neonBtn()) { Text("9:04 meds") }
        }
        if (s.answer.isNotBlank()) {
            Text(s.answer, color = Ink, modifier = Modifier.padding(vertical = 6.dp))
        }
        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Log health fact") },
            colors = fieldColors()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.logHealth(note); note = "" }, colors = neonBtn()) { Text("Ingest") }
            Button(onClick = onCapture, colors = neonBtn()) { Text("Play capture") }
            Button(onClick = { vm.startGuardian() }, colors = neonBtn()) { Text("Guardian") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.acToggle() }, colors = neonBtn()) { Text("IR AC") }
            Button(onClick = { vm.smsFamily("") }, colors = neonBtn()) { Text("Family SMS") }
        }
        Text("Guardian: ${s.guardianEvent}", color = Dim, fontSize = 12.sp)
        Text(s.voiceStatus, color = Dim, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(s.memories, key = { it.id }) { m ->
                Column(
                    Modifier
                        .graphicsLayer {
                            rotationX = s.tiltY * 8f
                            rotationY = s.tiltX * 8f
                            cameraDistance = 12f * density
                        }
                        .offset(x = (s.tiltX * 6).dp, y = (s.tiltY * 6).dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Glass)
                        .border(1.dp, Neon.copy(0.25f), RoundedCornerShape(18.dp))
                        .padding(12.dp)
                ) {
                    Text("${m.whenText}  ·  ${m.taxonomy.uppercase()}", color = Neon, fontSize = 11.sp)
                    Text(m.title, color = Ink, fontSize = 16.sp)
                    Text(m.body, color = Dim, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, color = Dim, fontSize = 10.sp, letterSpacing = 1.sp)
        Text(value, color = Ink, fontSize = 13.sp)
    }
}

@Composable
private fun neonBtn() = ButtonDefaults.buttonColors(containerColor = Color(0xFF12333A), contentColor = Neon)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Neon,
    unfocusedBorderColor = Dim,
    focusedLabelColor = Neon,
    unfocusedLabelColor = Dim,
    focusedTextColor = Ink,
    unfocusedTextColor = Ink
)
