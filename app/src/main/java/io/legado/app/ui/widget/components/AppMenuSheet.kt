package io.legado.app.ui.widget.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * 菜单动作项，由 [AppMenuSheet] / [AppDropdownMenu] 数据驱动渲染
 *
 * @property icon 图标
 * @property title 标题（调用方传 stringResource，遵守 §6.1 禁硬编码中文）
 * @property tint 图标与文字颜色，默认 null 走主题 onSurfaceVariant
 * @property checked 勾选态（复选类菜单），null 不显示勾选标记
 * @property onClick 点击回调
 */
data class MenuAction(
    val icon: ImageVector,
    val title: String,
    val tint: androidx.compose.ui.graphics.Color? = null,
    val checked: Boolean? = null,
    val header: Boolean = false,
    val onClick: () -> Unit
)

/**
 * 长按条目弹出的底部操作面板（L1 层），复用 [AppModalBottomSheet]
 *
 * 数据驱动：传入 [actions] 列表逐项渲染，图标+文字行，触控高度 48dp。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppMenuSheet(
    title: String? = null,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    sheetState: androidx.compose.material3.SheetState =
        androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true),
    modifier: Modifier = Modifier,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    AppModalBottomSheet(onDismiss = onDismiss, sheetState = sheetState) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp)
            )
        }
        if (title != null && actions.isNotEmpty()) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        actions.forEach { action ->
            if (action.header) {
                Text(
                    text = action.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = action.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 10.dp)
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable(onClick = action.onClick)
                        .padding(horizontal = 24.dp)
                ) {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.title,
                        tint = action.tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(24.dp)
                    )
                    Spacer(modifier = Modifier.width(20.dp))
                    Text(
                        text = action.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = action.tint ?: MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    )
                }
            }
        }
        content?.invoke(this)
    }
}
