package com.xzygis.silentguard.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xzygis.silentguard.data.EventStatus
import com.xzygis.silentguard.data.EventType
import com.xzygis.silentguard.data.MailSendRecord
import com.xzygis.silentguard.data.MailSendRecordDao
import com.xzygis.silentguard.data.MailSendStatus
import com.xzygis.silentguard.data.MonitorEvent
import com.xzygis.silentguard.data.MonitorEventDao
import com.xzygis.silentguard.location.AmapReverseGeocoder
import com.xzygis.silentguard.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private enum class FilterType(val label: String) {
    ALL("全部"), SMS("短信"), LOCATION("位置"), MAIL("邮件")
}

@Composable
fun ActivityLogScreen(
    dao: MonitorEventDao,
    mailRecordDao: MailSendRecordDao
) {
    var selectedFilter by remember { mutableStateOf(FilterType.ALL) }
    var expandedEventId by remember { mutableStateOf<Long?>(null) }
    var expandedMailRecordId by remember { mutableStateOf<Long?>(null) }

    val events by when (selectedFilter) {
        FilterType.ALL -> dao.getAllEvents()
        FilterType.SMS -> dao.getEventsByType(EventType.SMS)
        FilterType.LOCATION -> dao.getEventsByType(EventType.LOCATION)
        FilterType.MAIL -> dao.getAllEvents()
    }.collectAsState(initial = emptyList())
    val mailRecords by mailRecordDao.getAllRecords().collectAsState(initial = emptyList())
    val isMailTab = selectedFilter == FilterType.MAIL
    val isEmpty = if (isMailTab) mailRecords.isEmpty() else events.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 筛选栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterType.entries.forEach { filter ->
                val selected = selectedFilter == filter
                FilterChip(
                    selected = selected,
                    onClick = { selectedFilter = filter },
                    label = {
                        Text(
                            text = filter.label,
                            style = MaterialTheme.typography.labelLarge
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Accent,
                        selectedLabelColor = OnPrimary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }

        if (isEmpty) {
            // 空状态
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "没有记录",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (selectedFilter) {
                            FilterType.ALL -> "记录将在守护启动后出现"
                            FilterType.SMS -> "暂无短信记录"
                            FilterType.LOCATION -> "暂无位置记录"
                            FilterType.MAIL -> "暂无邮件发送记录"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (isMailTab) {
                    val groupedRecords = mailRecords.groupBy { dateLabel(it.timestamp) }

                    groupedRecords.forEach { (dateLabel, records) ->
                        dateHeader(dateLabel)

                        items(records, key = { "mail-${it.id}" }) { record ->
                            val isExpanded = expandedMailRecordId == record.id
                            MailRecordCard(
                                record = record,
                                isExpanded = isExpanded,
                                onClick = {
                                    expandedMailRecordId = if (isExpanded) null else record.id
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                } else {
                    val groupedEvents = events.groupBy { dateLabel(it.timestamp) }

                    groupedEvents.forEach { (dateLabel, dayEvents) ->
                        dateHeader(dateLabel)

                        items(dayEvents, key = { "event-${it.id}" }) { event ->
                            val isExpanded = expandedEventId == event.id
                            EventCard(
                                event = event,
                                isExpanded = isExpanded,
                                onClick = {
                                    expandedEventId = if (isExpanded) null else event.id
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(16.dp)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.dateHeader(dateLabel: String) {
    item {
        Text(
            text = dateLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}

@Composable
private fun EventCard(
    event: MonitorEvent,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
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
    val statusLabel = when (event.status) {
        EventStatus.SENT -> "已发送"
        EventStatus.PENDING -> "待发送"
        EventStatus.FAILED -> "发送失败"
    }
    val statusColor = when (event.status) {
        EventStatus.SENT -> Success
        EventStatus.PENDING -> Warning
        EventStatus.FAILED -> Error
    }
    val address = if (event.type == EventType.LOCATION) {
        AmapReverseGeocoder.extractAddress(event.detail)
    } else {
        null
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = event.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(event.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            // 展开详情
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Text(
                            text = event.detail.ifBlank { event.summary },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    if (event.latitude != null && event.longitude != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        if (address != null) {
                            Text(
                                text = "地址: $address",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "%.6f, %.6f".format(event.latitude, event.longitude),
                                style = MaterialTheme.typography.labelSmall,
                                color = LocationColor
                            )
                            if (event.accuracy != null) {
                                Text(
                                    text = "精度 %.0f米".format(event.accuracy),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MailRecordCard(
    record: MailSendRecord,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val statusLabel = when (record.status) {
        MailSendStatus.SENT -> "发送成功"
        MailSendStatus.FAILED -> "发送失败"
    }
    val statusColor = when (record.status) {
        MailSendStatus.SENT -> Success
        MailSendStatus.FAILED -> Error
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AccentSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MAIL",
                        style = MaterialTheme.typography.labelSmall,
                        color = Accent,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.subject,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = record.recipient.ifBlank { "未填写接收邮箱" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = SimpleDateFormat("HH:mm", Locale.getDefault())
                            .format(Date(record.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = statusColor.copy(alpha = 0.1f)
                    ) {
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "接收邮箱: ${record.recipient.ifBlank { "未填写" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (record.errorMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "失败原因: ${record.errorMessage}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Error
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun dateLabel(timestamp: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = timestamp }
    val today = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }

    return when {
        isSameDay(cal, today) -> "今天"
        isSameDay(cal, yesterday) -> "昨天"
        else -> SimpleDateFormat("M月d日", Locale.getDefault()).format(Date(timestamp))
    }
}

private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
    return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
            cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
}
