package io.legado.app.ui.source.debug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.model.Debug
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
    DebugChipRow {
        items(DebugFilter.entries, key = { it.name }) { filter ->
            FilterChip(
                selected = selected == filter,
                onClick = { onSelect(filter) },
                label = { Text(filter.title) },
            )
        }
    }
}

/**
 * 结构化事件卡片：类型标题 + 相对耗时 + 绝对时间戳 + 消息预览（点击看全文）。
 * 着色走取色唯一基线（MaterialTheme M3 角色，ThemeSpec 提供 container 角色）：
 * 错误=errorContainer / 响应=primaryContainer / 完成=tertiaryContainer / 过程=surfaceContainerHigh。
 */
@Composable
fun DebugEntryCard(
    entry: DebugEntryUi,
    kindTitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (container, content) = when (entry.kind) {
        -1 -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
        10, 20, 30, 40 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        1000 -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHigh to MaterialTheme.colorScheme.onSurfaceVariant
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
