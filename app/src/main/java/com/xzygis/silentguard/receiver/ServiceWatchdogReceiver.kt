package com.xzygis.silentguard.receiver

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.service.MonitorForegroundService
import kotlinx.coroutines.runBlocking

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
        val isGuardingEnabled = runBlocking { appConfig.getConfig().isGuardingEnabled }

        if (!isGuardingEnabled) {
            Log.d(TAG, "守护未开启，跳过服务检查")
            return
        }

        if (!isServiceRunning(context)) {
            Log.w(TAG, "检测到守护服务未运行，正在重启...")
            val serviceIntent = Intent(context, MonitorForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } else {
            Log.d(TAG, "守护服务正常运行中")
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
