package com.aquascope.halo

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.provider.Settings
import android.util.Log
import com.aquascope.AquaScopeApp

/**
 * Low-level access to OriginOS `vivo_light_service` (Monster Halo).
 *
 * On Android 9+ [ServiceManager] is a hidden API — [AquaScopeApp] applies
 * HiddenApiBypass before we look up the binder.
 */
class VivoLightServiceClient {

    private val descriptor = "vivo.app.vivolight.IVivoLightManager"

    private val txHasLight = 1
    private val txGetLightCase = 2
    private val txGetLightType = 3
    private val txStartLightJson = 5
    private val txStopLightById = 6
    private val txStopLightAll = 8
    private val txGetBreathingMode = 10
    private val txSetBreathingMode = 11

    private val clientToken = Binder()

    @Volatile private var cachedBinder: IBinder? = null
    @Volatile private var resolvedName: String? = null

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastStartId: Int = -1
        private set

    @Volatile var lastLookupDetail: String = "not probed"
        private set

    fun binderPresent(): Boolean = binder() != null

    fun available(): Boolean = binderPresent()

    fun hasLight(): Boolean = txBool(txHasLight)

    fun getLightCase(): Int = txInt(txGetLightCase)

    fun getLightType(): Int = txInt(txGetLightType)

    fun getBreathingMode(): Int = txInt(txGetBreathingMode)

    fun setBreathingMode(on: Boolean): Boolean =
        txVoid(txSetBreathingMode) { it.writeInt(if (on) 1 else 0) }

    fun ensureBreathingEnabled(): Boolean {
        if (!binderPresent()) return false
        // Always request ON — getBreathingMode can stay 0 until user enables
        // OriginOS "Rear light effects" even when setBreathingMode succeeds.
        setBreathingMode(true)
        val mode = getBreathingMode()
        Log.i(TAG, "breathing mode after set=$mode")
        return true
    }

    /**
     * Best-effort enable of OriginOS rear atmosphere light toggle.
     * May silently fail without WRITE_SECURE_SETTINGS — UI still opens settings.
     */
    fun tryEnableSystemToggle(context: Context): String {
        return try {
            val cr = context.contentResolver
            val key = "vivo_rear_atmosphere_light_effect_toggle_state"
            Settings.Secure.putInt(cr, key, 1)
            val v = Settings.Secure.getInt(cr, key, -1)
            "secure.$key=$v"
        } catch (t: Throwable) {
            "secure toggle blocked: ${t.message}"
        }
    }

    fun startLightJson(packageName: String, json: String): Int {
        val id = txInt(txStartLightJson) { p ->
            p.writeStrongBinder(clientToken)
            p.writeString(packageName)
            p.writeString(json)
        }
        lastStartId = id
        if (id <= 0) {
            lastError = "startLightJson returned $id"
            Log.w(TAG, "start failed id=$id json=${json.take(180)}")
        } else {
            lastError = null
            Log.i(TAG, "start ok id=$id name=$resolvedName")
        }
        return id
    }

    fun stopById(id: Int): Boolean =
        txVoid(txStopLightById) { it.writeInt(id) }

    fun stopAll(): Boolean = txVoid(txStopLightAll)

    fun diagnose(): String {
        val b = binder()
        if (b == null) {
            return "binder=MISSING · $lastLookupDetail" +
                (lastError?.let { " · $it" } ?: "")
        }
        return buildString {
            append("binder=OK")
            append(" svc=${resolvedName ?: "?"}")
            append(" hasLight=${hasLight()}")
            append(" case=${getLightCase()}")
            append(" type=${getLightType()}")
            append(" breathing=${getBreathingMode()}")
            append(" lastId=$lastStartId")
            lastError?.let { append(" err=$it") }
        }
    }

    fun forceReprobe() {
        cachedBinder = null
        resolvedName = null
        lastLookupDetail = "reprobe"
        binder()
    }

    private fun binder(): IBinder? {
        cachedBinder?.let { if (it.isBinderAlive) return it }
        AquaScopeApp.unlockHiddenApis()
        val found = lookupBinder()
        cachedBinder = found
        return found
    }

    private fun lookupBinder(): IBinder? {
        val sm = try {
            Class.forName("android.os.ServiceManager")
        } catch (t: Throwable) {
            lastLookupDetail = "ServiceManager class missing: ${t.message}"
            lastError = lastLookupDetail
            return null
        }

        val candidates = linkedSetOf(
            "vivo_light_service",
            "vivolight",
            "vivo_light",
            "IVivoLightManager",
            "vendor.vivo.hardware.vlight.IVlight/default"
        )

        // Discover any *light* / *vlight* service names via listServices
        try {
            val list = sm.getMethod("listServices").invoke(null) as? Array<*>
            list?.mapNotNull { it as? String }
                ?.filter {
                    val n = it.lowercase()
                    n.contains("vivo_light") || n.contains("vivolight") ||
                        n.contains("vlight") || n == "vivo_light_service"
                }
                ?.forEach { candidates.add(it) }
            lastLookupDetail = "listServices=${list?.size ?: -1} candidates=$candidates"
        } catch (t: Throwable) {
            lastLookupDetail = "listServices blocked: ${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, lastLookupDetail)
        }

        for (name in candidates) {
            val b = getService(sm, name) ?: checkService(sm, name)
            if (b != null) {
                // Prefer the Java framework service over the vendor HAL stub
                if (name == "vivo_light_service" || name.contains("vivolight", true) ||
                    descriptorMatches(b)
                ) {
                    resolvedName = name
                    lastLookupDetail = "resolved via $name"
                    lastError = null
                    return b
                }
                // Keep HAL as last resort only if nothing else works
                if (resolvedName == null && name.contains("vlight")) {
                    resolvedName = name
                    lastLookupDetail = "resolved HAL $name (may not speak IVivoLightManager)"
                }
            }
        }

        // Return HAL only if we cached its name and nothing better found
        resolvedName?.let { name ->
            val b = getService(sm, name) ?: checkService(sm, name)
            if (b != null) return b
        }

        if (lastError == null) {
            lastError = "getService returned null for all candidates"
        }
        lastLookupDetail = (lastLookupDetail + " · no binder").trim()
        return null
    }

    private fun getService(sm: Class<*>, name: String): IBinder? = try {
        sm.getMethod("getService", String::class.java).invoke(null, name) as IBinder?
    } catch (t: Throwable) {
        lastError = "getService($name): ${t.javaClass.simpleName}: ${t.message}"
        null
    }

    private fun checkService(sm: Class<*>, name: String): IBinder? = try {
        sm.getMethod("checkService", String::class.java).invoke(null, name) as IBinder?
    } catch (_: Throwable) {
        null
    }

    private fun descriptorMatches(b: IBinder): Boolean = try {
        b.interfaceDescriptor == descriptor
    } catch (_: Throwable) {
        false
    }

    private inline fun <T> tx(code: Int, write: (Parcel) -> Unit = {}, read: (Parcel) -> T): T? {
        val b = binder() ?: return null
        // Vendor HAL won't understand our AIDL codes — skip if wrong interface
        if (resolvedName?.contains("IVlight") == true) {
            lastError = "bound to vendor HAL, not IVivoLightManager — open Rear Light settings"
            return null
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            write(data)
            val ok = b.transact(code, data, reply, 0)
            if (!ok) {
                lastError = "transact($code) returned false"
                return null
            }
            reply.readException()
            read(reply)
        } catch (t: Throwable) {
            lastError = "transact($code): ${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, "transact $code failed", t)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun txInt(code: Int, write: (Parcel) -> Unit = {}): Int =
        tx(code, write) { it.readInt() } ?: -1

    private fun txBool(code: Int, write: (Parcel) -> Unit = {}): Boolean =
        tx(code, write) { it.readInt() != 0 } ?: false

    private fun txVoid(code: Int, write: (Parcel) -> Unit = {}): Boolean =
        tx(code, write) { true } ?: false

    companion object {
        private const val TAG = "VivoLight"

        fun rearLightSettingsIntent(): Intent {
            // Prefer explicit component — works from app process on OriginOS
            return Intent().setClassName(
                "com.android.settings",
                "com.android.settings.Settings\$VivoRearLightEffectsSettingsActivity"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        fun rearAtmosphereSettingsIntent(): Intent {
            return Intent("com.vivo.settings.REAR_LIGHT_EFFECTS_ATMOSPHERIC_SETTINGS")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}
