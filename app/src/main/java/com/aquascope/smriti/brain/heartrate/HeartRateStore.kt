package com.aquascope.smriti.brain.heartrate

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Persists recent wellness BPM readings (no camera frames). */
object HeartRateStore {
    private const val FILE = "heart_rate_history.json"
    private const val MAX = 50
    private val gson = Gson()

    fun save(context: Context, measurement: HeartRateMeasurement) {
        val all = load(context).toMutableList()
        all.add(0, measurement)
        while (all.size > MAX) all.removeAt(all.lastIndex)
        runCatching {
            file(context).writeText(gson.toJson(all))
        }
    }

    fun load(context: Context): List<HeartRateMeasurement> {
        val f = file(context)
        if (!f.isFile) return emptyList()
        return runCatching {
            val type = object : TypeToken<List<HeartRateMeasurement>>() {}.type
            gson.fromJson<List<HeartRateMeasurement>>(f.readText(), type).orEmpty()
        }.getOrElse { emptyList() }
    }

    private fun file(context: Context) =
        File(context.filesDir, "aquascope_data").also { it.mkdirs() }.resolve(FILE)
}
