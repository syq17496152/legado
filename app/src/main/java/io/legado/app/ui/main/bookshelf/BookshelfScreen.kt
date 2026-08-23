package io.legado.app.ui.main.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeColorOrNull
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.model.BookCover
import io.legado.app.utils.ColorUtils
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.ShelfGridSkeleton
import io.legado.app.ui.widget.components.BadgeDot
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.utils.toTimeAgo

/**
 * 书架 Compose 渲染（Phase 3）。
 * 受控组件：books/loading 由调用方（BookshelfFragment1/2）提供。
 * style1: 分组 Tab + 所选组书列表；style2(Folder): 平铺分组头 + 书。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    bookGroups: List<BookGroup>,
    books: List<Book>,
    loading: Boolean,
    error: Boolean = false,
    groupId: Long,
    isFolder: Boolean,
    topScrollTrigger: Long = 0L,
    isRefreshing: Boolean = false,
    topBarVersion: Int = 0,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onGroupSelected: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
) {
    val layout = androidx.compose.runtime.remember { AppConfig.bookshelfLayout }
    val showBookname = androidx.compose.runtime.remember { AppConfig.showBookname }
    val showUnread = androidx.compose.runtime.remember { AppConfig.showUnread }
    val showReadProgress = androidx.compose.runtime.remember { AppConfig.showBookshelfReadProgress }
    val showLastUpdateTime = androidx.compose.runtime.remember { AppConfig.showLastUpdateTime }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isFolder) {
            if (groupId == BookGroup.IdRoot) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FolderGroupList(
                        bookGroups = bookGroups,
                        layout = layout,
                        onGroupSelected = onGroupSelected,
                        onGroupLongClick = onGroupLongClick,
                    )
                }
                return
            }
        } else if (bookGroups.size > 1) {
            BookGroupTabs(
                bookGroups = bookGroups,
                selected = groupId,
                onGroupSelected = onGroupSelected,
                onGroupLongClick = onGroupLongClick,
                topBarVersion = topBarVersion,
            )
        }
        if (loading) {
            // 骨架屏必须与最终布局一致，避免"先画双列再刷成单列"的闪变（bug7）
            if (layout >= 2) {
                ShelfGridSkeleton(columns = layout, modifier = Modifier.fillMaxSize())
            } else {
                ShelfListSkeleton(compact = layout == 1, modifier = Modifier.fillMaxSize())
            }
        } else if (error) {
            EmptyStatePlaceholder(
                icon = Icons.Filled.Error,
                title = stringResource(R.string.load_error_retry),
                actionLabel = stringResource(R.string.retry),
                onAction = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (books.isEmpty()) {
            EmptyStatePlaceholder(
                icon = Icons.AutoMirrored.Filled.MenuBook,
                title = stringResource(R.string.bookshelf_empty_title),
                subtitle = stringResource(R.string.bookshelf_empty),
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val listState = rememberLazyListState()
            val gridState = rememberLazyGridState()
            LaunchedEffect(topScrollTrigger) {
                if (topScrollTrigger > 0) {
                    if (layout >= 2) {
                        gridState.scrollToItem(0)
                    } else {
                        listState.scrollToItem(0)
                    }
                }
            }
            val content: @Composable () -> Unit = {
                if (layout >= 2) {
                    BookGrid(
                        gridState = gridState,
                        books = books,
                        spanCount = layout,
                        showBookname = showBookname,
                        showUnread = showUnread,
                        showReadProgress = showReadProgress,
                        onBookClick = onBookClick,
                        onBookLongClick = onBookLongClick,
                    )
                } else {
                    BookList(
                        listState = listState,
                        books = books,
                        compact = layout == 1,
                        showUnread = showUnread,
                        showReadProgress = showReadProgress,
                        showLastUpdateTime = showLastUpdateTime,
                        onBookClick = onBookClick,
                        onBookLongClick = onBookLongClick,
                    )
                }
            }
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                content()
            }
        }
    }
}

internal fun List<Book>.sortedByBook(sortType: Int): List<Book> {
    return when (sortType) {
        1 -> sortedByDescending { it.latestChapterTime }
        2 -> sortedBy { it.name }
        3 -> sortedBy { it.order }
        4 -> sortedByDescending { maxOf(it.latestChapterTime, it.durChapterTime) }
        5 -> sortedBy { it.author }
        else -> sortedByDescending { it.durChapterTime }
    }
}

@Composable
private fun BookGroupTabs(
    bookGroups: List<BookGroup>,
    selected: Long,
    onGroupSelected: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    topBarVersion: Int,
) {
    val context = LocalContext.current
    // 顶栏设置变化（TOP_BAR_CHANGED）由调用方 bump topBarVersion 触发重组；
    // 主题变化走 Activity 重建，重新组合时按最新配置取色。
    val isNight = AppConfig.isNightTheme
    val config = remember(topBarVersion, isNight) {
        TopBarConfig.currentConfig(context, isNight)
    }
    // 取色逻辑对齐 RoundedTagBarView.applyTopBarStyle，保证顶栏设置/主题设置均生效。
    val tagBarColor = config.tagBarColor
        ?: if (config.style == TopBarConfig.STYLE_REGULAR) {
            android.graphics.Color.WHITE
        } else {
            context.themeColorOrNull(PreferKey.themeTabBackgroundColor)
                ?: context.themeMutedColorOrDefault()
        }
    val selectedBackground = TopBarConfig.withOpacity(
        config.tagSelectedColor ?: context.themeCardColorOrDefault(),
        config.tagSelectedAlpha
    )
    val selectedForeground = readableTagColor(context.accentColor, selectedBackground)
    val normalForeground = context.primaryTextColor
    val barBackground = TopBarConfig.withOpacity(tagBarColor, config.tagBarAlpha)
    val selectedIndex = bookGroups.indexOfFirst { it.groupId == selected }.coerceAtLeast(0)
    ScrollableTabRow(
        selectedTabIndex = selectedIndex,
        edgePadding = 8.dp,
        containerColor = Color(barBackground),
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedIndex]),
                color = Color(selectedBackground),
            )
        },
    ) {
        bookGroups.forEach { group ->
            val isSelected = group.groupId == selected
            Tab(
                selected = isSelected,
                onClick = { onGroupSelected(group.groupId) },
                modifier = Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { onGroupLongClick(group) },
                ),
                text = {
                    Text(
                        text = group.groupName,
                        color = if (isSelected) Color(selectedForeground) else Color(normalForeground),
                    )
                },
            )
        }
    }
}

/** 对齐 RoundedTagBarView.readableTagTextColor 的可读文字色计算。 */
private fun readableTagColor(preferredColor: Int, backgroundColor: Int): Int {
    if (android.graphics.Color.alpha(backgroundColor) < 40) return preferredColor
    val preferredIsLight = ColorUtils.isColorLight(preferredColor)
    val backgroundIsLight = ColorUtils.isColorLight(backgroundColor)
    return if (preferredIsLight != backgroundIsLight) {
        preferredColor
    } else if (backgroundIsLight) {
        android.graphics.Color.BLACK
    } else {
        android.graphics.Color.WHITE
    }
}

@Composable
private fun FolderGroupList(
    bookGroups: List<BookGroup>,
    layout: Int,
    onGroupSelected: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
) {
    if (layout >= 2) {
        FolderGroupGridContent(bookGroups, onGroupSelected, onGroupLongClick, layout)
    } else {
        FolderGroupListContent(bookGroups, onGroupSelected, onGroupLongClick)
    }
}

/**
 * 网格布局分组封面：大封面卡片 + 分组名在下（对齐原版 item_bookshelf_grid_group.xml）。
 */
@Composable
private fun FolderGroupGridContent(
    bookGroups: List<BookGroup>,
    onGroupSelected: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    spanCount: Int,
) {
    val showBookname = AppConfig.showBookname
    LazyVerticalGrid(
        columns = GridCells.Fixed(spanCount),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(bookGroups, key = { it.groupId }) { group ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onGroupSelected(group.groupId) },
                        onLongClick = { onGroupLongClick(group) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .clip(AppShapes.Chip)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    GroupCover(
                        group = group,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (showBookname != 1) {
                    Text(
                        text = group.groupName,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

/**
 * 列表布局分组封面：大封面(66x90) + 分组名在右（对齐原版 item_bookshelf_list_group.xml）。
 */
@Composable
private fun FolderGroupListContent(
    bookGroups: List<BookGroup>,
    onGroupSelected: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(bookGroups, key = { it.groupId }) { group ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(106.dp)
                    .combinedClickable(
                        onClick = { onGroupSelected(group.groupId) },
                        onLongClick = { onGroupLongClick(group) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupCover(
                    group = group,
                    modifier = Modifier
                        .size(width = 66.dp, height = 90.dp)
                        .clip(AppShapes.Chip),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = group.groupName,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * 分组封面：优先加载 [BookGroup.cover]（长按分组在 GroupEditDialog 中可本地导入替换），
 * 为空时用主题色背景 + 文件夹图标（替代原固定白图 image_cover_default，跟随主题）。
 */
@Composable
private fun GroupCover(
    group: BookGroup,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        if (group.cover.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp),
            )
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    ImageView(ctx).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                update = { view ->
                    BookCover.load(context, group.cover).into(view)
                },
            )
        }
    }
}

@Composable
private fun BookGrid(
    gridState: LazyGridState,
    books: List<Book>,
    spanCount: Int,
    showBookname: Int,
    showUnread: Boolean,
    showReadProgress: Boolean,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(spanCount),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        items(books, key = { it.bookUrl }) { book ->
            BookGridItem(
                book = book,
                showBookname = showBookname,
                showUnread = showUnread,
                showReadProgress = showReadProgress,
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
            )
        }
    }
}

@Composable
private fun BookGridItem(
    book: Book,
    showBookname: Int,
    showUnread: Boolean,
    showReadProgress: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.7f)
                .clip(AppShapes.Chip)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (book.getDisplayCover().isNullOrBlank()) {
                GeneratedCover(book, modifier = Modifier.fillMaxSize())
            } else {
                BookCoverImage(book, modifier = Modifier.fillMaxSize())
            }
            if (showUnread) {
                val unread = book.getUnreadChapterNum()
                if (unread > 0) {
                    BadgeDot(
                        count = unread,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                }
            }
            if (showBookname == 2) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // 书名遮罩：改用 M3 语义 scrim（原 Color.Black α0.55）
                        .background(color = MaterialTheme.colorScheme.scrim)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = book.name,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            if (showReadProgress) {
                val progress = book.readProgress()
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                    )
                }
            }
        }
        if (showBookname == 1) {
            Text(
                text = book.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        if (showBookname == 1 && book.author.isNotBlank()) {
            Text(
                text = book.author,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun BookList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    books: List<Book>,
    compact: Boolean,
    showUnread: Boolean,
    showReadProgress: Boolean,
    showLastUpdateTime: Boolean,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize(), state = listState) {
        items(books, key = { it.bookUrl }) { book ->
            BookListItem(
                book = book,
                compact = compact,
                showUnread = showUnread,
                showReadProgress = showReadProgress,
                showLastUpdateTime = showLastUpdateTime,
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
            )
        }
    }
}

@Composable
private fun BookListItem(
    book: Book,
    compact: Boolean,
    showUnread: Boolean,
    showReadProgress: Boolean,
    showLastUpdateTime: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val coverSize = if (compact) 48.dp else 60.dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 76.dp else 96.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(coverSize)
                .aspectRatio(0.7f)
                .clip(AppShapes.Chip)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (book.getDisplayCover().isNullOrBlank()) {
                GeneratedCover(book, modifier = Modifier.fillMaxSize(), iconSize = 24)
            } else {
                BookCoverImage(book, modifier = Modifier.fillMaxSize())
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = book.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (showUnread) {
                    val unread = book.getUnreadChapterNum()
                    if (unread > 0) {
                        Spacer(modifier = Modifier.width(6.dp))
                        BadgeDot(
                            count = unread,
                            contentColor = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (book.author.isNotBlank() && !compact) {
                Text(
                    text = book.author,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!book.durChapterTitle.isNullOrBlank()) {
                Text(
                    text = book.durChapterTitle ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (!compact && !book.latestChapterTitle.isNullOrBlank()) {
                Text(
                    text = book.latestChapterTitle ?: "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (showLastUpdateTime && !book.isLocal) {
                Text(
                    text = book.latestChapterTime.toTimeAgo(),
                    maxLines = 1,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            if (showReadProgress) {
                val progress = book.readProgress()
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(2.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
            }
        }
    }
}