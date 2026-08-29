package io.legado.app.ui.widget.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.compose.rememberAppSettingPalette

/**
 * 列表管理页通用「多选 + 开关 + 动作 + 拖拽排序」行（原 DictRule/AutoTask/TxtTocRule 三页私有行收敛，task 12.1A）。
 *
 * 规格：72dp 高、surface 底、combinedClickable（长按切换多选或外部透传 [onLongClick]）、
 * secondaryContainer α0.4 选中态（[checked]）。
 * 可选能力：独立 Switch（[onToggleEnable]）、Edit/Delete 快捷按钮（[onEdit]/[onDelete]）、
 * 行尾更多菜单（[moreActions]）、拖拽排序手柄（[dragStartIndex] 非空时显示，配合 [onDrag]/[onDragEnd]）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SettingsSelectableRow(
    checked: Boolean,
    title: String,
    subtitle: String? = null,
    enabled: Boolean,
    onToggleSelect: (Boolean) -> Unit,
    onToggleEnable: (Boolean) -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    moreActions: List<MenuAction>? = null,
    dragStartIndex: (() -> Unit)? = null,
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menuVisible by remember { mutableStateOf(false) }
    // H10/H11: 列表项直色（palette.settings.row），选中态用强调色低透明浮层（替代 M3 secondaryContainer/surface 派生色）
    val palette = rememberAppSettingPalette()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(
                if (checked) {
                    palette.accent.copy(alpha = 0.15f)
                } else {
                            Color(palette.row)
                        }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick ?: { onToggleSelect(!checked) }
            )
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggleSelect,
            colors = CheckboxDefaults.colors()
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (enabled) {
                    palette.primaryText
                } else {
                    palette.primaryText.copy(alpha = 0.5f)
                }
            )
            if (subtitle != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.secondaryText
                )
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggleEnable
        )
        if (onEdit != null) {
            IconButton(onClick = onEdit) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = palette.secondaryText
                )
            }
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = palette.secondaryText
                )
            }
        }
        if (moreActions != null) {
            Box {
                IconButton(onClick = { menuVisible = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more_menu),
                        tint = palette.secondaryText
                    )
                }
                AppDropdownMenu(
                    expanded = menuVisible,
                    onDismiss = { menuVisible = false },
                    actions = moreActions
                )
            }
        }
        if (dragStartIndex != null) {
            // 拖拽排序手柄：长按拖动
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = stringResource(R.string.more_menu),
                tint = palette.divider,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .pointerInput(title) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragStartIndex()
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                            onDragEnd = {
                                onDragEnd()
                            },
                            onDragCancel = {
                                onDragEnd()
                            }
                        )
                    }
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
    }
}
