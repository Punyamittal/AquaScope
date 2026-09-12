package com.aquascope.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MicrophoneInfo
import android.os.Build
import android.util.Log

/**
 * iQOO 15 has dual stereo speakers and several mics. AquaScope contact sensing
 * must use only the bottom-firing loudspeaker and the bottom primary mic —
 * not the earpiece / top speaker or the top / rear mics.
 *
 * Stereo mapping in portrait: landscape-right = phone bottom, so the chirp
 * is written to the right channel and the left (top) channel is silenced.
 */
object IqooAudioRouting {

    private const val TAG = "IqooAudio"

    /** Portrait dual-stereo on iQOO 15: right = bottom speaker, left = top/earpiece. */
    const val BOTTOM_SPEAKER_IS_RIGHT = true

    fun pickBottomSpeaker(audioManager: AudioManager): AudioDeviceInfo? {
        val outputs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val speakers = outputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        val named = speakers.find { looksLikeBottom(it) }
        val picked = named ?: speakers.firstOrNull() ?: outputs.find {
            it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        }
        Log.i(TAG, "Bottom speaker: ${describe(picked)}")
        return picked
    }

    fun pickBottomMic(audioManager: AudioManager): AudioDeviceInfo? {
        val inputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        val named = inputs.find { looksLikeBottom(it) }
        if (named != null) {
            Log.i(TAG, "Bottom mic (address): ${describe(named)}")
            return named
        }
        val byGeometry = pickMicByGeometry(audioManager, inputs)
        if (byGeometry != null) {
            Log.i(TAG, "Bottom mic (geometry): ${describe(byGeometry)}")
            return byGeometry
        }
        val picked = inputs.firstOrNull { !looksLikeNonBottomMic(it) } ?: inputs.firstOrNull()
        Log.i(TAG, "Bottom mic (fallback): ${describe(picked)}")
        return picked
    }

    fun pinPlayer(track: AudioTrack, speaker: AudioDeviceInfo?): Boolean {
        if (speaker == null) return false
        return try {
            track.setPreferredDevice(speaker)
        } catch (_: Exception) {
            false
        }
    }

    fun pinRecorder(record: AudioRecord, mic: AudioDeviceInfo?): Boolean {
        if (mic == null) return false
        return try {
            record.setPreferredDevice(mic)
        } catch (_: Exception) {
            false
        }
    }

    fun muteTopSpeaker(track: AudioTrack) {
        try {
            @Suppress("DEPRECATION")
            if (BOTTOM_SPEAKER_IS_RIGHT) {
                track.setStereoVolume(0f, 1f)
            } else {
                track.setStereoVolume(1f, 0f)
            }
        } catch (_: Exception) {
        }
    }

    fun monoToBottomSpeakerStereo(mono: ShortArray): ShortArray {
        val stereo = ShortArray(mono.size * 2)
        for (i in mono.indices) {
            if (BOTTOM_SPEAKER_IS_RIGHT) {
                stereo[i * 2] = 0
                stereo[i * 2 + 1] = mono[i]
            } else {
                stereo[i * 2] = mono[i]
                stereo[i * 2 + 1] = 0
            }
        }
        return stereo
    }

    fun clearEarpieceRoute(audioManager: AudioManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: Exception) {
            }
        }
    }

    private fun pickMicByGeometry(
        audioManager: AudioManager,
        inputs: List<AudioDeviceInfo>
    ): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || inputs.isEmpty()) return null
        return try {
            val mics = audioManager.microphones.filter { mic ->
                inputs.any { it.address == mic.address }
            }
            val withY = mics.filter { !it.position.y.isNaN() }
            val bottom = when {
                withY.isNotEmpty() ->
                    // Android coords: origin at bottom-left-back in portrait, so lowest Y is bottom.
                    withY.minByOrNull { it.position.y }
                else ->
                    mics.minByOrNull { it.indexInTheGroup } ?: mics.firstOrNull()
            }
            inputs.find { it.address == bottom?.address }
        } catch (_: Exception) {
            null
        }
    }

    private fun looksLikeBottom(device: AudioDeviceInfo): Boolean {
        val blob = deviceLabel(device)
        return blob.contains("bottom")
    }

    private fun looksLikeNonBottomMic(device: AudioDeviceInfo): Boolean {
        val blob = deviceLabel(device)
        return listOf("top", "front", "back", "rear", "cam", "ear").any { blob.contains(it) }
    }

    private fun deviceLabel(device: AudioDeviceInfo): String {
        val address = device.address.orEmpty()
        val name = device.productName?.toString().orEmpty()
        return "$address $name".lowercase()
    }

    private fun describe(device: AudioDeviceInfo?): String {
        if (device == null) return "none"
        return "id=${device.id} type=${device.type} addr=${device.address} name=${device.productName}"
    }
}
