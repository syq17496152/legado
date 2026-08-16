package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.legado.app.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * M3 阅读热力图日历（公共组件库三期 图表族）。
 *
 * 自包含 Column 卡片（Card 18dp 圆角包裹）；7 列网格周一起始 `chunked(7)`；
 * 格间距 2dp / 月份 Pill 间距 8dp；计数 `bodySmall`；格 ≥8dp 可点；
 * 颜色强度 `lerp(primaryContainer α0.42, primary, (value/max)²)`；月份 Pill `primary`。
 * 数据全部由参数注入，不依赖 ViewModel/DAO/Room，不查数据库。
 * 规格：ui-standards §3.4 `HeatmapCalendar`（task 12.39，from Suml-1/Legado_Max）。
 */
enum class HeatmapMode {
    COUNT,
    TIME
}

private const val HEATMAP_COUNT_BASELINE = 6
private const val HEATMAP_TIME_BASELINE_MINUTES = 120

/** 模式切换入口：次数 / 时长 两个 FilterChip。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeatmapCalendarStartAction(
    currentMode: HeatmapMode,
    onModeChanged: (HeatmapMode) -> Unit
) {
    Row {
        FilterChip(
            selected = currentMode == HeatmapMode.COUNT,
            onClick = { onModeChanged(HeatmapMode.COUNT) },
            label = { Text(stringResource(R.string.rr_heatmap_count)) },
            modifier = Modifier.padding(end = 4.dp)
        )
        FilterChip(
            selected = currentMode == HeatmapMode.TIME,
            onClick = { onModeChanged(HeatmapMode.TIME) },
            label = { Text(stringResource(R.string.rr_heatmap_duration)) }
        )
    }
}

/** 清除筛选入口：用于清除已选日期。 */
@Composable
fun HeatmapCalendarEndAction(
    onClearDate: () -> Unit
) {
    TextButton(onClick = onClearDate) {
        Icon(
            Icons.Default.Close,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            stringResource(R.string.rr_heatmap_clear_filter),
            color = MaterialTheme.colorScheme.error
        )
    }
}

/**
 * 主组件：自包含的阅读热力图日历区块。
 *
 * @param dailyReadCounts 每日阅读次数（key 为日期，value 为次数）
 * @param dailyReadTimes  每日阅读时长（key 为日期，value 为毫秒）
 * @param currentMode     当前展示模式（次数 / 时长）
 * @param selectedDate    当前选中的日期（可空）
 * @param onDateSelected  日期选择回调；再次点击已选日期时回传 null 表示清除
 * @param modifier        外部修饰符
 */
@Composable
fun HeatmapCalendarSection(
    dailyReadCounts: Map<LocalDate, Int>,
    dailyReadTimes: Map<LocalDate, Long>,
    currentMode: HeatmapMode,
    selectedDate: LocalDate?,
    onDateSelected: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier
) {
    val today = LocalDate.now()
    var currentYearMonth by remember { mutableStateOf(YearMonth.now()) }
    val timesStr = stringResource(R.string.rr_heatmap_times)
    val dayUnitStr = stringResource(R.string.rr_heatmap_day_unit)
    val prevMonthStr = stringResource(R.string.rr_heatmap_prev_month)
    val nextMonthStr = stringResource(R.string.rr_heatmap_next_month)
    val showByCountStr = stringResource(R.string.rr_heatmap_show_by_count)
    val showByDurationStr = stringResource(R.string.rr_heatmap_show_by_duration)

    val maxValue = remember(dailyReadCounts, dailyReadTimes, currentMode) {
        if (currentMode == HeatmapMode.COUNT) {
            (dailyReadCounts.values.maxOrNull() ?: 1).coerceAtLeast(HEATMAP_COUNT_BASELINE)
        } else {
            val maxTime = dailyReadTimes.values.maxOrNull() ?: 1L
            ((maxTime / 60000).toInt()).coerceAtLeast(HEATMAP_TIME_BASELINE_MINUTES)
        }
    }

    val monthDays = remember(currentYearMonth) {
        (1..currentYearMonth.lengthOfMonth()).map { currentYearMonth.atDay(it) }
    }
    val monthReadCount = remember(monthDays, dailyReadCounts) {
        monthDays.sumOf { dailyReadCounts[it] ?: 0 }
    }
    val monthReadTime = remember(monthDays, dailyReadTimes) {
        monthDays.sumOf { dailyReadTimes[it] ?: 0L }
    }
    val activeDays = remember(monthDays, dailyReadCounts, dailyReadTimes) {
        monthDays.count { (dailyReadCounts[it] ?: 0) > 0 || (dailyReadTimes[it] ?: 0L) > 0L }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = AppShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 月份导航行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = { currentYearMonth = currentYearMonth.minusMonths(1) }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = prevMonthStr
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(
                            R.string.rr_heatmap_year_month,
                            currentYearMonth.year,
                            currentYearMonth.monthValue
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (currentMode == HeatmapMode.COUNT) showByCountStr else showByDurationStr,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { currentYearMonth = currentYearMonth.plusMonths(1) }
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = nextMonthStr
                    )
                }
            }

            // 月份统计 Pill（间距 8dp，primary）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonthStatPill(
                    label = stringResource(R.string.rr_heatmap_read),
                    value = "$monthReadCount$timesStr",
                    modifier = Modifier.weight(1f)
                )
                MonthStatPill(
                    label = stringResource(R.string.rr_heatmap_duration),
                    value = formatHeatmapDuration(monthReadTime),
                    modifier = Modifier.weight(1f)
                )
                MonthStatPill(
                    label = stringResource(R.string.rr_heatmap_days),
                    value = "$activeDays$dayUnitStr",
                    modifier = Modifier.weight(1f)
                )
            }

            // 星期表头（周一起始，间距与格一致保证对齐）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                listOf(
                    stringResource(R.string.rr_heatmap_mon),
                    stringResource(R.string.rr_heatmap_tue),
                    stringResource(R.string.rr_heatmap_wed),
                    stringResource(R.string.rr_heatmap_thu),
                    stringResource(R.string.rr_heatmap_fri),
                    stringResource(R.string.rr_heatmap_sat),
                    stringResource(R.string.rr_heatmap_sun)
                ).forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center
                    )
                }
            }

            MonthCalendarGrid(
                yearMonth = currentYearMonth,
                dailyReadCounts = dailyReadCounts,
                dailyReadTimes = dailyReadTimes,
                mode = currentMode,
                maxValue = maxValue,
                selectedDate = selectedDate,
                today = today,
                onDateSelected = onDateSelected
            )

            HeatmapLegend()

            if (selectedDate != null) {
                SelectedDateSummary(
                    date = selectedDate,
                    readCount = dailyReadCounts[selectedDate] ?: 0,
                    readTime = dailyReadTimes[selectedDate] ?: 0L,
                    onClearDate = { onDateSelected(null) }
                )
            }
        }
    }
}

/** 7 列月份网格：周一起始 `chunked(7)`，格间距 2dp，格 ≥8dp 可点。 */
@Composable
private fun MonthCalendarGrid(
    yearMonth: YearMonth,
    dailyReadCounts: Map<LocalDate, Int>,
    dailyReadTimes: Map<LocalDate, Long>,
    mode: HeatmapMode,
    maxValue: Int,
    selectedDate: LocalDate?,
    today: LocalDate,
    onDateSelected: (LocalDate?) -> Unit
) {
    val firstDayOfMonth = yearMonth.atDay(1)
    val lastDayOfMonth = yearMonth.atEndOfMonth()
    val firstDayOfWeek = firstDayOfMonth.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    val days = remember(firstDayOfWeek, lastDayOfMonth) {
        generateSequence(firstDayOfWeek) { it.plusDays(1) }
            .takeWhile { !it.isAfter(lastDayOfMonth) || it.dayOfWeek != DayOfWeek.SUNDAY }
            .toList()
    }
    val weeks = days.chunked(7)

    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        weeks.forEach { week ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                week.forEach { date ->
                    val isCurrentMonth = date.month == yearMonth.month

                    val value = if (mode == HeatmapMode.COUNT) {
                        dailyReadCounts[date] ?: 0
                    } else {
                        ((dailyReadTimes[date] ?: 0L) / 60000).toInt()
                    }

                    val isSelected = date == selectedDate
                    val isToday = date == today
                    val backgroundColor = heatmapCellColor(
                        value = value,
                        maxValue = maxValue,
                        isCurrentMonth = isCurrentMonth,
                        isSelected = isSelected
                    )
                    val textColor = heatmapTextColor(
                        value = value,
                        maxValue = maxValue,
                        isCurrentMonth = isCurrentMonth,
                        isSelected = isSelected
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            // 格 ≥8dp
                            .sizeIn(minWidth = 8.dp, minHeight = 8.dp)
                            .clip(AppShapes.Chip)
                            .background(backgroundColor)
                            .then(
                                if (isSelected) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                                        shape = AppShapes.Chip
                                    )
                                } else if (isToday) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = AppShapes.Chip
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .clickable(enabled = isCurrentMonth) {
                                onDateSelected(if (isSelected) null else date)
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor,
                            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

/** 颜色强度图例：`lerp(primaryContainer α0.42, primary, (value/max)²)`。 */
@Composable
private fun HeatmapLegend() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.rr_heatmap_less),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(4.dp))
        repeat(5) { index ->
            val color = if (index == 0) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
            } else {
                val ratio = index / 4f
                val intensity = ratio * ratio
                lerp(
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
                    MaterialTheme.colorScheme.primary,
                    intensity
                )
            }
            Surface(
                modifier = Modifier
                    .size(14.dp)
                    .padding(1.dp),
                shape = AppShapes.Tiny,
                color = color
            ) {}
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            stringResource(R.string.rr_heatmap_more),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 月份统计 Pill：容器 primary、文字 onPrimary。 */
@Composable
private fun MonthStatPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = AppShapes.Chip,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                maxLines = 1
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

/** 已选日期摘要（含清除入口）。 */
@Composable
private fun SelectedDateSummary(
    date: LocalDate,
    readCount: Int,
    readTime: Long,
    onClearDate: () -> Unit
) {
    val timesStr = stringResource(R.string.rr_heatmap_times)
    val dateFormatStr = stringResource(R.string.rr_heatmap_date_format)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.Chip,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = date.format(DateTimeFormatter.ofPattern(dateFormatStr, Locale.CHINA)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${readCount}$timesStr · ${formatHeatmapDuration(readTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HeatmapCalendarEndAction(onClearDate = onClearDate)
        }
    }
}

/** 格子背景色：强度 = (value/max)²，在 primaryContainer(α0.42) 与 primary 之间插值。 */
@Composable
private fun heatmapCellColor(
    value: Int,
    maxValue: Int,
    isCurrentMonth: Boolean,
    isSelected: Boolean
): Color {
    if (isSelected) {
        return MaterialTheme.colorScheme.primary
    }
    if (!isCurrentMonth) {
        return MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)
    }
    if (value <= 0) {
        return MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.38f)
    }
    val ratio = (value.toFloat() / maxValue).coerceIn(0f, 1f)
    val intensity = ratio * ratio
    return lerp(
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        MaterialTheme.colorScheme.primary,
        intensity
    )
}

/** 格子文字颜色：按强度与选中态切换，保证可读性。 */
@Composable
private fun heatmapTextColor(
    value: Int,
    maxValue: Int,
    isCurrentMonth: Boolean,
    isSelected: Boolean
): Color {
    if (isSelected) {
        return MaterialTheme.colorScheme.onPrimary
    }
    if (!isCurrentMonth) {
        return MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    }
    if (value <= 0) {
        return MaterialTheme.colorScheme.onSurfaceVariant
    }
    val ratio = (value.toFloat() / maxValue).coerceIn(0f, 1f)
    return if (ratio > 0.72f) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
}

/**
 * 将毫秒时长格式化为可读文本（如 "2小时30分" / "1天3小时"）。
 * 天数/小时/分钟单位均取自字符串资源，避免硬编码中文。
 */
@Composable
private fun formatHeatmapDuration(totalMillis: Long): String {
    val totalMinutes = (totalMillis / 60000L).toInt().coerceAtLeast(0)
    val dayUnit = stringResource(R.string.rr_heatmap_duration_day)
    val hourUnit = stringResource(R.string.rr_heatmap_duration_hour)
    val minuteUnit = stringResource(R.string.rr_heatmap_duration_minute)
    return buildString {
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        if (days > 0) append(days).append(dayUnit)
        if (hours > 0) append(hours).append(hourUnit)
        if (minutes > 0 || isEmpty()) append(minutes).append(minuteUnit)
    }
}
