package io.legado.app.ui.highlight

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar

/**
 * 高亮规则管理页 Compose 受控组件（L-C5 枝叶页，S2 列表管理样板）。
 * 状态由宿主（Activity）传入，事件全部上抛；顶栏 GlassTopAppBar + 搜索 +
 * 规则列表（Checkbox 启停/编辑/更多菜单）+ 空态 + 条目操作 AppMenuSheet。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HighlightRuleScreen(
    rules: List<HighlightRule>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onGroupManage: () -> Unit,
    onPreset: () -> Unit,
    onRestoreDefault: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onItemClick: (HighlightRule) -> Unit,
    onEnableToggle: (HighlightRule, Boolean) -> Unit,
    onDelete: (HighlightRule) -> Unit,
    onToTop: (HighlightRule) -> Unit,
    onToBottom: (HighlightRule) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var moreMenuVisible by remember { mutableStateOf(false) }
    var menuRule by remember { mutableStateOf<HighlightRule?>(null) }

    val filtered = remember(rules, searchQuery) {
        if (searchQuery.isBlank()) rules
        else rules.filter { it.getDisplayName().contains(searchQuery.trim(), ignoreCase = true) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            GlassTopAppBar(
                title = stringResource(R.string.highlight_rule_manage),
                navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavClick = onBack,
                actions = {
                    if (!searchVisible) {
                        IconButton(onClick = { searchVisible = true }) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = stringResource(R.string.action_search)
                            )
                        }
                        IconButton(onClick = onAdd) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.menu_add_highlight_rule)
                            )
                        }
                        IconButton(onClick = { moreMenuVisible = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_menu)
                            )
                        }
                    }
                    AppDropdownMenu(
                        expanded = moreMenuVisible,
                        onDismiss = { moreMenuVisible = false },
                        actions = listOf(
                            MenuAction(
                                icon = Icons.Default.Folder,
                                title = stringResource(R.string.highlight_rule_group_manage_title),
                                onClick = onGroupManage
                            ),
                            MenuAction(
                                icon = Icons.Default.Star,
                                title = stringResource(R.string.highlight_rule_preset),
                                onClick = onPreset
                            ),
                            MenuAction(
                                icon = Icons.Default.Refresh,
                                title = stringResource(R.string.highlight_rule_restore_default),
                                onClick = onRestoreDefault
                            ),
                            MenuAction(
                                icon = Icons.Default.FileUpload,
                                title = stringResource(R.string.import_highlight_rule),
                                onClick = onImport
                            ),
                            MenuAction(
                                icon = Icons.Default.FileDownload,
                                title = stringResource(R.string.export_highlight_rule),
                                onClick = onExport
                            )
                        )
                    )
                }
            )
            if (searchVisible) {
                SettingsSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange
                )
            }
            if (filtered.isEmpty()) {
                EmptyStatePlaceholder(
                    icon = Icons.Default.List,
                    title = stringResource(R.string.highlight_rule_empty_title),
                    subtitle = stringResource(R.string.highlight_rule_empty_subtitle),
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(filtered, key = { it.id }) { rule ->
                        HighlightRuleItem(
                            rule = rule,
                            onClick = { onItemClick(rule) },
                            onEnableToggle = { onEnableToggle(rule, it) },
                            onMore = { menuRule = rule }
                        )
                    }
                }
            }
        }

        menuRule?.let { rule ->
            AppMenuSheet(
                title = rule.getDisplayName(),
                actions = listOf(
                    MenuAction(
                        Icons.Default.KeyboardArrowUp,
                        stringResource(R.string.to_top),
                        onClick = { menuRule = null; onToTop(rule) }
                    ),
                    MenuAction(
                        Icons.Default.KeyboardArrowDown,
                        stringResource(R.string.to_bottom),
                        onClick = { menuRule = null; onToBottom(rule) }
                    ),
                    MenuAction(
                        Icons.Default.Delete,
                        stringResource(R.string.delete),
                        onClick = { menuRule = null; onDelete(rule) }
                    )
                ),
                onDismiss = { menuRule = null }
            )
        }
    }
}

@Composable
private fun HighlightRuleItem(
    rule: HighlightRule,
    onClick: () -> Unit,
    onEnableToggle: (Boolean) -> Unit,
    onMore: () -> Unit
) {
    val palette = rememberAppSettingPalette()
            Surface(
                color = Color(palette.row),
        shape = AppShapes.Chip,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp)
        ) {
            Checkbox(
                checked = rule.enabled,
                onCheckedChange = onEnableToggle,
                modifier = Modifier.heightIn(min = 40.dp)
            )
            Text(
                text = rule.getDisplayName(),
                style = MaterialTheme.typography.bodyLarge,
                color = palette.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onClick)
                    .padding(vertical = 12.dp)
            )
            IconButton(onClick = onClick) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.edit)
                )
            }
            IconButton(onClick = onMore) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.more_menu)
                )
            }
        }
    }
}
