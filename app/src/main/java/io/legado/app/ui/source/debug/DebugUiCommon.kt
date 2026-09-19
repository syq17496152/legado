package io.legado.app.ui.source.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.model.Debug
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * debug-page-redesign：书源/订阅源调试页共享 UI 模型与组件（对标 MD3阅读 调试页）。
 * 结构化事件卡片 / 过滤 Chips / 横滑 Chips 行在此单点维护，两个 Screen 复用。
 */

/** 日志过滤维度（AD：学 MD3 全部/过程/响应/错误四档） */
enum class DebugFilter(val title: String) {
    ALL("全部"),
    MESSAGES("过程"),
    RESPONSES("响应"),
    ERRORS("错误");

    fun matches(kind: Int): Boolean = when (this) {
        ALL -> true
        MESSAGES -> kind == 1 || kind == 1000
        RESPONSES -> kind == 10 || kind == 20 || kind == 30 || kind == 40
        ERRORS -> kind == -1
    }
}

/** 列表条目 UI 模型（kind 语义见 [Debug.DebugEvent]） */
data class DebugEntryUi(
    val id: Long,
    val kind: Int,
    val message: String,
    val timestamp: Long,
    val elapsedMillis: Long,
)

fun Debug.DebugEvent.toEntryUi(id: Long): DebugEntryUi =
    DebugEntryUi(id = id, kind = kind, message = message, timestamp = timestamp, elapsedMillis = elapsedMillis)

/** 导出文本渲染：类型标题 + 相对耗时 + 绝对时间戳 + 消息全文（供复制/分享） */
fun List<DebugEntryUi>.buildExportText(kindTitle: (Int) -> String): String =
    joinToString("\n\n") { entry ->
        buildString {
            append("== ${kindTitle(entry.kind)}")
            append(" (+%.3fs)".format(entry.elapsedMillis / 1000.0))
            append(" ")
            append(SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp)))
            append(" ==\n")
            append(entry.message)
        }
    }

/**
 * 调试结论条（F65，ui-subpage-optimization）：把「判断调试是否通过」从读完所有日志
 * 降为看一行结论。
 *
 * - 成功：✓ 调试完成 · 总耗时 X.Xs（绿）
 * - 失败：✗ 调试失败 · 错误概要（danger 红）
 * - 取消：⏹ 已取消 · 总耗时 X.Xs（中性）
 *
 * 总耗时 = 末事件相对耗时（[DebugEntryUi.elapsedMillis] 数据现成），纯展示层，不引入链路追踪。
 */
@Composable
fun DebugSummaryBar(
    phaseKind: Int,
    errorMessage: String?,
    totalElapsedMillis: Long,
    modifier: Modifier = Modifier,
) {
    val settings = rememberAppSettingPalette()

    @Composable
    fun row(label: String, detail: String, container: Color, content: Color) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = when (phaseKind) {
                            1000 -> Icons.Default.Check
                            -1 -> Icons.Default.ErrorOutline
                            else -> Icons.Default.Stop
                        },
                        contentDescription = null,
                        tint = content,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
                Text(detail, style = MaterialTheme.typography.labelMedium, color = content)
            }
        }
    }

    when (phaseKind) {
        1000 ->
            row(
                label = "调试完成",
                detail = "总耗时 %.2fs".format(totalElapsedMillis / 1000.0),
                container = Color(settings.row),
                // success 语义色走既有登记资源（theme-tokens §9.3，非硬编码）
                content = colorResource(R.color.success),
            )
        -1 ->
            row(
                label = "调试失败",
                detail = errorMessage?.take(24) ?: "存在错误",
                container = settings.danger.copy(alpha = 0.12f),
                content = settings.danger,
            )
        else ->
            row(
                label = "已取消",
                detail = "总耗时 %.2fs".format(totalElapsedMillis / 1000.0),
                container = Color(settings.row),
                content = settings.secondaryText,
            )
    }
}

/** 横滑 Chips 行（fadingEdge 由使用方按需叠加，先保持简单） */
@Composable
fun DebugChipRow(
    content: LazyListScope.() -> Unit
) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

@Composable
fun DebugFilterChips(
    selected: DebugFilter,
    onSelect: (DebugFilter) -> Unit,
) {
    // 取色基线归位（2026-09-13）：palette 直色，禁止 M3 colorScheme 派生色
    val settings = rememberAppSettingPalette()
    DebugChipRow {
        items(DebugFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.title) },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color(settings.row),
                    labelColor = settings.secondaryText,
                    selectedContainerColor = settings.accent,
                    selectedLabelColor = settings.onAccent
                )
            )
        }
    }
}

/**
 * 结构化事件卡片：类型标题 + 相对耗时 + 绝对时间戳 + 消息预览（点击看全文）。
 * 着色走取色唯一基线（AppSettingPalette 直色，2026-09-13 归位）：
 * 错误=danger 淡底 / 响应=accent 淡底 / 完成=row+primaryText / 过程=row+secondaryText。
 */
@Composable
fun DebugEntryCard(
    entry: DebugEntryUi,
    kindTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = rememberAppSettingPalette()
    val (container, content) = when (entry.kind) {
        -1 -> settings.danger.copy(alpha = 0.16f) to settings.danger
        10, 20, 30, 40 -> settings.accent.copy(alpha = 0.16f) to settings.accent
        1000 -> Color(settings.row) to settings.primaryText
        else -> Color(settings.row) to settings.secondaryText
    }
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(kindTitle, style = MaterialTheme.typography.labelMedium)
                Text(
                    "+%.3fs".format(entry.elapsedMillis / 1000.0),
                    style = MaterialTheme.typography.labelSmall,
                    color = settings.secondaryText,
                )
            }
            Text(
                entry.message,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = settings.secondaryText,
            )
        }
    }
}
