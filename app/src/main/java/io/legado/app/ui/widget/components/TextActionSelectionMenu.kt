package io.legado.app.ui.widget.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup

/**
 * 阅读器选区工具条（P2-reader §3 阅读器族，S5）。
 *
 * [Popup] 弹层，锚点由 `textMenuPosition` View 坐标桥接传入；
 * 色格 2 行 6 色（圆 40dp，间距 8dp），动作行单行横排，条目 h12；
 * 色槽：图标 `primary` / 文字 `onSurface` / destructive `error`。
 * 无二级菜单。
 * 规格：ui-standards §3.4 `TextActionSelectionMenu`（task 12.2D，替换现状 TextActionMenu）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TextActionSelectionMenu(
    x: Int,
    y: Int,
    colorSwatches: List<Color>,
    actions: List<TextActionItem>,
    onColorClick: (Color) -> Unit,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Popup(
        alignment = Alignment.TopStart,
        offset = androidx.compose.ui.unit.IntOffset(x, y),
    ) {
        Surface(
            shape = AppShapes.Button,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
            modifier = modifier,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(12.dp),
            ) {
                if (colorSwatches.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        colorSwatches.forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                    .clickable { onColorClick(color) },
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    actions.forEach { action ->
                        TextActionItemView(action, onClick = { onActionClick(action.id) })
                    }
                }
            }
        }
    }
}

data class TextActionItem(
    val id: String,
    val icon: ImageVector? = null,
    val label: String? = null,
    val destructive: Boolean = false,
)

@Composable
private fun TextActionItemView(
    action: TextActionItem,
    onClick: () -> Unit,
) {
    val tint = if (action.destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .sizeIn(minHeight = 48.dp)
            .clip(AppShapes.Chip)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (action.icon != null) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.label,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        }
        if (action.label != null) {
            Text(
                text = action.label,
                style = MaterialTheme.typography.bodyMedium,
                color = tint,
            )
        }
    }
}