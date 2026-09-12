package com.aquascope.halo

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import com.aquascope.AquaScopeApp
import java.lang.reflect.InvocationTargetException

/**
 * OriginOS Monster Halo client for iQOO 15.
 *
 * Do not hard-code AIDL transaction numbers — they shifted on OriginOS 6.
 * Prefer [com.vivo.framework.vivolight.VivoLightManager] via reflection.
 */
class VivoLightServiceClient {

    private val clientToken = Binder()

    @Volatile private var cachedBinder: IBinder? = null
    @Volatile private var resolvedName: String? = null
    @Volatile private var manager: Any? = null
    @Volatile private var aidl: Any? = null

    @Volatile var lastError: String? = null
        private set

    @Volatile var lastStartId: Int = -1
        private set

    @Volatile var lastLookupDetail: String = "not probed"
        private set

    @Volatile var lastStartPath: String = ""
        private set

    fun binderPresent(): Boolean = manager() != null || binder() != null

    fun available(): Boolean = binderPresent()

    fun hasLight(): Boolean = boolCall("hasLight")

    fun getLightCase(): Int = intCall("getLightCase")

    fun getLightType(): Int = intCall("getLightType")

    fun getBreathingMode(): Int = intCall("getBreathingLightOpenMode")

    fun setBreathingMode(on: Boolean): Boolean =
        voidCall("setBreathingLightOpenMode", if (on) 1 else 0)

    fun ensureBreathingEnabled(): Boolean {
        if (!binderPresent()) return false
        setBreathingMode(true)
        val lightType = getLightType().takeIf { it > 0 } ?: 2000
        voidCall("setEffectType", lightType, 1)
        voidCall("setEffectSubType", lightType, 1, 2)
        val mode = getBreathingMode()
        Log.i(TAG, "breathing mode after set=$mode type=$lightType hasLight=${hasLight()}")
        return true
    }

    fun tryEnableSystemToggle(context: Context): String {
        val keys = listOf(
            "vivo_rear_atmosphere_light_effect_toggle_state",
            "vivo_rear_light_effect_toggle_state",
            "rear_light_effect_enable",
            "vivo_breathing_light_open",
            "breathing_light_open"
        )
        val bits = keys.map { key ->
            try {
                Settings.Secure.putInt(context.contentResolver, key, 1)
                val v = Settings.Secure.getInt(context.contentResolver, key, -1)
                "$key=$v"
            } catch (t: Throwable) {
                try {
                    Settings.System.putInt(context.contentResolver, key, 1)
                    val v = Settings.System.getInt(context.contentResolver, key, -1)
                    "sys.$key=$v"
                } catch (t2: Throwable) {
                    "$key blocked"
                }
            }
        }
        return bits.joinToString(" · ")
    }

    fun startLightJson(packageName: String, json: String): Int {
        lastError = null
        manager()
        val lightType = getLightType().takeIf { it > 0 } ?: 2000
        val lightCase = getLightCase().takeIf { it > 0 } ?: 0
        val id = firstPositive(
            "startLight(type,json)" to { invoke(manager, "startLight", lightType, json) },
            "startLight(case,json)" to { invoke(manager, "startLight", lightCase, json) },
            "startLightForGame(pkg,json)" to { invoke(manager, "startLightForGame", packageName, json) },
            "startLight(pkg,json)" to { invoke(manager, "startLight", packageName, json) },
            "startLightByDuration" to { invoke(manager, "startLightByDuration", lightType, json, 60_000) },
            "startLightByTimes" to { invoke(manager, "startLightByTimes", lightType, json, 120, true) },
            "startLight(record)" to { startViaRecord(json) },
            "aidl.startLightJson" to { startViaAidl("startLightJson", packageName, json) },
            "aidl.startLightForGame" to { startViaAidl("startLightForGame", packageName, json) }
        )
        lastStartId = id
        if (id > 0) {
            invoke(manager, "updateBrightnessById", id, 100)
            Log.i(TAG, "start ok id=$id via $lastStartPath type=$lightType")
        } else {
            lastError = "all start paths failed · $lastLookupDetail"
            Log.w(TAG, "start failed json=${json.take(180)} err=$lastError")
        }
        return id
    }

    fun stopById(id: Int): Boolean {
        if (id <= 0) return false
        voidCall("stopLightById", id)
        invoke(aidl(), "stopLightByIdFromUser", id)
        return true
    }

    fun stopAll(): Boolean {
        voidCall("stopLightAll")
        invoke(aidl(), "stopLightAllFromUser")
        return true
    }

    fun diagnose(): String {
        manager()
        return buildString {
            append(if (manager != null) "mgr=OK" else "mgr=MISSING")
            append(" binder=${if (binder() != null) "OK" else "MISSING"}")
            append(" svc=${resolvedName ?: "?"}")
            append(" hasLight=${hasLight()}")
            append(" case=${getLightCase()}")
            append(" type=${getLightType()}")
            append(" breathing=${getBreathingMode()}")
            append(" lastId=$lastStartId")
            if (lastStartPath.isNotBlank()) append(" via=$lastStartPath")
            lastError?.let { append(" err=$it") }
        }
    }

    fun forceReprobe() {
        cachedBinder = null
        resolvedName = null
        manager = null
        aidl = null
        lastLookupDetail = "reprobe"
        manager()
        binder()
    }

    private fun manager(): Any? {
        manager?.let { return it }
        AquaScopeApp.unlockHiddenApis()
        manager = try {
            val cls = Class.forName("com.vivo.framework.vivolight.VivoLightManager")
            cls.getMethod("getInstance").invoke(null)
        } catch (t: Throwable) {
            lastLookupDetail = "VivoLightManager missing: ${t.javaClass.simpleName}: ${t.message}"
            Log.w(TAG, lastLookupDetail)
            null
        }
        if (manager != null) lastLookupDetail = "VivoLightManager.getInstance"
        return manager
    }

    private fun aidl(): Any? {
        aidl?.let { return it }
        val b = binder() ?: return null
        aidl = try {
            Class.forName("vivo.app.vivolight.IVivoLightManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, b)
        } catch (t: Throwable) {
            Log.w(TAG, "asInterface failed: ${t.message}")
            null
        }
        return aidl
    }

    private fun startViaRecord(json: String): Any? {
        val recCls = try {
            Class.forName("vivo.app.vivolight.VivoLightRecord")
        } catch (_: Throwable) {
            return null
        }
        val ctor = recCls.constructors.firstOrNull { it.parameterTypes.size == 6 } ?: return null
        val lightCase = getLightCase().takeIf { it > 0 } ?: 0
        val record = ctor.newInstance(lightCase, json, 100, false, true, 1)
        return invoke(manager, "startLight", record)
    }

    private fun startViaAidl(method: String, packageName: String, json: String): Any? {
        val svc = aidl() ?: return null
        return invoke(svc, method, clientToken, packageName, json)
    }

    private fun firstPositive(vararg attempts: Pair<String, () -> Any?>): Int {
        for ((name, fn) in attempts) {
            val raw = try {
                fn()
            } catch (t: Throwable) {
                val msg = "$name ${root(t).javaClass.simpleName}: ${root(t).message}"
                lastError = msg
                Log.w(TAG, msg)
                continue
            }
            val id = (raw as? Int) ?: continue
            if (id > 0) {
                lastStartPath = name
                lastError = null
                return id
            }
            lastError = "$name returned $id"
        }
        return -1
    }

    private fun intCall(name: String): Int = (invoke(manager(), name) as? Int) ?: -1

    private fun boolCall(name: String): Boolean = (invoke(manager(), name) as? Boolean) ?: false

    private fun voidCall(name: String, vararg args: Any?): Boolean = invoke(manager(), name, *args) != UNSET

    private fun invoke(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return UNSET
        val methods = target.javaClass.methods.filter { it.name == name && it.parameterTypes.size == args.size }
        var last: Throwable? = null
        for (m in methods) {
            try {
                return m.invoke(target, *args)
            } catch (t: Throwable) {
                last = root(t)
            }
        }
        if (methods.isEmpty()) {
            lastError = "$name not found on ${target.javaClass.name}"
        } else {
            lastError = "$name: ${last?.javaClass?.simpleName}: ${last?.message}"
        }
        return UNSET
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
        for (name in listOf("vivo_light_service", "vivolight", "vivo_light")) {
            val b = try {
                sm.getMethod("getService", String::class.java).invoke(null, name) as IBinder?
            } catch (_: Throwable) {
                null
            }
            if (b != null) {
                resolvedName = name
                return b
            }
        }
        return null
    }

    private fun root(t: Throwable): Throwable =
        (t as? InvocationTargetException)?.cause ?: t

    companion object {
        private const val TAG = "VivoLight"
        private val UNSET = Any()

        fun rearLightSettingsIntent(): Intent {
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
