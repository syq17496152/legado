package io.legado.app.ui.book.source.manage

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Domain
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderDelete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ImportExport
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.GroupHeader
import io.legado.app.ui.widget.components.ListLayoutMenu
import io.legado.app.ui.widget.components.ListLayoutOption
import io.legado.app.ui.widget.components.ListSortOption
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar

/**
 * 书源管理页 Compose 受控组件（S2 样板页核心）。
 * 状态全部由宿主（Activity）传入，事件全部上抛；顶栏 GlassTopAppBar + SettingsSearchBar +
 * ListLayoutMenu（布局切换+排序）+ 三视图（列表/紧凑/网格）+ 更多菜单（导入/分组/筛选/回收站）。
 */
@Composable
fun BookSourceScreen(
    sources: List<BookSourcePart>,
    groups: List<String>,
    currentType: Int,
    currentGroup: String?,
    currentLayout: Int,
    currentSortKey: String,
    sortAscending: Boolean,
    groupSourcesByDomain: Boolean,
    isFolderViewMode: Boolean,
    sourceGroupStyle: Int,
    isShowingFolder: Boolean,
    folderItems: List<String>,
    searchQuery: String,
    isSelecting: Boolean,
    selectedCount: Int,
    selectedUrls: Set<String>,
    checkMessages: Map<String, String>,
    isChecking: Boolean,
    showTopBar: Boolean = true,
    onBack: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onLayoutSelect: (Int) -> Unit,
    onSortSelect: (key: String, ascending: Boolean) -> Unit,
    onTypeSelect: (Int) -> Unit,
    onGroupSelect: (String?) -> Unit,
    onFolderClick: (String) -> Unit,
    onFolderConfig: () -> Unit,
    onAddSource: () -> Unit,
    onImportLocal: () -> Unit,
    onImportOnline: () -> Unit,
    onImportQr: () -> Unit,
    onGroupManage: () -> Unit,
    onGroupSourcesByDomain: () -> Unit,
    onRecycleBin: () -> Unit,
    onHelp: () -> Unit,
    onItemClick: (BookSourcePart) -> Unit,
    onItemLongClick: (BookSourcePart) -> Unit,
    onEnableToggle: (BookSourcePart, Boolean) -> Unit,
    onEdit: (BookSourcePart) -> Unit,
    onDebug: (BookSourcePart) -> Unit,
    onCopyUrl: (BookSourcePart) -> Unit,
    onMore: (BookSourcePart) -> Unit,
    onSelectAll: (Boolean) -> Unit,
    onRevertSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onBatchAction: (BookSourceBatchAction) -> Unit,
    onGroupBatchEnable: (List<BookSourcePart>, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchVisible by remember { mutableStateOf(searchQuery.isNotEmpty()) }
    var moreMenuVisible by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val layoutOptions = listOf(
        ListLayoutOption(0, Icons.Default.ViewList, stringResource(R.string.layout_list)),
        ListLayoutOption(1, Icons.Default.ViewAgenda, stringResource(R.string.layout_list_compact)),
        ListLayoutOption(2, Icons.Default.GridView, stringResource(R.string.layout_grid2))
    )
    val sortOptions = listOf(
        ListSortOption("0", stringResource(R.string.source_sort_0)),
        ListSortOption("1", stringResource(R.string.source_sort_1)),
        ListSortOption("2", stringResource(R.string.source_sort_2)),
        ListSortOption("3", stringResource(R.string.source_sort_3)),
        ListSortOption("4", stringResource(R.string.source_sort_4)),
        ListSortOption("5", stringResource(R.string.source_sort_5))
    )
    // 布局归一化：2-6 网格列数视为「网格」选项
    val layoutForMenu = if (currentLayout in 2..6) 2 else currentLayout

    if (showTopBar) {
        BackHandler {
            when {
                isSelecting -> onBack()
                searchVisible && searchQuery.isNotEmpty() -> onSearchQueryChange("")
                else -> onBack()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (showTopBar) {
                GlassTopAppBar(
                    title = if (searchVisible) "" else stringResource(R.string.book_source),
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
                            ListLayoutMenu(
                                layoutOptions = layoutOptions,
                                sortOptions = sortOptions,
                                currentLayout = layoutForMenu,
                                currentSortKey = currentSortKey,
                                currentAscending = sortAscending,
                                onLayoutSelect = onLayoutSelect,
                                onSortSelect = onSortSelect
                            )
                            IconButton(onClick = { moreMenuVisible = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.more)
                                )
                            }
                            BookSourceMoreMenu(
                                expanded = moreMenuVisible,
                                onDismiss = { moreMenuVisible = false },
                                groups = groups,
                                currentType = currentType,
                                currentGroup = currentGroup,
                                groupSourcesByDomain = groupSourcesByDomain,
                                onFolderConfig = onFolderConfig,
                                onAddSource = onAddSource,
                                onImportLocal = onImportLocal,
                                onImportOnline = onImportOnline,
                                onImportQr = onImportQr,
                                onGroupManage = onGroupManage,
                                onGroupSourcesByDomain = onGroupSourcesByDomain,
                                onRecycleBin = onRecycleBin,
                                onHelp = onHelp,
                                onTypeSelect = onTypeSelect,
                                onGroupSelect = onGroupSelect
                            )
                        }
                    }
                )
            }
            if (showTopBar && searchVisible) {
                SettingsSearchBar(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange
                )
                QuickFilterWords(
                    query = searchQuery,
                    onQueryChange = onSearchQueryChange
                )
            }

            if (isSelecting) {
                SelectActionBarCompose(
                    selectedCount = selectedCount,
                    totalCount = sources.size,
                    onSelectAll = onSelectAll,
                    onRevert = onRevertSelection,
                    onDelete = onDeleteSelection,
                    onAction = onBatchAction,
                    onClose = onBack
                )
            }

            when {
                groupSourcesByDomain && searchQuery.isEmpty() -> {
                    if (sources.isEmpty()) {
                        EmptyStatePlaceholder(
                            icon = Icons.Default.LibraryBooks,
                            title = stringResource(R.string.book_source_empty_title),
                            subtitle = stringResource(R.string.book_source_empty_subtitle),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        GroupedSourceList(
                            sources = sources,
                            groups = groups,
                            sourceGroupStyle = sourceGroupStyle,
                            groupByDomain = true,
                            layout = currentLayout,
                            isSelecting = isSelecting,
                            selectedUrls = selectedUrls,
                            checkMessages = checkMessages,
                            onItemClick = onItemClick,
                            onItemLongClick = onItemLongClick,
                            onEnableToggle = onEnableToggle,
                            onEdit = onEdit,
                            onDebug = onDebug,
                            onCopyUrl = onCopyUrl,
                            onMore = onMore,
                            onGroupBatchEnable = onGroupBatchEnable,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                isFolderViewMode && isShowingFolder -> {
                    if (sources.isEmpty()) {
                        EmptyStatePlaceholder(
                            icon = Icons.Default.LibraryBooks,
                            title = stringResource(R.string.book_source_empty_title),
                            subtitle = stringResource(R.string.book_source_empty_subtitle),
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        GroupedSourceList(
                            sources = sources,
                            groups = groups,
                            sourceGroupStyle = sourceGroupStyle,
                            groupByDomain = false,
                            layout = currentLayout,
                            isSelecting = isSelecting,
                            selectedUrls = selectedUrls,
                            checkMessages = checkMessages,
                            onItemClick = onItemClick,
                            onItemLongClick = onItemLongClick,
                            onEnableToggle = onEnableToggle,
                            onEdit = onEdit,
                            onDebug = onDebug,
                            onCopyUrl = onCopyUrl,
                            onMore = onMore,
                            onGroupBatchEnable = onGroupBatchEnable,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                isShowingFolder -> {
                    FolderGrid(
                        folderItems = folderItems,
                        onFolderClick = onFolderClick,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                sources.isEmpty() && searchQuery.isEmpty() && !isSelecting -> {
                    EmptyStatePlaceholder(
                        icon = Icons.Default.LibraryBooks,
                        title = stringResource(R.string.book_source_empty_title),
                        subtitle = stringResource(R.string.book_source_empty_subtitle),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                currentLayout == 0 -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(sources, key = { it.bookSourceUrl }) { source ->
                            BookSourceListItem(
                                source = source,
                                hostText = if (groupSourcesByDomain)
                                    getSourceHostText(source) else null,
                                checkMessage = checkMessages[source.bookSourceUrl],
                                isSelecting = isSelecting,
                                isChecked = selectedUrls.contains(source.bookSourceUrl),
                                enabledToggle = { onEnableToggle(source, it) },
                                onClick = { onItemClick(source) },
                                onLongClick = { onItemLongClick(source) },
                                onEdit = { onEdit(source) },
                                onDebug = { onDebug(source) },
                                onCopyUrl = { onCopyUrl(source) },
                                onMore = { onMore(source) }
                            )
                        }
                    }
                }
                currentLayout == 1 -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(sources, key = { it.bookSourceUrl }) { source ->
                            BookSourceCompactItem(
                                source = source,
                                isSelecting = isSelecting,
                                isChecked = selectedUrls.contains(source.bookSourceUrl),
                                enabledToggle = { onEnableToggle(source, it) },
                                onClick = { onItemClick(source) },
                                onLongClick = { onItemLongClick(source) }
                            )
                        }
                    }
                }
                else -> {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val columns = when {
                            maxWidth < 400.dp -> 2
                            maxWidth < 600.dp -> 3
                            maxWidth < 800.dp -> 4
                            else -> 6
                        }
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(columns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(8.dp)
                        ) {
                            items(sources, key = { it.bookSourceUrl }) { source ->
                                BookSourceGridItem(
                                    source = source,
                                    isSelecting = isSelecting,
                                    isChecked = selectedUrls.contains(source.bookSourceUrl),
                                    onClick = { onItemClick(source) },
                                    onLongClick = { onItemLongClick(source) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 书源域名分组头文本（分组模式下列表 item 上方显示）。
 */
private fun getSourceHostText(source: BookSourcePart): String {
    val host = source.lastHost ?: source.bookSourceUrl
        .substringAfter("://", "")
        .substringBefore("/", "")
    return host
}

/**
 * 搜索快捷词条：enabled / disabled / need_login / no_group / enabled_explore / disabled_explore。
 */
@Composable
private fun QuickFilterWords(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val words = listOf(
        stringResource(R.string.enabled),
        stringResource(R.string.disabled),
        stringResource(R.string.need_login),
        stringResource(R.string.no_group),
        stringResource(R.string.enabled_explore),
        stringResource(R.string.disabled_explore)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        words.forEach { word ->
            Text(
                text = word,
                style = MaterialTheme.typography.labelSmall,
                color = if (query.contains(word)) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onQueryChange(word) }
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

/**
 * 文件夹网格视图（按类型/按分组）。
 */
@Composable
private fun FolderGrid(
    folderItems: List<String>,
    onFolderClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier) {
        val columns = when {
            maxWidth < 400.dp -> 3
            maxWidth < 600.dp -> 4
            else -> 5
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp)
        ) {
            items(folderItems) { item ->
                FolderGridItem(
                    name = item,
                    onClick = { onFolderClick(item) }
                )
            }
        }
    }
}

/**
 * 文件夹卡片项。
 */
@Composable
private fun FolderGridItem(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp)
            )
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

/**
 * 分组结果：组 key（type:/group:/host: 前缀）+ 组标题 + 组内书源。
 */
private data class SourceGroup(
    val key: String,
    val title: String,
    val sources: List<BookSourcePart>
)

/**
 * 分组态书源列表（V4 折叠渲染）：按类型/分组/域名插入 GroupHeader（折叠箭头+组名+
 * 启用数/总数徽标+组操作菜单 全部启用(N)/全部停用(N)），整行点击切折叠，折叠集
 * rememberSaveable 保存，切分组方式时清空失效 key；LazyColumn 内 key="group:xxx"+contentType。
 */
@Composable
private fun GroupedSourceList(
    sources: List<BookSourcePart>,
    groups: List<String>,
    sourceGroupStyle: Int,
    groupByDomain: Boolean,
    layout: Int,
    isSelecting: Boolean,
    selectedUrls: Set<String>,
    checkMessages: Map<String, String>,
    onItemClick: (BookSourcePart) -> Unit,
    onItemLongClick: (BookSourcePart) -> Unit,
    onEnableToggle: (BookSourcePart, Boolean) -> Unit,
    onEdit: (BookSourcePart) -> Unit,
    onDebug: (BookSourcePart) -> Unit,
    onCopyUrl: (BookSourcePart) -> Unit,
    onMore: (BookSourcePart) -> Unit,
    onGroupBatchEnable: (List<BookSourcePart>, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    // 按当前分组方式构建组列表（组序稳定：类型固定 0-4 / 分组按 groups 顺序 + no_group 兜底 / 域名按 host）
    val sourceGroups = buildSourceGroups(sources, sourceGroupStyle, groupByDomain, groups)
    val groupKeySaver = listSaver<Set<String>, String>(
        save = { it.toList() },
        restore = { it.toSet() }
    )
    var collapsedGroups by rememberSaveable(stateSaver = groupKeySaver) {
        mutableStateOf(setOf<String>())
    }
    // 切分组方式（类型/分组/域名）时清空折叠集，丢弃失效 key
    LaunchedEffect(sourceGroupStyle, groupByDomain) {
        collapsedGroups = emptySet()
    }

    if (layout >= 2) {
        // 网格视图：分组 header 占满整行，组内书源网格卡片
        BoxWithConstraints(modifier = modifier) {
            val columns = when {
                maxWidth < 400.dp -> 2
                maxWidth < 600.dp -> 3
                maxWidth < 800.dp -> 4
                else -> 6
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                sourceGroups.forEach { group ->
                    val enabledCount = group.sources.count { it.enabled }
                    item(
                        key = "group:${group.key}",
                        contentType = "groupHeader",
                        span = { GridItemSpan(maxLineSpan) }
                    ) {
                        val enableAllTitle = stringResource(
                            R.string.group_header_enable_all, group.sources.size
                        )
                        val disableAllTitle = stringResource(
                            R.string.group_header_disable_all, group.sources.size
                        )
                        GroupHeader(
                            name = group.title,
                            enabledCount = enabledCount,
                            totalCount = group.sources.size,
                            collapsed = group.key in collapsedGroups,
                            onToggleCollapse = {
                                collapsedGroups = if (group.key in collapsedGroups) {
                                    collapsedGroups - group.key
                                } else {
                                    collapsedGroups + group.key
                                }
                            },
                            onMenuActions = {
                                listOf(
                                    MenuAction(
                                        icon = Icons.Default.Done,
                                        title = enableAllTitle,
                                        onClick = { onGroupBatchEnable(group.sources, true) }
                                    ),
                                    MenuAction(
                                        icon = Icons.Default.Clear,
                                        title = disableAllTitle,
                                        onClick = { onGroupBatchEnable(group.sources, false) }
                                    )
                                )
                            }
                        )
                    }
                    if (group.key !in collapsedGroups) {
                        items(group.sources, key = { it.bookSourceUrl }) { source ->
                            BookSourceGridItem(
                                source = source,
                                isSelecting = isSelecting,
                                isChecked = selectedUrls.contains(source.bookSourceUrl),
                                onClick = { onItemClick(source) },
                                onLongClick = { onItemLongClick(source) }
                            )
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 4.dp)
        ) {
            sourceGroups.forEach { group ->
                val enabledCount = group.sources.count { it.enabled }
                item(
                    key = "group:${group.key}",
                    contentType = "groupHeader"
                ) {
                    val enableAllTitle = stringResource(
                        R.string.group_header_enable_all, group.sources.size
                    )
                    val disableAllTitle = stringResource(
                        R.string.group_header_disable_all, group.sources.size
                    )
                    GroupHeader(
                        name = group.title,
                        enabledCount = enabledCount,
                        totalCount = group.sources.size,
                        collapsed = group.key in collapsedGroups,
                        onToggleCollapse = {
                            collapsedGroups = if (group.key in collapsedGroups) {
                                collapsedGroups - group.key
                            } else {
                                collapsedGroups + group.key
                            }
                        },
                        onMenuActions = {
                            listOf(
                                MenuAction(
                                    icon = Icons.Default.Done,
                                    title = enableAllTitle,
                                    onClick = { onGroupBatchEnable(group.sources, true) }
                                ),
                                MenuAction(
                                    icon = Icons.Default.Clear,
                                    title = disableAllTitle,
                                    onClick = { onGroupBatchEnable(group.sources, false) }
                                )
                            )
                        }
                    )
                }
                if (group.key !in collapsedGroups) {
                    items(group.sources, key = { it.bookSourceUrl }) { source ->
                        if (layout == 1) {
                            BookSourceCompactItem(
                                source = source,
                                isSelecting = isSelecting,
                                isChecked = selectedUrls.contains(source.bookSourceUrl),
                                enabledToggle = { onEnableToggle(source, it) },
                                onClick = { onItemClick(source) },
                                onLongClick = { onItemLongClick(source) }
                            )
                        } else {
                            BookSourceListItem(
                                source = source,
                                hostText = null,
                                checkMessage = checkMessages[source.bookSourceUrl],
                                isSelecting = isSelecting,
                                isChecked = selectedUrls.contains(source.bookSourceUrl),
                                enabledToggle = { onEnableToggle(source, it) },
                                onClick = { onItemClick(source) },
                                onLongClick = { onItemLongClick(source) },
                                onEdit = { onEdit(source) },
                                onDebug = { onDebug(source) },
                                onCopyUrl = { onCopyUrl(source) },
                                onMore = { onMore(source) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 按分组方式构建组列表。
 * sourceGroupStyle==1 按类型（0-4 固定序）；==2 按自定义分组（groups 顺序 + 未分组兜底）；
 * groupByDomain 按域名（lastHost）。
 */
@Composable
private fun buildSourceGroups(
    sources: List<BookSourcePart>,
    sourceGroupStyle: Int,
    groupByDomain: Boolean,
    groups: List<String>
): List<SourceGroup> {
    return when {
        groupByDomain -> {
            sources.groupBy { getSourceHostText(it) }
                .map { (host, list) -> SourceGroup("host:$host", host, list) }
                .sortedBy { it.title }
        }
        sourceGroupStyle == 1 -> {
            val typeKeys = listOf(
                R.string.type_text, R.string.type_audio,
                R.string.type_image, R.string.type_file, R.string.type_video
            )
            typeKeys.mapIndexedNotNull { type, labelRes ->
                val list = sources.filter { it.bookSourceType == type }
                if (list.isEmpty()) null
                else SourceGroup("type:$type", stringResource(labelRes), list)
            }
        }
        else -> {
            val noGroupTitle = stringResource(R.string.no_group)
            // 自定义分组：先按 groups 顺序，再把不在 groups 里的组追加，最后未分组兜底
            val groupMap = LinkedHashMap<String, MutableList<BookSourcePart>>()
            groups.forEach { groupMap[it] = mutableListOf() }
            sources.forEach { source ->
                val groupName = source.bookSourceGroup
                    ?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
                    ?: noGroupTitle
                groupMap.getOrPut(groupName) { mutableListOf() }.add(source)
            }
            val ordered = LinkedHashMap<String, MutableList<BookSourcePart>>()
            groups.forEach { g -> groupMap[g]?.let { ordered[g] = it } }
            groupMap.forEach { (g, list) ->
                if (g != noGroupTitle && !ordered.containsKey(g)) ordered[g] = list
            }
            groupMap[noGroupTitle]?.let { ordered[noGroupTitle] = it }
            ordered.map { (g, list) -> SourceGroup("group:$g", g, list) }
        }
    }
}

/**
 * 顶栏更多菜单：分组/类型筛选 + 导入导出 + 域名分组 + 回收站/帮助。
 */
@Composable
private fun BookSourceMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    groups: List<String>,
    currentType: Int,
    currentGroup: String?,
    groupSourcesByDomain: Boolean,
    onFolderConfig: () -> Unit,
    onAddSource: () -> Unit,
    onImportLocal: () -> Unit,
    onImportOnline: () -> Unit,
    onImportQr: () -> Unit,
    onGroupManage: () -> Unit,
    onGroupSourcesByDomain: () -> Unit,
    onRecycleBin: () -> Unit,
    onHelp: () -> Unit,
    onTypeSelect: (Int) -> Unit,
    onGroupSelect: (String?) -> Unit
) {
    AppDropdownMenu(
        expanded = expanded,
        onDismiss = onDismiss,
        actions = buildList {
            add(
                MenuAction(
                    icon = Icons.Default.Add,
                    title = stringResource(R.string.add_book_source),
                    onClick = onAddSource
                )
            )
            add(
                MenuAction(
                    icon = Icons.Default.ImportExport,
                    title = stringResource(R.string.import_local),
                    onClick = onImportLocal
                )
            )
            add(
                MenuAction(
                    icon = Icons.Default.LibraryBooks,
                    title = stringResource(R.string.import_on_line),
                    onClick = onImportOnline
                )
            )
            add(
                MenuAction(
                    icon = Icons.Default.QrCodeScanner,
                    title = stringResource(R.string.import_by_qr_code),
                    onClick = onImportQr
                )
            )
            add(
                MenuAction(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.group_manage),
                    onClick = onGroupManage
                )
            )
            add(
                MenuAction(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.source_folder_config),
                    onClick = onFolderConfig
                )
            )
            add(
                MenuAction(
                    icon = Icons.Default.Domain,
                    title = stringResource(R.string.group_sources_by_domain),
                    onClick = onGroupSourcesByDomain
                )
            )
            add(
                MenuAction(
                    icon = Icons.Default.Label,
                    title = stringResource(R.string.source_type),
                    header = true,
                    onClick = {}
                )
            )
            listOf(
                -1 to (R.string.all to Icons.Default.SelectAll),
                0 to (R.string.type_text to Icons.Default.Article),
                1 to (R.string.type_audio to Icons.Default.MusicNote),
                2 to (R.string.type_image to Icons.Default.Image),
                3 to (R.string.type_file to Icons.Default.Description),
                4 to (R.string.type_video to Icons.Default.VideoLibrary)
            ).forEach { (type, pair) ->
                add(
                    MenuAction(
                        icon = pair.second,
                        title = stringResource(pair.first),
                        checked = currentType == type,
                        onClick = { onTypeSelect(type) }
                    )
                )
            }
            if (groups.isNotEmpty()) {
                add(
                    MenuAction(
                        icon = Icons.Default.Label,
                        title = stringResource(R.string.menu_action_group),
                        header = true,
                        onClick = {}
                    )
                )
                add(
                    MenuAction(
                        icon = Icons.Default.SelectAll,
                        title = stringResource(R.string.all_groups),
                        checked = currentGroup == null,
                        onClick = { onGroupSelect(null) }
                    )
                )
                groups.forEach { group ->
                    add(
                        MenuAction(
                            icon = Icons.Default.Folder,
                            title = group,
                            checked = currentGroup == group,
                            onClick = { onGroupSelect(group) }
                        )
                    )
                }
            }
            add(
                MenuAction(
                    icon = Icons.Default.RestoreFromTrash,
                    title = stringResource(R.string.menu_recycle_bin),
                    onClick = onRecycleBin
                )
            )
            add(
                MenuAction(
                    icon = Icons.Default.Help,
                    title = stringResource(R.string.help),
                    onClick = onHelp
                )
            )
        }
    )
}

/**
 * 批量操作类型（对应 book_source_sel.xml 12 项）。
 */
enum class BookSourceBatchAction {
    ENABLE,
    DISABLE,
    ENABLE_EXPLORE,
    DISABLE_EXPLORE,
    CHECK_SOURCE,
    CHECK_INTERVAL,
    TOP,
    BOTTOM,
    ADD_GROUP,
    REMOVE_GROUP,
    EXPORT,
    SHARE
}

/**
 * 多选批量操作栏（Compose 版 SelectActionBar，对应 View SelectActionBar + book_source_sel.xml）。
 * 全选/取消全选 + 反转 + 删除 + ⋮ 12 项批量菜单。
 */
@Composable
fun SelectActionBarCompose(
    selectedCount: Int,
    totalCount: Int,
    onSelectAll: (Boolean) -> Unit,
    onRevert: () -> Unit,
    onDelete: () -> Unit,
    onAction: (BookSourceBatchAction) -> Unit,
    onClose: () -> Unit
) {
    var menuVisible by remember { mutableStateOf(false) }
    val allSelected = selectedCount > 0 && selectedCount == totalCount

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = allSelected,
                onCheckedChange = onSelectAll,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = stringResource(
                    if (allSelected) R.string.select_cancel_count
                    else R.string.select_all_count,
                    selectedCount, totalCount
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRevert) {
                Text(stringResource(R.string.revert_selection))
            }
            TextButton(onClick = onDelete) {
                Text(
                    text = stringResource(R.string.delete),
                    color = MaterialTheme.colorScheme.error
                )
            }
            Box {
                IconButton(onClick = { menuVisible = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                AppDropdownMenu(
                    expanded = menuVisible,
                    onDismiss = { menuVisible = false },
                    actions = batchMenuItems.map { (action, labelRes) ->
                        MenuAction(
                            icon = when (action) {
                                BookSourceBatchAction.ENABLE -> Icons.Default.Check
                                BookSourceBatchAction.DISABLE -> Icons.Default.Clear
                                BookSourceBatchAction.ENABLE_EXPLORE -> Icons.Default.Explore
                                BookSourceBatchAction.DISABLE_EXPLORE -> Icons.Default.ExploreOff
                                BookSourceBatchAction.CHECK_SOURCE -> Icons.Default.FactCheck
                                BookSourceBatchAction.CHECK_INTERVAL -> Icons.Default.Timer
                                BookSourceBatchAction.TOP -> Icons.Default.ArrowUpward
                                BookSourceBatchAction.BOTTOM -> Icons.Default.ArrowDownward
                                BookSourceBatchAction.ADD_GROUP -> Icons.Default.CreateNewFolder
                                BookSourceBatchAction.REMOVE_GROUP -> Icons.Default.FolderDelete
                                BookSourceBatchAction.EXPORT -> Icons.Default.FileDownload
                                BookSourceBatchAction.SHARE -> Icons.Default.Share
                            },
                            title = stringResource(labelRes),
                            onClick = { onAction(action) }
                        )
                    }
                )
            }
        }
    }
}

private val batchMenuItems: List<Pair<BookSourceBatchAction, Int>> = listOf(
    BookSourceBatchAction.ENABLE to R.string.enable_selection,
    BookSourceBatchAction.DISABLE to R.string.disable_selection,
    BookSourceBatchAction.ENABLE_EXPLORE to R.string.enable_explore,
    BookSourceBatchAction.DISABLE_EXPLORE to R.string.disable_explore,
    BookSourceBatchAction.CHECK_SOURCE to R.string.check_select_source,
    BookSourceBatchAction.CHECK_INTERVAL to R.string.check_selected_interval,
    BookSourceBatchAction.TOP to R.string.selection_to_top,
    BookSourceBatchAction.BOTTOM to R.string.selection_to_bottom,
    BookSourceBatchAction.ADD_GROUP to R.string.add_group,
    BookSourceBatchAction.REMOVE_GROUP to R.string.remove_group,
    BookSourceBatchAction.EXPORT to R.string.export_selection,
    BookSourceBatchAction.SHARE to R.string.share_selected_source
)
