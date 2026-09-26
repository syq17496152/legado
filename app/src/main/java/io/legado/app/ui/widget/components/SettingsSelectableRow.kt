package io.legado.app.ui.widget.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/**
 * 列表管理页通用「多选 + 开关 + 动作 + 拖拽排序」行。
 *
 * **2026-09-26 行组件收敛（用户裁定）**：本组件原为**自绘裸 `Row`**（72dp 高 / 无 Card / 无圆角），
 * 而「我的」下的其余管理页（书源/订阅源/替换规则/高亮规则等）统一用 `AppManagementScaffold` +
 * `AppManagementListRow`（Miuix Card 家族：圆角 = `panelRadius` / 底色 = `palette.row` /
 * 外边距 12dp / `minHeight` 56dp）⇒ 出现「**同脚手架不同行**」。
 *
 * 现改为 `AppManagementListRow` 的**薄壳**：视觉、取色、圆角、行距全部由该单源决定；
 * 本组件只负责把「多选 / 开关 / 编辑 / 删除 / 更多 / 拖拽手柄」六个能力位映射过去，
 * **不再自带任何像素或色值**（取色随 `AppManagementPalette`，禁用硬编码色）。
 *
 * 能力映射口径：
 * - `checked` → `selected`；`onToggleSelect: (Boolean) -> Unit` → `onToggleSelection = { onToggleSelect(!checked) }`
 * - `enabled`/`onToggleEnable` → `switchChecked`/`onSwitchChange`
 * - `onEdit` / `onDelete` → 同名参数（图标由 `AppManagementListRow` 单源决定）
 * - `moreActions: List<MenuAction>` → `AppManagementMenuAction`（图标**双源**同链透传，漏传即静默丢图标）
 * - 拖拽手柄改挂 `leadingContent`（**与书源管理一致的行首手柄位**；原实现挂在行尾），
 *   交互（长按拖动 / 回调时机）逐字不变。
 *
 * 有意视觉变化（两条）：①拖拽手柄位 行尾 → 行首；②消费页移除行间自绘分隔线——卡片行间距由
 * 列表容器 `AppManagementLazyColumn` 单源决定（与书源管理一致；卡片行再叠分隔线属「同脚手架不同行」）。
 *
 * `rowHeight` 传入 `minHeight`（与书源管理基线同为 56dp）；**实际行距不得在页内用 dp 常量推算**——
 * 真实高度 = `minHeight` + Card 内边距（上下各 8dp）+ Card 垂直外边距（上下各 4dp）+ 列表项间距，
 * 且随字体缩放变化。拖拽换序所需的行距必须从 `LazyListState.layoutInfo` **实测**取得（见各页实现），
 * 硬编码 dp 会随上述任一因素变化而失配 ⇒ 换序错位（2026-09-26 实测修正：旧写死 64dp 与真实
 * 80dp 不符）。
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
    rowHeight: Dp = 56.dp,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    AppManagementListRow(
        title = title,
        subtitle = subtitle,
        palette = palette,
        modifier = modifier,
        selected = checked,
        onToggleSelection = { onToggleSelect(!checked) },
        switchChecked = enabled,
        onSwitchChange = onToggleEnable,
        minHeight = rowHeight,
        // 与「书源管理 / 订阅源 / 替换规则」基线行逐字对齐：不叠面板纹理图（BookSourceScreen 同口径），
        // 仅保留圆角底色 + 描边 ⇒ 全管理族列表观感单源。
        drawPanelImage = false,
        onClick = onClick,
        onLongClick = onLongClick ?: { onToggleSelect(!checked) },
        onEdit = onEdit,
        onDelete = onDelete,
        moreActions = moreActions.orEmpty().map { action ->
            AppManagementMenuAction(
                text = action.title.toString(),
                // 顶栏包 §1.2 路径 B：图标双源同链透传（漏传 ⇒ 溢出条目静默无图标）
                icon = action.icon,
                iconRes = action.iconRes,
                checked = action.checked == true,
                onClick = action.onClick
            )
        },
        leadingContent = dragStartIndex?.let {
            {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = stringResource(R.string.more_menu),
                    tint = palette.settings.secondaryText,
                    modifier = Modifier.pointerInput(title) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragStartIndex() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() }
                        )
                    }
                )
            }
        }
    )
}