package com.aquascope.smriti.llm

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/**
 * Record mic audio and transcribe with on-device Whisper Tiny.
 */
object WhisperVoiceSession {

    private const val TAG = "WhisperVoice"
    private val engineRef = AtomicReference<WhisperSttEngine?>()

    fun isAvailable(store: LocalModelStore): Boolean = store.findWhisperInstalled() != null

    fun transcribe(
        context: Context,
        modelFile: File,
        seconds: Int = 6,
        languageTag: String? = Locale.getDefault().toLanguageTag()
    ): Result<String> = runCatching {
        val pcm = recordPcm16(seconds)
        if (pcm.isEmpty()) error("No audio captured")
        val engine = obtainEngine(context, modelFile)
        val text = engine.transcribe(pcm, languageTag)
        if (text.isBlank()) error("Whisper heard silence")
        text
    }

    fun release() {
        engineRef.getAndSet(null)?.close()
    }

    private fun obtainEngine(context: Context, modelFile: File): WhisperSttEngine {
        engineRef.get()?.let { return it }
        synchronized(this) {
            engineRef.get()?.let { return it }
            val tokenizer = WhisperTokenizer.load(context.applicationContext)
            val created = WhisperSttEngine(modelFile, tokenizer)
            engineRef.set(created)
            return created
        }
    }

    private fun recordPcm16(seconds: Int): ShortArray {
        val sampleRate = WhisperMelFrontend.SAMPLE_RATE
        val total = sampleRate * seconds.coerceIn(2, 20)
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf.coerceAtLeast(sampleRate)
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            error("Mic init failed")
        }
        val out = ShortArray(total)
        try {
            record.startRecording()
            var got = 0
            while (got < total) {
                val n = record.read(out, got, total - got)
                if (n < 0) break
                got += n
            }
            Log.i(TAG, "Captured $got samples (${seconds}s window)")
            return if (got == total) out else out.copyOf(got)
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }
}
