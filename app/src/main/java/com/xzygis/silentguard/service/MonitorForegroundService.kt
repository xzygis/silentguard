package com.xzygis.silentguard.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
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
import com.xzygis.silentguard.mail.MailSender
import com.xzygis.silentguard.receiver.ServiceWatchdogReceiver
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
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastRecordedLocation: Location? = null
    private var isLocationLoopRunning = false
    // 自适应间隔：连续未移动次数
    private var stationaryCount = 0
    private val MAX_INTERVAL_MULTIPLIER = 2
    // 静止状态开始时间，用于最大静止时长重置
    private var stationarySinceMillis = 0L
    // 最大静止持续时间（毫秒），超过后强制重置为正常频率
    private val MAX_STATIONARY_DURATION_MS = 30 * 60 * 1000L // 30分钟

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        appConfig = AppConfig(this)
        createNotificationChannel()
        initWakeLock()
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
            startDailySummaryScheduler()
        }
        // 设置 AlarmManager 兜底唤醒
        scheduleWatchdogAlarm()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 用户从最近任务划掉 app 时触发，立即重启服务
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved: 用户划掉任务，尝试重启服务")
        // 通过 AlarmManager 延迟 1 秒重启服务
        val restartIntent = Intent(this, ServiceWatchdogReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this, 1, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        EmailScheduleWorker.cancel(this)
        releaseWakeLock()
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
     * 间歇式定位循环：每个周期执行一次 getCurrentLocation，完成后释放定位资源。
     * 自适应间隔：设备静止时逐步延长间隔（最大4倍），移动时立即恢复正常间隔。
     * 夜间自动切换低功耗定位精度。
     */
    private fun startLocationPollingLoop() {
        serviceScope.launch {
            try {
                val config = appConfig.configFlow.first()
                val baseIntervalMinutes = config.locationIntervalMinutes
                val useHighAccuracy = config.useHighAccuracy
                Log.d(TAG, "启动间歇式定位循环: 基础间隔=${baseIntervalMinutes}分钟, 高精度=$useHighAccuracy")

                while (isActive) {
                    // 静止超时重置：超过最大静止持续时间后，重置计数恢复正常频率
                    if (stationaryCount > 0 && stationarySinceMillis > 0) {
                        val stationaryDuration = System.currentTimeMillis() - stationarySinceMillis
                        if (stationaryDuration >= MAX_STATIONARY_DURATION_MS) {
                            Log.d(TAG, "静止超过${MAX_STATIONARY_DURATION_MS / 3600000}小时，重置定位频率")
                            stationaryCount = 0
                            stationarySinceMillis = 0L
                        }
                    }

                    // 夜间自动延长基础间隔
                    val nightAdjusted = if (isNightTime()) {
                        maxOf(baseIntervalMinutes, NIGHT_INTERVAL_MINUTES)
                    } else {
                        baseIntervalMinutes
                    }

                    // 自适应间隔：静止时逐步翻倍，最大2倍
                    val multiplier = minOf(1 shl stationaryCount, MAX_INTERVAL_MULTIPLIER)
                    val actualInterval = nightAdjusted * multiplier

                    // 静止达到上限时强制使用高精度定位，避免系统返回缓存位置
                    val forceHighAccuracy = stationaryCount >= 2
                    val effectiveHighAccuracy = if (isNightTime() && !forceHighAccuracy) {
                        false
                    } else if (forceHighAccuracy) {
                        true
                    } else {
                        useHighAccuracy
                    }

                    // 仅在定位期间持有 WakeLock
                    acquireWakeLock()
                    val moved = fetchAndRecordLocation(effectiveHighAccuracy)
                    releaseWakeLock()

                    // 更新静止计数
                    if (moved) {
                        stationaryCount = 0
                        stationarySinceMillis = 0L
                    } else {
                        if (stationaryCount == 0) {
                            stationarySinceMillis = System.currentTimeMillis()
                        }
                        stationaryCount = minOf(stationaryCount + 1, 3)
                    }

                    val delayMillis = actualInterval * 60 * 1000L
                    Log.d(TAG, "下次定位将在 ${actualInterval} 分钟后 (夜间=${isNightTime()}, 静止次数=$stationaryCount, 倍率=$multiplier, 强制高精度=$forceHighAccuracy)")
                    delay(delayMillis)
                }
            } catch (e: Exception) {
                Log.e(TAG, "定位循环异常: ${e.message}", e)
                isLocationLoopRunning = false
                // 延迟重试
                delay(30_000L)
                if (!isLocationLoopRunning) {
                    isLocationLoopRunning = true
                    startLocationPollingLoop()
                }
            }
        }
    }

    /**
     * 单次定位 + 去重 + 记录
     * @return true 表示位置有变化（已记录），false 表示位置未变化或获取失败
     */
    private suspend fun fetchAndRecordLocation(useHighAccuracy: Boolean): Boolean {
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "缺少位置权限，跳过本次定位")
            return false
        }

        try {
            val priority = if (useHighAccuracy) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }

            val cancellationToken = CancellationTokenSource()
            val location: Location? = try {
                fusedLocationClient.getCurrentLocation(priority, cancellationToken.token).await()
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation 失败，尝试 lastLocation: ${e.message}")
                fusedLocationClient.lastLocation.await()
            }

            if (location == null) {
                Log.w(TAG, "无法获取位置")
                return false
            }

            // 去重：距离上次记录不足 100 米则跳过
            lastRecordedLocation?.let { last ->
                if (last.distanceTo(location) < DEDUP_DISTANCE_METERS) {
                    Log.d(TAG, "位置变化不足${DEDUP_DISTANCE_METERS}米，跳过记录")
                    return false
                }
            }

            // 记录位置
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
            return true
        } catch (e: Exception) {
            Log.e(TAG, "定位记录失败: ${e.message}", e)
            return false
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

    /**
     * 每日 23:59 自动发送当天位置汇总邮件（即使没有新轨迹点也发送）
     */
    private fun startDailySummaryScheduler() {
        serviceScope.launch {
            while (isActive) {
                val now = Calendar.getInstance()
                val target = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                    // 如果当前已过 23:59，则设定为明天
                    if (before(now)) add(Calendar.DAY_OF_MONTH, 1)
                }
                val delayMs = target.timeInMillis - now.timeInMillis
                Log.d(TAG, "每日汇总邮件将在 ${delayMs / 60000} 分钟后发送")
                delay(delayMs)

                try {
                    sendDailySummaryEmail()
                } catch (e: Exception) {
                    Log.e(TAG, "每日汇总邮件发送失败: ${e.message}", e)
                }
            }
        }
    }

    /**
     * 发送当天所有位置记录的汇总邮件（不依赖 PENDING 状态）
     */
    private suspend fun sendDailySummaryEmail() {
        val dao = AppDatabase.getInstance(this).monitorEventDao()
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todayEvents = dao.getTodayLocationEvents(startOfToday)
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val subject = if (todayEvents.isEmpty()) {
            "[$deviceModel] 每日位置汇总 - 今日无轨迹点"
        } else {
            "[$deviceModel] 每日位置汇总 - ${todayEvents.size}条记录"
        }

        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val body = if (todayEvents.isEmpty()) {
            "今日设备未产生任何位置记录。设备可能处于静止状态或服务未正常运行。\n\n设备: $deviceModel\n报告时间: ${timeFormat.format(Date())}"
        } else {
            buildString {
                appendLine("设备: $deviceModel")
                appendLine("今日共 ${todayEvents.size} 条位置记录")
                appendLine()
                todayEvents.forEach { event ->
                    appendLine("--- ${timeFormat.format(Date(event.timestamp))} ---")
                    appendLine(event.detail)
                    appendLine()
                }
            }
        }

        val sent = MailSender(this).sendMail(subject, body)
        if (sent) {
            Log.d(TAG, "每日汇总邮件发送成功")
        } else {
            Log.w(TAG, "每日汇总邮件发送失败")
        }
    }

    /**
     * 初始化 WakeLock 实例（不立即 acquire）
     */
    private fun initWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SilentGuard::LocationWakeLock"
        )
        Log.d(TAG, "WakeLock 已初始化（按需获取模式）")
    }

    /**
     * 按需获取 WakeLock（仅在定位期间持有，最长 30 秒超时保护）
     */
    private fun acquireWakeLock() {
        if (::wakeLock.isInitialized && !wakeLock.isHeld) {
            wakeLock.acquire(30_000L) // 最长 30 秒自动释放，防止泄漏
        }
    }

    /**
     * 释放 WakeLock
     */
    private fun releaseWakeLock() {
        if (::wakeLock.isInitialized && wakeLock.isHeld) {
            wakeLock.release()
        }
    }

    /**
     * 设置 AlarmManager 兜底唤醒。
     * 间隔跟随用户定位设置：兜底间隔 = 定位间隔 * 2（至少 10 分钟）
     */
    private fun scheduleWatchdogAlarm() {
        serviceScope.launch {
            val config = appConfig.configFlow.first()
            val watchdogIntervalMs = maxOf(config.locationIntervalMinutes * 2, 10) * 60 * 1000L

            val intent = Intent(this@MonitorForegroundService, ServiceWatchdogReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                this@MonitorForegroundService, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.setRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + watchdogIntervalMs,
                watchdogIntervalMs,
                pendingIntent
            )
            Log.d(TAG, "AlarmManager 兜底唤醒已设置: 每${watchdogIntervalMs / 60000}分钟检查一次")
        }
    }
}
