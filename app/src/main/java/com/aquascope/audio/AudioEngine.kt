package com.aquascope.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Handles simultaneous chirp playback + recording.
 * Keeps AEC/NS/AGC disabled for the full capture window to preserve the raw surface response.
 */
class AudioEngine(
    private val context: Context,
    private val chirpDurationSec: Double = ChirpGenerator.DEFAULT_DURATION_SEC
) {
    // Extra recording tail to capture surface decay after chirp ends
    // TODO: Tune tail length based on observed decay times
    private val tailDurationSec = 0.5

    data class CaptureResult(
        val recorded: DoubleArray,
        val reference: DoubleArray,
        val sampleRate: Int
    )

    /**
     * Play chirp through speaker and simultaneously record microphone.
     * Must be called from a coroutine scope.
     */
    suspend fun playAndRecord(): CaptureResult = withContext(Dispatchers.IO) {
        val sampleRate = pickSampleRate()
        val totalRecordSamples = ((chirpDurationSec + tailDurationSec) * sampleRate).toInt()
        val chirp = ChirpGenerator.generate(sampleRate = sampleRate, durationSec = chirpDurationSec)
        val chirpShorts = ChirpGenerator.toShortArray(chirp)

        val minRecordBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minRecordBuf > 0) { "Recording not supported at $sampleRate Hz" }
        val recordBufSize = maxOf(minRecordBuf * 2, 4096 * 2)

        var recorder: AudioRecord? = null
        var player: AudioTrack? = null
        val heldEffects = mutableListOf<AudioEffect>()
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousMode = audioManager.mode
        val previousSpeaker = audioManager.isSpeakerphoneOn
        val playbackError = AtomicReference<Exception?>(null)

        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true

            recorder = createRecorder(sampleRate, recordBufSize)
            heldEffects += holdEffectsDisabled(recorder.audioSessionId)

            player = createPlayer(sampleRate)
            try {
                player.setVolume(1.0f)
            } catch (_: Exception) {
            }

            val recordedShorts = ShortArray(totalRecordSamples)
            var totalRead = 0

            // Stream chirp on a background thread while we capture on this thread
            val playThread = Thread({
                try {
                    var offset = 0
                    while (offset < chirpShorts.size && playbackError.get() == null) {
                        val chunk = minOf(1024, chirpShorts.size - offset)
                        val written = player.write(chirpShorts, offset, chunk)
                        if (written < 0) {
                            throw IllegalStateException("AudioTrack write failed (code $written)")
                        }
                        offset += written
                    }
                } catch (e: Exception) {
                    playbackError.set(e)
                }
            }, "aquascope-chirp-play").also { it.start() }

            recorder.startRecording()
            player.play()

            while (totalRead < totalRecordSamples) {
                playbackError.get()?.let { throw it }
                val read = recorder.read(
                    recordedShorts, totalRead,
                    minOf(2048, totalRecordSamples - totalRead)
                )
                if (read > 0) {
                    totalRead += read
                } else {
                    throw IllegalStateException("Microphone read failed (code $read)")
                }
            }

            playThread.join(3000)

            playbackError.get()?.let { throw it }

            val minAcceptable = (chirpDurationSec * sampleRate).toInt()
            require(totalRead >= minAcceptable) {
                "Capture too short ($totalRead samples, need ≥ $minAcceptable)"
            }

            val recorded = DoubleArray(totalRead) {
                recordedShorts[it].toDouble() / Short.MAX_VALUE
            }
            CaptureResult(recorded, chirp, sampleRate)
        } finally {
            try {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = previousSpeaker
                audioManager.mode = previousMode
            } catch (_: Exception) {
            }
            try {
                recorder?.run {
                    if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
                    release()
                }
            } catch (_: Exception) {
            }
            try {
                player?.run {
                    if (playState == AudioTrack.PLAYSTATE_PLAYING) stop()
                    flush()
                    release()
                }
            } catch (_: Exception) {
            }
            heldEffects.forEach { effect ->
                try {
                    effect.release()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun pickSampleRate(): Int {
        for (rate in listOf(44100, 48000, 16000)) {
            val playOk = AudioTrack.getMinBufferSize(
                rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val recOk = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (playOk > 0 && recOk > 0) return rate
        }
        return 44100
    }

    private fun createPlayer(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuf > 0) { "Playback not supported at $sampleRate Hz" }

        // MODE_STREAM is far more reliable across OEMs than MODE_STATIC
        val streamBuf = maxOf(minBuf * 2, 4096 * 2)

        val attempts = listOf(
            {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(streamBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                    .build()
            },
            {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(streamBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            },
            {
                // Legacy constructor — still works on devices where Builder fails
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    streamBuf,
                    AudioTrack.MODE_STREAM
                )
            }
        )

        var lastError: Exception? = null
        for (factory in attempts) {
            try {
                val track = factory()
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    return track
                }
                track.release()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("AudioTrack failed to initialize", lastError)
    }

    private fun createRecorder(sampleRate: Int, recordBufSize: Int): AudioRecord {
        var lastError: Exception? = null
        for (source in preferredAudioSources()) {
            try {
                val recorder = AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    recordBufSize
                )
                if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                    return recorder
                }
                recorder.release()
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IllegalStateException("Could not initialize microphone capture", lastError)
    }

    private fun preferredAudioSources(): List<Int> = listOf(
        MediaRecorder.AudioSource.UNPROCESSED,
        MediaRecorder.AudioSource.MIC,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.DEFAULT
    )

    private fun holdEffectsDisabled(sessionId: Int): List<AudioEffect> {
        val held = mutableListOf<AudioEffect>()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.also {
                    it.enabled = false
                    held += it
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.also {
                    it.enabled = false
                    held += it
                }
            }
        } catch (_: Exception) {
        }
        try {
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.also {
                    it.enabled = false
                    held += it
                }
            }
        } catch (_: Exception) {
        }
        return held
    }
}
