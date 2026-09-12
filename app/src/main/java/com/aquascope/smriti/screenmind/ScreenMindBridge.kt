package com.aquascope.smriti.screenmind

import android.content.Context
import android.content.SharedPreferences
import com.aquascope.smriti.model.PhysicalEvent

/**
 * Singleton bridge managing AquaScope's connection and episodic sync to ScreenMind desktop AI memory.
 */
class ScreenMindBridge private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val client: ScreenMindClient = ScreenMindClient(getServerUrl())

    @Volatile
    var lastStatus: ScreenMindStatus? = null
        private set

    fun getServerUrl(): String {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_URL) ?: DEFAULT_URL
    }

    fun setServerUrl(url: String) {
        val clean = url.trim()
        prefs.edit().putString(KEY_SERVER_URL, clean).apply()
        client.updateBaseUrl(clean)
    }

    fun isAutoSyncEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_SYNC, true)
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_SYNC, enabled).apply()
    }

    suspend fun probeConnection(): ScreenMindStatus {
        val res = client.checkHealth()
        val status = res.getOrElse {
            ScreenMindStatus(
                connected = false,
                serverUrl = getServerUrl(),
                version = "1.0",
                modelReady = false,
                message = it.message ?: "Connection failed"
            )
        }
        lastStatus = status
        return status
    }

    suspend fun syncScan(event: PhysicalEvent): Result<Boolean> {
        return client.syncPhysicalEvent(event)
    }

    companion object {
        private const val PREFS_NAME = "screenmind_bridge_prefs"
        private const val KEY_SERVER_URL = "screenmind_server_url"
        private const val KEY_AUTO_SYNC = "screenmind_auto_sync"
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
