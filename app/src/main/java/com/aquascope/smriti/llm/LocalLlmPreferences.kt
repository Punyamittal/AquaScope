package com.aquascope.smriti.llm

import android.content.Context

class LocalLlmPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).commit()
        }

    var hfAccessToken: String
        get() = prefs.getString(KEY_HF_TOKEN, "").orEmpty()
        set(value) {
            val token = LocalModelDownloadPolicy.extractHfToken(value).orEmpty()
            prefs.edit().putString(KEY_HF_TOKEN, token).commit()
        }

    fun hasHfAccessToken(): Boolean = hfAccessToken.isNotBlank()

    fun maskedToken(): String = LocalModelDownloadPolicy.maskToken(hfAccessToken)

    fun clearHfAccessToken() {
        prefs.edit().remove(KEY_HF_TOKEN).commit()
    }

    companion object {
        private const val PREFS = "smriti_local_llm"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_HF_TOKEN = "hf_access_token"
    }
}
