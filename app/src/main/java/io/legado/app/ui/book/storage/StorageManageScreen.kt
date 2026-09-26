package io.legado.app.ui.book.storage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.AppTextDialog
import io.legado.app.ui.widget.components.ConfirmDialog
import io.legado.app.ui.widget.components.EmptyStateAction
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.MetricGrid
import io.legado.app.ui.widget.components.MetricItem
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementLazyColumn
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/**
 * 书库存储管理页 Compose 受控组件（L-B15 枝叶页，S2 列表族）。
 *
 * 顶部 MetricGrid 统计卡（总量 + 分项数）+ 分项存储行（名称/大小/路径 + 清除按钮），
 * 弹窗全部在组件内管理：详情（AppTextDialog）、清除确认（ConfirmDialog）、
 * 清空全部确认（ConfirmDialog）；确认动作上抛宿主执行。
 */
data class StorageManageDisplayItem(
    val name: String,
    val size: String,
    val path: String,
    val detailText: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageManageScreen(
    items: List<StorageManageDisplayItem>,
    totalSize: String,
    isLoading: Boolean,
    loadError: String?,
    onBack: () -> Unit,
    onItemClick: (Int) -> Unit,
    onDeleteConfirm: (Int) -> Unit,
    onClearAllConfirm: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    var detailIndex by remember { mutableStateOf<Int?>(null) }
    var deleteIndex by remember { mutableStateOf<Int?>(null) }
    var clearAllVisible by remember { mutableStateOf(false) }

    // followup F5：统一管理族壳（AppManagementScaffold 平移，删页内自绘 GlassTopAppBar）
    val palette = rememberAppManagementPalette()
    val moreMenuActions = listOf(
        MenuAction(
            icon = Icons.Default.Refresh,
            title = stringResource(R.string.refresh),
            onClick = onRefresh
        ),
        MenuAction(
            icon = Icons.Default.DeleteSweep,
            title = stringResource(R.string.clear_all_cache),
            onClick = { clearAllVisible = true }
        )
    )

    AppManagementScaffold(
        title = stringResource(R.string.storage_manage),
        selectedCount = 0,
        totalCount = items.size,
        modifier = modifier,
        palette = palette,
        onBack = onBack,
        topActions = listOf(
            AppManagementAction(
                text = stringResource(R.string.more_menu),
                menuActions = {
                    moreMenuActions.map { menuAction ->
                        AppManagementMenuAction(
                            text = menuAction.title,
                            // 顶栏包 §1.2 路径 B：图标双源同链透传（漏传 ⇒ 溢出条目静默无图标）
                            icon = menuAction.icon,
                            iconRes = menuAction.iconRes,
                            onClick = menuAction.onClick
                        )
                    }
                }
            )
        )
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                isLoading -> ShelfListSkeleton()
                loadError != null -> EmptyStatePlaceholder(
                    icon = Icons.Default.ErrorOutline,
                    title = loadError,
                    primaryAction = EmptyStateAction(stringResource(R.string.retry), onRefresh),
                    modifier = Modifier.fillMaxSize()
                )
                items.isEmpty() -> EmptyStatePlaceholder(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.empty),
                    modifier = Modifier.fillMaxSize()
                )
                // 列表容器收敛（2026-09-26）：与书源管理同容器（项间距 8dp + 快速滚动条 + 导航栏内边距），
                // 行间距不再由页内自绘分隔线承载
                else -> AppManagementLazyColumn(palette = palette) {
                    item(key = "metrics") {
                        MetricGrid(
                            metrics = listOf(
                                MetricItem(
                                    label = stringResource(R.string.storage_total),
                                    value = totalSize,
                                    icon = Icons.Default.Storage
                                ),
                                MetricItem(
                                    label = stringResource(R.string.storage_items),
                                    value = items.size.toString(),
                                    icon = Icons.Default.List
                                )
                            ),
                            columns = 2
                        )
                    }
                    items(items = items, key = { it.name }) { item ->
                        // 行组件收敛（2026-09-26）：原 `SettingsClickRow`（另一行族，无 Card 观感）
                        // → 管理族唯一行 `AppManagementListRow`，与字典规则/TXT目录规则/自动任务同观感；
                        // 行尾文字取色一并改走面 token `accent`（原 M3 派生键 colorScheme.primary）。
                        AppManagementListRow(
                            title = item.name,
                            subtitle = item.path,
                            palette = palette,
                            // 与书源管理基线行同高（56dp）；不叠面板纹理图（BookSourceScreen 同口径）
                            minHeight = 56.dp,
                            drawPanelImage = false,
                            onClick = { detailIndex = items.indexOf(item) },
                            trailingBeforeSwitch = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.size,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = palette.settings.accent,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { deleteIndex = items.indexOf(item) }) {
                                        Text(
                                            text = stringResource(R.string.clear),
                                            color = palette.settings.accent
                                        )
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // 详情弹窗
    detailIndex?.let { index ->
        items.getOrNull(index)?.let { item ->
            AppTextDialog(
                title = stringResource(R.string.storage_manage),
                text = item.detailText,
                confirmText = stringResource(R.string.ok),
                onDismiss = { detailIndex = null }
            )
        }
    }

    // 单分项清除确认
    deleteIndex?.let { index ->
        ConfirmDialog(
            title = stringResource(R.string.clear),
            text = stringResource(R.string.sure_del),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                deleteIndex = null
                onDeleteConfirm(index)
            },
            onDismiss = { deleteIndex = null }
        )
    }

    // 清空全部确认
    if (clearAllVisible) {
        ConfirmDialog(
            title = stringResource(R.string.clear_all_cache),
            text = stringResource(R.string.clear_all_cache_confirm),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            destructive = true,
            onConfirm = {
                clearAllVisible = false
                onClearAllConfirm()
            },
            onDismiss = { clearAllVisible = false }
        )
    }
}

