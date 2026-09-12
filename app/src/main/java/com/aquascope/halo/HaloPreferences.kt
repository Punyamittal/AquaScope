package com.aquascope.halo

import android.content.Context

class HaloPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** Privacy / night — hard disable physical + prefer sim off */
    var nightMode: Boolean
        get() = prefs.getBoolean(KEY_NIGHT, false)
        set(value) = prefs.edit().putBoolean(KEY_NIGHT, value).apply()

    /** 0=Low 1=Medium 2=High */
    var brightnessLevel: Int
        get() = prefs.getInt(KEY_BRIGHTNESS, 1).coerceIn(0, 2)
        set(value) = prefs.edit().putInt(KEY_BRIGHTNESS, value.coerceIn(0, 2)).apply()

    /** Always show on-screen halo indicator (useful even when hardware works) */
    var showSimulator: Boolean
        get() = prefs.getBoolean(KEY_SIM, true)
        set(value) = prefs.edit().putBoolean(KEY_SIM, value).apply()

    fun brightnessScale(): Float = when (brightnessLevel) {
        0 -> 0.35f
        2 -> 0.95f
        else -> 0.65f
    }

    fun brightnessLabel(): String = when (brightnessLevel) {
        0 -> "Low"
        2 -> "High"
        else -> "Medium"
    }

    companion object {
        private const val PREFS = "smriti_halo"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_NIGHT = "night"
        private const val KEY_BRIGHTNESS = "brightness"
        private const val KEY_SIM = "show_sim"
    }
}
