package com.smriti.brain.telemetry

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.provider.Settings
import java.util.concurrent.atomic.AtomicLong

class TelemetryMonitor(private val context: Context) {
    private val tokensWindow = AtomicLong(0)
    private val tokensAt = AtomicLong(System.nanoTime())

    fun noteTokens(n: Int) {
        tokensWindow.addAndGet(n.toLong())
    }

    fun snapshot(): TelemetrySnapshot {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level >= 0) (level * 100) / scale.coerceAtLeast(1) else -1
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetworkInfo
        val airGapped = airplane || net == null || !net.isConnected
        val now = System.nanoTime()
        val prev = tokensAt.getAndSet(now)
        val tok = tokensWindow.getAndSet(0)
        val dt = ((now - prev).coerceAtLeast(1)) / 1_000_000_000f
        return TelemetrySnapshot(
            ramUsedMb = ((mem.totalMem - mem.availMem) / (1024 * 1024)),
            ramTotalMb = mem.totalMem / (1024 * 1024),
            ramAvailMb = mem.availMem / (1024 * 1024),
            batteryPct = pct,
            discharging = status != BatteryManager.BATTERY_STATUS_CHARGING &&
                status != BatteryManager.BATTERY_STATUS_FULL,
            tokensPerSec = tok / dt,
            airGapped = airGapped,
            airplaneMode = airplane
        )
    }
}
