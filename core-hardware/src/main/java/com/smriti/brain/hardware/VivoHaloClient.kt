package com.smriti.brain.hardware

import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** OriginOS Monster Halo via VivoLightManager — no hardcoded AIDL codes. */
class VivoHaloClient(private val packageName: String) {
    private val token = Binder()

    fun play(json: String): Int {
        unlock()
        val mgr = manager() ?: return -1
        try {
            mgr.javaClass.getMethod("setBreathingLightOpenMode", Int::class.javaPrimitiveType)
                .invoke(mgr, 1)
        } catch (_: Throwable) {
        }
        val methods = mgr.javaClass.methods.filter { it.name == "startLight" && it.parameterTypes.size == 2 }
        for (m in methods) {
            try {
                val id = m.invoke(mgr, packageName, json) as? Int ?: continue
                if (id > 0) return id
            } catch (_: Throwable) {
            }
        }
        return startJson(json)
    }

    private fun startJson(json: String): Int {
        val b = service() ?: return -1
        return try {
            val stub = Class.forName("vivo.app.vivolight.IVivoLightManager\$Stub")
            val api = stub.getMethod("asInterface", IBinder::class.java).invoke(null, b) ?: return -1
            val m = api.javaClass.methods.firstOrNull {
                it.name == "startLightJson" && it.parameterTypes.size == 3
            } ?: return -1
            m.invoke(api, token, packageName, json) as? Int ?: -1
        } catch (t: Throwable) {
            Log.w(TAG, "startJson failed: ${t.message}")
            -1
        }
    }

    private fun manager(): Any? = try {
        Class.forName("com.vivo.framework.vivolight.VivoLightManager")
            .getMethod("getInstance").invoke(null)
    } catch (_: Throwable) {
        null
    }

    private fun service(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        sm.getMethod("getService", String::class.java).invoke(null, "vivo_light_service") as IBinder?
    } catch (_: Throwable) {
        null
    }

    private fun unlock() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("L")
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        private const val TAG = "SmritiHalo"
    }
}
