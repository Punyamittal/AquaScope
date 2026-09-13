package com.aquascope.smriti.brain.heartrate

/**
 * Detects whether a fingertip is covering the rear camera under torch light.
 * Torch + finger typically yields high red, elevated brightness, and a fairly uniform ROI.
 */
object FingerDetector {

    private const val MIN_BRIGHTNESS = 40f
    private const val MAX_BRIGHTNESS = 245f
    private const val MIN_RED = 55f
    private const val MIN_RED_RATIO = 1.15f

    fun analyze(
        redMean: Float,
        greenMean: Float,
        blueMean: Float,
        brightness: Float
    ): Pair<Boolean, Float> {
        if (brightness < MIN_BRIGHTNESS || brightness > MAX_BRIGHTNESS) {
            return false to 0f
        }
        if (redMean < MIN_RED) return false to 0f

        val gb = ((greenMean + blueMean) * 0.5f).coerceAtLeast(1f)
        val redRatio = redMean / gb
        val brightnessScore = when {
            brightness in 70f..210f -> 1f
            brightness in MIN_BRIGHTNESS..MAX_BRIGHTNESS -> 0.55f
            else -> 0f
        }
        val redScore = ((redRatio - 1f) / 0.8f).coerceIn(0f, 1f)
        val coverage = (0.55f * redScore + 0.45f * brightnessScore).coerceIn(0f, 1f)
        val covered = redRatio >= MIN_RED_RATIO && coverage >= 0.45f
        return covered to coverage
    }
}
