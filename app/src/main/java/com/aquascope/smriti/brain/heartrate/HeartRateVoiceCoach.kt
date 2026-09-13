package com.aquascope.smriti.brain.heartrate

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Speaks short coaching prompts as measurement phases change.
 */
class HeartRateVoiceCoach(context: Context) : TextToSpeech.OnInitListener {

    private val ready = AtomicBoolean(false)
    private var lastPhase: MeasurementPhase? = null
    private var lastSpokenAt = 0L
    private val engine = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            ready.set(false)
            return
        }
        val res = engine.setLanguage(Locale.US)
        ready.set(
            res != TextToSpeech.LANG_MISSING_DATA &&
                res != TextToSpeech.LANG_NOT_SUPPORTED
        )
        engine.setSpeechRate(0.95f)
    }

    fun speakIntro() {
        lastPhase = MeasurementPhase.PLACE_FINGER
        lastSpokenAt = System.currentTimeMillis()
        speak(
            "Starting heart rate measurement. Place your fingertip gently over the rear camera and flashlight. Keep your finger still and cover both completely.",
            flush = true
        )
    }

    fun onPhase(phase: MeasurementPhase, bpm: Int? = null) {
        if (phase == lastPhase) return
        // Avoid chattering on rapid PLACE_FINGER <-> DETECTING flips
        val now = System.currentTimeMillis()
        if (phase == MeasurementPhase.PLACE_FINGER && lastPhase == MeasurementPhase.DETECTING_PULSE &&
            now - lastSpokenAt < 2500L
        ) {
            return
        }
        lastPhase = phase
        lastSpokenAt = now
        when (phase) {
            MeasurementPhase.PLACE_FINGER ->
                speak("Place your fingertip over the camera and flashlight.")
            MeasurementPhase.DETECTING_PULSE ->
                speak("Finger detected. Hold still while I find your pulse.")
            MeasurementPhase.MEASURING ->
                speak("Measuring. Keep your finger still.")
            MeasurementPhase.POOR_SIGNAL ->
                speak("Poor signal. Keep your finger still and completely cover the camera and flashlight.")
            MeasurementPhase.COMPLETE -> {
                val bpmLine = bpm?.let { " Your heart rate is $it beats per minute." }.orEmpty()
                speak("Measurement complete.$bpmLine This is for wellness purposes only.")
            }
            MeasurementPhase.ERROR ->
                speak("Camera or flashlight is unavailable on this device.")
            MeasurementPhase.IDLE -> Unit
        }
    }

    fun stop() {
        runCatching { engine.stop() }
    }

    fun shutdown() {
        runCatching {
            engine.stop()
            engine.shutdown()
        }
        ready.set(false)
    }

    private fun speak(text: String, flush: Boolean = true) {
        if (!ready.get()) return
        val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        engine.speak(text, mode, null, "hr_${System.currentTimeMillis()}")
    }
}
