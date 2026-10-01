package com.xzygis.silentguard.receiver

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.service.MonitorForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.xzygis.silentguard.diagnostics.GuardHealth

/**
 * AlarmManager 兜底唤醒接收器。
 * 定期检查 MonitorForegroundService 是否在运行，如果被杀则重启。
 */
class ServiceWatchdogReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ServiceWatchdog"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val appConfig = AppConfig(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (appConfig.getConfig().isGuardingEnabled && !GuardHealth(context).isAlive()) {
                    GuardHealth.start(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "服务恢复失败: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isServiceRunning(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (MonitorForegroundService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
