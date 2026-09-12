package com.smriti.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * SMRITI "Cyber-Tactile Glassmorphism" theme (SPEC §3) — DARK ONLY.
 * USER-SPECIFIED palette (overrides any default guidance):
 *   background  #0A0A0C  (near-black)
 *   primary     #00F0FF  (phosphor cyan)
 *   secondary   #FF003C  (crimson)
 *   tertiary    #FFB800  (amber)
 */
val SmritiBg = Color(0xFF0A0A0C)
val SmritiCyan = Color(0xFF00F0FF)
val SmritiCrimson = Color(0xFFFF003C)
val SmritiAmber = Color(0xFFFFB800)
val SmritiEmerald = Color(0xFF10B981)

val SmritiOnBg = Color(0xFFE6F6F8)
val SmritiOnBgDim = Color(0xFF8FA3A8)
val SmritiSurface = Color(0xFF101014)
val SmritiSurfaceHigh = Color(0xFF16161C)

/** Glassmorphism card tokens (SPEC §3): semi-transparent white bg + hairline border, 24 dp corner. */
val GlassBg = Color(0x14FFFFFF)
val GlassBorder = Color(0x33FFFFFF)
val GlassCorner = 24.dp
val GlassShape = RoundedCornerShape(GlassCorner)

private val SmritiDarkScheme = darkColorScheme(
    primary = SmritiCyan,
    onPrimary = Color(0xFF00282B),
    primaryContainer = Color(0xFF0A3A40),
    onPrimaryContainer = SmritiCyan,
    secondary = SmritiCrimson,
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF40001A),
    onSecondaryContainer = Color(0xFFFFB3C0),
    tertiary = SmritiAmber,
    onTertiary = Color(0xFF2E1E00),
    tertiaryContainer = Color(0xFF3D2A00),
    onTertiaryContainer = SmritiAmber,
    background = SmritiBg,
    onBackground = SmritiOnBg,
    surface = SmritiBg,
    onSurface = SmritiOnBg,
    surfaceVariant = SmritiSurface,
    onSurfaceVariant = SmritiOnBgDim,
    error = SmritiCrimson,
    onError = Color(0xFFFFFFFF),
    outline = Color(0xFF2A2A33),
    outlineVariant = GlassBorder,
)

private val SmritiShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(GlassCorner),
)

/**
 * Dark-only wrapper: the palette is user-specified and fixed — the system
 * light/dark toggle is intentionally ignored (SPEC §3 "dark-only").
 */
@Composable
fun SmritiTheme(content: @Composable () -> Unit) {
    @Suppress("UNUSED_VARIABLE")
    val ignoredSystemTheme = isSystemInDarkTheme() // deliberately unused: dark-only product
    MaterialTheme(
        colorScheme = SmritiDarkScheme,
        typography = Typography(),
        shapes = SmritiShapes,
        content = content,
    )
}
