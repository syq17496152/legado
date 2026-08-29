package io.legado.app.ui.book.import

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TagChip

data class ImportBookDisplayItem(
    val name: String,
    val isDir: Boolean,
    val isOnBookShelf: Boolean,
    val tag: String,
    val size: String,
    val date: String,
    val isSelected: Boolean
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ImportBookScreen(
    items: List<ImportBookDisplayItem>,
    currentPath: String,
    canGoBack: Boolean,
    isLoading: Boolean,
    title: String,
    menuActions: List<MenuAction>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onGoBack: () -> Unit,
    onItemClick: (Int) -> Unit,
    onItemLongClick: (Int) -> Unit,
    emptyMessage: String,
    modifier: Modifier = Modifier
) {
    var moreMenuVisible by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxSize()) {
        GlassTopAppBar(
            title = title,
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                // topbar-icon-semantics-fix 3.3：alwaysShow 项直出一级图标
                //（对齐原版 book_remote.xml/import_book.xml always：刷新/排序/选目录）
                menuActions.filter { it.alwaysShow }.forEach { action ->
                    IconButton(onClick = action.onClick) {
                        Icon(action.icon, contentDescription = action.title)
                    }
                }
                val overflowActions = menuActions.filter { !it.alwaysShow }
                if (overflowActions.isNotEmpty()) {
                    Box {
                        IconButton(onClick = { moreMenuVisible = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        AppDropdownMenu(
                            expanded = moreMenuVisible,
                            onDismiss = { moreMenuVisible = false },
                            actions = overflowActions
                        )
                    }
                }
            }
        )
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text(title) },
            leadingIcon = {
                Icon(Icons.Default.Search, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)
        )
        PathBar(currentPath = currentPath, canGoBack = canGoBack, onGoBack = onGoBack)
        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            if (items.isEmpty() && !isLoading) {
                EmptyStatePlaceholder(
                    icon = Icons.Default.Folder, title = emptyMessage,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(items = items, key = { index, item -> "$index-${item.name}" }) { index, item ->
                        ImportBookItemRow(item = item, onClick = { onItemClick(index) }, onLongClick = { onItemLongClick(index) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PathBar(currentPath: String, canGoBack: Boolean, onGoBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = currentPath, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onGoBack, enabled = canGoBack) {
            Text(stringResource(R.string.go_back), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ImportBookItemRow(item: ImportBookDisplayItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(60.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                item.isDir -> Icon(Icons.Default.Folder, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                item.isOnBookShelf -> Icon(Icons.Default.LibraryBooks, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                else -> Icon(
                    if (item.isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                    contentDescription = null,
                    tint = if (item.isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(text = item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!item.isDir) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TagChip(text = item.tag)
                    Text(text = item.size, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = item.date, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** 导入列表行：目录/书架/本地文件 */