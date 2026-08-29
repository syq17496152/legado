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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

/**
 * 导入项的状态：新增 / 更新 / 已有。
 */
enum class ImportState {
    NEW, UPDATE, EXIST
}

/**
 * Import Dialog 列表项数据模型。
 *
 * @param name 源名称
 * @param comment 备注（可空，为空或未开启显示时隐藏）
 * @param state 新增/更新/已有 状态
 */
data class ImportItem(
    val name: String,
    val comment: String?,
    val state: ImportState
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
 * 全部文案走 stringResource，颜色走 MaterialTheme.colorScheme，禁止硬编码中文与 Color(0x)。
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

    AppModalBottomSheet(onDismiss = onDismiss) {
        // ---------- 顶部：标题 + 菜单 ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(start = 16.dp, end = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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
            color = MaterialTheme.colorScheme.outlineVariant,
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
                    CircularProgressIndicator()
                }
            }

            errorMsg != null -> {
                Text(
                    text = errorMsg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
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
                        .heightIn(max = 440.dp)
                ) {
                    itemsIndexed(items) { index, item ->
                        ImportItemRow(
                            index = index,
                            item = item,
                            isChecked = selected.getOrElse(index) { false },
                            showComment = showComment,
                            onToggle = onToggleSelect,
                            onEdit = onEditItem
                        )
                        if (index < items.lastIndex) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant,
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
            OutlinedButton(
                onClick = onToggleSelectAll,
                shape = AppShapes.Button,
                modifier = Modifier
                    .height(48.dp)
                    .weight(1f)
            ) {
                Text(
                    text = if (isSelectAll) {
                        stringResource(R.string.select_cancel_count, selectCount, items.size)
                    } else {
                        stringResource(R.string.select_all_count, selectCount, items.size)
                    }
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(
                onClick = onDismiss,
                shape = AppShapes.Button,
                modifier = Modifier
                    .height(48.dp)
                    .width(96.dp)
            ) {
                Text(text = stringResource(R.string.cancel))
            }
            Button(
                onClick = onImport,
                shape = AppShapes.Button,
                modifier = Modifier
                    .height(48.dp)
                    .width(96.dp)
            ) {
                Text(text = stringResource(R.string.import_str))
            }
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
                onCheckedChange = { onToggle(index) }
            )
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ImportStateBadge(item.state)
            IconButton(
                onClick = { onEdit(index) }
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (showComment && !item.comment.isNullOrBlank()) {
            Text(
                text = item.comment,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 6.dp)
            )
        }
    }
}

/**
 * 新增/更新/已有 状态徽标。
 */
@Composable
private fun ImportStateBadge(state: ImportState) {
    val (text, color) = when (state) {
        ImportState.NEW -> stringResource(R.string.import_status_new) to
            MaterialTheme.colorScheme.primaryContainer
        ImportState.UPDATE -> stringResource(R.string.import_status_update) to
            MaterialTheme.colorScheme.tertiaryContainer
        ImportState.EXIST -> stringResource(R.string.import_status_exist) to
            MaterialTheme.colorScheme.secondaryContainer
    }
    Surface(
        shape = AppShapes.Chip,
        color = color
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
