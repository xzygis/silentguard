package com.xzygis.silentguard.ui.screen

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView as AndroidTextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import com.amap.api.maps.CoordinateConverter
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private enum class TimeRange(val label: String, val hours: Int) {
    TODAY("今天", 24),
    WEEK("本周", 168),
    ALL("全部", -1)
}

private data class TrackMarkerInfo(
    val title: String,
    val status: String,
    val action: String
)

private const val LOCATION_DEDUP_DISTANCE_METERS = 100f

private fun Context.px(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}

private fun createTrackInfoWindow(context: Context, marker: Marker): View {
    val info = marker.`object` as? TrackMarkerInfo
    val titleText = info?.title ?: marker.title.orEmpty()
    val statusText = info?.status ?: marker.snippet.orEmpty()
    val actionText = info?.action ?: "点击卡片在地图中打开"

    return LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.px(14), context.px(12), context.px(14), context.px(12))
        background = GradientDrawable().apply {
            setColor(AndroidColor.WHITE)
            cornerRadius = context.px(14).toFloat()
            setStroke(context.px(1), AndroidColor.parseColor("#E5E7EB"))
        }
        elevation = context.px(6).toFloat()
        minimumWidth = context.px(180)

        addView(AndroidTextView(context).apply {
            text = titleText
            setTextColor(AndroidColor.parseColor("#111827"))
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
        })

        addView(AndroidTextView(context).apply {
            text = statusText
            setTextColor(AndroidColor.parseColor("#10B981"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, context.px(8), 0, 0)
        })

        addView(AndroidTextView(context).apply {
            text = actionText
            setTextColor(AndroidColor.parseColor("#6B7280"))
            textSize = 12f
            includeFontPadding = false
            setPadding(0, context.px(6), 0, 0)
        })
    }
}

private fun MonitorEvent.toAmapLatLng(context: Context): LatLng? {
    val latitude = latitude ?: return null
    val longitude = longitude ?: return null
    val gpsLatLng = LatLng(latitude, longitude)

    return try {
        CoordinateConverter(context).apply {
            from(CoordinateConverter.CoordType.GPS)
            coord(gpsLatLng)
        }.convert()
    } catch (e: Exception) {
        Log.w("MapScreen", "GPS 坐标转换高德坐标失败，使用原始坐标: ${e.message}")
        gpsLatLng
    }
}

private fun renderTrackOnMap(
    map: AMap,
    context: Context,
    locations: List<MonitorEvent>
) {
    map.clear()

    val mapLocations = locations.mapNotNull { event ->
        val latLng = event.toAmapLatLng(context) ?: return@mapNotNull null
        event to latLng
    }
    if (mapLocations.isEmpty()) return

    if (mapLocations.size >= 2) {
        val polylineOptions = PolylineOptions().apply {
            mapLocations.forEach { (_, latLng) ->
                add(latLng)
            }
            width(8f)
            color(AndroidColor.parseColor("#10B981"))
            geodesic(true)
        }
        map.addPolyline(polylineOptions)
    }

    mapLocations.forEachIndexed { index, (event, latLng) ->
        val isEndpoint = index == 0 || index == mapLocations.lastIndex
        val isSent = event.status == EventStatus.SENT

        val markerColor = when {
            isSent -> BitmapDescriptorFactory.HUE_GREEN
            event.status == EventStatus.FAILED -> BitmapDescriptorFactory.HUE_RED
            else -> BitmapDescriptorFactory.HUE_ORANGE
        }

        val statusStr = when (event.status) {
            EventStatus.SENT -> "已发送"
            EventStatus.FAILED -> "发送失败"
            else -> "待发送"
        }

        val markerOptions = MarkerOptions().apply {
            position(latLng)
            title(formatTrackTime(event.timestamp))
            snippet(statusStr)
            icon(BitmapDescriptorFactory.defaultMarker(markerColor))
            if (!isEndpoint) {
                anchor(0.5f, 0.5f)
            }
        }
        map.addMarker(markerOptions)?.`object` = TrackMarkerInfo(
            title = formatTrackTime(event.timestamp),
            status = statusStr,
            action = "点击卡片在高德地图中打开"
        )
    }

    val latest = mapLocations.maxBy { (event, _) -> event.timestamp }
    map.moveCamera(
        CameraUpdateFactory.newLatLngZoom(
            latest.second,
            16f
        )
    )
}

private suspend fun getCurrentDeviceLocation(context: Context): Location? {
    getGmsLocation(context)?.let { return it }
    return getSystemLocation(context)
}

private suspend fun getGmsLocation(context: Context): Location? {
    val gmsAvailable = try {
        com.google.android.gms.common.GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == com.google.android.gms.common.ConnectionResult.SUCCESS
    } catch (e: Exception) {
        false
    }

    if (!gmsAvailable) {
        Log.w("MapScreen", "Google Play Services 不可用，改用系统定位")
        return null
    }

    return try {
        val fusedClient = LocationServices.getFusedLocationProviderClient(context)
        fusedClient.lastLocation.await() ?: fusedClient.getCurrentLocation(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY,
            CancellationTokenSource().token
        ).await()
    } catch (e: Exception) {
        Log.w("MapScreen", "GMS 定位失败，改用系统定位: ${e.message}")
        null
    }
}

private suspend fun getSystemLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        ?: return null

    return try {
        val providers = locationManager.getProviders(true)
        val lastLocation = providers
            .mapNotNull { provider -> locationManager.getLastKnownLocation(provider) }
            .maxByOrNull { it.time }

        lastLocation ?: withTimeoutOrNull(10_000L) {
            suspendCancellableCoroutine { continuation ->
                val provider = when {
                    locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> providers.firstOrNull()
                }

                if (provider == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                var resumed = false
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(location)
                            locationManager.removeUpdates(this)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    locationManager.removeUpdates(listener)
                }
                locationManager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
        }
    } catch (e: SecurityException) {
        Log.w("MapScreen", "系统定位失败：缺少定位权限")
        null
    } catch (e: Exception) {
        Log.w("MapScreen", "系统定位失败: ${e.message}")
        null
    }
}

private fun isNearLastLocation(location: Location, lastEvent: MonitorEvent?): Boolean {
    val lastLatitude = lastEvent?.latitude ?: return false
    val lastLongitude = lastEvent.longitude ?: return false
    val distance = FloatArray(1)
    Location.distanceBetween(
        lastLatitude,
        lastLongitude,
        location.latitude,
        location.longitude,
        distance
    )
    return distance[0] < LOCATION_DEDUP_DISTANCE_METERS
}

@Composable
fun MapScreen(dao: MonitorEventDao) {
    var selectedRange by remember { mutableStateOf(TimeRange.TODAY) }
    val context = LocalContext.current
    var aMapInstance by remember { mutableStateOf<AMap?>(null) }
    var isMapLoaded by remember { mutableStateOf(false) }

    // 打开页面时立即获取一次当前位置并记录
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            try {
                val location = getCurrentDeviceLocation(context)
                if (location != null) {
                    val latestLocation = dao.getLatestLocationEvent()
                    if (isNearLastLocation(location, latestLocation)) {
                        Log.d("MapScreen", "位置变化不足${LOCATION_DEDUP_DISTANCE_METERS}米，跳过页面打开记录")
                        return@LaunchedEffect
                    }

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
                    Log.w("MapScreen", "无法获取位置：GMS 和系统定位均未返回结果")
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

    val locations by dao.getLocationEventsBetween(startTime, Long.MAX_VALUE)
        .collectAsState(initial = emptyList())

    val recordedCount = locations.count { it.status == EventStatus.PENDING }
    val reportedCount = locations.count { it.status == EventStatus.SENT }

    LaunchedEffect(locations, aMapInstance, isMapLoaded) {
        val map = aMapInstance ?: return@LaunchedEffect
        if (isMapLoaded) {
            renderTrackOnMap(map, context, locations)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AMapView(
            modifier = Modifier.fillMaxSize(),
            onMapReady = { map ->
                map.uiSettings.isZoomControlsEnabled = false
                map.uiSettings.isMyLocationButtonEnabled = false
                map.uiSettings.isScaleControlsEnabled = true
                map.setInfoWindowAdapter(object : AMap.InfoWindowAdapter {
                    override fun getInfoWindow(marker: Marker): View {
                        return createTrackInfoWindow(context, marker)
                    }

                    override fun getInfoContents(marker: Marker): View? = null
                })
                map.setOnInfoWindowClickListener { marker ->
                    val uri = Uri.parse(
                        "https://uri.amap.com/marker?position=${marker.position.longitude},${marker.position.latitude}&name=轨迹点"
                    )
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                }
                map.setOnMapLoadedListener {
                    isMapLoaded = true
                    renderTrackOnMap(map, context, locations)
                }
                aMapInstance = map
            }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
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
            }
        }

        if (locations.isEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 32.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
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
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                shadowElevation = 6.dp
            ) {
                Column(modifier = Modifier.padding(vertical = 12.dp)) {
                    TrackSummaryBar(
                        totalCount = locations.size,
                        pendingCount = recordedCount,
                        reportedCount = reportedCount
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        itemsIndexed(locations, key = { _, e -> e.id }) { index, event ->
                            LocationPointItem(
                                event = event,
                                isFirst = index == 0,
                                isLast = index == locations.lastIndex,
                                onClick = {
                                    // 点击跳转到高德地图 App（如已安装）或网页版
                                    val latLng = event.toAmapLatLng(context)
                                    val uri = Uri.parse(
                                        if (latLng != null) {
                                            "https://uri.amap.com/marker?position=${latLng.longitude},${latLng.latitude}&name=轨迹点"
                                        } else {
                                            "https://uri.amap.com/marker?position=${event.longitude},${event.latitude}&name=轨迹点"
                                        }
                                    )
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            )
                        }
                        item { Spacer(modifier = Modifier.height(4.dp)) }
                    }
                }
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
    val timelineColor = when (event.status) {
        EventStatus.SENT -> LocationColor
        EventStatus.FAILED -> Error
        EventStatus.PENDING -> Warning
    }

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
                            text = formatTrackTime(event.timestamp),
                            style = MaterialTheme.typography.bodyMedium,
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
                            EventStatus.SENT -> "已发送"
                            EventStatus.FAILED -> "发送失败"
                            else -> "待发送"
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
                    Text(
                        text = "${event.title.ifBlank { "位置记录" }} · 点击在高德地图中打开",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = SimpleDateFormat("MM/dd", Locale.getDefault())
                        .format(Date(event.timestamp)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TrackSummaryBar(
    totalCount: Int,
    pendingCount: Int,
    reportedCount: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TrackSummaryItem(
                label = "轨迹点",
                value = totalCount.toString(),
                color = MaterialTheme.colorScheme.onSurface
            )
            TrackSummaryItem(
                label = "待发送",
                value = pendingCount.toString(),
                color = Warning
            )
            TrackSummaryItem(
                label = "已发送",
                value = reportedCount.toString(),
                color = LocationColor
            )
        }
    }
}

@Composable
private fun RowScope.TrackSummaryItem(
    label: String,
    value: String,
    color: androidx.compose.ui.graphics.Color
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatTrackTime(timestamp: Long): String {
    val today = java.util.Calendar.getInstance()
    val target = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    val prefix = if (
        today.get(java.util.Calendar.YEAR) == target.get(java.util.Calendar.YEAR) &&
        today.get(java.util.Calendar.DAY_OF_YEAR) == target.get(java.util.Calendar.DAY_OF_YEAR)
    ) {
        "今天"
    } else {
        SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(timestamp))
    }
    return "$prefix ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))}"
}
