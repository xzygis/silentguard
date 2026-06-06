package com.xzygis.silentguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.xzygis.silentguard.MainActivity
import com.xzygis.silentguard.R
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.data.AppDatabase
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.mail.MailWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MonitorForegroundService : Service() {

    companion object {
        private const val TAG = "MonitorService"
        private const val CHANNEL_ID = "monitor_channel"
        private const val NOTIFICATION_ID = 1
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var appConfig: AppConfig
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        appConfig = AppConfig(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)
        startLocationUpdates()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        if (::locationCallback.isInitialized) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
        }
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "手机监控服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "手机监控前台服务通知"
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
            .setContentTitle("手机监控运行中")
            .setContentText("正在监控短信和位置信息")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startLocationUpdates() {
        serviceScope.launch {
            val config = appConfig.configFlow.first()
            val intervalMillis = config.locationIntervalMinutes * 60 * 1000L

            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                intervalMillis
            ).setMinUpdateIntervalMillis(intervalMillis).build()

            locationCallback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val location = result.lastLocation ?: return
                    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val currentTime = timeFormat.format(Date())
                    val mapsLink = "https://maps.google.com/maps?q=${location.latitude},${location.longitude}"

                    val subject = "[位置] 定时上报"
                    val body = buildString {
                        appendLine("经度: ${location.longitude}")
                        appendLine("纬度: ${location.latitude}")
                        appendLine("精度: ${location.accuracy}米")
                        appendLine("时间: $currentTime")
                        appendLine("Google Maps: $mapsLink")
                    }

                    // 记录到数据库
                    val event = MonitorEvent(
                        type = EventType.LOCATION,
                        title = "位置上报",
                        summary = "%.4f, %.4f".format(location.latitude, location.longitude),
                        detail = body,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        status = EventStatus.PENDING
                    )
                    serviceScope.launch {
                        try {
                            val dao = AppDatabase.getInstance(this@MonitorForegroundService).monitorEventDao()
                            val eventId = dao.insert(event)
                            MailWorker.enqueue(this@MonitorForegroundService, subject, body)
                            dao.updateStatus(eventId, EventStatus.SENT)
                        } catch (e: Exception) {
                            Log.e(TAG, "记录位置事件失败: ${e.message}", e)
                        }
                    }
                }
            }

            if (ActivityCompat.checkSelfPermission(
                    this@MonitorForegroundService,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                    this@MonitorForegroundService,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    locationCallback,
                    Looper.getMainLooper()
                )
            } else {
                Log.w(TAG, "缺少位置权限，无法请求位置更新")
            }
        }
    }
}
