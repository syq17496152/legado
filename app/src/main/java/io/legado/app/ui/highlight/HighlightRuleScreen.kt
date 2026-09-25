package io.legado.app.ui.highlight

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.read.config.HighlightRule
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.EmptyStateAction
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.rememberAppManagementPalette

/**
 * 高亮规则管理页 Compose 受控组件（L-C5 枝叶页，S2 列表管理样板）。
 * 状态由宿主（Activity）传入，事件全部上抛；统一壳 AppManagementScaffold（顶栏+搜索）+
 * 规则列表（Switch 启停/编辑/更多菜单）+ 空态 + 条目操作 AppMenuSheet。
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
    var menuRule by remember { mutableStateOf<HighlightRule?>(null) }

    val filtered = remember(rules, searchQuery) {
        if (searchQuery.isBlank()) rules
        else rules.filter { it.getDisplayName().contains(searchQuery.trim(), ignoreCase = true) }
    }

    // followup F5：统一管理族壳（AppManagementScaffold 平移，删页内自绘 GlassTopAppBar/SettingsSearchBar）
    val palette = rememberAppManagementPalette()
    val moreMenuActions = listOf(
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
    AppManagementScaffold(
        title = stringResource(R.string.highlight_rule_manage),
        selectedCount = 0,
        totalCount = rules.size,
        modifier = modifier,
        palette = palette,
        searchQuery = searchQuery,
        searchHint = stringResource(R.string.settings_search),
        onSearchChange = onSearchQueryChange,
        onBack = onBack,
        topActions = listOf(
            AppManagementAction(
                text = stringResource(R.string.menu_add_highlight_rule),
                // bugfix-0908f 资产统一：新增图标统一全站细线资产 ic_add（原引 Material 粗线 Icons.Default.Add）
                iconRes = R.drawable.ic_add,
                onClick = onAdd
            ),
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
    ) { palette ->
        if (filtered.isEmpty()) {
            EmptyStatePlaceholder(
                icon = Icons.Default.List,
                title = stringResource(R.string.highlight_rule_empty_title),
                subtitle = stringResource(R.string.highlight_rule_empty_subtitle),
                // F51 空态引导强化：保留原文案，补「预设规则 / 恢复默认」两个可立即脱困的次操作
                secondaryActions = listOf(
                    EmptyStateAction(
                        label = stringResource(R.string.highlight_rule_preset),
                        onClick = onPreset
                    ),
                    EmptyStateAction(
                        label = stringResource(R.string.highlight_rule_restore_default),
                        onClick = onRestoreDefault
                    )
                ),
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
                        palette = palette,
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

/**
 * F49：启停控件由 M3 `Checkbox` 收敛为管理族行组件 [AppManagementListRow] + Switch，
 * 与书源/订阅源管理页的启停形态统一（复选框语义=多选，开关语义=启停，此前语义错配）。
 * 触控目标由 40dp 提升到行级 56dp，整行可点（编辑）。
 */
@Composable
private fun HighlightRuleItem(
    rule: HighlightRule,
    palette: AppManagementPalette,
    onClick: () -> Unit,
    onEnableToggle: (Boolean) -> Unit,
    onMore: () -> Unit
) {
    AppManagementListRow(
        title = rule.getDisplayName(),
        palette = palette,
        switchChecked = rule.enabled,
        onSwitchChange = onEnableToggle,
        onClick = onClick,
        onEdit = onClick,
        onMore = onMore,
        minHeight = 56.dp
    )
}
