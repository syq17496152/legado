package io.legado.app.ui.widget.components

import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 垂直滚动条指示（追随内容手势/位置，适配 LazyListState / LazyGridState）
 */
@Composable
fun VerticalScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier
) {
    val firstVisible = listState.firstVisibleItemIndex
    val totalItems = listState.layoutInfo.totalItemsCount
    val offsetFraction = if (totalItems > 0)
        firstVisible.toFloat() / totalItems.coerceAtLeast(1)
    else 0f

    InternalScrollbar(
        offsetFraction = offsetFraction,
        modifier = modifier
    )
}

/**
 * LazyGrid 版本（firstVisibleRow 等同列表索引语义）。
 */
@Composable
fun VerticalScrollbar(
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    val firstVisibleRow = gridState.firstVisibleItemIndex
    val totalItems = gridState.layoutInfo.totalItemsCount
    val offsetFraction = if (totalItems > 0)
        firstVisibleRow.toFloat() / totalItems.coerceAtLeast(1)
    else 0f

    InternalScrollbar(
        offsetFraction = offsetFraction,
        modifier = modifier
    )
}

@Composable
private fun InternalScrollbar(
    offsetFraction: Float,
    modifier: Modifier = Modifier
) {
    val shouldShow by remember(offsetFraction) {
        derivedStateOf { offsetFraction > 0f }
    }
    if (!shouldShow) return

    val trackColor = MaterialTheme.colorScheme.surface
    val thumbColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    // thumb 高度比例：offsetFraction 越大（滚动越靠后），指示块越高，近似表达位置
    val thumbFraction = (0.2f + offsetFraction.coerceIn(0f, 1f) * 0.3f).coerceIn(0.15f, 0.5f)

    Box(
        modifier = modifier
            .width(4.dp)
            .fillMaxHeight()
            .padding(vertical = 4.dp)
            .background(trackColor, AppShapes.Tiny),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight(thumbFraction)
                .background(thumbColor, AppShapes.Tiny)
        )
    }
}