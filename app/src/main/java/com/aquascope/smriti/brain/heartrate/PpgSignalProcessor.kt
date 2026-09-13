package com.aquascope.smriti.brain.heartrate

/**
 * Rolling PPG pipeline: detrend → band-pass → peaks → BPM + confidence.
 */
class PpgSignalProcessor(
    private val maxSeconds: Double = 32.0,
    private val measureTargetSec: Double = 25.0
) {
    private val red = ArrayList<Float>(512)
    private val ts = ArrayList<Long>(512)
    private val filteredBuf = ArrayList<Double>(512)
    private val bandPass = BandPassFilter()

    private var dcEma = 0.0
    private var smoothedBpm: Double? = null
    private var coveredFrames = 0
    private var totalFrames = 0
    private var coverageSum = 0f
    private var lastRed: Float? = null
    private var measureStartNs: Long? = null
    private var firstSampleNs: Long? = null

    data class Snapshot(
        val phase: MeasurementPhase,
        val liveBpm: Int?,
        val finalBpm: Int?,
        val confidence: Float,
        val quality: SignalQuality,
        val progress: Float,
        val elapsedSeconds: Int,
        val fingerCovered: Boolean,
        val status: String,
        val waveform: List<Float> = emptyList()
    )

    fun reset() {
        red.clear()
        ts.clear()
        filteredBuf.clear()
        bandPass.reset()
        dcEma = 0.0
        smoothedBpm = null
        coveredFrames = 0
        totalFrames = 0
        coverageSum = 0f
        lastRed = null
        measureStartNs = null
        firstSampleNs = null
    }

    fun onFrame(frame: FrameAnalysis): Snapshot {
        totalFrames++
        coverageSum += frame.coverageScore
        if (frame.fingerCovered) coveredFrames++

        val prev = lastRed
        lastRed = frame.redMean

        // Reject abrupt motion / uncover spikes
        if (prev != null && kotlin.math.abs(frame.redMean - prev) > 55f) {
            return currentSnapshot(frame.fingerCovered, forcePoor = true)
        }

        if (!frame.fingerCovered) {
            // Keep a short buffer but don't advance measurement
            return currentSnapshot(false)
        }

        if (firstSampleNs == null) firstSampleNs = frame.timestampNs
        red += frame.redMean
        ts += frame.timestampNs
        trimBuffer(frame.timestampNs)

        val rate = estimateSampleRate()
        bandPass.updateSampleRate(rate)

        // DC removal via EMA, then band-pass
        val alpha = 0.02
        dcEma = if (filteredBuf.isEmpty()) frame.redMean.toDouble() else alpha * frame.redMean + (1 - alpha) * dcEma
        val detrended = frame.redMean - dcEma
        val filtered = bandPass.filter(detrended)
        filteredBuf += filtered
        while (filteredBuf.size > red.size) filteredBuf.removeAt(0)
        while (filteredBuf.size < red.size) filteredBuf.add(0, 0.0)

        val elapsed = ((frame.timestampNs - (firstSampleNs ?: frame.timestampNs)) / 1e9)

        if (red.size < 20 || elapsed < 2.0) {
            return Snapshot(
                phase = MeasurementPhase.PLACE_FINGER,
                liveBpm = null,
                finalBpm = null,
                confidence = 0f,
                quality = SignalQuality.INVALID,
                progress = (elapsed / measureTargetSec).toFloat().coerceIn(0f, 1f),
                elapsedSeconds = elapsed.toInt(),
                fingerCovered = true,
                status = "Place finger",
                waveform = waveformForUi()
            )
        }

        val values = filteredBuf.toDoubleArray()
        val times = LongArray(ts.size) { ts[it] }
        val peaks = PeakDetector.detect(values, times)
        val estimate = HeartRateEstimator.estimate(peaks, values, rate)
        val wave = waveformForUi()

        if (estimate == null || estimate.validBeats < 2) {
            return Snapshot(
                phase = MeasurementPhase.DETECTING_PULSE,
                liveBpm = null,
                finalBpm = null,
                confidence = 0f,
                quality = SignalQuality.INVALID,
                progress = (elapsed / measureTargetSec).toFloat().coerceIn(0f, 1f),
                elapsedSeconds = elapsed.toInt(),
                fingerCovered = true,
                status = "Detecting pulse",
                waveform = wave
            )
        }

        if (measureStartNs == null) measureStartNs = frame.timestampNs
        val measureElapsed = ((frame.timestampNs - (measureStartNs ?: frame.timestampNs)) / 1e9)

        smoothedBpm = when (val s = smoothedBpm) {
            null -> estimate.bpm
            else -> 0.75 * s + 0.25 * estimate.bpm
        }

        val coveredRatio = if (totalFrames == 0) 0f else coveredFrames.toFloat() / totalFrames
        val avgCoverage = if (totalFrames == 0) 0f else coverageSum / totalFrames
        val snr = SignalQualityAnalyzer.approximateSnr(values, peaks)
        val quality = SignalQualityAnalyzer.classify(
            confidence = estimate.confidence,
            validBeats = estimate.validBeats,
            coverageScore = avgCoverage,
            fingerCoveredRatio = coveredRatio,
            ibiStdSec = estimate.ibiStdSec,
            snrApprox = snr
        )

        val live = smoothedBpm?.toInt()?.coerceIn(40, 200)
        val progress = (measureElapsed / measureTargetSec).toFloat().coerceIn(0f, 1f)

        if (measureElapsed >= measureTargetSec && estimate.validBeats >= 5) {
            val finalOk = quality != SignalQuality.INVALID && estimate.confidence >= 0.45f
            return Snapshot(
                phase = if (finalOk) MeasurementPhase.COMPLETE else MeasurementPhase.POOR_SIGNAL,
                liveBpm = if (finalOk) live else null,
                finalBpm = if (finalOk) live else null,
                confidence = estimate.confidence,
                quality = if (finalOk) quality else SignalQuality.INVALID,
                progress = 1f,
                elapsedSeconds = measureElapsed.toInt().coerceAtLeast(elapsed.toInt()),
                fingerCovered = true,
                status = if (finalOk) "Measurement complete" else "Poor signal",
                waveform = wave
            )
        }

        if (quality == SignalQuality.INVALID && measureElapsed > 8.0) {
            return Snapshot(
                phase = MeasurementPhase.POOR_SIGNAL,
                liveBpm = null,
                finalBpm = null,
                confidence = estimate.confidence,
                quality = SignalQuality.INVALID,
                progress = progress,
                elapsedSeconds = measureElapsed.toInt(),
                fingerCovered = true,
                status = "Poor signal",
                waveform = wave
            )
        }

        // Only show BPM after enough valid beats
        val showLive = estimate.validBeats >= 4 && estimate.confidence >= 0.45f
        return Snapshot(
            phase = MeasurementPhase.MEASURING,
            liveBpm = if (showLive) live else null,
            finalBpm = null,
            confidence = estimate.confidence,
            quality = quality,
            progress = progress,
            elapsedSeconds = measureElapsed.toInt(),
            fingerCovered = true,
            status = "Measuring",
            waveform = wave
        )
    }

    private fun currentSnapshot(fingerCovered: Boolean, forcePoor: Boolean = false): Snapshot {
        val elapsed = if (firstSampleNs == null) 0.0 else {
            val last = ts.lastOrNull() ?: firstSampleNs!!
            (last - firstSampleNs!!) / 1e9
        }
        val wave = waveformForUi()
        return when {
            forcePoor && fingerCovered -> Snapshot(
                phase = MeasurementPhase.POOR_SIGNAL,
                liveBpm = smoothedBpm?.toInt(),
                finalBpm = null,
                confidence = 0.2f,
                quality = SignalQuality.LOW,
                progress = (elapsed / measureTargetSec).toFloat().coerceIn(0f, 1f),
                elapsedSeconds = elapsed.toInt(),
                fingerCovered = true,
                status = "Poor signal",
                waveform = wave
            )
            !fingerCovered -> Snapshot(
                phase = MeasurementPhase.PLACE_FINGER,
                liveBpm = null,
                finalBpm = null,
                confidence = 0f,
                quality = SignalQuality.INVALID,
                progress = (elapsed / measureTargetSec).toFloat().coerceIn(0f, 1f),
                elapsedSeconds = elapsed.toInt(),
                fingerCovered = false,
                status = "Place finger",
                waveform = wave
            )
            else -> Snapshot(
                phase = MeasurementPhase.DETECTING_PULSE,
                liveBpm = null,
                finalBpm = null,
                confidence = 0f,
                quality = SignalQuality.INVALID,
                progress = (elapsed / measureTargetSec).toFloat().coerceIn(0f, 1f),
                elapsedSeconds = elapsed.toInt(),
                fingerCovered = true,
                status = "Detecting pulse",
                waveform = wave
            )
        }
    }

    /** Downsample filtered PPG for Compose Canvas (keeps UI light). */
    fun waveformForUi(maxPoints: Int = 140): List<Float> {
        if (filteredBuf.isEmpty()) return emptyList()
        val src = filteredBuf
        if (src.size <= maxPoints) return src.map { it.toFloat() }
        val out = ArrayList<Float>(maxPoints)
        val step = src.size.toFloat() / maxPoints
        var i = 0
        while (i < maxPoints) {
            val idx = (i * step).toInt().coerceIn(0, src.lastIndex)
            out += src[idx].toFloat()
            i++
        }
        return out
    }

    private fun trimBuffer(nowNs: Long) {
        val cutoff = nowNs - (maxSeconds * 1e9).toLong()
        while (ts.isNotEmpty() && ts.first() < cutoff) {
            ts.removeAt(0)
            red.removeAt(0)
            if (filteredBuf.isNotEmpty()) filteredBuf.removeAt(0)
        }
    }

    private fun estimateSampleRate(): Double {
        if (ts.size < 8) return 30.0
        val dt = (ts.last() - ts.first()) / 1e9
        if (dt <= 0.05) return 30.0
        return ((ts.size - 1) / dt).coerceIn(10.0, 60.0)
    }
}
