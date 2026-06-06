package com.xzygis.silentguard.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xzygis.silentguard.config.MonitorConfig
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MailSendRecordDao
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.data.MonitorEventDao
import com.xzygis.silentguard.diagnostics.AppDiagnostics
import com.xzygis.silentguard.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun DashboardScreen(
    isGuarding: Boolean,
    dao: MonitorEventDao,
    mailRecordDao: MailSendRecordDao,
    config: MonitorConfig,
    onToggleGuarding: (Boolean) -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToActivityLog: () -> Unit = {},
    onNavigateToMap: () -> Unit = {}
) {
    val context = LocalContext.current
    val todayStart = getTodayStartMillis()
    val totalToday by dao.getEventCountSince(todayStart).collectAsState(initial = 0)
    val smsToday by dao.getEventCountByTypeSince(EventType.SMS, todayStart).collectAsState(initial = 0)
    val recentEvents by dao.getRecentEvents(10).collectAsState(initial = emptyList())
    val pendingCount by dao.getEventCountByStatus(EventStatus.PENDING).collectAsState(initial = 0)
    val latestMail by mailRecordDao.getLatestRecord().collectAsState(initial = null)
    val smsPermissionGranted = AppDiagnostics.hasSmsPermission(context)
    val smsNotificationReadEnabled = AppDiagnostics.hasNotificationReadAccess(context)
    val locationPermissionGranted = AppDiagnostics.hasLocationPermission(context)
    val batteryReady = AppDiagnostics.isIgnoringBatteryOptimizations(context)
    val mailConfigured = config.senderEmail.isNotBlank() &&
            config.senderPassword.isNotBlank() &&
            config.recipientEmail.isNotBlank()
    val smsReady = smsPermissionGranted || smsNotificationReadEnabled
    val healthItems = listOf(
        HealthItem(
            "短信记录", smsReady,
            if (smsPermissionGranted) "短信权限已开启" else if (smsNotificationReadEnabled) "短信通知读取已开启" else "开启短信权限，或允许读取短信通知",
            onClick = if (smsReady) onNavigateToActivityLog else {
                { context.startActivity(AppDiagnostics.notificationAccessIntent()) }
            }
        ),
        HealthItem(
            "定位记录", locationPermissionGranted,
            if (locationPermissionGranted) "定位权限已开启" else "需要定位权限",
            onClick = if (locationPermissionGranted) onNavigateToMap else {
                { context.startActivity(AppDiagnostics.appDetailsIntent(context)) }
            }
        ),
        HealthItem(
            "邮件发送", mailConfigured,
            if (mailConfigured) "最近: ${latestMail?.status ?: "暂无记录"}" else "请先配置邮箱",
            onClick = if (!mailConfigured) onNavigateToSettings else null
        ),
        HealthItem(
            "后台运行", batteryReady,
            if (batteryReady) "已忽略电池优化" else "建议允许后台运行",
            onClick = if (batteryReady) null else {
                { context.startActivity(AppDiagnostics.batteryOptimizationIntent()) }
            }
        )
    )

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
                isGuarding = isGuarding,
                healthItems = healthItems,
                onToggleGuarding = onToggleGuarding
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
                    label = "今日记录",
                    color = Accent,
                    surfaceColor = AccentSurface
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = smsToday.toString(),
                    label = "短信记录",
                    color = SmsColor,
                    surfaceColor = SmsSurface
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    value = pendingCount.toString(),
                    label = "待发送",
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

private data class HealthItem(
    val label: String,
    val isReady: Boolean,
    val description: String,
    val onClick: (() -> Unit)? = null
)

@Composable
private fun StatusHero(
    isGuarding: Boolean,
    healthItems: List<HealthItem>,
    onToggleGuarding: (Boolean) -> Unit
) {
    val issueCount = healthItems.count { !it.isReady }
    val guardState = when {
        !isGuarding -> GuardState.STOPPED
        issueCount == 0 -> GuardState.HEALTHY
        else -> GuardState.NEEDS_ATTENTION
    }
    val statusColor by animateColorAsState(
        targetValue = guardState.color,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "statusColor"
    )
    val infiniteTransition = rememberInfiniteTransition(label = "guardBreathing")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingScale"
    )
    val breathingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.18f,
        targetValue = 0.38f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingAlpha"
    )
    val indicatorScale = if (isGuarding) breathingScale else 0.9f
    val haloAlpha = if (isGuarding) breathingAlpha else 0.16f

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 状态指示灯
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .scale(indicatorScale)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                statusColor.copy(alpha = haloAlpha),
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
                text = guardState.title,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = guardState.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                healthItems.forEach { item ->
                    HealthStatusRow(item = item)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleGuarding(!isGuarding) },
                shape = RoundedCornerShape(14.dp),
                color = if (isGuarding) ErrorSurface else Accent
            ) {
                Text(
                    text = if (isGuarding) "停止守护" else "启动守护",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 13.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isGuarding) Error else OnPrimary,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private enum class GuardState(
    val title: String,
    val subtitle: String,
    val color: androidx.compose.ui.graphics.Color
) {
    HEALTHY("守护正常", "短信记录、位置记录与邮件发送已准备好", Success),
    NEEDS_ATTENTION("需要处理", "有关键能力未就绪，建议先完成配置", Warning),
    STOPPED("守护已停止", "启动后将开始记录并按计划发送", TextTertiary)
}

@Composable
private fun HealthStatusRow(item: HealthItem) {
    val clickModifier = if (item.onClick != null) {
        Modifier.clickable { item.onClick.invoke() }
    } else {
        Modifier
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                color = if (item.isReady) SuccessSurface else WarningSurface,
                shape = RoundedCornerShape(12.dp)
            )
            .then(clickModifier)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (item.isReady) Success else Warning)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = if (item.isReady) "正常" else "待处理",
            style = MaterialTheme.typography.labelSmall,
            color = if (item.isReady) Success else Warning,
            fontWeight = FontWeight.SemiBold
        )
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
                text = "启动守护后将在此显示记录",
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
