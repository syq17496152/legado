package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读器更多操作弹层内容区（P2-reader §3 阅读器族，S5）。
 *
 * 4 列 [LazyVerticalGrid] + [HorizontalPager] 分页（每页 8 项，20+ 动作分页承载），
 * 动作数据驱动（[ReaderMoreAction]）；网格项 ≥48dp、8dp 间距、容器 padding h16；
 * 色槽：图标 `primary` / 文字 `onSurface` / destructive `error`；点击动作 / 长按进编辑模式。
 * 由调用方通过 [AppModalBottomSheet] 包裹展示。
 * 规格：ui-standards §3.4 `ReaderMoreActionsSheet`（task 12.2B，from HapeLee）。
 */

/** 阅读器更多操作数据模型（id 用于编辑模式定位）。 */
data class ReaderMoreAction(
    val id: String,
    val icon: ImageVector,
    val label: String,
    val destructive: Boolean = false,
)

private const val PAGE_SIZE = 8
private const val GRID_COLUMNS = 4

@Composable
fun ReaderMoreActionsSheet(
    actions: List<ReaderMoreAction>,
    onActionClick: (ReaderMoreAction) -> Unit,
    onActionLongClick: ((ReaderMoreAction) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val pageCount = max(1, (actions.size + PAGE_SIZE - 1) / PAGE_SIZE)
    val pagerState = rememberPagerState(initialPage = 0) { pageCount }

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val start = page * PAGE_SIZE
            val pageActions = actions.subList(start, min(start + PAGE_SIZE, actions.size))
            LazyVerticalGrid(
                columns = GridCells.Fixed(GRID_COLUMNS),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(pageActions) { action ->
                    ReaderMoreActionItem(
                        action = action,
                        onClick = { onActionClick(action) },
                        onLongClick = onActionLongClick?.let { { it(action) } },
                    )
                }
            }
        }
        if (pageCount > 1) {
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            ) {
                repeat(pageCount) { index ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (selected) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                }
                            )
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderMoreActionItem(
    action: ReaderMoreAction,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val tint = if (action.destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    val labelColor = if (action.destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .sizeIn(minHeight = 48.dp)
            .clip(AppShapes.Button)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(vertical = 8.dp, horizontal = 4.dp),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelMedium,
            color = labelColor,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
