package com.smriti.aqua.audio

import com.smriti.aqua.dsp.DspEngine
import com.smriti.aqua.sensors.AccelRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates one active acoustic probe:
 *
 *   1. starts the accelerometer recorder,
 *   2. starts a blocking mic capture of [listenMs] on Dispatchers.IO,
 *   3. plays the ultrasonic chirp ~[CHIRP_DELAY_MS] after recording starts
 *      (so the capture window contains pre-chirp room tone, the chirp itself,
 *      and the echo/decay tail),
 *   4. stops the accelerometer,
 *   5. computes fingerprint + spectrogram via [DspEngine].
 *
 * Timeline (defaults): |---- 50 ms ----|== 400 ms chirp ==|---- 750 ms tail ----|
 *                      0 ms                                1200 ms (listenMs)
 *
 * Caller must hold the RECORD_AUDIO runtime permission (requested in
 * MainActivity). Heavy DSP runs on Dispatchers.Default.
 */
class AcousticProbe(private val accel: AccelRecorder) {

    data class ProbeResult(
        /** Raw mono 48 kHz capture, length = SAMPLE_RATE * listenMs / 1000. */
        val pcm: ShortArray,
        /** Z-axis accelerometer samples collected during the capture window. */
        val accelZ: FloatArray,
        /** 72-dim fingerprint (see SPEC section 2). */
        val fingerprint: FloatArray,
        /** Row-major [frames][mels] log-mel spectrogram, normalized 0..1. */
        val spectrogram: FloatArray,
        val frames: Int,
        val mels: Int
    ) {
        // data-class arrays: keep equals/hashCode referential-consistent.
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    /**
     * Runs one full probe. Suspends until capture, playback and DSP finish
     * (~listenMs + DSP time). Never throws for audio hardware races; failures
     * surface as zero-padded buffers from [AudioCapture].
     */
    suspend fun probe(chirpMs: Int = 400, listenMs: Int = 1200): ProbeResult =
        withContext(Dispatchers.IO) {
            val chirp = DspEngine.generateChirp(
                DspEngine.SAMPLE_RATE, CHIRP_F0_HZ, CHIRP_F1_HZ, chirpMs
            )

            accel.start()

            // Play the chirp shortly after the blocking record call has had time
            // to start the AudioRecord, so the chirp lands inside the window.
            val playback = launch {
                delay(CHIRP_DELAY_MS)
                ChirpPlayer().play(chirp)
            }

            // Blocking capture on this IO thread; returns exactly listenMs of samples.
            val pcm = AudioCapture().record(listenMs)
            playback.join()

            val accelZ = accel.stop()

            val (fingerprint, spectrogram, dims) = withContext(Dispatchers.Default) {
                val fp = DspEngine.computeFingerprint(pcm, DspEngine.SAMPLE_RATE)
                val sg = DspEngine.computeSpectrogram(pcm, DspEngine.SAMPLE_RATE)
                val d = DspEngine.spectrogramDims(pcm.size)
                Triple(fp, sg, d)
            }

            ProbeResult(
                pcm = pcm,
                accelZ = accelZ,
                fingerprint = fingerprint,
                spectrogram = spectrogram,
                frames = dims.getOrElse(0) { 0 },
                mels = dims.getOrElse(1) { 0 }
            )
        }

    companion object {
        /** Delay between record start and chirp playback (SPEC section 4). */
        const val CHIRP_DELAY_MS = 50L
        const val CHIRP_F0_HZ = 17000f
        const val CHIRP_F1_HZ = 23000f
    }
}
