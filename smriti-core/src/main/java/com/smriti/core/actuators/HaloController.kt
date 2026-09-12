package com.smriti.core.actuators

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Monster Halo RGB states (SPEC §2.3).
 * [color] is the ARGB value sent to the vendor broadcast, the fallback
 * notification light, and (via [HardwareActuators.haloState]) the in-app
 * edge shader. [pattern] is the vendor pattern token: pulse/strobe/solid.
 */
enum class HaloState(
    val color: Int,
    val pattern: String,
    val channelId: String,
    val channelLabel: String,
    val notifyId: Int,
) {
    STANDBY_EMERALD(0xFF10B981.toInt(), "solid", "smriti_halo_standby", "Halo standby", 4101),
    EXTRACTION_CYAN(0xFF00F0FF.toInt(), "pulse", "smriti_halo_extraction", "Halo extraction", 4102),
    GAME_CRIMSON(0xFFFF003C.toInt(), "pulse", "smriti_halo_game", "Halo game", 4103),
    CONFIRM_AMBER(0xFFFFB800.toInt(), "solid", "smriti_halo_confirm", "Halo confirm", 4104),
    EMERGENCY_STROBE(0xFFFFFFFF.toInt(), "strobe", "smriti_halo_emergency", "Halo emergency", 4105);
}

/**
 * Hardware actuator facade (SPEC §2.3).
 *
 * [setHalo] drives the iQOO Monster Halo through three parallel paths:
 *  1. [haloState] StateFlow consumed by the in-app UI edge shader.
 *  2. A vendor broadcast intent ([HALO_ACTION], extras [EXTRA_COLOR]/[EXTRA_PATTERN])
 *     guarded by try/catch in case the vendor receiver is absent.
 *  3. A fallback: one lazily-created low-importance NotificationChannel per state
 *     with [NotificationChannel.enableLights] + per-state light color and public
 *     lockscreen visibility, plus a posted notification so the LED/lights fire.
 *
 * Haptics and IR are delegated to [Haptics] and [IrBlaster] so the §2.3 surface
 * stays exactly: haloState / setHalo / haptic / irBlast.
 */
class HardwareActuators(context: Context) {

    private val appContext: Context = context.applicationContext

    private val _haloState = MutableStateFlow(HaloState.STANDBY_EMERALD)
    val haloState: StateFlow<HaloState> = _haloState.asStateFlow()

    private val haptics = Haptics(appContext)
    private val irBlaster = IrBlaster(appContext)

    @Volatile
    private var haloChannelsCreated = false

    fun setHalo(state: HaloState) {
        _haloState.value = state
        broadcastVendorIntent(state)
        postFallbackLight(state)
    }

    fun haptic(event: HapticEvent) = haptics.haptic(event)

    fun irBlast(frequencyHz: Int, pattern: IntArray): Boolean =
        irBlaster.irBlast(frequencyHz, pattern)

    // --- Path 2: vendor broadcast -------------------------------------------

    private fun broadcastVendorIntent(state: HaloState) {
        val intent = Intent(HALO_ACTION).apply {
            putExtra(EXTRA_COLOR, state.color)
            putExtra(EXTRA_PATTERN, state.pattern)
            haloPackage?.let { setPackage(it) }
        }
        try {
            appContext.sendBroadcast(intent)
        } catch (e: Exception) {
            // Vendor halo receiver absent/blocked — fallback light path still runs.
        }
    }

    // --- Path 3: notification-light fallback --------------------------------

    private fun ensureHaloChannels(nm: NotificationManager) {
        if (haloChannelsCreated) return
        synchronized(this) {
            if (haloChannelsCreated) return
            for (state in HaloState.entries) {
                val channel = NotificationChannel(
                    state.channelId,
                    state.channelLabel,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "SMRITI halo fallback light: ${state.name}"
                    enableLights(true)
                    lightColor = state.color
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                    setSound(null, null)
                    enableVibration(false)
                }
                nm.createNotificationChannel(channel)
            }
            haloChannelsCreated = true
        }
    }

    private fun postFallbackLight(state: HaloState) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        try {
            ensureHaloChannels(nm)
            val strobe = state.pattern == PATTERN_STROBE
            val notification = Notification.Builder(appContext, state.channelId)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("SMRITI Halo")
                .setContentText(state.name)
                .setLights(state.color, if (strobe) 120 else 1000, if (strobe) 120 else 1000)
                .setOngoing(false)
                .build()
            nm.notify(state.notifyId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted yet (runtime permission owned by UI) — flow + broadcast still delivered.
        } catch (e: Exception) {
            // Notification pipeline unavailable — never crash on actuator path.
        }
    }

    companion object {
        /** Vendor halo broadcast action — configurable if the OEM SDK uses a different action. */
        const val HALO_ACTION = "com.iqoo.smriti.HALO_STATE"
        const val EXTRA_COLOR = "color"
        const val EXTRA_PATTERN = "pattern"

        const val PATTERN_PULSE = "pulse"
        const val PATTERN_STROBE = "strobe"
        const val PATTERN_SOLID = "solid"

        /**
         * Optional package guard for the vendor broadcast (e.g. the OEM halo service
         * package). Null = unscoped broadcast. Configurable at startup.
         */
        @Volatile
        var haloPackage: String? = null
    }
}
