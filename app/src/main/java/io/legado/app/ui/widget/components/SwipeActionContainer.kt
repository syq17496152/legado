package io.legado.app.ui.widget.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * 滑动操作容器（Swipe 思路）：内容左滑露出右侧固定操作区。
 * 操作区常驻右侧，多个操作按钮在 Row 中横向排列（实测总宽），
 * 内容层以 offset 往左移显示其下的操作区；松手自动回弹。
 */
@Composable
fun SwipeActionContainer(
    actionContent: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    var offset by remember { mutableStateOf(0f) }
    var actionWidthPx by remember { mutableStateOf(0f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
    ) {
        // 操作区（常驻右侧，被内容覆盖；Row 按内容宽度横向排列，右侧对齐）
        Row(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .onGloballyPositioned { actionWidthPx = it.size.width.toFloat() },
            verticalAlignment = Alignment.CenterVertically
        ) {
            actionContent()
        }
        // 内容层（可拖动）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(offset.roundToInt(), 0) }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        offset = (offset + delta).coerceIn(-actionWidthPx, 0f)
                    },
                    onDragStopped = {
                        offset = if (offset < -actionWidthPx / 2f) -actionWidthPx else 0f
                    }
                )
        ) {
            content()
        }
    }
}
