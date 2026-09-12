package com.aquascope.smriti.llm

import android.content.Context

class LocalLlmPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var hfAccessToken: String
        get() = prefs.getString(KEY_HF_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HF_TOKEN, value.trim()).apply()

    fun hasHfAccessToken(): Boolean = hfAccessToken.isNotBlank()

    companion object {
        private const val PREFS = "smriti_local_llm"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HF_TOKEN = "hf_access_token"
    }
}
