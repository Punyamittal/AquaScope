package com.smriti.core.memory

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * On-device OCR for SMRITI (SPEC section 2.1).
 *
 * Uses ML Kit's **bundled** Latin text recognizer
 * (com.google.mlkit:text-recognition:16.0.1): the recognition model ships inside
 * the APK, so recognition is 100% offline — no Play Services dynamic delivery,
 * no network. This honors the project's hard NO-INTERNET constraint.
 *
 * Usage:
 *   val ocr = OcrProcessor()
 *   ocr.process(bitmap) { text -> /* feed into SmritiMemoryEngine.insertRaw(text, "ocr") */ }
 *   ... ocr.close()  // when the owner is destroyed
 */
class OcrProcessor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Recognize text in [bitmap] asynchronously; [onResult] fires with the full
     * recognized text (empty string on failure — degrade gracefully, never crash).
     *
     * The [InputImage] wraps the caller-owned bitmap (rotation 0) and needs no
     * explicit cleanup: unlike the CameraX ImageProxy path there is no buffer to
     * release here — the bitmap itself is recycled/closed by its producer. The only
     * native resource held by this class is the recognizer, released via [close].
     */
    fun process(bitmap: Bitmap, onResult: (String) -> Unit) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText -> onResult(visionText.text) }
            .addOnFailureListener { onResult("") }
    }

    /** Release the recognizer's native resources. Idempotent; call from the owner's teardown. */
    fun close() {
        recognizer.close()
    }
}
