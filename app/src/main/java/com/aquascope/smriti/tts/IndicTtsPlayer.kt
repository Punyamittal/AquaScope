package com.aquascope.smriti.tts

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays WAV bytes returned by [IndicTtsClient] via a temp-file + MediaPlayer.
 * No existing MediaPlayer/SoundPool usage elsewhere in this app — this is a
 * new, narrowly-scoped playback path used only for the network Indic TTS voice.
 */
class IndicTtsPlayer(private val context: Context) {

    private var player: MediaPlayer? = null

    fun play(
        wavBytes: ByteArray,
        onStart: () -> Unit = {},
        onDone: () -> Unit = {},
        onError: () -> Unit = {}
    ) {
        stop()
        try {
            val tmp = File.createTempFile("smriti_tts_", ".wav", context.cacheDir)
            tmp.writeBytes(wavBytes)
            player = MediaPlayer().apply {
                setDataSource(tmp.absolutePath)
                setOnPreparedListener {
                    onStart()
                    it.start()
                }
                setOnCompletionListener {
                    tmp.delete()
                    it.release()
                    if (player === it) player = null
                    onDone()
                }
                setOnErrorListener { mp, what, extra ->
                    Log.w(TAG, "MediaPlayer error what=$what extra=$extra")
                    tmp.delete()
                    mp.release()
                    if (player === mp) player = null
                    onError()
                    true
                }
                prepareAsync()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TTS playback failed", t)
            onError()
        }
    }

    fun stop() {
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        player = null
    }

    companion object {
        private const val TAG = "IndicTtsPlayer"
    }
}
