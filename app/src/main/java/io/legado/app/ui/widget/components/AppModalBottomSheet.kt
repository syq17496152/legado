package io.legado.app.ui.widget.components

import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 应用级 ModalBottomSheet 容器（AD-15：统一弹层形态）。
 * - 默认 M3 ModalBottomSheet，拖拽关闭
 * - dialog = true 时退化为简单列在弹窗主题内（用于 Web/低版本兜底场景）
 * - 顶部装饰条 + 圆角 + 自动避让导航栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppModalBottomSheet(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    dragHandle: @Composable (() -> Unit)? = {
        BottomSheetDefaults.DragHandle()
    },
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.SheetTop,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 8.dp,
        dragHandle = dragHandle
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            content()
        }
    }
}

/**
 * 高亮/文案选区场景的半屏 Sheet：ContentView 高度拖动、peek 固定为部分展开。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberHalfSheetState(
    flingBehavior: FlingBehavior? = null,
    confirmValueChange: (SheetValue) -> Boolean = { true },
    skipPartiallyExpanded: Boolean = false
): SheetState = rememberModalBottomSheetState(
    skipPartiallyExpanded = skipPartiallyExpanded,
    confirmValueChange = confirmValueChange
)