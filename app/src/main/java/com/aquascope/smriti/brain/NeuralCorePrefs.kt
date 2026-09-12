package com.aquascope.smriti.brain

import android.content.Context

class NeuralCorePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var guardianOn: Boolean
        get() = prefs.getBoolean(KEY_GUARDIAN, false)
        set(value) = prefs.edit().putBoolean(KEY_GUARDIAN, value).apply()

    var irArmed: Boolean
        get() = prefs.getBoolean(KEY_IR, false)
        set(value) = prefs.edit().putBoolean(KEY_IR, value).apply()

    companion object {
        private const val PREFS = "smriti_neural_core"
        private const val KEY_GUARDIAN = "guardian_on"
        private const val KEY_IR = "ir_armed"
    }
}
