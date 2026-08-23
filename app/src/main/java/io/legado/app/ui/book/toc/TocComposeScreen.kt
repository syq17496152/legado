package io.legado.app.ui.book.toc

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Bookmark
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.ContentProcessor
import io.legado.app.help.book.isAudio
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.isImage
import io.legado.app.help.book.isLocalTxt
import io.legado.app.help.book.simulatedTotalChapterNum
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementIconAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.ComposeLazyListFastScroller
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class TocPage {
    Chapters,
    Bookmarks
}

@Composable
fun TocComposeScreen(
    bookUrl: String,
    contentRefreshTick: Int,
    cacheRefreshTick: Int,
    viewModel: TocViewModel,
    onBack: () -> Unit,
    onBookLoaded: (Book) -> Unit,
    onOpenChapter: (Book, List<BookChapter>, BookChapter) -> Unit,
    onEditBookmark: (Bookmark, Int) -> Unit,
    onShowTocRule: (Book?) -> Unit,
    onUpdateToc: (Book) -> Unit,
    onExportBookmark: () -> Unit,
    onExportBookmarkMd: () -> Unit,
    onShowLog: () -> Unit
) {
    LegadoComposeTheme {
        val context = LocalContext.current
        val palette = rememberAppManagementPalette()
        var selectedPage by remember { mutableStateOf(TocPage.Chapters) }
        var searchQuery by remember { mutableStateOf("") }
        var refreshTick by remember { mutableIntStateOf(0) }
        // 字数仅是显示开关(值已在解析时入库到 chapter.wordCount)，用 Compose 状态即时切换，
        // 避免像替换/翻转那样触发整页 DB 重查 + 全量标题重建。
        var countWords by remember { mutableStateOf(AppConfig.tocCountWords) }
        var bookLoaded by remember { mutableStateOf(false) }
        var chaptersLoaded by remember { mutableStateOf(false) }
        var bookmarksLoaded by remember { mutableStateOf(false) }
        var chapterList by remember { mutableStateOf<List<BookChapter>>(emptyList()) }
        var visibleChapters by remember { mutableStateOf<List<BookChapter>>(emptyList()) }
        var bookmarks by remember { mutableStateOf<List<Bookmark>>(emptyList()) }
        var titleContext by remember { mutableStateOf<TocTitleContext?>(null) }
        var chapterTitleMap by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var cacheFileNames by remember { mutableStateOf<Set<String>?>(null) }
        var chapterCacheMap by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
        var collapsedVolumeIndexes by remember { mutableStateOf<Set<Int>>(emptySet()) }
        val chapterListState = rememberLazyListState()
        val bookmarkListState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        // 卷标题吸顶后会盖住其下章节，定位当前章时上移一个标题高度，让当前章露在标题下方。
        val volumeHeaderOffsetPx = with(LocalDensity.current) { -42.dp.roundToPx() }
        val hasVolumes by remember { derivedStateOf { chapterList.any { it.isVolume } } }
        val exportText = stringResource(R.string.export)
        val exportMdText = stringResource(R.string.export_md)
        val logText = stringResource(R.string.log)
        val chapterActionLabels = TocChapterActionLabels(
            txtTocRule = stringResource(R.string.txt_toc_rule),
            splitLongChapter = stringResource(R.string.split_long_chapter),
            reverseToc = stringResource(R.string.reverse_toc),
            useReplace = stringResource(R.string.use_replace),
            loadWordCount = stringResource(R.string.load_word_count),
            log = logText
        )

        val book by produceState<Book?>(initialValue = null, bookUrl, refreshTick, contentRefreshTick) {
            bookLoaded = false
            value = withContext(Dispatchers.IO) {
                bookUrl.takeIf { it.isNotBlank() }?.let { appDb.bookDao.getBook(it) }
            }
            bookLoaded = true
        }

        LaunchedEffect(book) {
            book?.let(onBookLoaded)
        }

        LaunchedEffect(book, refreshTick, contentRefreshTick) {
            val currentBook = book
            titleContext = if (currentBook == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    TocTitleContext(
                        replaceRules = ContentProcessor.get(
                            currentBook.name,
                            currentBook.origin
                        ).getTitleReplaceRules(),
                        useReplace = AppConfig.tocUiUseReplace && currentBook.getUseReplaceRule(),
                        replaceBook = currentBook.toReplaceBook()
                    )
                }
            }
        }

        LaunchedEffect(book, searchQuery, refreshTick, contentRefreshTick) {
            val currentBook = book ?: return@LaunchedEffect
            if (searchQuery.isNotBlank()) {
                delay(180)
            }
            chaptersLoaded = false
            val end = currentBook.simulatedTotalChapterNum() - 1
            val chapters = withContext(Dispatchers.IO) {
                if (searchQuery.isBlank()) {
                    appDb.bookChapterDao.getChapterList(currentBook.bookUrl, 0, end)
                } else {
                    appDb.bookChapterDao.search(currentBook.bookUrl, searchQuery, 0, end)
                }
            }
            val (collapsedIndexes, nextVisibleChapters, nextHasVolumes) = withContext(Dispatchers.Default) {
                val nextCollapsedIndexes = if (searchQuery.isBlank()) {
                    collapsedVolumeIndexesFor(
                        chapters = chapters,
                        durChapterIndex = currentBook.durChapterIndex
                    )
                } else {
                    collapsedVolumeIndexes
                }
                Triple(
                    nextCollapsedIndexes,
                    visibleChapters(chapters, searchQuery, nextCollapsedIndexes),
                    chapters.any { it.isVolume }
                )
            }
            if (searchQuery.isBlank()) {
                chapterList = chapters
                collapsedVolumeIndexes = collapsedIndexes
            }
            visibleChapters = nextVisibleChapters
            chaptersLoaded = true
            if (
                selectedPage == TocPage.Chapters &&
                nextVisibleChapters.isNotEmpty() &&
                !chapterListState.isScrollInProgress
            ) {
                if (searchQuery.isBlank()) {
                    val pos = visiblePositionOf(nextVisibleChapters, currentBook.durChapterIndex)
                    chapterListState.scrollToItem(
                        pos.coerceIn(0, nextVisibleChapters.lastIndex),
                        if (nextHasVolumes) volumeHeaderOffsetPx else 0
                    )
                } else {
                    chapterListState.scrollToItem(0)
                }
            }
        }

        LaunchedEffect(book, cacheRefreshTick) {
            val currentBook = book ?: return@LaunchedEffect
            cacheFileNames = null
            cacheFileNames = withContext(Dispatchers.IO) { BookHelp.getChapterFiles(currentBook) }
        }

        LaunchedEffect(book, visibleChapters, cacheFileNames, cacheRefreshTick) {
            val currentBook = book ?: return@LaunchedEffect
            val visibleSnapshot = visibleChapters
            val cacheSnapshot = cacheFileNames ?: return@LaunchedEffect
            if (visibleSnapshot.isEmpty()) {
                chapterCacheMap = emptyMap()
                return@LaunchedEffect
            }
            chapterCacheMap = withContext(Dispatchers.IO) {
                visibleSnapshot.associate { chapter ->
                    chapter.primaryStr() to isChapterCached(currentBook, chapter, cacheSnapshot)
                }
            }
        }

        LaunchedEffect(visibleChapters, titleContext) {
            val visibleSnapshot = visibleChapters
            val titleSnapshot = titleContext
            if (visibleSnapshot.isEmpty() || titleSnapshot == null) {
                chapterTitleMap = emptyMap()
                return@LaunchedEffect
            }
            chapterTitleMap = withContext(Dispatchers.Default) {
                visibleSnapshot.associate { chapter ->
                    chapter.primaryStr() to chapter.getDisplayTitle(
                        replaceRules = titleSnapshot.replaceRules,
                        useReplace = titleSnapshot.useReplace,
                        replaceBook = titleSnapshot.replaceBook
                    )
                }
            }
        }

        LaunchedEffect(book, searchQuery, selectedPage, refreshTick, contentRefreshTick) {
            val currentBook = book ?: return@LaunchedEffect
            if (selectedPage != TocPage.Bookmarks) return@LaunchedEffect
            if (searchQuery.isNotBlank()) {
                delay(180)
            }
            bookmarksLoaded = false
            val result = withContext(Dispatchers.IO) {
                if (searchQuery.isBlank()) {
                    appDb.bookmarkDao.getByBook(currentBook.name, currentBook.author)
                } else {
                    appDb.bookmarkDao.search(currentBook.name, currentBook.author, searchQuery)
                }
            }
            bookmarks = result
            bookmarksLoaded = true
            if (bookmarks.isNotEmpty()) {
                val pos = if (searchQuery.isBlank()) {
                    bookmarks.indexOfLast { it.chapterIndex < currentBook.durChapterIndex }
                        .coerceAtLeast(0)
                } else {
                    0
                }
                bookmarkListState.scrollToItem(pos.coerceIn(0, bookmarks.lastIndex))
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Transparent)
        ) {
            TocTopBar(
                selectedPage = selectedPage,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onBack = onBack,
                onSelectPage = { selectedPage = it },
                chapterActions = {
                    buildChapterActions(
                        book = book,
                        labels = chapterActionLabels,
                        onShowTocRule = { onShowTocRule(book) },
                        onReverseToc = {
                            viewModel.reverseToc {
                                refreshTick++
                                (context as? android.app.Activity)?.setResult(
                                    android.app.Activity.RESULT_OK,
                                    android.content.Intent()
                                        .putExtra("index", it.durChapterIndex)
                                        .putExtra("chapterPos", 0)
                                )
                                scope.launch {
                                    chapterListState.scrollToItem(
                                        visiblePositionOf(visibleChapters, it.durChapterIndex),
                                        if (hasVolumes) volumeHeaderOffsetPx else 0
                                    )
                                }
                            }
                        },
                        onToggleUseReplace = { refreshTick++ },
                        onToggleWordCount = { countWords = AppConfig.tocCountWords },
                        onToggleSplitLongChapter = { targetBook ->
                            targetBook.setSplitLongChapter(!targetBook.getSplitLongChapter())
                            onUpdateToc(targetBook)
                            refreshTick++
                        },
                        onShowLog = onShowLog
                    )
                },
                bookmarkActions = {
                    listOf(
                        AppManagementMenuAction(text = exportText, onClick = onExportBookmark),
                        AppManagementMenuAction(text = exportMdText, onClick = onExportBookmarkMd),
                        AppManagementMenuAction(text = logText, onClick = onShowLog)
                    )
                }
            )

            Box(modifier = Modifier.weight(1f)) {
                when (selectedPage) {
                    TocPage.Chapters -> {
                        when {
                            visibleChapters.isNotEmpty() -> {
                                TocChapterList(
                                    book = book,
                                    chapters = visibleChapters,
                                    chapterTitleMap = chapterTitleMap,
                                    collapsedVolumeIndexes = collapsedVolumeIndexes,
                                    chapterCacheMap = chapterCacheMap,
                                    countWords = countWords,
                                    hasVolumes = hasVolumes,
                                    listState = chapterListState,
                                    onToggleVolume = { chapter ->
                                        if (!chapter.isVolume || searchQuery.isNotBlank()) return@TocChapterList
                                        val nextCollapsedVolumeIndexes = if (chapter.index in collapsedVolumeIndexes) {
                                            collapsedVolumeIndexes - chapter.index
                                        } else {
                                            collapsedVolumeIndexes + chapter.index
                                        }
                                        collapsedVolumeIndexes = nextCollapsedVolumeIndexes
                                        scope.launch {
                                            visibleChapters = withContext(Dispatchers.Default) {
                                                visibleChapters(
                                                    chapterList,
                                                    searchQuery,
                                                    nextCollapsedVolumeIndexes
                                                )
                                            }
                                        }
                                    },
                                    onOpenChapter = { chapter ->
                                        book?.let { onOpenChapter(it, chapterList, chapter) }
                                    }
                                )
                            }
                            bookLoaded && book == null -> TocEmptyState(text = stringResource(R.string.empty))
                            chaptersLoaded -> TocEmptyState(text = stringResource(R.string.empty))
                            else -> Box(modifier = Modifier.fillMaxSize())
                        }
                    }

                    TocPage.Bookmarks -> {
                        when {
                            bookmarks.isNotEmpty() -> {
                                TocBookmarkList(
                                    bookmarks = bookmarks,
                                    listState = bookmarkListState,
                                    onClick = { bookmark ->
                                        (context as? android.app.Activity)?.run {
                                            setResult(
                                                android.app.Activity.RESULT_OK,
                                                android.content.Intent()
                                                    .putExtra("index", bookmark.chapterIndex)
                                                    .putExtra("chapterPos", bookmark.chapterPos)
                                            )
                                            finish()
                                        }
                                    },
                                    onLongClick = onEditBookmark
                                )
                            }
                            bookLoaded && book == null -> TocEmptyState(text = stringResource(R.string.empty))
                            bookmarksLoaded -> TocEmptyState(text = stringResource(R.string.empty))
                            else -> Box(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }

            if (selectedPage == TocPage.Chapters) {
                TocBottomBar(
                    book = book,
                    onCurrentClick = {
                        book?.let {
                            scope.launch {
                                chapterListState.scrollToItem(
                                    visiblePositionOf(visibleChapters, it.durChapterIndex),
                                    if (hasVolumes) volumeHeaderOffsetPx else 0
                                )
                            }
                        }
                    },
                    onTopClick = {
                        scope.launch { chapterListState.scrollToItem(0) }
                    },
                    onBottomClick = {
                        scope.launch {
                            if (visibleChapters.isNotEmpty()) {
                                chapterListState.scrollToItem(visibleChapters.lastIndex)
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TocEmptyState(text: String) {
    val palette = rememberAppManagementPalette()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        AppManagementCard(
            palette = palette,
            insidePadding = PaddingValues(horizontal = 18.dp, vertical = 18.dp),
            drawPanelImage = true
        ) {
            Text(
                text = text,
                color = palette.settings.secondaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = palette.settings.bodyFontFamily,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TocTopBar(
    selectedPage: TocPage,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onSelectPage: (TocPage) -> Unit,
    chapterActions: @Composable () -> List<AppManagementMenuAction>,
    bookmarkActions: @Composable () -> List<AppManagementMenuAction>
) {
    val palette = rememberAppManagementPalette()
    val moreActions = if (selectedPage == TocPage.Bookmarks) {
        bookmarkActions()
    } else {
        chapterActions()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppManagementIconAction(
                iconRes = R.drawable.ic_arrow_back,
                contentDescription = null,
                tint = palette.settings.primaryText,
                onClick = onBack
            )
            Text(
                text = stringResource(
                    if (selectedPage == TocPage.Bookmarks) R.string.bookmark else R.string.chapter_list
                ),
                color = palette.settings.primaryText,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.settings.titleFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            AppManagementMoreActionButton(
                actionsProvider = { moreActions },
                palette = palette,
                contentDescription = stringResource(R.string.more_menu)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        TocSearchField(
            query = searchQuery,
            onQueryChange = onSearchQueryChange
        )
        Spacer(modifier = Modifier.height(8.dp))
        TocTabs(
            selectedPage = selectedPage,
            onSelectPage = onSelectPage,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun TocSearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    val palette = rememberAppManagementPalette()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(palette.settings.row))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.ic_search),
                contentDescription = null,
                tint = palette.settings.secondaryText,
                modifier = Modifier.size(18.dp)
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(
                    color = palette.settings.primaryText,
                    fontSize = 14.sp,
                    fontFamily = palette.settings.bodyFontFamily
                ),
                cursorBrush = SolidColor(palette.settings.accent),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
                decorationBox = { inner ->
                    if (query.isBlank()) {
                        Text(
                            text = stringResource(R.string.search),
                            color = palette.settings.secondaryText,
                            fontSize = 14.sp,
                            fontFamily = palette.settings.bodyFontFamily
                        )
                    }
                    inner()
                }
            )
        }
    }
}

@Composable
private fun TocTabs(
    selectedPage: TocPage,
    onSelectPage: (TocPage) -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(palette.settings.row))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        TocTabButton(
            text = stringResource(R.string.chapter_list),
            selected = selectedPage == TocPage.Chapters,
            onClick = { onSelectPage(TocPage.Chapters) },
            modifier = Modifier.weight(1f)
        )
        TocTabButton(
            text = stringResource(R.string.bookmark),
            selected = selectedPage == TocPage.Bookmarks,
            onClick = { onSelectPage(TocPage.Bookmarks) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TocTabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val palette = rememberAppManagementPalette()
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) palette.settings.accent.copy(alpha = 0.16f) else Color.Transparent
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) palette.settings.accent else palette.settings.primaryText,
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            fontFamily = palette.settings.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TocChapterList(
    book: Book?,
    chapters: List<BookChapter>,
    chapterTitleMap: Map<String, String>,
    collapsedVolumeIndexes: Set<Int>,
    chapterCacheMap: Map<String, Boolean>,
    countWords: Boolean,
    hasVolumes: Boolean,
    listState: LazyListState,
    onToggleVolume: (BookChapter) -> Unit,
    onOpenChapter: (BookChapter) -> Unit
) {
    val palette = rememberAppManagementPalette()
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp)
        ) {
            // 卷仍作为 lazy item 发射，保持索引与 visibleChapters 一一对应，滚动定位逻辑无需改动。
            chapters.forEach { chapter ->
                val key = "${chapter.bookUrl}|${chapter.index}|${chapter.url}"
                if (chapter.isVolume) {
                    stickyHeader(key = key, contentType = "volume") {
                        TocVolumeHeaderRow(
                            palette = palette,
                            title = chapterTitleMap[chapter.primaryStr()] ?: chapter.title,
                            collapsed = collapsedVolumeIndexes.contains(chapter.index),
                            onClick = { onToggleVolume(chapter) }
                        )
                    }
                } else {
                    item(key = key, contentType = "chapter") {
                        TocChapterRow(
                            palette = palette,
                            book = book,
                            chapter = chapter,
                            title = chapterTitleMap[chapter.primaryStr()] ?: chapter.title,
                            cached = chapterCacheMap[chapter.primaryStr()] ?: true,
                            countWords = countWords,
                            indented = hasVolumes,
                            onClick = { onOpenChapter(chapter) }
                        )
                    }
                }
            }
        }
        ComposeLazyListFastScroller(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun TocVolumeHeaderRow(
    palette: AppManagementPalette,
    title: String,
    collapsed: Boolean,
    onClick: () -> Unit
) {
    val occludingBackground = remember(palette.settings.page, palette.settings.row) {
        if (palette.settings.page.alpha < 0.98f) {
            Color(palette.settings.row)
        } else {
            palette.settings.page.copy(alpha = 1f)
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 吸顶标题必须不透明，否则下方章节会从标题后透出；先铺不透明 page 底再叠强调色薄染。
            .zIndex(1f)
            .background(occludingBackground)
            .background(palette.settings.accent.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .padding(end = 10.dp)
                .size(width = 3.dp, height = 16.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(palette.settings.accent)
        )
        Text(
            text = title,
            color = palette.settings.primaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = palette.settings.titleFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            painter = painterResource(
                id = if (collapsed) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            ),
            contentDescription = null,
            tint = palette.settings.secondaryText,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
private fun TocChapterRow(
    palette: AppManagementPalette,
    book: Book?,
    chapter: BookChapter,
    title: String,
    cached: Boolean,
    countWords: Boolean,
    indented: Boolean,
    onClick: () -> Unit
) {
    val isCurrent = book?.durChapterIndex == chapter.index
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowBackground = when {
        isCurrent -> palette.settings.accent.copy(alpha = 0.14f)
        pressed -> Color(palette.settings.rowPressed)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            // 当前章/按压态整行通栏高亮（去掉内缩圆角药丸，看着更清爽）。
            .background(rowBackground)
            .padding(
                start = if (indented) 22.dp else 14.dp,
                end = 12.dp,
                top = 11.dp,
                bottom = 11.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (chapter.isVip && !chapter.isPay) {
            Icon(
                painter = painterResource(id = R.drawable.ic_lock_outline),
                contentDescription = null,
                tint = palette.settings.secondaryText,
                modifier = Modifier
                    .size(18.dp)
                    .padding(end = 4.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (isCurrent) palette.settings.accent else palette.settings.primaryText,
                fontSize = 14.sp,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = palette.settings.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val meta = listOfNotNull(
                chapter.tag?.takeIf { it.isNotBlank() },
                chapter.wordCount?.takeIf { countWords && it.isNotBlank() }
            ).joinToString("  ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp,
                    fontFamily = palette.settings.bodyFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
        val iconRes = when {
            isCurrent -> R.drawable.ic_check
            !cached -> R.drawable.ic_outline_cloud_24
            else -> null
        }
        iconRes?.let {
            Icon(
                painter = painterResource(id = it),
                contentDescription = null,
                tint = if (isCurrent) palette.settings.accent else palette.settings.secondaryText,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun TocBookmarkList(
    bookmarks: List<Bookmark>,
    listState: LazyListState,
    onClick: (Bookmark) -> Unit,
    onLongClick: (Bookmark, Int) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            itemsIndexed(
                items = bookmarks,
                key = { _, bookmark -> bookmark.time }
            ) { index, bookmark ->
                TocBookmarkRow(
                    bookmark = bookmark,
                    onClick = { onClick(bookmark) },
                    onLongClick = { onLongClick(bookmark, index) }
                )
            }
        }
        ComposeLazyListFastScroller(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd)
        )
    }
}

@Composable
private fun TocBookmarkRow(
    bookmark: Bookmark,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    AppManagementCard(
        palette = palette,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        drawPanelImage = true,
        onClick = onClick,
        onLongClick = onLongClick
    ) {
        Text(
            text = bookmark.chapterName,
            color = palette.settings.primaryText,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.settings.bodyFontFamily,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (bookmark.bookText.isNotBlank()) {
            Text(
                text = bookmark.bookText,
                color = palette.settings.primaryText,
                fontSize = 13.sp,
                fontFamily = palette.settings.bodyFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (bookmark.content.isNotBlank()) {
            Text(
                text = bookmark.content,
                color = palette.settings.secondaryText,
                fontSize = 12.sp,
                fontFamily = palette.settings.bodyFontFamily,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TocBottomBar(
    book: Book?,
    onCurrentClick: () -> Unit,
    onTopClick: () -> Unit,
    onBottomClick: () -> Unit
) {
    val palette = rememberAppManagementPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppManagementCard(
            palette = palette,
            modifier = Modifier.weight(1f),
            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            drawPanelImage = true,
            onClick = onCurrentClick
        ) {
            Text(
                text = book?.let { "${it.durChapterTitle}(${it.durChapterIndex + 1}/${it.simulatedTotalChapterNum()})" }
                    ?: stringResource(R.string.chapter_list),
                color = palette.settings.primaryText,
                fontSize = 12.sp,
                fontFamily = palette.settings.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis
            )
        }
        TocBottomIconButton(
            iconRes = R.drawable.ic_arrow_drop_up,
            contentDescription = stringResource(R.string.go_to_top),
            onClick = onTopClick
        )
        TocBottomIconButton(
            iconRes = R.drawable.ic_arrow_drop_down,
            contentDescription = stringResource(R.string.go_to_bottom),
            onClick = onBottomClick
        )
    }
}

@Composable
private fun TocBottomIconButton(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val palette = rememberAppManagementPalette()
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val panelRadiusPx = palette.settings.panelRadiusPx
    val panelImage = remember(context, panelRadiusPx, palette.settings.themeSignature) {
        UiCorner.panelImageDrawable(context, panelRadiusPx)
    }
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .defaultMinSize(minWidth = 42.dp, minHeight = 42.dp)
            .clip(RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp))
            .appSettingPanelBackground(
                normalColor = if (pressed) palette.settings.rowPressed else palette.settings.row,
                panelImage = panelImage,
                borderColor = palette.settings.border,
                radiusPx = panelRadiusPx
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(9.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = palette.settings.primaryText,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
private fun buildChapterActions(
    book: Book?,
    labels: TocChapterActionLabels,
    onShowTocRule: () -> Unit,
    onReverseToc: () -> Unit,
    onToggleUseReplace: () -> Unit,
    onToggleWordCount: () -> Unit,
    onToggleSplitLongChapter: (Book) -> Unit,
    onShowLog: () -> Unit
): List<AppManagementMenuAction> {
    val actions = arrayListOf<AppManagementMenuAction>()
    if (book?.isLocalTxt == true) {
        actions.add(AppManagementMenuAction(text = labels.txtTocRule, onClick = onShowTocRule))
        actions.add(
            AppManagementMenuAction(
                text = labels.splitLongChapter,
                checked = book.getSplitLongChapter(),
                onClick = { onToggleSplitLongChapter(book) }
            )
        )
    }
    actions.add(AppManagementMenuAction(text = labels.reverseToc, onClick = onReverseToc))
    actions.add(
        AppManagementMenuAction(
            text = labels.useReplace,
            checked = AppConfig.tocUiUseReplace,
            onClick = {
                AppConfig.tocUiUseReplace = !AppConfig.tocUiUseReplace
                onToggleUseReplace()
            }
        )
    )
    actions.add(
        AppManagementMenuAction(
            text = labels.loadWordCount,
            checked = AppConfig.tocCountWords,
            onClick = {
                AppConfig.tocCountWords = !AppConfig.tocCountWords
                onToggleWordCount()
            }
        )
    )
    actions.add(AppManagementMenuAction(text = labels.log, onClick = onShowLog))
    return actions
}

private data class TocChapterActionLabels(
    val txtTocRule: String,
    val splitLongChapter: String,
    val reverseToc: String,
    val useReplace: String,
    val loadWordCount: String,
    val log: String
)

private data class TocTitleContext(
    val replaceRules: List<io.legado.app.data.entities.ReplaceRule>,
    val useReplace: Boolean,
    val replaceBook: io.legado.app.data.entities.ReplaceBook?
)

private fun collapsedVolumeIndexesFor(
    chapters: List<BookChapter>,
    durChapterIndex: Int
): Set<Int> {
    var currentVolumeIndex: Int? = null
    val volumeIndexes = linkedSetOf<Int>()
    chapters.forEach { chapter ->
        if (!chapter.isVolume) return@forEach
        volumeIndexes.add(chapter.index)
        if (chapter.index <= durChapterIndex && chapter.index > (currentVolumeIndex ?: Int.MIN_VALUE)) {
            currentVolumeIndex = chapter.index
        }
    }
    currentVolumeIndex?.let(volumeIndexes::remove)
    return volumeIndexes
}

private fun visibleChapters(
    chapters: List<BookChapter>,
    searchKey: String,
    collapsedVolumeIndexes: Set<Int>
): List<BookChapter> {
    if (searchKey.isNotBlank() || collapsedVolumeIndexes.isEmpty()) return chapters
    val visible = arrayListOf<BookChapter>()
    var hideUntilNextVolume = false
    chapters.forEach { chapter ->
        if (chapter.isVolume) {
            visible.add(chapter)
            hideUntilNextVolume = collapsedVolumeIndexes.contains(chapter.index)
        } else if (!hideUntilNextVolume) {
            visible.add(chapter)
        }
    }
    return visible
}

private fun visiblePositionOf(chapters: List<BookChapter>, chapterIndex: Int): Int {
    val exact = chapters.indexOfFirst { it.index == chapterIndex }
    if (exact >= 0) return exact
    return chapters.indexOfLast { it.index < chapterIndex }.coerceAtLeast(0)
}

private fun isChapterCached(
    book: Book?,
    chapter: BookChapter,
    cacheFileNames: Set<String>
): Boolean {
    if (book == null) return false
    return book.isLocal ||
            chapter.isVolume ||
            if (book.isAudio || book.isImage) {
                // 音频/图片场景：按已加载的缓存文件名集合判断（当前项目自有音频缓存实现，无 Archive 的 isMediaCached）
                cacheFileNames.contains(chapter.getFileName())
            } else {
                BookHelp.hasContent(book, chapter)
            }
}
