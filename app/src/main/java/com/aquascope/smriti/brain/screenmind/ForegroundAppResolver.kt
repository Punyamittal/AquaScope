package com.aquascope.smriti.brain.screenmind

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process

/**
 * Best-effort foreground app label for ScreenMind clips.
 * Needs PACKAGE_USAGE_STATS when available; otherwise returns null.
 */
object ForegroundAppResolver {

    data class ForegroundApp(val packageName: String, val label: String)

    fun current(context: Context): ForegroundApp? {
        val app = context.applicationContext
        if (!hasUsageAccess(app)) return null
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return null
        val end = System.currentTimeMillis()
        val start = end - 60_000L
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            ?: return null
        val top = stats
            .filter { it.lastTimeUsed > 0 && it.packageName != app.packageName }
            .maxByOrNull { it.lastTimeUsed }
            ?: return null
        val label = runCatching {
            val pm = app.packageManager
            val info = pm.getApplicationInfo(top.packageName, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrElse { top.packageName.substringAfterLast('.') }
        return ForegroundApp(top.packageName, label)
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }
}
