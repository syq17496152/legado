package io.legado.app.ui.book.bookmark

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.Bookmark
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.GroupHeader

/**
 * 全部书签页 Compose 受控组件（L-B5 枝叶页，S2 列表管理范式）。
 * 状态由宿主（Activity）传入，事件全部上抛；顶栏 GlassTopAppBar + 导出菜单 +
 * 按书分组的书签列表（GroupHeader 折叠/收起）+ 空态 + 点击查书跳读/长按编辑。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AllBookmarkScreen(
    bookmarks: List<Bookmark>,
    onBack: () -> Unit,
    onExportJson: () -> Unit,
    onExportMd: () -> Unit,
    onItemClick: (Bookmark) -> Unit,
    onItemLongClick: (Bookmark) -> Unit,
    modifier: Modifier = Modifier
) {
    var moreMenuVisible by remember { mutableStateOf(false) }
    var collapsedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val groups = remember(bookmarks) {
        bookmarks.groupBy { with(it) { "$bookName($bookAuthor)" } }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopAppBar(
                title = stringResource(R.string.all_bookmark),
                navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavClick = onBack,
                actions = {
                    IconButton(onClick = { moreMenuVisible = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.more_menu)
                        )
                    }
                    DropdownMenu(
                        expanded = moreMenuVisible,
                        onDismissRequest = { moreMenuVisible = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export)) },
                            onClick = { moreMenuVisible = false; onExportJson() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_md)) },
                            onClick = { moreMenuVisible = false; onExportMd() }
                        )
                    }
                }
            )
            if (groups.isEmpty()) {
                EmptyStatePlaceholder(
                    icon = Icons.Default.Bookmarks,
                    title = stringResource(R.string.all_bookmark_empty_title),
                    subtitle = stringResource(R.string.all_bookmark_empty_subtitle),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(bottom = 8.dp)
                ) {
                    groups.forEach { (groupName, items) ->
                        val collapsed = groupName in collapsedGroups
                        item(key = "header_$groupName") {
                            GroupHeader(
                                name = groupName,
                                enabledCount = items.size,
                                totalCount = items.size,
                                collapsed = collapsed,
                                onToggleCollapse = {
                                    collapsedGroups =
                                        if (collapsed) collapsedGroups - groupName
                                        else collapsedGroups + groupName
                                }
                            )
                        }
                        if (!collapsed) {
                            items(items, key = { it.time }) { bookmark ->
                                BookmarkItem(
                                    bookmark = bookmark,
                                    onClick = { onItemClick(bookmark) },
                                    onLongClick = { onItemLongClick(bookmark) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BookmarkItem(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = AppShapes.Chip,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = bookmark.chapterName,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (bookmark.bookText.isNotEmpty()) {
                Text(
                    text = bookmark.bookText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (bookmark.content.isNotEmpty()) {
                Text(
                    text = bookmark.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
