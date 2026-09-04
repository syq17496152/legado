package io.legado.app.ui.book.storage

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.MetricGrid
import io.legado.app.ui.widget.components.MetricItem
import io.legado.app.ui.widget.components.SettingsClickRow
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.compose.AppManagementAction
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
                            onClick = menuAction.onClick
                        )
                    }
                }
            )
        )
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            when {
                isLoading -> ShelfListSkeleton()
                loadError != null -> EmptyStatePlaceholder(
                    icon = Icons.Default.ErrorOutline,
                    title = loadError,
                    actionLabel = stringResource(R.string.retry),
                    onAction = onRefresh,
                    modifier = Modifier.fillMaxSize()
                )
                items.isEmpty() -> EmptyStatePlaceholder(
                    icon = Icons.Default.Storage,
                    title = stringResource(R.string.empty),
                    modifier = Modifier.fillMaxSize()
                )
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                        SettingsClickRow(
                            icon = null,
                            title = item.name,
                            subtitle = item.path,
                            onClick = { detailIndex = items.indexOf(item) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = item.size,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        maxLines = 1
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    TextButton(onClick = { deleteIndex = items.indexOf(item) }) {
                                        Text(
                                            text = stringResource(R.string.clear),
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            thickness = 0.5.dp
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

