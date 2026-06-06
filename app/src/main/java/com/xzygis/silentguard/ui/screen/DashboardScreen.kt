package com.xzygis.silentguard.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.data.MonitorEventDao
import com.xzygis.silentguard.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun DashboardScreen(
    isMonitoring: Boolean,
    dao: MonitorEventDao,
    onToggleMonitoring: (Boolean) -> Unit = {}
) {
    val todayStart = getTodayStartMillis()
    val totalToday by dao.getEventCountSince(todayStart).collectAsState(initial = 0)
    val smsToday by dao.getEventCountByTypeSince(EventType.SMS, todayStart).collectAsState(initial = 0)
    val locationToday by dao.getEventCountByTypeSince(EventType.LOCATION, todayStart).collectAsState(initial = 0)
    val recentEvents by dao.getRecentEvents(10).collectAsState(initial = emptyList())

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { Spacer(modifier = Modifier.height(8.dp)) }

        // 状态英雄区
        item {
            StatusHero(
                isMonitoring = isMonitoring,
                onToggleMonitoring = onToggleMonitoring
            )
        }

        // 统计卡片
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = totalToday.toString(),
                    label = "今日事件",
                    color = Accent,
                    surfaceColor = AccentSurface
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = smsToday.toString(),
                    label = "短信拦截",
                    color = SmsColor,
                    surfaceColor = SmsSurface
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = locationToday.toString(),
                    label = "位置上报",
                    color = LocationColor,
                    surfaceColor = LocationSurface
                )
            }
        }

        // 最近活动
        item {
            Text(
                text = "最近活动",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        if (recentEvents.isEmpty()) {
            item {
                EmptyState()
            }
        } else {
            items(recentEvents, key = { it.id }) { event ->
                EventTimelineItem(event = event)
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun StatusHero(isMonitoring: Boolean, onToggleMonitoring: (Boolean) -> Unit) {
    val statusColor by animateColorAsState(
        targetValue = if (isMonitoring) Success else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "statusColor"
    )
    val pulseScale by animateFloatAsState(
        targetValue = if (isMonitoring) 1f else 0.9f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "pulseScale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleMonitoring(!isMonitoring) },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态指示灯
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                statusColor.copy(alpha = 0.3f),
                                statusColor.copy(alpha = 0.05f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = if (isMonitoring) "守护中" else "已停止",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (isMonitoring) "点击停止监控" else "点击启动监控",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatCard(
    modifier: Modifier = Modifier,
    value: String,
    label: String,
    color: androidx.compose.ui.graphics.Color,
    surfaceColor: androidx.compose.ui.graphics.Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = surfaceColor
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                color = color,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = color.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun EventTimelineItem(event: MonitorEvent) {
    val typeColor = when (event.type) {
        EventType.SMS -> SmsColor
        EventType.LOCATION -> LocationColor
    }
    val typeSurface = when (event.type) {
        EventType.SMS -> SmsSurface
        EventType.LOCATION -> LocationSurface
    }
    val typeLabel = when (event.type) {
        EventType.SMS -> "SMS"
        EventType.LOCATION -> "GPS"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 类型标签
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(typeSurface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = typeColor,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Text(
                    text = event.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = formatTime(event.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val statusColor = when (event.status) {
                    EventStatus.SENT -> Success
                    EventStatus.PENDING -> Warning
                    EventStatus.FAILED -> Error
                }
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "暂无活动记录",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "启动监控后将在此显示事件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "刚刚"
        diff < TimeUnit.HOURS.toMillis(1) -> "${diff / TimeUnit.MINUTES.toMillis(1)}分钟前"
        diff < TimeUnit.DAYS.toMillis(1) -> "${diff / TimeUnit.HOURS.toMillis(1)}小时前"
        else -> SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun getTodayStartMillis(): Long {
    val cal = java.util.Calendar.getInstance()
    cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
    cal.set(java.util.Calendar.MINUTE, 0)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
