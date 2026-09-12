package com.smriti.aqua.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Warm, low-saturation SMRITI AQUA palette. No blue-purple gradients anywhere.
val Cream = Color(0xFFFAF7F2)      // background
val Ink = Color(0xFF1C1917)        // primary text
val Amber = Color(0xFFB45309)      // accent
val AmberDeep = Color(0xFF7C3A08)  // pressed / deep accent
val AmberSoft = Color(0xFFF3E3CE)  // amber container
val SandLine = Color(0xFFD8CCB8)   // outlines
val StoneText = Color(0xFF6B6259)  // secondary text
val MossGreen = Color(0xFF4D7C0F)  // NORMAL / ok
val MossGreenSoft = Color(0xFFE4EAD3)
val RustRed = Color(0xFF9C3D2E)    // CONFIRMED / danger
val RustRedSoft = Color(0xFFF0DAD3)
val SlateGrey = Color(0xFF64748B)  // BASELINE chip (blue-grey)
val SlateGreySoft = Color(0xFFE2E5EA)
val DeepBrown = Color(0xFF3B2410)  // spectrogram hot end

private val AquaLightColors = lightColorScheme(
    primary = Amber,
    onPrimary = Cream,
    primaryContainer = AmberSoft,
    onPrimaryContainer = AmberDeep,
    secondary = Color(0xFF8A6D4B),
    onSecondary = Cream,
    secondaryContainer = Color(0xFFEFE6D8),
    onSecondaryContainer = Color(0xFF4A3823),
    tertiary = MossGreen,
    onTertiary = Cream,
    tertiaryContainer = MossGreenSoft,
    onTertiaryContainer = Color(0xFF2F4A07),
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = Color(0xFFF1EAE0),
    onSurfaceVariant = StoneText,
    outline = SandLine,
    outlineVariant = Color(0xFFE7DECF),
    error = RustRed,
    onError = Cream,
    errorContainer = RustRedSoft,
    onErrorContainer = Color(0xFF5C2115),
)

@Composable
fun AquaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AquaLightColors,
        content = content,
    )
}
