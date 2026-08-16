package io.legado.app.ui.replace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.legado.app.R
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar

/**
 * L-C4 替换净化页（列表页）：Compose 顶栏 + 搜索桥接。
 *
 * 说明：本页列表内核（RecyclerView + 拖拽排序 + 滑选多选 + SelectActionBar 批量）
 * 保留 View 层（拖拽/滑选/批量在 Compose 中重写风险高，按 AD-20「内核 View 桥接」原则
 * 保留），仅顶栏/搜索/菜单 Compose 化；功能零删减（分组管理/启用停用无分组分组筛选/
 * 添加/本地在线扫码导入/帮助 全保留）。
 */
@Composable
fun ReplaceRuleTopBar(
    searchQuery: String,
    searchVisible: Boolean,
    groups: List<String>,
    onSearchQueryChange: (String) -> Unit,
    onSearchVisibleChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onGroupManage: () -> Unit,
    onFilterEnabled: () -> Unit,
    onFilterDisabled: () -> Unit,
    onFilterNoGroup: () -> Unit,
    onFilterGroup: (String) -> Unit,
    onImportOnline: () -> Unit,
    onImportLocal: () -> Unit,
    onImportQr: () -> Unit,
    onHelp: () -> Unit,
    modifier: Modifier = Modifier
) {
    var groupMenuVisible by remember { mutableStateOf(false) }
    var moreMenuVisible by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        GlassTopAppBar(
            title = stringResource(R.string.replace_purify),
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                if (!searchVisible) {
                    IconButton(onClick = { onSearchVisibleChange(true) }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.action_search)
                        )
                    }
                }
                IconButton(onClick = onAdd) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = stringResource(R.string.add_replace_rule)
                    )
                }
                IconButton(onClick = { groupMenuVisible = true }) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = stringResource(R.string.menu_action_group)
                    )
                }
                AppDropdownMenu(
                    expanded = groupMenuVisible,
                    onDismiss = { groupMenuVisible = false },
                    actions = buildList {
                        add(
                            MenuAction(
                                icon = Icons.Default.Folder,
                                title = stringResource(R.string.group_manage),
                                onClick = onGroupManage
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.Check,
                                title = stringResource(R.string.enabled),
                                onClick = onFilterEnabled
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.Block,
                                title = stringResource(R.string.disabled),
                                onClick = onFilterDisabled
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.FolderOff,
                                title = stringResource(R.string.no_group),
                                onClick = onFilterNoGroup
                            )
                        )
                        if (groups.isNotEmpty()) {
                            groups.forEach { group ->
                                add(
                                    MenuAction(
                                        icon = Icons.Default.Group,
                                        title = group,
                                        onClick = { onFilterGroup(group) }
                                    )
                                )
                            }
                        }
                    }
                )
                IconButton(onClick = { moreMenuVisible = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more_menu)
                    )
                }
                AppDropdownMenu(
                    expanded = moreMenuVisible,
                    onDismiss = { moreMenuVisible = false },
                    actions = listOf(
                        MenuAction(
                            icon = Icons.Default.FileUpload,
                            title = stringResource(R.string.import_local),
                            onClick = onImportLocal
                        ),
                        MenuAction(
                            icon = Icons.Default.CloudDownload,
                            title = stringResource(R.string.import_on_line),
                            onClick = onImportOnline
                        ),
                        MenuAction(
                            icon = Icons.Default.QrCode,
                            title = stringResource(R.string.import_by_qr_code),
                            onClick = onImportQr
                        ),
                        MenuAction(
                            icon = Icons.Default.Help,
                            title = stringResource(R.string.help),
                            onClick = onHelp
                        )
                    )
                )
            }
        )
        if (searchVisible) {
            SettingsSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                placeholder = stringResource(R.string.replace_purify_search)
            )
        }
    }
}
