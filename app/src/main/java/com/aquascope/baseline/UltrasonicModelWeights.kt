package com.aquascope.baseline

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * Loads Fisher/grid-refined feature weights from assets/ultrasonic/trained_weights.json
 * (produced by tools/train_ultrasonic_scorer.py from on-device locations.json).
 */
object UltrasonicModelWeights {
    private const val TAG = "UltrasonicWeights"
    private const val ASSET = "ultrasonic/trained_weights.json"

    @Volatile private var loaded = false
    @Volatile var featureWeights: DoubleArray? = null
        private set
    @Volatile var featureScales: DoubleArray? = null
        private set
    @Volatile var dryRef: Double? = null
        private set
    @Volatile var moistRef: Double? = null
        private set
    @Volatile var dryPercentile: Double = 0.70
        private set
    @Volatile var moistPercentile: Double = 0.30
        private set
    @Volatile var distanceBlend: Double = 0.0
        private set
    @Volatile var useMedian: Boolean = true
        private set

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
                val o = JSONObject(text)
                val w = o.getJSONArray("featureWeights")
                val s = o.getJSONArray("featureScales")
                if (w.length() == 5 && s.length() == 5) {
                    val weights = DoubleArray(5) { w.getDouble(it) }
                    val scales = DoubleArray(5) { s.getDouble(it) }
                    featureWeights = weights
                    featureScales = scales
                    if (o.has("dryRef")) dryRef = o.getDouble("dryRef")
                    if (o.has("moistRef")) moistRef = o.getDouble("moistRef")
                    if (o.has("dryPercentile")) dryPercentile = o.getDouble("dryPercentile")
                    if (o.has("moistPercentile")) moistPercentile = o.getDouble("moistPercentile")
                    if (o.has("distanceBlend")) distanceBlend = o.getDouble("distanceBlend")
                    if (o.has("useMedian")) useMedian = o.getBoolean("useMedian")
                    Log.i(
                        TAG,
                        "loaded trained weights w=${weights.contentToString()} " +
                            "blend=$distanceBlend median=$useMedian " +
                            "dq=$dryPercentile mq=$moistPercentile"
                    )
                }
                Unit
            } catch (t: Throwable) {
                Log.i(TAG, "no trained weights asset (${t.message}) - using defaults")
            } finally {
                loaded = true
            }
        }
    }
}
