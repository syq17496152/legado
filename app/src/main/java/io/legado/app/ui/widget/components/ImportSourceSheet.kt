package io.legado.app.ui.widget.components

import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppSettingPalette
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/**
 * 导入项的状态：新增 / 更新 / 已有 / 已过滤（导入校验未通过，import-source-quality-filter）。
 */
enum class ImportState {
    NEW, UPDATE, EXIST, FILTERED
}

/**
 * Import Dialog 列表项数据模型。
 *
 * @param name 源名称
 * @param comment 备注（可空，为空或未开启显示时隐藏）
 * @param state 新增/更新/已有/已过滤 状态
 * @param stateDetail FILTERED 态的白话原因（"网址打不开"等，S5 注释类字段截断 50 字符）
 */
data class ImportItem(
    val name: String,
    val comment: String?,
    val state: ImportState,
    val stateDetail: String? = null
)

/**
 * Import Dialog 族通用底部面板组件（S6 支干样板）。
 *
 * 抽取 8 个 Import Dialog（Rss/BookSource/DictRule/ReplaceRule/TxtTocRule/Theme/HttpTts/...）
 * 的高度同构 UI 为单一可复用 Compose 组件：
 *  - 顶部：标题 + MoreVert 下拉菜单（[AppDropdownMenu]，数据驱动）
 *  - 中部：列表项（勾选/名称/可展开备注/新增-更新-已有状态徽标/编辑按钮）
 *  - 底部：全选-取消/取消/导入 操作栏（12dp 圆角、48dp 高）
 *
 * 全部文案走 stringResource，颜色走 AppSettingPalette 直色（取色唯一基线），禁止硬编码中文与 Color(0x)。
 *
 * @param title 标题（调用方传 stringResource）
 * @param items 列表数据（[ImportItem]，state 用于展示新增/更新/已有）
 * @param selected 勾选态列表，长度与 items 一致
 * @param showComment 是否展示备注
 * @param onToggleSelect 切换单项勾选（入参为下标）
 * @param onToggleSelectAll 全选/取消全选
 * @param onEditItem 编辑某项（入参为下标）
 * @param onImport 底部导入回调
 * @param onDismiss 关闭回调
 * @param menuActions 顶部菜单动作（[MenuAction] 自带 onClick，见项目既有契约）
 * @param onMenuAction 菜单动作触发回调（复用 [MenuAction.onClick] 基础上额外通知）
 * @param loading 加载中（展示转圈）
 * @param errorMsg 错误文案（非空则展示错误，否则展示列表）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSourceSheet(
    title: String,
    items: List<ImportItem>,
    selected: List<Boolean>,
    showComment: Boolean,
    onToggleSelect: (Int) -> Unit,
    onToggleSelectAll: () -> Unit,
    onEditItem: (Int) -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
    menuActions: List<MenuAction>,
    onMenuAction: (MenuAction) -> Unit = {},
    loading: Boolean,
    errorMsg: String?
) {
    var menuExpanded by remember { mutableStateOf(false) }
    // UI 规范归位（2026-09-13 用户批评：暗色下文字黑色看不清）：
    // 取色走 AppSettingPalette 直色（ThemeStore 链），禁止 MaterialTheme.colorScheme（H9/H11 铁律）
    // 注：miuix 子调色板供 LegadoMiuixActionButton 消费
    val managementPalette = rememberAppManagementPalette()
    val settings = managementPalette.settings

    AppModalBottomSheet(onDismiss = onDismiss) {
        // ---------- 顶部：标题 + 菜单 ----------
        // heightIn(min)：大字号缩放下标题撑开防截断
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .padding(start = 16.dp, end = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = settings.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = settings.secondaryText
                    )
                }
                val wrappedActions = menuActions.map { action ->
                    action.copy(onClick = {
                        action.onClick()
                        onMenuAction(action)
                    })
                }
                AppDropdownMenu(
                    expanded = menuExpanded,
                    onDismiss = { menuExpanded = false },
                    actions = wrappedActions
                )
            }
        }
        HorizontalDivider(
            color = settings.divider,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        // ---------- 中部：加载 / 错误 / 列表 ----------
        when {
            loading -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                ) {
                    CircularProgressIndicator(color = settings.accent)
                }
            }

            errorMsg != null -> {
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = settings.danger,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 小屏适配（900px/450dp 实证）：440dp 上限会把底部操作栏推出屏外，
                        // 大集合导入时"导入"按钮不可见不可点（import-source-quality-filter 真机 E2E 铁证）
                        .heightIn(max = 300.dp)
                ) {
                    itemsIndexed(items) { index, item ->
                        ImportItemRow(
                            index = index,
                            item = item,
                            isChecked = selected.getOrElse(index) { false },
                            showComment = showComment,
                            settings = settings,
                            onToggle = onToggleSelect,
                            onEdit = onEditItem
                        )
                        if (index < items.lastIndex) {
                            HorizontalDivider(
                                color = settings.divider,
                                modifier = Modifier.padding(start = 52.dp)
                            )
                        }
                    }
                }
            }
        }

        // ---------- 底部：全选-取消 / 取消 / 导入 ----------
        val selectCount = selected.count { it }
        val isSelectAll = selected.isNotEmpty() && selected.all { it }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // 底栏按钮归位管理族基线 LegadoMiuixActionButton（M3 OutlinedButton/Button 裸色弃用）
            LegadoMiuixActionButton(
                text = if (isSelectAll) {
                    stringResource(R.string.select_cancel_count, selectCount, items.size)
                } else {
                    stringResource(R.string.select_all_count, selectCount, items.size)
                },
                palette = managementPalette.miuix,
                onClick = onToggleSelectAll,
                minWidth = 0.dp,
                minHeight = 48.dp,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = managementPalette.miuix,
                onClick = onDismiss,
                minWidth = 96.dp,
                minHeight = 48.dp
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.import_str),
                palette = managementPalette.miuix,
                onClick = onImport,
                primary = true,
                minWidth = 96.dp,
                minHeight = 48.dp
            )
        }
    }
}

/**
 * 单条导入列表项：勾选 + 名称 + 状态徽标 + 编辑按钮，可展开备注。
 */
@Composable
private fun ImportItemRow(
    index: Int,
    item: ImportItem,
    isChecked: Boolean,
    showComment: Boolean,
    settings: AppSettingPalette,
    onToggle: (Int) -> Unit,
    onEdit: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(index) }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Checkbox(
                checked = isChecked,
                onCheckedChange = { onToggle(index) },
                colors = CheckboxDefaults.colors(
                    checkedColor = settings.accent,
                    uncheckedColor = settings.disabledText,
                    checkmarkColor = settings.onAccent
                )
            )
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = settings.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ImportStateBadge(item.state, settings)
            IconButton(
                onClick = { onEdit(index) }
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = settings.secondaryText,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (showComment && !item.comment.isNullOrBlank()) {
            Text(
                text = item.comment,
                style = MaterialTheme.typography.bodySmall,
                color = settings.secondaryText,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 6.dp)
            )
        }
        // FILTERED 态：白话原因展示（S5：截断 50 字符）
        if (item.state == ImportState.FILTERED && !item.stateDetail.isNullOrBlank()) {
            Text(
                text = item.stateDetail!!.take(50),
                style = MaterialTheme.typography.bodySmall,
                color = settings.danger,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 6.dp)
            )
        }
    }
}

/**
 * 新增/更新/已有/已过滤 状态徽标。
 * 取色基线归位：accent/danger/row 直色（原 M3 container 派生色弃用）。
 */
@Composable
private fun ImportStateBadge(state: ImportState, settings: AppSettingPalette) {
    val (text, bg, fg) = when (state) {
        ImportState.NEW -> Triple(
            stringResource(R.string.import_status_new),
            settings.accent.copy(alpha = 0.16f),
            settings.accent
        )
        ImportState.UPDATE -> Triple(
            stringResource(R.string.import_status_update),
            settings.accent.copy(alpha = 0.16f),
            settings.accent
        )
        ImportState.EXIST -> Triple(
            stringResource(R.string.import_status_exist),
            androidx.compose.ui.graphics.Color(settings.row),
            settings.secondaryText
        )
        ImportState.FILTERED -> Triple(
            stringResource(R.string.import_status_filtered),
            settings.danger.copy(alpha = 0.16f),
            settings.danger
        )
    }
    Surface(
        shape = AppShapes.Chip,
        color = bg
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
