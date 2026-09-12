package com.aquascope.smriti.screenmind

import android.content.Context
import android.content.SharedPreferences

/**
 * Singleton bridge managing AquaScope's connection to ScreenMind desktop AI memory.
 */
class ScreenMindBridge private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val client: ScreenMindClient = ScreenMindClient(getServerUrl())

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    fun setServerUrl(url: String) {
        val clean = url.trim()
        prefs.edit().putString(KEY_SERVER_URL, clean).apply()
        client.updateBaseUrl(clean)
    }

    companion object {
        private const val PREFS_NAME = "screenmind_bridge_prefs"
        private const val KEY_SERVER_URL = "screenmind_server_url"
        const val DEFAULT_URL = "http://10.0.2.2:7777"

        @Volatile
        private var instance: ScreenMindBridge? = null

        fun get(context: Context): ScreenMindBridge {
            return instance ?: synchronized(this) {
                instance ?: ScreenMindBridge(context.applicationContext).also { instance = it }
            }
        }
    }
}
