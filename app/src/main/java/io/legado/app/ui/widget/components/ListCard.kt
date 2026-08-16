package io.legado.app.ui.widget.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 列表条目卡片原语（S2 列表工具，公共组件库三期）。
 *
 * `Surface` clip 圆角 **18dp** + 背景 + `heightIn`；content 内边距由 [BookListCardMetrics] 传入（默认 h16）；
 * 布局与交互解耦，content 以 lambda 抛出；`combinedClickable`（点击+长按由调用方回调）。
 * 容器色默认 `surface`（暗色 `surfaceVariant` lerp 见 ui-standards §4.5，由调用方按需传入）。
 * 规格：ui-standards §3.4 `ListCard`（task 12.2F，from legado-archive BookListCardComponents）。
 */

/** ListCard 度量参数（布局与交互解耦）。 */
data class BookListCardMetrics(
    val cornerRadius: Dp = 18.dp,
    val contentPadding: PaddingValues = PaddingValues(16.dp),
    val minHeight: Dp = 72.dp,
)

@Composable
fun ListCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    metrics: BookListCardMetrics = BookListCardMetrics(),
    containerColor: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = metrics.minHeight)
            .clip(RoundedCornerShape(metrics.cornerRadius))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(metrics.cornerRadius),
        color = containerColor,
    ) {
        Box(modifier = Modifier.padding(metrics.contentPadding)) {
            content()
        }
    }
}
