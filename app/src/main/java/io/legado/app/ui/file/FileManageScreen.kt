package io.legado.app.ui.file

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/**
 * L-C9 文件管理页（S2 列表管理页）：全 Compose 内容区。
 *
 * 交互：路径导航条（root + 逐级跳转）/ 文件列表（上级 .. / 文件夹 / 文件）/
 * 搜索过滤 / 长按文件夹或文件弹删除菜单 / 返回键回上级（Activity 侧处理）。
 */
data class FileManageDisplayItem(
    val path: String,
    val name: String,
    val isUpDir: Boolean,
    val isDir: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManageScreen(
    items: List<FileManageDisplayItem>,
    pathSegments: List<String>,
    isLoading: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onUpDir: () -> Unit,
    onOpenDir: (Int) -> Unit,
    onOpenFile: (Int) -> Unit,
    onJumpPath: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var deleteTarget by remember { mutableStateOf<Int?>(null) }

    // followup F5：统一管理族壳（AppManagementScaffold 平移，删页内自绘 GlassTopAppBar/OutlinedTextField 搜索）
    val palette = rememberAppManagementPalette()
    AppManagementScaffold(
        title = stringResource(R.string.file_manage),
        selectedCount = 0,
        totalCount = items.size,
        modifier = modifier,
        palette = palette,
        searchQuery = searchQuery,
        searchHint = stringResource(R.string.screen),
        onSearchChange = onSearchQueryChange,
        onBack = onBack
    ) { _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            PathBar(pathSegments = pathSegments, onJumpPath = onJumpPath)
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
                if (items.isEmpty() && !isLoading) {
                    EmptyStatePlaceholder(
                        icon = Icons.Default.Folder,
                        title = stringResource(R.string.empty),
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(items = items, key = { _, item -> item.path }) { index, item ->
                            Box {
                                FileManageItemRow(
                                    item = item,
                                    onClick = {
                                        when {
                                            item.isUpDir -> onUpDir()
                                            item.isDir -> onOpenDir(index)
                                            else -> onOpenFile(index)
                                        }
                                    },
                                    onLongClick = {
                                        if (!item.isUpDir) deleteTarget = index
                                    }
                                )
                                AppDropdownMenu(
                                    expanded = deleteTarget == index,
                                    onDismiss = { deleteTarget = null },
                                    actions = listOf(
                                        MenuAction(
                                            icon = Icons.Default.Delete,
                                            title = stringResource(R.string.delete),
                                            onClick = { deleteTarget = null; onDelete(index) }
                                        )
                                    )
                                )
                            }
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 路径导航条：root + 逐级目录，点击跳转 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PathBar(pathSegments: List<String>, onJumpPath: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LazyRow(verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(pathSegments) { index, segment ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.combinedClickable(
                        onClick = { onJumpPath(index) },
                        enabled = true
                    )
                ) {
                    Text(
                        text = segment,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (index == pathSegments.lastIndex) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (index != pathSegments.lastIndex) {
                        Icon(
                            Icons.Default.NavigateNext,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileManageItemRow(
    item: FileManageDisplayItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                item.isUpDir -> Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = stringResource(R.string.go_back),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
                item.isDir -> Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                else -> Icon(
                    Icons.Default.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
