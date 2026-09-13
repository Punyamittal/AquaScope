package com.aquascope.smriti.brain.heartrate

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

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
fun HeartRateScreen(
    state: HeartRateUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Navy)
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Box(Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Text("Back", color = Cyan, fontSize = 13.sp)
            }
            Column(Modifier.align(Alignment.Center)) {
                Text(
                    "HEART RATE",
                    color = Ink,
                    fontSize = 18.sp,
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "NEURAL CORE",
                    color = Cyan,
                    fontSize = 10.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = 14.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CameraFeedCard(
                measuring = state.measuring || state.phase == MeasurementPhase.COMPLETE,
                fingerCovered = state.fingerCovered,
                onPreviewReady = onPreviewReady
            )

            when (state.phase) {
                MeasurementPhase.IDLE, MeasurementPhase.ERROR -> IdleBody(state)
                MeasurementPhase.COMPLETE -> CompleteBody(state)
                else -> MeasuringBody(state)
            }

            if (state.waveform.size >= 8 &&
                (state.measuring || state.phase == MeasurementPhase.COMPLETE)
            ) {
                PpgGraphCard(
                    title = if (state.phase == MeasurementPhase.COMPLETE) {
                        "Pulse waveform"
                    } else {
                        "Live PPG signal"
                    },
                    samples = state.waveform
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        when {
            state.phase == MeasurementPhase.COMPLETE ||
                (state.phase == MeasurementPhase.IDLE && state.bpm != null) -> {
                PrimaryButton("Measure Again", onStart)
            }
            state.measuring -> {
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Ink),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Ocean)
                ) { Text("Stop", fontSize = 15.sp) }
            }
            else -> PrimaryButton("Start Measurement", onStart)
        }

        Text(
            "This measurement is for wellness purposes only and is not intended to diagnose, treat, or monitor medical conditions.",
            color = Faint,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 14.dp, bottom = 4.dp)
        )
    }
}

@Composable
private fun CameraFeedCard(
    measuring: Boolean,
    fingerCovered: Boolean,
    onPreviewReady: (PreviewView) -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface, Panel)
            .border(1.dp, if (fingerCovered) Cyan.copy(alpha = 0.45f) else Hairline, Panel)
            .padding(12.dp)
    ) {
        Text(
            "REAR CAMERA",
            color = Cyan,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Medium
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .aspectRatio(4f / 3f)
                .background(Color(0xFF041018), RoundedCornerShape(16.dp))
                .border(1.dp, Hairline, RoundedCornerShape(16.dp))
        ) {
            AndroidView(
                factory = { ctx ->
                    HeartRateCameraController.createPreviewView(ctx).also(onPreviewReady)
                },
                modifier = Modifier.fillMaxSize()
            )
            if (!measuring) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color(0x99041A2B)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Camera preview starts\nwhen you begin",
                        color = Mute,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 18.sp
                    )
                }
            } else if (fingerCovered) {
                Text(
                    "Finger covering lens",
                    color = Cyan,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp)
                        .background(Navy.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun PpgGraphCard(title: String, samples: List<Float>) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface, Panel)
            .border(1.dp, Hairline, Panel)
            .padding(16.dp)
    ) {
        Text(
            title.uppercase(),
            color = Cyan,
            fontSize = 10.sp,
            letterSpacing = 1.6.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            "Filtered fingertip pulse signal",
            color = Faint,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )
        PpgWaveformGraph(
            samples = samples,
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(Color(0xFF041018), RoundedCornerShape(14.dp))
                .border(1.dp, Hairline, RoundedCornerShape(14.dp))
                .padding(8.dp)
        )
    }
}

@Composable
fun PpgWaveformGraph(
    samples: List<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        if (samples.size < 2) return@Canvas
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for (v in samples) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val range = (max - min).coerceAtLeast(1e-3f)
        val path = Path()
        val n = samples.size
        samples.forEachIndexed { i, v ->
            val x = size.width * i / (n - 1).coerceAtLeast(1)
            val y = size.height - ((v - min) / range) * size.height * 0.86f - size.height * 0.07f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Baseline
        drawLine(
            color = Hairline,
            start = Offset(0f, size.height / 2f),
            end = Offset(size.width, size.height / 2f),
            strokeWidth = 1f
        )
        drawPath(
            path = path,
            color = Cyan,
            style = Stroke(width = 2.4f, cap = StrokeCap.Round)
        )
    }
}

@Composable
private fun IdleBody(state: HeartRateUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface, Panel)
            .border(1.dp, Hairline, Panel)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Measure your heart rate using\nyour phone's camera and flashlight.",
            color = Ink,
            fontSize = 16.sp,
            lineHeight = 24.sp,
            textAlign = TextAlign.Center
        )
        Text(
            "Place your fingertip gently over the rear camera and flashlight. Keep your finger still and cover both completely.",
            color = Mute,
            fontSize = 13.sp,
            lineHeight = 19.sp,
            textAlign = TextAlign.Center
        )
        Text(
            "Voice guidance will coach you through each step.",
            color = Faint,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
        state.errorMessage?.let {
            Text(it, color = Amber, fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MeasuringBody(state: HeartRateUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface, Panel)
            .border(1.dp, Cyan.copy(alpha = 0.35f), Panel)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val display = state.liveBpm?.toString() ?: "—"
        Text(
            display,
            color = Ink,
            fontSize = 56.sp,
            fontWeight = FontWeight.Light,
            letterSpacing = 2.sp
        )
        Text("BPM", color = Cyan, fontSize = 14.sp, letterSpacing = 3.sp)

        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress = state.progress.coerceIn(0f, 1f),
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp),
            color = Cyan,
            trackColor = Hairline
        )
        Text(
            "${(state.progress * 100).toInt()}% · ${state.elapsedSeconds}s",
            color = Faint,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(Modifier.height(14.dp))
        Text(
            phaseLabel(state.phase),
            color = Ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            if (state.phase == MeasurementPhase.POOR_SIGNAL) {
                "Poor signal. Keep your finger still and completely cover the camera and flashlight."
            } else {
                "Keep your finger still."
            },
            color = Mute,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            "Signal quality: ${qualityLabel(state.signalQuality, state.confidence)}",
            color = Faint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

@Composable
private fun CompleteBody(state: HeartRateUiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Surface, Panel)
            .border(1.dp, Cyan.copy(alpha = 0.55f), Panel)
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "${state.bpm ?: state.liveBpm ?: "—"}",
            color = Ink,
            fontSize = 56.sp,
            fontWeight = FontWeight.Light
        )
        Text("BPM", color = Cyan, fontSize = 14.sp, letterSpacing = 3.sp)
        Spacer(Modifier.height(14.dp))
        Text(
            "Signal quality: ${qualityLabel(state.signalQuality, state.confidence)}",
            color = Mute,
            fontSize = 14.sp
        )
        Text(
            "Measurement duration: ${state.elapsedSeconds} sec",
            color = Faint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "Measurement complete",
            color = Cyan,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 14.dp)
        )
    }
}

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Cyan,
            contentColor = Navy
        ),
        elevation = ButtonDefaults.buttonElevation(0.dp)
    ) { Text(label, fontSize = 15.sp) }
}

private fun phaseLabel(phase: MeasurementPhase): String = when (phase) {
    MeasurementPhase.PLACE_FINGER -> "Place finger"
    MeasurementPhase.DETECTING_PULSE -> "Detecting pulse"
    MeasurementPhase.MEASURING -> "Measuring"
    MeasurementPhase.POOR_SIGNAL -> "Poor signal"
    MeasurementPhase.COMPLETE -> "Measurement complete"
    MeasurementPhase.ERROR -> "Unavailable"
    MeasurementPhase.IDLE -> "Ready"
}

private fun qualityLabel(q: SignalQuality, confidence: Float): String = when (q) {
    SignalQuality.HIGH -> "High"
    SignalQuality.MEDIUM -> "Good"
    SignalQuality.LOW -> "Low"
    SignalQuality.INVALID -> if (confidence <= 0f) "Waiting" else "Poor"
}
