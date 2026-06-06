package com.xzygis.silentguard.ui.screen

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.PolylineOptions
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.data.MonitorEventDao
import com.xzygis.silentguard.ui.component.AMapView
import com.xzygis.silentguard.ui.theme.*
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class TimeRange(val label: String, val hours: Int) {
    TODAY("今天", 24),
    WEEK("本周", 168),
    ALL("全部", -1)
}

@Composable
fun MapScreen(dao: MonitorEventDao) {
    var selectedRange by remember { mutableStateOf(TimeRange.TODAY) }
    val context = LocalContext.current
    var aMapInstance by remember { mutableStateOf<AMap?>(null) }

    // 打开页面时立即获取一次当前位置并记录
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            try {
                // 检查 Google Play Services 是否可用
                val gmsAvailable = try {
                    com.google.android.gms.common.GoogleApiAvailability.getInstance()
                        .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
                } catch (e: Exception) {
                    false
                }

                if (!gmsAvailable) {
                    Log.w("MapScreen", "Google Play Services 不可用，跳过自动定位")
                    return@LaunchedEffect
                }

                val fusedClient = LocationServices.getFusedLocationProviderClient(context)
                var location = fusedClient.lastLocation.await()
                if (location == null) {
                    val cts = CancellationTokenSource()
                    location = fusedClient.getCurrentLocation(
                        Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token
                    ).await()
                }
                if (location != null) {
                    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val event = MonitorEvent(
                        type = EventType.LOCATION,
                        title = "手动定位",
                        summary = "%.4f, %.4f".format(location.latitude, location.longitude),
                        detail = buildString {
                            appendLine("经度: ${location.longitude}")
                            appendLine("纬度: ${location.latitude}")
                            appendLine("精度: ${location.accuracy}米")
                            appendLine("时间: ${timeFormat.format(Date())}")
                            appendLine("来源: 轨迹页面打开")
                        },
                        latitude = location.latitude,
                        longitude = location.longitude,
                        accuracy = location.accuracy,
                        status = EventStatus.PENDING
                    )
                    dao.insert(event)
                } else {
                    Log.w("MapScreen", "无法获取位置：lastLocation 和 getCurrentLocation 均返回 null")
                }
            } catch (e: Exception) {
                Log.e("MapScreen", "获取当前位置失败: ${e.message}", e)
            }
        } else {
            Log.w("MapScreen", "位置权限未授予，跳过自动定位")
        }
    }

    val now = System.currentTimeMillis()
    val startTime = if (selectedRange.hours > 0) {
        now - selectedRange.hours * 3600_000L
    } else {
        0L
    }

    val locations by dao.getLocationEventsBetween(startTime, now)
        .collectAsState(initial = emptyList())

    val recordedCount = locations.count { it.status == EventStatus.PENDING }
    val reportedCount = locations.count { it.status == EventStatus.SENT }

    // 当位置数据变化时更新地图覆盖物
    LaunchedEffect(locations) {
        val map = aMapInstance ?: return@LaunchedEffect
        map.clear()

        val validLocations = locations.filter { it.latitude != null && it.longitude != null }
        if (validLocations.isEmpty()) return@LaunchedEffect

        // 添加轨迹连线
        if (validLocations.size >= 2) {
            val polylineOptions = PolylineOptions().apply {
                validLocations.forEach { event ->
                    add(LatLng(event.latitude!!, event.longitude!!))
                }
                width(8f)
                color(AndroidColor.parseColor("#10B981"))
                geodesic(true)
            }
            map.addPolyline(polylineOptions)
        }

        // 添加标记点
        validLocations.forEachIndexed { index, event ->
            val latLng = LatLng(event.latitude!!, event.longitude!!)
            val isEndpoint = index == 0 || index == validLocations.lastIndex
            val isSent = event.status == EventStatus.SENT

            val markerColor = when {
                isSent -> BitmapDescriptorFactory.HUE_GREEN
                event.status == EventStatus.FAILED -> BitmapDescriptorFactory.HUE_RED
                else -> BitmapDescriptorFactory.HUE_ORANGE
            }

            val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault())
                .format(Date(event.timestamp))
            val statusStr = when (event.status) {
                EventStatus.SENT -> "已报"
                EventStatus.FAILED -> "失败"
                else -> "待报"
            }

            val markerOptions = MarkerOptions().apply {
                position(latLng)
                title("$timeStr · $statusStr")
                snippet("%.6f, %.6f · 精度 %.0f米".format(
                    event.latitude, event.longitude, event.accuracy ?: 0f
                ))
                icon(BitmapDescriptorFactory.defaultMarker(markerColor))
                if (!isEndpoint) {
                    anchor(0.5f, 0.5f)
                }
            }
            map.addMarker(markerOptions)
        }

        // 调整地图视角到所有点的范围
        val boundsBuilder = LatLngBounds.Builder()
        validLocations.forEach { event ->
            boundsBuilder.include(LatLng(event.latitude!!, event.longitude!!))
        }
        try {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 80))
        } catch (e: Exception) {
            // 所有点相同位置时 bounds 无效，直接移到该点
            val first = validLocations.first()
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(
                LatLng(first.latitude!!, first.longitude!!), 16f
            ))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 时间筛选
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TimeRange.entries.forEach { range ->
                FilterChip(
                    selected = selectedRange == range,
                    onClick = { selectedRange = range },
                    label = {
                        Text(
                            text = range.label,
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = LocationColor,
                        selectedLabelColor = OnPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            // 轨迹点数统计
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = WarningSurface
                ) {
                    Text(
                        text = "$recordedCount 待报",
                        style = MaterialTheme.typography.labelMedium,
                        color = Warning,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = LocationSurface
                ) {
                    Text(
                        text = "$reportedCount 已报",
                        style = MaterialTheme.typography.labelMedium,
                        color = LocationColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
            }
        }

        if (locations.isEmpty()) {
            // 空状态也显示地图（默认视角）
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                AMapView(
                    modifier = Modifier.fillMaxSize(),
                    onMapReady = { map ->
                        map.uiSettings.isZoomControlsEnabled = false
                        map.uiSettings.isMyLocationButtonEnabled = false
                        aMapInstance = map
                    }
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "暂无轨迹数据",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "正在获取位置信息...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            // 高德地图
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                AMapView(
                    modifier = Modifier.fillMaxSize(),
                    onMapReady = { map ->
                        map.uiSettings.isZoomControlsEnabled = false
                        map.uiSettings.isMyLocationButtonEnabled = false
                        map.uiSettings.isScaleControlsEnabled = true
                        aMapInstance = map
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 轨迹点列表
            LazyColumn(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(locations, key = { _, e -> e.id }) { index, event ->
                    LocationPointItem(
                        event = event,
                        isFirst = index == 0,
                        isLast = index == locations.lastIndex,
                        onClick = {
                            // 点击跳转到高德地图 App（如已安装）或网页版
                            val uri = Uri.parse(
                                "https://uri.amap.com/marker?position=${event.longitude},${event.latitude}&name=轨迹点"
                            )
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    )
                }
                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun LocationPointItem(
    event: MonitorEvent,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit
) {
    val isReported = event.status == EventStatus.SENT
    val timelineColor = if (isReported) LocationColor else Warning

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 时间轴线
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            if (!isFirst) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(12.dp)
                        .background(timelineColor.copy(alpha = 0.3f))
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }

            Box(
                modifier = Modifier
                    .size(if (isFirst || isLast) 10.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (isFirst || isLast) timelineColor else timelineColor.copy(alpha = 0.5f))
            )

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(12.dp)
                        .background(timelineColor.copy(alpha = 0.3f))
                )
            } else {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Surface(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = 4.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "%.6f, %.6f".format(event.latitude, event.longitude),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium
                        )
                        // 状态标签
                        val statusColor = when (event.status) {
                            EventStatus.SENT -> LocationColor
                            EventStatus.FAILED -> Error
                            else -> Warning
                        }
                        val statusText = when (event.status) {
                            EventStatus.SENT -> "已报"
                            EventStatus.FAILED -> "失败"
                            else -> "待报"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier
                                .background(
                                    color = statusColor.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (event.accuracy != null) {
                        Text(
                            text = "精度 %.0f米".format(event.accuracy),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Text(
                    text = SimpleDateFormat("HH:mm", Locale.getDefault())
                        .format(Date(event.timestamp)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
