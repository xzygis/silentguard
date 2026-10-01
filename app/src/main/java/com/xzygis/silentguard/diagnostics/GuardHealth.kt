package com.xzygis.silentguard.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Build
import com.xzygis.silentguard.service.MonitorForegroundService

class GuardHealth(context: Context) {
    private val prefs = context.getSharedPreferences("guard_health", Context.MODE_PRIVATE)
    var lastFix: Long
        get() = prefs.getLong("last_fix", 0)
        set(value) { prefs.edit().putLong("last_fix", value).apply() }
    var heartbeat: Long
        get() = prefs.getLong("heartbeat", 0)
        set(value) { prefs.edit().putLong("heartbeat", value).apply() }
    var startError: String
        get() = prefs.getString("start_error", "").orEmpty()
        set(value) { prefs.edit().putString("start_error", value).apply() }
    fun isAlive(now: Long = System.currentTimeMillis()): Boolean =
        heartbeat > 0 && now - heartbeat in 0..180_000

    companion object {
        fun start(context: Context, fromVisibleActivity: Boolean = false): Boolean {
            val health = GuardHealth(context)
            if (!AppDiagnostics.hasLocationPermission(context) ||
                (!fromVisibleActivity && !AppDiagnostics.hasBackgroundLocationPermission(context))) {
                health.startError = "需要定位权限；请打开应用恢复守护"
                return false
            }
            return try {
                val intent = Intent(context, MonitorForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
                health.startError = ""
                true
            } catch (e: RuntimeException) {
                health.startError = "系统限制后台启动，请打开应用恢复守护"
                false
            }
        }
    }
}
