package com.xzygis.silentguard.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.xzygis.silentguard.MainActivity
import com.xzygis.silentguard.R
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.data.AppDatabase
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.location.AmapReverseGeocoder
import com.xzygis.silentguard.location.DeviceLocationProvider
import com.xzygis.silentguard.location.LocationPolicy
import com.xzygis.silentguard.diagnostics.AppDiagnostics
import com.xzygis.silentguard.mail.EmailScheduleWorker
import com.xzygis.silentguard.mail.MailSender
import com.xzygis.silentguard.mail.MailWorker
import com.xzygis.silentguard.diagnostics.GuardHealth
import com.xzygis.silentguard.receiver.ServiceWatchdogReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        private const val LOCATION_ALERT_CHECK_INTERVAL_MS = 10 * 60 * 1000L
        private const val LOCATION_ALERT_REPEAT_INTERVAL_MS = 2 * 60 * 60 * 1000L
        private const val BATTERY_ALERT_REPEAT_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val LOW_BATTERY_THRESHOLD_PERCENT = 10
        private const val LOW_BATTERY_RECOVERY_PERCENT = 15
    }

    private lateinit var appConfig: AppConfig
    private lateinit var wakeLock: PowerManager.WakeLock
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastRecordedLocation: Location? = null
    @Volatile private var lastLocationFixElapsed = SystemClock.elapsedRealtime()
    @Volatile private var nextLocationAttemptElapsed = 0L
    @Volatile private var healthyNextAttemptElapsed = 0L
    @Volatile private var lastLocationAttemptStatus = "尚未尝试定位"
    private val alertPrefs by lazy { getSharedPreferences("alert_schedule", Context.MODE_PRIVATE) }
    private var lastLocationAlertMillis: Long
        get() = alertPrefs.getLong("location_alert", 0)
        set(value) { alertPrefs.edit().putLong("location_alert", value).apply() }
    private var lastLowBatteryAlertMillis: Long
        get() = alertPrefs.getLong("battery_alert", 0)
        set(value) { alertPrefs.edit().putLong("battery_alert", value).apply() }
    private var isLocationLoopRunning = false
    private enum class LocationOutcome { MOVED, STATIONARY, FAILED }
    // 自适应间隔：连续未移动次数
    private var stationaryCount = 0
    private var failureCount = 0
    private lateinit var health: GuardHealth
    private val MAX_INTERVAL_MULTIPLIER = 2
    // 静止状态开始时间，用于最大静止时长重置
    private var stationarySinceMillis = 0L
    // 最大静止持续时间（毫秒），超过后强制重置为正常频率
    private val MAX_STATIONARY_DURATION_MS = 30 * 60 * 1000L // 30分钟

    override fun onCreate() {
        super.onCreate()
        appConfig = AppConfig(this)
        health = GuardHealth(this)
        if (health.lastFix > 0) {
            lastLocationFixElapsed = SystemClock.elapsedRealtime() -
                (System.currentTimeMillis() - health.lastFix).coerceAtLeast(0)
        }
        createNotificationChannel()
        initWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: RuntimeException) {
            health.startError = "前台服务启动受限，请检查定位权限"
            stopSelf()
            return START_NOT_STICKY
        }
        // 防止 START_STICKY 重启或重复调用导致多个循环并发
        if (!isLocationLoopRunning) {
            isLocationLoopRunning = true
            startLocationPollingLoop()
            startEmailScheduler()
            com.xzygis.silentguard.mail.DailySummaryWorker.schedule(this)
            startLocationHealthCheckLoop()
            serviceScope.launch {
                while (isActive) {
                    if (!appConfig.getConfig().isGuardingEnabled) { stopSelf(); break }
                    health.heartbeat = System.currentTimeMillis()
                    delay(60_000)
                }
            }
            serviceScope.launch { MailWorker.recover(this@MonitorForegroundService) }
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
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        health.heartbeat = 0
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
     * 自适应间隔：设备静止时逐步延长间隔（最大2倍），移动时立即恢复正常间隔。
     * 夜间自动切换低功耗定位精度。
     */
    private fun startLocationPollingLoop() {
        serviceScope.launch {
            try {
                while (isActive) {
                    val config = appConfig.getConfig()
                    if (!config.isGuardingEnabled) { stopSelf(); break }
                    val baseIntervalMinutes = config.locationIntervalMinutes
                    val useHighAccuracy = config.useHighAccuracy
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
                    val outcome = try {
                        fetchAndRecordLocation(effectiveHighAccuracy)
                    } finally {
                        releaseWakeLock()
                    }

                    // 定位失败不能证明设备静止，恢复正常采样频率并尽快重试。
                    if (outcome != LocationOutcome.STATIONARY) {
                        stationaryCount = 0
                        stationarySinceMillis = 0L
                    } else {
                        if (stationaryCount == 0) {
                            stationarySinceMillis = System.currentTimeMillis()
                        }
                        stationaryCount = minOf(stationaryCount + 1, 3)
                    }

                    val successfulInterval = if (outcome == LocationOutcome.MOVED) nightAdjusted else actualInterval
                    failureCount = if (outcome == LocationOutcome.FAILED) (failureCount + 1).coerceAtMost(5) else 0
                    val prerequisitesMissing = !AppDiagnostics.hasLocationPermission(this@MonitorForegroundService) ||
                        !androidx.core.location.LocationManagerCompat.isLocationEnabled(
                            getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager)
                    val delayMillis = if (outcome == LocationOutcome.FAILED) {
                        if (prerequisitesMissing) 15 * 60_000L else (60_000L shl (failureCount - 1)).coerceAtMost(5 * 60_000L)
                    } else LocationPolicy.nextDelayMillis(
                        outcome != LocationOutcome.FAILED, successfulInterval
                    )
                    nextLocationAttemptElapsed = SystemClock.elapsedRealtime() + delayMillis
                    // 仅正常采样间隔可以推迟告警；失败后的快速重试不能持续压制告警。
                    healthyNextAttemptElapsed = if (outcome == LocationOutcome.FAILED) 0L else nextLocationAttemptElapsed
                    Log.d(TAG, "下次定位将在 ${delayMillis / 60000} 分钟后 (结果=$outcome, 夜间=${isNightTime()}, 静止次数=$stationaryCount)")
                    checkAndSendLowBatteryAlert()
                    withTimeoutOrNull((nextLocationAttemptElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(1L)) {
                        appConfig.configFlow.filter { it != config }.first()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "定位循环异常: ${e.javaClass.simpleName}")
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
     * 区分位置变化、位置静止与失败，避免把定位故障当作静止而降低采样频率。
     */
    private suspend fun fetchAndRecordLocation(useHighAccuracy: Boolean): LocationOutcome {
        if (!appConfig.getConfig().isGuardingEnabled) return LocationOutcome.FAILED
        if (ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "缺少位置权限，跳过本次定位")
            lastLocationAttemptStatus = "失败：未授予前台定位权限"
            return LocationOutcome.FAILED
        }

        try {
            lastLocationAttemptStatus = "正在请求定位"
            // 优先 GMS，无 GMS 设备（如华为）自动降级到系统定位
            val location: Location? = DeviceLocationProvider.getCurrentLocation(this, useHighAccuracy)

            if (location == null) {
                Log.w(TAG, "无法获取位置")
                lastLocationAttemptStatus = "失败：定位源未返回一分钟内的位置（不可用、超时或缓存过期）"
                return LocationOutcome.FAILED
            }
            lastLocationFixElapsed = location.elapsedRealtimeNanos / 1_000_000L
            health.lastFix = System.currentTimeMillis()
            lastLocationAttemptStatus = "成功：来源=${location.provider}，精度=${location.accuracy}米"

            // 去重：距离上次记录不足 100 米则跳过
            lastRecordedLocation?.let { last ->
                if (last.distanceTo(location) < DEDUP_DISTANCE_METERS) {
                    Log.d(TAG, "位置变化不足${DEDUP_DISTANCE_METERS}米，跳过记录")
                    return LocationOutcome.STATIONARY
                }
            }

            // 记录位置
            val config = appConfig.getConfig()
            if (!config.isGuardingEnabled) return LocationOutcome.FAILED
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
            if (!appConfig.getConfig().isGuardingEnabled) return LocationOutcome.FAILED
            dao.insert(event)
            lastRecordedLocation = location
            Log.d(TAG, "位置已记录")
            return LocationOutcome.MOVED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "定位记录失败: ${e.javaClass.simpleName}")
            lastLocationAttemptStatus = "定位或记录失败：${e.javaClass.simpleName}"
            return LocationOutcome.FAILED
        }
    }

    private fun startEmailScheduler() {
        serviceScope.launch {
            try {
                appConfig.configFlow.collect { config ->
                    if (config.isGuardingEnabled) {
                        EmailScheduleWorker.schedule(this@MonitorForegroundService, config.emailIntervalMinutes.toLong())
                    } else EmailScheduleWorker.cancel(this@MonitorForegroundService)
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动邮件调度失败: ${e.javaClass.simpleName}")
                // 延迟重试
                delay(10_000L)
                startEmailScheduler()
            }
        }
    }

    /**
     * 异常未定位告警：关注是否成功拿到定位结果，不以是否新增轨迹点为准。
     * 这样设备静止导致轨迹去重时不会误报，只有连续拿不到定位时才告警。
     */
    private fun startLocationHealthCheckLoop() {
        serviceScope.launch {
            while (isActive) {
                delay(LOCATION_ALERT_CHECK_INTERVAL_MS)
                try {
                    val config = appConfig.configFlow.first()
                    if (!config.isGuardingEnabled) continue
                    val alertThresholdMs = maxOf(config.locationIntervalMinutes * 3, 30) * 60 * 1000L
                    val now = System.currentTimeMillis()
                    val elapsed = SystemClock.elapsedRealtime()
                    val noFixDuration = elapsed - lastLocationFixElapsed
                    val canSendAgain = now - lastLocationAlertMillis >= LOCATION_ALERT_REPEAT_INTERVAL_MS
                    if (LocationPolicy.shouldAlert(
                            elapsed, lastLocationFixElapsed, alertThresholdMs, healthyNextAttemptElapsed
                        ) && canSendAgain
                    ) {
                        if (sendLocationMissingAlert(noFixDuration, alertThresholdMs)) lastLocationAlertMillis = now
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "异常未定位检查失败: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    private suspend fun sendLocationMissingAlert(noFixDurationMs: Long, thresholdMs: Long): Boolean {
        val dao = AppDatabase.getInstance(this).monitorEventDao()
        val latestLocation = dao.getLatestLocationEvent()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val subject = "[$deviceModel] 异常未定位告警"
        val body = buildString {
            appendLine("SilentGuard 已连续 ${noFixDurationMs / 60000} 分钟未成功获取定位。")
            appendLine()
            appendLine("设备: $deviceModel")
            appendLine("告警时间: ${timeFormat.format(Date())}")
            appendLine("告警阈值: ${thresholdMs / 60000} 分钟")
            appendLine("最近定位尝试: $lastLocationAttemptStatus")
            appendLine("定位诊断: ${AppDiagnostics.locationStatus(this@MonitorForegroundService)}")
            appendLine()
            if (latestLocation == null) {
                appendLine("最近轨迹: 暂无位置记录")
            } else {
                appendLine("最近轨迹时间: ${timeFormat.format(Date(latestLocation.timestamp))}")
                appendLine("最近轨迹摘要: ${latestLocation.summary}")
                if (latestLocation.latitude != null && latestLocation.longitude != null) {
                    appendLine("Google Maps: https://maps.google.com/maps?q=${latestLocation.latitude},${latestLocation.longitude}")
                }
            }
            appendLine()
            appendLine("可能原因: 定位权限被关闭、系统限制后台定位、GPS/网络不可用、服务被系统限制。")
        }

        return MailWorker.enqueue(this, subject, body) != null
    }

    private suspend fun checkAndSendLowBatteryAlert() {
        try {
            val batteryInfo = getBatteryInfo() ?: return
            if (batteryInfo.percent >= LOW_BATTERY_RECOVERY_PERCENT) {
                lastLowBatteryAlertMillis = 0
                return
            }

            if (batteryInfo.percent > LOW_BATTERY_THRESHOLD_PERCENT) return

            val now = System.currentTimeMillis()
            val canSendAgain = now - lastLowBatteryAlertMillis >= BATTERY_ALERT_REPEAT_INTERVAL_MS
            if ((lastLowBatteryAlertMillis == 0L || canSendAgain) && sendLowBatteryAlert(batteryInfo)) {
                lastLowBatteryAlertMillis = now
            }
        } catch (e: Exception) {
            Log.e(TAG, "低电量检查失败: ${e.javaClass.simpleName}")
        }
    }

    private fun getBatteryInfo(): BatteryInfo? {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(
            percent = (level * 100) / scale,
            isCharging = isCharging
        )
    }

    private suspend fun sendLowBatteryAlert(batteryInfo: BatteryInfo): Boolean {
        val dao = AppDatabase.getInstance(this).monitorEventDao()
        val latestLocation = dao.getLatestLocationEvent()
        val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
        val subject = "[$deviceModel] 低电量提醒 - ${batteryInfo.percent}%"
        val body = buildString {
            appendLine("监护设备电量已低于 ${LOW_BATTERY_THRESHOLD_PERCENT}%，请尽快充电，避免设备关机后失联。")
            appendLine()
            appendLine("设备: $deviceModel")
            appendLine("当前电量: ${batteryInfo.percent}%")
            appendLine("充电状态: ${if (batteryInfo.isCharging) "正在充电" else "未充电"}")
            appendLine("提醒时间: ${timeFormat.format(Date())}")
            appendLine()
            if (latestLocation == null) {
                appendLine("最近轨迹: 暂无位置记录")
            } else {
                appendLine("最近轨迹时间: ${timeFormat.format(Date(latestLocation.timestamp))}")
                appendLine("最近轨迹摘要: ${latestLocation.summary}")
                if (latestLocation.latitude != null && latestLocation.longitude != null) {
                    appendLine("Google Maps: https://maps.google.com/maps?q=${latestLocation.latitude},${latestLocation.longitude}")
                }
            }
        }

        return MailWorker.enqueue(this, subject, body) != null
    }

    private data class BatteryInfo(
        val percent: Int,
        val isCharging: Boolean
    )

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
     * 覆盖 GMS 10 秒 + 系统定位 45 秒的等待窗口，保留超时保护。
     */
    private fun acquireWakeLock() {
        if (::wakeLock.isInitialized && !wakeLock.isHeld) {
            wakeLock.acquire(65_000L)
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
