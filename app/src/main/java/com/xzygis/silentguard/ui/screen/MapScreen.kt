package com.xzygis.silentguard.ui.screen

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.xzygis.silentguard.config.AppConfig
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.data.MonitorEventDao
import com.xzygis.silentguard.location.AmapReverseGeocoder
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
    val statusColor: Int,
    val time: String,
    val address: String?,
    val coordinate: String,
    val accuracy: String
)

private const val LOCATION_DEDUP_DISTANCE_METERS = 100f

private fun Context.px(value: Int): Int {
    return (value * resources.displayMetrics.density).toInt()
}

private fun createNumberedMarkerIcon(
    context: Context,
    number: Int,
    status: EventStatus
) = BitmapDescriptorFactory.fromBitmap(
    Bitmap.createBitmap(context.px(38), context.px(38), Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this)
        val fillColor = when (status) {
            EventStatus.SENT -> AndroidColor.parseColor("#0F9F7A")
            EventStatus.FAILED -> AndroidColor.parseColor("#DC2626")
            EventStatus.PENDING -> AndroidColor.parseColor("#F59E0B")
        }
        val strokeWidth = context.px(2).toFloat()
        val bounds = RectF(
            strokeWidth,
            strokeWidth,
            width - strokeWidth,
            height - strokeWidth
        )

        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.WHITE
            textAlign = Paint.Align.CENTER
            textSize = context.px(if (number < 100) 15 else 11).toFloat()
            typeface = Typeface.DEFAULT_BOLD
        }

        canvas.drawOval(bounds, fillPaint)
        canvas.drawOval(bounds, strokePaint)

        val label = if (number > 99) "99+" else number.toString()
        val textY = height / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(label, width / 2f, textY, textPaint)
    }
)

private fun createTrackInfoWindow(context: Context, marker: Marker): View {
    val info = marker.`object` as? TrackMarkerInfo
    val titleText = info?.title ?: marker.title.orEmpty()
    val statusText = info?.status ?: marker.snippet.orEmpty()
    val detailLines = listOfNotNull(
        info?.time,
        info?.address?.let { "地址: $it" },
        info?.coordinate,
        info?.accuracy
    )

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
            setTextColor(info?.statusColor ?: AndroidColor.parseColor("#0F9F7A"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            includeFontPadding = false
            setPadding(0, context.px(8), 0, 0)
        })

        addView(AndroidTextView(context).apply {
            text = detailLines.joinToString("\n")
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
        val statusStr = when (event.status) {
            EventStatus.SENT -> "已上报"
            EventStatus.FAILED -> "上报失败"
            EventStatus.PENDING -> "待上报"
        }
        val statusColor = when (event.status) {
            EventStatus.SENT -> AndroidColor.parseColor("#0F9F7A")
            EventStatus.FAILED -> AndroidColor.parseColor("#DC2626")
            EventStatus.PENDING -> AndroidColor.parseColor("#F59E0B")
        }
        val sequence = index + 1
        val timeText = formatTrackTime(event.timestamp)
        val coordinateText = "坐标: %.6f, %.6f".format(event.latitude, event.longitude)
        val accuracyText = "精度: ${event.accuracy?.let { "%.0f米".format(it) } ?: "未知"}"

        val markerOptions = MarkerOptions().apply {
            position(latLng)
            title("轨迹点 #$sequence")
            snippet(statusStr)
            icon(createNumberedMarkerIcon(context, sequence, event.status))
            anchor(0.5f, 0.5f)
        }
        map.addMarker(markerOptions)?.`object` = TrackMarkerInfo(
            title = "轨迹点 #$sequence",
            status = statusStr,
            statusColor = statusColor,
            time = "时间: $timeText",
            address = AmapReverseGeocoder.extractAddress(event.detail),
            coordinate = coordinateText,
            accuracy = accuracyText
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

                    val config = AppConfig(context).getConfig()
                    val address = AmapReverseGeocoder.resolveAddress(
                        context = context,
                        apiKey = config.amapWebApiKey,
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                    val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    val event = MonitorEvent(
                        type = EventType.LOCATION,
                        title = "手动定位",
                        summary = AmapReverseGeocoder.formatSummary(
                            address,
                            location.latitude,
                            location.longitude
                        ),
                        detail = buildString {
                            if (address != null) appendLine("地址: $address")
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
                map.setOnMarkerClickListener { marker ->
                    map.animateCamera(CameraUpdateFactory.newLatLngZoom(marker.position, 16f))
                    marker.showInfoWindow()
                    true
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
                .padding(top = 12.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                TimeRange.entries.forEach { range ->
                    val selected = selectedRange == range
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) {
                                    LocationColor.copy(alpha = 0.14f)
                                } else {
                                    androidx.compose.ui.graphics.Color.Transparent
                                }
                            )
                            .clickable { selectedRange = range }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = range.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) LocationColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
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
        }
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
