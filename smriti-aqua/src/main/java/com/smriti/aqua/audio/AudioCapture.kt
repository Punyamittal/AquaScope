package com.smriti.aqua.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.smriti.aqua.dsp.DspEngine

/**
 * Blocking mono microphone capture at 48 kHz PCM_16BIT.
 *
 * Prefers [MediaRecorder.AudioSource.UNPROCESSED] (flat response, no vendor
 * AGC/EQ — required for repeatable ultrasonic fingerprints in the 17-23 kHz
 * band) and falls back to [MediaRecorder.AudioSource.MIC] on devices that do
 * not expose an unprocessed source.
 *
 * [record] blocks the calling thread for the full requested duration and
 * returns exactly durationMs worth of samples. Call from a background thread
 * (see [com.smriti.aqua.audio.AcousticProbe]).
 */
class AudioCapture {

    /**
     * Records [durationMs] milliseconds of audio.
     *
     * @return ShortArray of exactly SAMPLE_RATE * durationMs / 1000 samples.
     *   If the recorder fails to start, the returned buffer is all zeros (the
     *   pipeline still produces a well-shaped, deterministic result).
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested at runtime in MainActivity before any capture starts.
    fun record(durationMs: Int): ShortArray {
        val totalSamples = DspEngine.SAMPLE_RATE * durationMs / 1000
        val out = ShortArray(totalSamples)

        val minBuffer = AudioRecord.getMinBufferSize(
            DspEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 4x min buffer (and at least one full read chunk) to absorb scheduler jitter.
        val bufferBytes = maxOf(minBuffer * 4, totalSamples * 2 / 4)

        val recorder = createRecorder(bufferBytes) ?: return out

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return out
            }

            var offset = 0
            while (offset < totalSamples) {
                val n = recorder.read(out, offset, totalSamples - offset)
                when {
                    n > 0 -> offset += n
                    // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT:
                    // abort and return what we have (zero-padded tail).
                    else -> break
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        return out
    }

    /**
     * Builds an AudioRecord, preferring UNPROCESSED and falling back to MIC.
     * Returns null if neither source can be constructed (extremely rare).
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO is requested at runtime in MainActivity before any capture starts.
    private fun createRecorder(bufferBytes: Int): AudioRecord? {
        for (source in intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC
        )) {
            val candidate = runCatching {
                AudioRecord(
                    source,
                    DspEngine.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }.getOrNull()
            if (candidate != null && candidate.state == AudioRecord.STATE_INITIALIZED) {
                return candidate
            }
            candidate?.release()
        }
        return null
    }
}
