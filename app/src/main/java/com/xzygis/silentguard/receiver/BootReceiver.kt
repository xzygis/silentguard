package com.xzygis.silentguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.service.MonitorForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appConfig = AppConfig(context)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val config = appConfig.configFlow.first()
                if (config.isMonitoringEnabled) {
                    val serviceIntent = Intent(context, MonitorForegroundService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.i(TAG, "开机自启动监控服务")
                }
            } catch (e: Exception) {
                Log.e(TAG, "开机启动服务失败: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
