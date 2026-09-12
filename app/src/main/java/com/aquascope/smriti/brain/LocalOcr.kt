package com.aquascope.smriti.brain

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.aquascope.halo.SmritiLightState
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object LocalOcr {
    suspend fun read(context: Context, uri: Uri): String = withContext(Dispatchers.Default) {
        val image = InputImage.fromFilePath(context, uri)
        recognize(image)
    }

    suspend fun readBitmap(bitmap: Bitmap): String = withContext(Dispatchers.Default) {
        recognize(InputImage.fromBitmap(bitmap, 0))
    }

    private suspend fun recognize(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            client.process(image)
                .addOnSuccessListener { result ->
                    if (cont.isActive) cont.resume(result.text.orEmpty())
                    client.close()
                }
                .addOnFailureListener { e ->
                    if (cont.isActive) cont.resumeWithException(e)
                    client.close()
                }
        }
}

fun TaxonomyParser.Kind.toHalo(): SmritiLightState = when (this) {
    TaxonomyParser.Kind.HEALTH -> SmritiLightState.HEALTH_OK
    TaxonomyParser.Kind.GAME -> SmritiLightState.GAME_KILL
    TaxonomyParser.Kind.ACOUSTIC -> SmritiLightState.GUARDIAN
    TaxonomyParser.Kind.UNKNOWN -> SmritiLightState.EXTRACTION
    else -> SmritiLightState.EXTRACTION
}
