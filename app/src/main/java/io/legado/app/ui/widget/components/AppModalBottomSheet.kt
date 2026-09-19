package io.legado.app.ui.widget.components

import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

/**
 * 应用级 ModalBottomSheet 容器（AD-15：统一弹层形态）。
 * - 默认 M3 ModalBottomSheet，拖拽关闭
 * - dialog = true 时退化为简单列在弹窗主题内（用于 Web/低版本兜底场景）
 * - 顶部装饰条 + 圆角 + 自动避让导航栏
 * - 取色基线（2026-09-13 归位）：surface/文字走 AppDialogStyle 直色（ThemeStore 链），
 *   禁止 MaterialTheme.colorScheme（M3 派生色不随主题背景直读，H9/H11 铁律）
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
    val dialogStyle = rememberAppDialogStyle()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.SheetTop,
        containerColor = dialogStyle.surface,
        contentColor = dialogStyle.primaryText,
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