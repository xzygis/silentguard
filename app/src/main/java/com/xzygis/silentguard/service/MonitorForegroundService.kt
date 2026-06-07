package com.xzygis.silentguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.xzygis.silentguard.MainActivity
import com.xzygis.silentguard.R
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.data.AppDatabase
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.location.AmapReverseGeocoder
import com.xzygis.silentguard.mail.EmailScheduleWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MonitorForegroundService : Service() {

    companion object {
        private const val TAG = "GuardService"
        private const val CHANNEL_ID = "guard_channel"
        private const val NOTIFICATION_ID = 1
        private const val NIGHT_START_HOUR = 23
        private const val NIGHT_END_HOUR = 6
        private const val NIGHT_INTERVAL_MINUTES = 30
        private const val DEDUP_DISTANCE_METERS = 100f
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var appConfig: AppConfig
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastRecordedLocation: Location? = null
    private var isLocationLoopRunning = false

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        appConfig = AppConfig(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        // 防止 START_STICKY 重启或重复调用导致多个循环并发
        if (!isLocationLoopRunning) {
            isLocationLoopRunning = true
            startLocationPollingLoop()
            startEmailScheduler()
            startEmailCheckLoop()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        EmailScheduleWorker.cancel(this)
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SilentGuard 守护服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "SilentGuard 前台守护服务通知"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SilentGuard 守护运行中")
            .setContentText("正在按配置记录短信与位置")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun isNightTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    /**
     * 间歇式定位循环：每次只做一次性定位（getCurrentLocation），
     * 完成后立即释放位置资源，等待下一个周期再请求。
     * 这样系统不会显示"一直在使用定位服务"。
     */
    private fun startLocationPollingLoop() {
        serviceScope.launch {
            try {
                val config = appConfig.configFlow.first()
                val baseIntervalMinutes = config.locationIntervalMinutes
                val useHighAccuracy = config.useHighAccuracy
                Log.d(TAG, "启动间歇式定位: 间隔=${baseIntervalMinutes}分钟, 高精度=$useHighAccuracy")

                while (isActive) {
                    // 计算当前周期的间隔（夜间自动延长）
                    val actualInterval = if (isNightTime()) {
                        maxOf(baseIntervalMinutes, NIGHT_INTERVAL_MINUTES)
                    } else {
                        baseIntervalMinutes
                    }

                    // 执行一次性定位
                    try {
                        fetchAndRecordLocation(useHighAccuracy)
                    } catch (e: Exception) {
                        Log.e(TAG, "单次定位失败: ${e.message}", e)
                    }

                    // 等待下一个定位周期
                    val delayMillis = actualInterval * 60 * 1000L
                    Log.d(TAG, "下次定位将在 ${actualInterval} 分钟后, 夜间=${isNightTime()}")
                    delay(delayMillis)
                }
            } catch (e: Exception) {
                Log.e(TAG, "定位循环异常: ${e.message}", e)
                // 延迟后重启循环
                delay(30_000L)
                startLocationPollingLoop()
            }
        }
    }

    /**
     * 执行一次性定位并记录结果。
     * 使用 getCurrentLocation 获取位置后立即释放，不持有位置监听器。
     */
    private suspend fun fetchAndRecordLocation(useHighAccuracy: Boolean) {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "缺少位置权限，跳过本次定位")
            return
        }

        val priority = if (useHighAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val cancellationSource = CancellationTokenSource()
        val location: Location? = try {
            fusedLocationClient.getCurrentLocation(priority, cancellationSource.token).await()
        } catch (e: Exception) {
            Log.w(TAG, "getCurrentLocation 失败，尝试 lastLocation: ${e.message}")
            fusedLocationClient.lastLocation.await()
        }

        if (location == null) {
            Log.w(TAG, "无法获取位置（getCurrentLocation 和 lastLocation 均为空）")
            return
        }

        // 位置去重：距离上次记录 < 100m 则跳过
        lastRecordedLocation?.let { last ->
            if (last.distanceTo(location) < DEDUP_DISTANCE_METERS) {
                Log.d(TAG, "位置变化不足${DEDUP_DISTANCE_METERS}米，跳过记录")
                return
            }
        }

        // 记录位置
        try {
            val config = appConfig.getConfig()
            val address = AmapReverseGeocoder.resolveAddress(
                context = this@MonitorForegroundService,
                apiKey = config.amapWebApiKey,
                latitude = location.latitude,
                longitude = location.longitude
            )
            val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            val currentTime = timeFormat.format(Date())
            val mapsLink = "https://maps.google.com/maps?q=${location.latitude},${location.longitude}"

            val body = buildString {
                if (address != null) appendLine("地址: $address")
                appendLine("经度: ${location.longitude}")
                appendLine("纬度: ${location.latitude}")
                appendLine("精度: ${location.accuracy}米")
                appendLine("时间: $currentTime")
                appendLine("Google Maps: $mapsLink")
            }

            val event = MonitorEvent(
                type = EventType.LOCATION,
                title = "位置记录",
                summary = AmapReverseGeocoder.formatSummary(
                    address,
                    location.latitude,
                    location.longitude
                ),
                detail = body,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracy = location.accuracy,
                status = EventStatus.PENDING
            )
            val dao = AppDatabase.getInstance(this@MonitorForegroundService).monitorEventDao()
            dao.insert(event)
            lastRecordedLocation = location
            Log.d(TAG, "位置已记录: ${location.latitude}, ${location.longitude}")
        } catch (e: Exception) {
            Log.e(TAG, "记录位置事件失败: ${e.message}", e)
        }
    }

    private fun startEmailScheduler() {
        serviceScope.launch {
            try {
                val config = appConfig.configFlow.first()
                val intervalMinutes = config.emailIntervalMinutes.toLong()
                Log.d(TAG, "启动邮件调度: 间隔=${intervalMinutes}分钟")
                EmailScheduleWorker.schedule(this@MonitorForegroundService, intervalMinutes)
            } catch (e: Exception) {
                Log.e(TAG, "启动邮件调度失败: ${e.message}", e)
                // 延迟重试
                delay(10_000L)
                startEmailScheduler()
            }
        }
    }

    /**
     * 前台服务内的邮件发送循环，作为 WorkManager 周期任务的补充。
     * 由于前台服务不受 Doze 限制，此循环能确保在 App 运行期间
     * 按配置的间隔检查并触发待发送位置邮件。
     */
    private fun startEmailCheckLoop() {
        serviceScope.launch {
            try {
                val config = appConfig.configFlow.first()
                val intervalMillis = config.emailIntervalMinutes * 60 * 1000L
                Log.d(TAG, "启动前台邮件检查循环: 间隔=${config.emailIntervalMinutes}分钟")

                // 首次等待一个完整间隔
                delay(intervalMillis)

                while (isActive) {
                    try {
                        val dao = AppDatabase.getInstance(this@MonitorForegroundService).monitorEventDao()
                        val pendingCount = dao.getPendingLocationEvents().size
                        if (pendingCount > 0) {
                            Log.d(TAG, "前台邮件检查: 发现 $pendingCount 条待发送位置记录，触发 EmailScheduleWorker")
                            EmailScheduleWorker.schedule(this@MonitorForegroundService, config.emailIntervalMinutes.toLong())
                        } else {
                            Log.d(TAG, "前台邮件检查: 无待发送记录")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "前台邮件检查失败: ${e.message}", e)
                    }
                    delay(intervalMillis)
                }
            } catch (e: Exception) {
                Log.e(TAG, "邮件检查循环启动失败: ${e.message}", e)
            }
        }
    }
}
