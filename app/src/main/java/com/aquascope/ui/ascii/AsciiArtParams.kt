package com.aquascope.ui.ascii

/**
 * Parameters matching the 21st.dev ASCII editor recipe JSON (starry nights / mosaic).
 */
data class AsciiArtParams(
    val renderMode: String = "mosaic",
    val bgMode: String = "solid",
    val bgBlur: Float = 12f,
    val bgOpacity: Float = 90f,
    val cellSize: Int = 16,
    val coverage: Float = 100f,
    val invert: Boolean = false,
    val styleBlend: String = "source-over",
    val charSet: String = "standard",
    val customChars: String = "",
    val brightness: Float = 12f,
    val contrast: Float = 115f,
    val edgeEmphasis: Float = 0f,
    val density: Float = 0f,
    val toneCurve: List<TonePoint> = listOf(TonePoint(0f, 0f), TonePoint(1f, 1f)),
    val tint: Int = 0xFF3CA6FF.toInt(),
    val tintOpacity: Float = 0f,
    val overlayBlend: String = "multiply",
    val saturation: Float = 100f,
    val grayscale: Float = 0f,
    val blurType: String = "off",
    val blurAmount: Float = 35f,
    val animated: Boolean = true,
    val animStyle: String = "wave",
    val animSpeed: Float = 100f,
    val animIntensity: Float = 60f,
    val pfx: PostFx = PostFx(),
    val lights: Lights = Lights(),
    val mask: Mask = Mask(),
    val solidBgColor: Int = 0xFF000000.toInt()
) {
    data class TonePoint(val x: Float, val y: Float)
    data class FxKnob(val enabled: Boolean = false, val intensity: Float = 40f)
    data class PostFx(
        val vignette: FxKnob = FxKnob(true, 38f),
        val scanLines: FxKnob = FxKnob(false, 40f),
        val chromatic: FxKnob = FxKnob(false, 15f),
        val bloom: FxKnob = FxKnob(true, 25f),
        val filmGrain: FxKnob = FxKnob(false, 30f),
        val glitch: FxKnob = FxKnob(false, 20f),
        val pixelate: FxKnob = FxKnob(false, 15f),
        val halftone: FxKnob = FxKnob(false, 20f),
        val filmDust: FxKnob = FxKnob(false, 20f)
    )
    data class LightPoint(
        val x: Float,
        val y: Float,
        val radius: Float = 0.25f,
        val intensity: Float = 0.8f
    )
    data class Lights(val enabled: Boolean = false, val points: List<LightPoint> = emptyList())
    data class Mask(
        val enabled: Boolean = false,
        val invert: Boolean = false,
        val dataUrl: String? = null
    )

    companion object {
        /** Recipe baked for Ask SMRITI — 21st.dev “starry nights” mosaic look. */
        fun starryNights() = AsciiArtParams(
            renderMode = "mosaic",
            bgMode = "solid",
            bgBlur = 12f,
            bgOpacity = 90f,
            cellSize = 16,
            coverage = 100f,
            invert = false,
            brightness = 12f,
            contrast = 115f,
            tint = 0xFF3CA6FF.toInt(),
            tintOpacity = 0f,
            saturation = 100f,
            grayscale = 0f,
            blurType = "off",
            animated = true,
            animStyle = "wave",
            animSpeed = 100f,
            animIntensity = 60f,
            pfx = PostFx(
                vignette = FxKnob(true, 38f),
                bloom = FxKnob(true, 25f)
            )
        )
    }
}
