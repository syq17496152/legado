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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.readProgress
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.onAccentFor
import io.legado.app.lib.theme.rememberThemeUiPalette
import io.legado.app.lib.theme.titleTypeface
import io.legado.app.model.BookCover
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.components.ShelfGridSkeleton
import io.legado.app.ui.widget.components.ShelfListSkeleton
import io.legado.app.ui.widget.compose.rememberAppSettingPalette
import io.legado.app.utils.toTimeAgo

/**
 * 书架 Compose 渲染（Phase 3 + config-needs-restart-fix 参数化改造）。
 * 受控组件：books/loading 与布局配置（layout/showBookname/listItemStyle/introLines/margin/数据类开关）
 * 均由调用方（BookshelfFragment1/2）提供——Fragment 在 BOOKSHELF_REFRESH/STRUCTURE_CHANGED
 * 事件回调重读 AppConfig 后写入 mutableStateOf 传入，替代原 remember{AppConfig.x} 一次性快照
 * （修复"布局配置需重启才生效"根因，AD-04）。
 * style1: 分组 Tab + 所选组书列表；style2(Folder): 平铺分组头 + 书。
 * 取色：AD-07 归位（UiCorner/ThemeUiPalette/AppSettingPalette 直色，M3 派生色禁令）。
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
    layout: Int,
    showBookname: Int,
    listItemStyle: Int,
    introLines: Int,
    margin: Int,
    showUnread: Boolean,
    showReadProgress: Boolean,
    showLastUpdateTime: Boolean,
    onRefresh: () -> Unit = {},
    onRetry: () -> Unit = {},
    onGroupSelected: (Long) -> Unit = {},
    onGroupLongClick: (BookGroup) -> Unit = {},
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (isFolder) {
            if (groupId == BookGroup.IdRoot) {
                Box(modifier = Modifier.fillMaxSize()) {
                    FolderGroupList(
                        bookGroups = bookGroups,
                        layout = layout,
                        showBookname = showBookname,
                        listItemStyle = listItemStyle,
                        margin = margin,
                        onGroupSelected = onGroupSelected,
                        onGroupLongClick = onGroupLongClick,
                    )
                }
                return
            }
        }
        // 非文件夹（style1）不再在 Compose 侧渲染分组 Tab——分组切换由调用方 MainTopBarView 顶栏驱动
        // ui-theme-governance-followup F2：state hoist 至分支外（loading 时不组合会导致滚动位置归零）
        // + rememberSaveable（无 key=跨分组保持滚动位置，有意决策，越界由 LazyGrid clamp 兜底）
        val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }
        val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
        LaunchedEffect(topScrollTrigger) {
            if (topScrollTrigger > 0) {
                if (layout >= 2) {
                    gridState.scrollToItem(0)
                } else {
                    listState.scrollToItem(0)
                }
            }
        }
        if (loading && books.isEmpty()) {
            // 骨架屏仅首次加载（无数据）全屏展示；已有数据时静默刷新保持列表（滚动位置不丢）
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
            val content: @Composable () -> Unit = {
                if (layout >= 2) {
                    BookGrid(
                        gridState = gridState,
                        books = books,
                        spanCount = layout,
                        margin = margin,
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
                        listItemStyle = listItemStyle,
                        introLines = introLines,
                        margin = margin,
                        showUnread = showUnread,
                        showReadProgress = showReadProgress,
                        showLastUpdateTime = showLastUpdateTime,
                        onBookClick = onBookClick,
                        onBookLongClick = onBookLongClick,
                    )
                }
            }
            // 刷新条件化（ui-theme-governance-followup F2/红队 R5-3）：仅列表回顶才放行下拉刷新，
            // 非顶部下拉不误触发全量刷新（onRefresh 短路，指示器不出现）
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    val atTop = if (layout >= 2) {
                        !gridState.canScrollBackward
                    } else {
                        !listState.canScrollBackward
                    }
                    if (atTop) onRefresh()
                },
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

// ===== 卡片样式分档（对齐 archive listItemStyle Classic/RoundedCard，AD-05/K1）=====

/** 列表行最小高度（compact/normal），archive：Classic 82/112、RoundedCard 112/154 */
private fun shelfRowMinHeight(compact: Boolean, rounded: Boolean): Int = when {
    rounded && !compact -> 154
    rounded -> 112
    compact -> 82
    else -> 112
}

/** 列表封面宽度 dp（compact/normal），archive：Classic 58/78、RoundedCard 68/94 */
private fun shelfCoverWidth(compact: Boolean, rounded: Boolean): Int = when {
    rounded && !compact -> 94
    rounded -> 68
    compact -> 58
    else -> 78
}

@Composable
private fun FolderGroupList(
    bookGroups: List<BookGroup>,
    layout: Int,
    showBookname: Int,
    listItemStyle: Int,
    margin: Int,
    onGroupSelected: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
) {
    if (layout >= 2) {
        FolderGroupGridContent(
            bookGroups,
            showBookname,
            margin,
            onGroupSelected,
            onGroupLongClick,
            layout
        )
    } else {
        FolderGroupListContent(
            bookGroups,
            listItemStyle,
            margin,
            onGroupSelected,
            onGroupLongClick
        )
    }
}

/**
 * 网格布局分组封面：大封面卡片 + 分组名在下（对齐原版 item_bookshelf_grid_group.xml）。
 * margin 驱动间距（config-needs-restart-fix K9）；分组名 minLines=2 + titleFontFamily（A3）。
 */
@Composable
private fun FolderGroupGridContent(
    bookGroups: List<BookGroup>,
    showBookname: Int,
    margin: Int,
    onGroupSelected: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
    spanCount: Int,
) {
    val context = LocalContext.current
    val themeUiPalette = rememberThemeUiPalette()
    val palette = rememberAppSettingPalette()
    val coverBg = Color(UiCorner.surfaceColor(themeUiPalette.cardColor))
    val m = margin.coerceAtLeast(2).dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(spanCount),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = m, top = m, end = m, bottom = m),
        horizontalArrangement = Arrangement.spacedBy(m),
        verticalArrangement = Arrangement.spacedBy(m),
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
                        .aspectRatio(0.75f)
                        .clip(AppShapes.Chip)
                        .background(coverBg),
                ) {
                    GroupCover(
                        group = group,
                        coverBg = coverBg,
                        secondaryText = palette.secondaryText,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // showBookname 语义（K7 修正后）：1=显示书名，与书籍条目一致
                if (showBookname == 1) {
                    Text(
                        text = group.groupName,
                        maxLines = 2,
                        minLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp,
                        fontFamily = FontFamily(context.titleTypeface()),
                        color = palette.primaryText,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/**
 * 列表布局分组封面：大封面 + 分组名在右（对齐原版 item_bookshelf_list_group.xml）。
 * 封面尺寸随 listItemStyle 档位（A3）；margin 驱动纵向间距（K9）。
 */
@Composable
private fun FolderGroupListContent(
    bookGroups: List<BookGroup>,
    listItemStyle: Int,
    margin: Int,
    onGroupSelected: (Long) -> Unit,
    onGroupLongClick: (BookGroup) -> Unit,
) {
    val context = LocalContext.current
    val themeUiPalette = rememberThemeUiPalette()
    val palette = rememberAppSettingPalette()
    val coverBg = Color(UiCorner.surfaceColor(themeUiPalette.cardColor))
    val rounded = listItemStyle == 1
    val coverWidth = shelfCoverWidth(compact = false, rounded = rounded).dp
    val rowMinHeight = shelfRowMinHeight(compact = false, rounded = rounded).dp
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = margin.coerceAtLeast(2).dp),
    ) {
        items(bookGroups, key = { it.groupId }) { group ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = rowMinHeight)
                    .combinedClickable(
                        onClick = { onGroupSelected(group.groupId) },
                        onLongClick = { onGroupLongClick(group) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GroupCover(
                    group = group,
                    coverBg = coverBg,
                    secondaryText = palette.secondaryText,
                    modifier = Modifier
                        .width(coverWidth)
                        .aspectRatio(0.75f)
                        .clip(AppShapes.Chip),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = group.groupName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 16.sp,
                        fontFamily = FontFamily(context.titleTypeface()),
                        color = palette.primaryText,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.bookshelf),
                        maxLines = 1,
                        fontSize = 13.sp,
                        color = palette.secondaryText,
                    )
                }
            }
        }
    }
}

/**
 * 分组封面：优先加载 [BookGroup.cover]（长按分组在 GroupEditDialog 中可本地导入替换），
 * 为空时用主题卡片色背景 + 文件夹图标（跟随主题，AD-07 取色归位）。
 */
@Composable
private fun GroupCover(
    group: BookGroup,
    coverBg: Color,
    secondaryText: Color,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(coverBg),
    ) {
        if (group.cover.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Filled.FolderOpen,
                contentDescription = null,
                tint = secondaryText,
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
    margin: Int,
    showBookname: Int,
    showUnread: Boolean,
    showReadProgress: Boolean,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
) {
    val m = margin.coerceAtLeast(2).dp
    LazyVerticalGrid(
        columns = GridCells.Fixed(spanCount),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(start = m, top = m, end = m, bottom = m),
        horizontalArrangement = Arrangement.spacedBy(m),
        verticalArrangement = Arrangement.spacedBy(m),
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
    val context = LocalContext.current
    val themeUiPalette = rememberThemeUiPalette()
    val palette = rememberAppSettingPalette()
    val coverBg = Color(UiCorner.surfaceColor(themeUiPalette.cardColor))
    val accent = palette.accent
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // K8 修复：对齐 archive 标准书封比例 0.75（原 0.7）
                .aspectRatio(0.75f)
                .clip(AppShapes.Chip)
                .background(coverBg),
        ) {
            if (book.getDisplayCover().isNullOrBlank()) {
                GeneratedCover(book, modifier = Modifier.fillMaxSize())
            } else {
                BookCoverImage(book, modifier = Modifier.fillMaxSize())
            }
            if (showUnread) {
                val unread = book.getUnreadChapterNum()
                if (unread > 0) {
                    val hasNew = book.latestChapterTime > book.durChapterTime
                    ShelfStatusBadge(
                        count = unread,
                        hasNew = hasNew,
                        accent = accent,
                        scrim = Color(themeUiPalette.mutedColor),
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
            if (showBookname == 2) {
                // 遮罩模式：对齐 archive——无底色，白字叠印左下；浅色封面上加轻阴影保证可读（light-theme-contrast-fix 2.11）
                Text(
                    text = book.name,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    fontFamily = FontFamily(context.titleTypeface()),
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    style = TextStyle(
                        shadow = Shadow(
                            color = Color.Black.copy(alpha = 0.6f),
                            blurRadius = 6f,
                        )
                    ),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(6.dp),
                )
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
                        color = accent,
                        trackColor = Color(themeUiPalette.cardColor),
                    )
                }
            }
        }
        if (showBookname == 1) {
            Text(
                text = book.name,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                fontFamily = FontFamily(context.titleTypeface()),
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                color = palette.primaryText,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
            if (book.author.isNotBlank()) {
                Text(
                    text = book.author,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    color = palette.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BookList(
    listState: androidx.compose.foundation.lazy.LazyListState,
    books: List<Book>,
    compact: Boolean,
    listItemStyle: Int,
    introLines: Int,
    margin: Int,
    showUnread: Boolean,
    showReadProgress: Boolean,
    showLastUpdateTime: Boolean,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(vertical = margin.coerceAtLeast(2).dp),
    ) {
        items(books, key = { it.bookUrl }) { book ->
            BookListItem(
                book = book,
                compact = compact,
                rounded = listItemStyle == 1,
                introMaxLines = introLines.coerceIn(0, 3),
                showUnread = showUnread,
                showReadProgress = showReadProgress,
                showLastUpdateTime = showLastUpdateTime,
                onClick = { onBookClick(book) },
                onLongClick = { onBookLongClick(book) },
            )
        }
    }
}

/**
 * 列表条目（对齐 archive BookshelfListItem 双样式，A3 全量）：
 * Classic = 无背景 clip 2dp；RoundedCard = palette.settings.row 填充 + 边框 + actionRadius 圆角（基线 B）。
 * 简介行受 introLines 控制（0=隐藏）；compact 模式 author/进度合并单行；状态右列（角标/更新时间）。
 */
@Composable
private fun BookListItem(
    book: Book,
    compact: Boolean,
    rounded: Boolean,
    introMaxLines: Int,
    showUnread: Boolean,
    showReadProgress: Boolean,
    showLastUpdateTime: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val context = LocalContext.current
    val themeUiPalette = rememberThemeUiPalette()
    val palette = rememberAppSettingPalette()
    val coverBg = Color(UiCorner.surfaceColor(themeUiPalette.cardColor))
    val accent = palette.accent
    val coverWidth = shelfCoverWidth(compact = compact, rounded = rounded).dp
    val rowMinHeight = shelfRowMinHeight(compact = compact, rounded = rounded).dp
    val innerHorizontal = if (rounded) {
        if (compact) 10.dp else 12.dp
    } else {
        8.dp
    }
    val innerVertical = when {
        rounded && compact -> 8.dp
        rounded -> 10.dp
        compact -> 4.dp
        else -> 5.dp
    }
    val rowModifier = if (rounded) {
        Modifier
            .fillMaxWidth()
            .heightIn(min = rowMinHeight)
            .shadow(if (AppConfig.bookCoverShadow) 2.dp else 0.dp, RoundedCornerShape(UiCorner.actionRadius(context)), clip = false)
            .clip(RoundedCornerShape(UiCorner.actionRadius(context)))
            .background(Color(palette.row))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = innerHorizontal, vertical = innerVertical)
    } else {
        Modifier
            .fillMaxWidth()
            .heightIn(min = rowMinHeight)
            .clip(RoundedCornerShape(2.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = innerVertical)
    }
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(coverWidth)
                // K8 修复：width + aspectRatio（原 size 前置致比例失效渲染正方形）
                .aspectRatio(0.75f)
                .clip(AppShapes.Chip)
                .background(coverBg),
        ) {
            if (book.getDisplayCover().isNullOrBlank()) {
                GeneratedCover(book, modifier = Modifier.fillMaxSize(), iconSize = 24)
            } else {
                BookCoverImage(book, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = book.name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 16.sp,
                    fontFamily = FontFamily(context.titleTypeface()),
                    fontWeight = FontWeight.Medium,
                    color = palette.primaryText,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            if (compact) {
                // compact：author + 当前章节 合并单行（对齐 archive " • " 拼接）
                val joined = listOf(book.author, book.durChapterTitle.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" • ")
                if (joined.isNotBlank()) {
                    Text(
                        text = joined,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        color = palette.secondaryText,
                    )
                }
            } else {
                // 普通模式：author / 当前章节 / 最新章节 三行 meta（对齐 archive，A3）
                if (book.author.isNotBlank()) {
                    ShelfMetaRow(icon = { Icons.Outlined.Person }, text = book.author, tint = palette.secondaryText)
                }
                if (!book.durChapterTitle.isNullOrBlank()) {
                    ShelfMetaRow(
                        icon = { Icons.Filled.History },
                        text = book.durChapterTitle.orEmpty(),
                        tint = palette.secondaryText
                    )
                }
                if (!book.latestChapterTitle.isNullOrBlank()) {
                    ShelfMetaRow(
                        icon = { Icons.Outlined.Schedule },
                        text = book.latestChapterTitle.orEmpty(),
                        tint = palette.secondaryText
                    )
                }
            }
            if (introMaxLines > 0 && !book.getDisplayIntro().isNullOrBlank()) {
                Text(
                    text = book.getDisplayIntro().orEmpty(),
                    maxLines = introMaxLines,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = palette.secondaryText,
                )
            }
            if (showReadProgress) {
                val progress = book.readProgress()
                if (progress != null && progress > 0f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .height(2.dp),
                        color = accent,
                        trackColor = Color(themeUiPalette.cardColor),
                    )
                }
            }
        }
        // 状态右列（对齐 archive）：角标 + 更新时间
        Column(
            horizontalAlignment = Alignment.End,
            modifier = Modifier.padding(start = 8.dp),
        ) {
            if (showUnread) {
                val unread = book.getUnreadChapterNum()
                if (unread > 0) {
                    val hasNew = book.latestChapterTime > book.durChapterTime
                    ShelfStatusBadge(
                        count = unread,
                        hasNew = hasNew,
                        accent = accent,
                        scrim = Color(themeUiPalette.mutedColor),
                    )
                }
            }
            if (showLastUpdateTime && !book.isLocal) {
                Text(
                    text = book.latestChapterTime.toTimeAgo(),
                    maxLines = 1,
                    fontSize = 11.sp,
                    color = palette.secondaryText,
                )
            }
        }
    }
}

/** meta 行：14dp 图标 + 13sp 文本（对齐 archive meta 图标行） */
@Composable
private fun ShelfMetaRow(
    icon: () -> androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    tint: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 3.dp),
    ) {
        Icon(
            imageVector = icon(),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            color = tint,
        )
    }
}

/** 未读角标（对齐 archive BookshelfStatusBadge）：新章 accent 底 / 普通 muted 底，字色按底色亮度自适应（light-theme-contrast-fix 2.10） */
@Composable
private fun ShelfStatusBadge(
    count: Int,
    hasNew: Boolean,
    accent: Color,
    scrim: Color,
    modifier: Modifier = Modifier,
) {
    val badgeBg = if (hasNew) accent else scrim
    Box(
        modifier = modifier
            .padding(5.dp)
            .background(badgeBg, CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = count.toString(),
            color = Color(LocalContext.current.onAccentFor(badgeBg.toArgb())),
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
