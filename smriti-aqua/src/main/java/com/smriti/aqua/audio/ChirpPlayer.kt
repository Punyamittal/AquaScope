package com.smriti.aqua.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.smriti.aqua.dsp.DspEngine

/**
 * Plays a PCM chirp through the speaker.
 *
 * AudioTrack in MODE_STATIC: the whole buffer is handed to the track up front,
 * so playback timing is hardware-accurate (important for probe synchronization).
 * Blocks the calling thread until playback completes; call from a background
 * thread (see [com.smriti.aqua.audio.AcousticProbe], which does exactly that).
 *
 * Usage:
 *   val chirp = DspEngine.generateChirp(DspEngine.SAMPLE_RATE, 17000f, 23000f, 400)
 *   ChirpPlayer().play(chirp)
 */
class ChirpPlayer {

    /**
     * Writes [pcm] fully into a MODE_STATIC AudioTrack, starts playback, blocks
     * until all frames have been rendered, then releases the track.
     */
    fun play(pcm: ShortArray) {
        if (pcm.isEmpty()) return

        val track = buildTrack(pcm)
        try {
            // MODE_STATIC: write must happen before play(). Loop until the full
            // buffer is accepted (write can return a partial count).
            var written = 0
            while (written < pcm.size) {
                val n = track.write(pcm, written, pcm.size - written)
                if (n < 0) {
                    // Write error (e.g. ERROR_DEAD_OBJECT) — abort playback.
                    return
                }
                written += n
            }

            // Arm the end-of-buffer marker BEFORE play() so very short buffers
            // cannot race past it before the listener is installed.
            val waiter = CompletionWaiter(track, pcm.size)
            val durationMs = pcm.size * 1000L / DspEngine.SAMPLE_RATE
            val deadline = System.currentTimeMillis() + durationMs + TAIL_MARGIN_MS + POLL_TIMEOUT_MS

            track.play()
            waiter.await(deadline)
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun buildTrack(pcm: ShortArray): AudioTrack {
        val bufferBytes = pcm.size * 2
        val attributes = AudioAttributes.Builder()
            // MEDIA usage keeps the chirp on the normal media volume path so the
            // user can control loudness; ultrasonic content is unaffected.
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(DspEngine.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        return AudioTrack(
            attributes,
            format,
            bufferBytes,
            AudioTrack.MODE_STATIC,
            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
        )
    }

    /**
     * Waits on the AudioTrack playback-head marker (fired when the read head
     * reaches the end of the static buffer), with a bounded wall-clock timeout
     * in case the callback is lost.
     */
    private class CompletionWaiter(track: AudioTrack, totalFrames: Int) :
        AudioTrack.OnPlaybackPositionUpdateListener {

        private val lock = Object()
        private var markerHit = false

        init {
            // Returns a status code, so it is NOT a Kotlin property — call directly.
            track.setNotificationMarkerPosition(totalFrames)
            track.setPlaybackPositionUpdateListener(this)
        }

        override fun onMarkerReached(track: AudioTrack?) {
            synchronized(lock) {
                markerHit = true
                lock.notifyAll()
            }
        }

        override fun onPeriodicNotification(track: AudioTrack?) = Unit

        fun await(deadlineMs: Long) {
            synchronized(lock) {
                while (!markerHit) {
                    val remaining = deadlineMs - System.currentTimeMillis()
                    if (remaining <= 0) break
                    lock.wait(remaining)
                }
            }
        }
    }

    private companion object {
        /** Extra time beyond nominal duration for hardware output latency. */
        const val TAIL_MARGIN_MS = 150L
        /** Upper bound on how long we wait past the deadline. */
        const val POLL_TIMEOUT_MS = 1000L
    }
}
