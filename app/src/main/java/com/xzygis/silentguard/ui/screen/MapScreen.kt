package com.xzygis.silentguard.ui.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.data.MonitorEventDao
import com.xzygis.silentguard.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
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

    val now = System.currentTimeMillis()
    val startTime = if (selectedRange.hours > 0) {
        now - selectedRange.hours * 3600_000L
    } else {
        0L
    }

    val locations by dao.getLocationEventsBetween(startTime, now)
        .collectAsState(initial = emptyList())

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

            // 轨迹点数
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = LocationSurface
            ) {
                Text(
                    text = "${locations.size} 个点",
                    style = MaterialTheme.typography.labelMedium,
                    color = LocationColor,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        if (locations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
                        text = "位置上报后将在此显示轨迹",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            // 轨迹概览卡片
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                TrajectoryMiniMap(
                    locations = locations,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(16.dp)
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
                            val uri = Uri.parse("https://maps.google.com/maps?q=${event.latitude},${event.longitude}")
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
private fun TrajectoryMiniMap(
    locations: List<MonitorEvent>,
    modifier: Modifier = Modifier
) {
    val lineColor = LocationColor
    val dotColor = LocationColor
    val bgGridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.1f)

    Canvas(modifier = modifier) {
        if (locations.size < 2) {
            // 单点 - 画一个大点
            if (locations.size == 1) {
                drawCircle(
                    color = dotColor,
                    radius = 8.dp.toPx(),
                    center = Offset(size.width / 2, size.height / 2)
                )
            }
            return@Canvas
        }

        val lats = locations.mapNotNull { it.latitude }
        val lngs = locations.mapNotNull { it.longitude }
        val minLat = lats.min()
        val maxLat = lats.max()
        val minLng = lngs.min()
        val maxLng = lngs.max()

        val latRange = (maxLat - minLat).coerceAtLeast(0.001)
        val lngRange = (maxLng - minLng).coerceAtLeast(0.001)

        val padding = 24.dp.toPx()
        val drawWidth = size.width - padding * 2
        val drawHeight = size.height - padding * 2

        fun toOffset(lat: Double?, lng: Double?): Offset {
            if (lat == null || lng == null) return Offset(size.width / 2, size.height / 2)
            val x = padding + ((lng - minLng) / lngRange * drawWidth).toFloat()
            val y = padding + ((maxLat - lat) / latRange * drawHeight).toFloat()
            return Offset(x, y)
        }

        // 画连线
        for (i in 0 until locations.size - 1) {
            val start = toOffset(locations[i].latitude, locations[i].longitude)
            val end = toOffset(locations[i + 1].latitude, locations[i + 1].longitude)
            drawLine(
                color = lineColor.copy(alpha = 0.4f),
                start = start,
                end = end,
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round,
                pathEffect = PathEffect.cornerPathEffect(8.dp.toPx())
            )
        }

        // 画点
        locations.forEachIndexed { index, event ->
            val offset = toOffset(event.latitude, event.longitude)
            val isEndpoint = index == 0 || index == locations.lastIndex
            drawCircle(
                color = if (isEndpoint) dotColor else dotColor.copy(alpha = 0.6f),
                radius = if (isEndpoint) 6.dp.toPx() else 3.dp.toPx(),
                center = offset
            )
            if (isEndpoint) {
                drawCircle(
                    color = dotColor.copy(alpha = 0.2f),
                    radius = 12.dp.toPx(),
                    center = offset
                )
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
    val timelineColor = LocationColor

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
                    Text(
                        text = "%.6f, %.6f".format(event.latitude, event.longitude),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
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
