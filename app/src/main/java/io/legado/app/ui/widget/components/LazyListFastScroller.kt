package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 书架快速滚动条（对应原版 RecyclerView FastScroll 的右侧拖动条）。
 *
 * 支持 [LazyListState]（列表布局）与 [LazyGridState]（网格布局）两种状态。
 * 基于 index 近似计算滑块位置与高度（不依赖精确 item 高度），支持拖动跳转：
 * - 滑块高度 = 视口可见 item 数 / 总 item 数，最小 24dp
 * - 拖动时按拖动距离比例换算目标 index 并 scrollToItem
 *
 * 简化说明：近似实现，未做 item 高度精确测量（书架行高基本一致，误差可忽略）。
 */
@Composable
fun LazyListFastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    thumbColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
) {
    FastScrollerCore(
        totalCount = { state.layoutInfo.totalItemsCount },
        firstVisibleIndex = { state.firstVisibleItemIndex },
        visibleCount = { state.layoutInfo.visibleItemsInfo.size },
        onScrollToIndex = { index -> state.scrollToItem(index) },
        modifier = modifier,
        trackColor = trackColor,
        thumbColor = thumbColor,
    )
}

/**
 * 网格布局版本（LazyVerticalGrid）。
 */
@Composable
fun LazyListFastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    thumbColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
) {
    FastScrollerCore(
        totalCount = { state.layoutInfo.totalItemsCount },
        firstVisibleIndex = { state.firstVisibleItemIndex },
        visibleCount = { state.layoutInfo.visibleItemsInfo.size },
        onScrollToIndex = { index -> state.scrollToItem(index) },
        modifier = modifier,
        trackColor = trackColor,
        thumbColor = thumbColor,
    )
}

@Composable
private fun FastScrollerCore(
    totalCount: () -> Int,
    firstVisibleIndex: () -> Int,
    visibleCount: () -> Int,
    onScrollToIndex: suspend (Int) -> Unit,
    modifier: Modifier,
    trackColor: Color,
    thumbColor: Color,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var containerHeightPx by remember { mutableStateOf(0) }
    // 拖动累计位移（px），用于计算目标位置
    var dragOffsetY by remember { mutableFloatStateOf(0f) }

    val total = totalCount()
    val firstVisible = firstVisibleIndex()
    val visible = visibleCount().coerceAtLeast(1)

    val thumbHeightPx = if (total > 0 && containerHeightPx > 0) {
        (containerHeightPx * visible / total.toFloat()).toInt().coerceAtLeast(24)
    } else {
        0
    }
    val maxThumbOffsetPx = (containerHeightPx - thumbHeightPx).coerceAtLeast(0)
    val thumbOffsetPx = if (total > 1 && containerHeightPx > 0) {
        (maxThumbOffsetPx * firstVisible / (total - 1).toFloat()).toInt()
    } else {
        0
    }

    Box(
        modifier = modifier
            .width(18.dp)
            .fillMaxHeight()
            .onSizeChanged { containerHeightPx = it.height }
            .background(trackColor, AppShapes.Chip)
            .pointerInput(total, maxThumbOffsetPx, containerHeightPx) {
                detectDragGestures(
                    onDragStart = {
                        dragOffsetY = thumbOffsetPx.toFloat()
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetY += dragAmount.y
                        if (total > 1 && maxThumbOffsetPx > 0) {
                            val progress = (dragOffsetY / maxThumbOffsetPx).coerceIn(0f, 1f)
                            val targetIndex = (progress * (total - 1)).roundToInt()
                            scope.launch { onScrollToIndex(targetIndex) }
                        }
                    },
                    onDragEnd = { dragOffsetY = 0f },
                    onDragCancel = { dragOffsetY = 0f },
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        if (thumbHeightPx > 0) {
            Box(
                modifier = Modifier
                    .width(18.dp)
                    .height(with(density) { thumbHeightPx.toDp() })
                    .offset(y = with(density) { thumbOffsetPx.toDp() })
                    .clip(AppShapes.Chip)
                    .background(thumbColor),
            )
        }
    }
}
