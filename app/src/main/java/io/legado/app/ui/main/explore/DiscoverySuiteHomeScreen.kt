package io.legado.app.ui.main.explore

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import android.content.Context
import io.legado.app.R
import io.legado.app.data.entities.SearchBook
import io.legado.app.help.config.AppConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementMoreActionButton
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.SearchBookPreviewOverlay
import io.legado.app.ui.widget.compose.SearchBookPreviewState
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.image.CoverImageView
import kotlin.math.roundToInt

private const val BOOK_COVER_ASPECT_RATIO = 0.75f
private val DiscoverySuiteEmphasizedEasing = CubicBezierEasing(0.20f, 0.00f, 0.00f, 1.00f)

@Composable
fun DiscoverySuiteHomeScreen(
    selectedSuite: DiscoverySuite?,
    suites: List<DiscoverySuite>,
    selectedSuiteId: String,
    widgetBooks: Map<String, List<SearchBook>>,
    rankedWidgetBooks: Map<String, Map<String, List<SearchBook>>>,
    loadingWidgetIds: Set<String>,
    scrollToTopSignal: Int,
    onSearchClick: () -> Unit,
    onSuiteClick: () -> Unit,
    onSuiteSelect: (DiscoverySuite) -> Unit,
    onBookClick: (SearchBook) -> Unit,
    onBookPreviewOpen: (SearchBook) -> Unit,
    onTagClick: (DiscoverySuiteWidgetTarget) -> Unit,
    onRefreshWidget: (DiscoverySuiteWidget) -> Unit,
    onHorizontalLoadMore: (DiscoverySuiteWidget) -> Unit,
    onRankedLoadMore: (DiscoverySuiteWidget, DiscoverySuiteWidgetTarget) -> Unit,
    onCanScrollBackwardChanged: (Boolean) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val baseRenderConfig = rememberBookshelfListRenderConfig()
    val opacityMultiplier = selectedSuite?.opacityMultiplier ?: 1f
    val renderConfig = remember(context, baseRenderConfig, opacityMultiplier) {
        baseRenderConfig.withSuiteOpacityMultiplier(context, opacityMultiplier)
    }
    val bottomBarPadding = with(LocalDensity.current) {
        context.resources.getDimensionPixelSize(R.dimen.main_content_bottom_bar_padding).toDp()
    }
    val palette = renderConfig.palette
    val canScrollBackward by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    LaunchedEffect(canScrollBackward) {
        onCanScrollBackwardChanged(canScrollBackward)
    }
    LaunchedEffect(scrollToTopSignal) {
        if (scrollToTopSignal > 0) {
            if (AppConfig.isEInkMode) {
                listState.scrollToItem(0)
            } else {
                listState.animateScrollToItem(0)
            }
        }
    }
    var previewState by remember { mutableStateOf<SearchBookPreviewState?>(null) }
    val onBookPreview: (SearchBook, Rect?) -> Unit = { book, bounds ->
        previewState = SearchBookPreviewState(book, bounds)
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            DiscoverySuiteSearchBar(
                selectedSuiteId = selectedSuiteId,
                suites = suites,
                renderConfig = renderConfig,
                onSearchClick = onSearchClick,
                onSuiteClick = onSuiteClick,
                onSuiteSelect = onSuiteSelect
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = bottomBarPadding + 12.dp),
                content = {
                    if (selectedSuite == null) {
                        item(key = "suite_empty") {
                            DiscoverySuiteEmptyState(
                                title = context.getString(R.string.discovery_suite_empty_title),
                                summary = context.getString(R.string.discovery_suite_empty_summary),
                                action = context.getString(R.string.discovery_suite_manage),
                                renderConfig = renderConfig,
                                onActionClick = onSuiteClick
                            )
                        }
                    } else if (selectedSuite.widgets.isEmpty()) {
                        item(key = "suite_no_widgets") {
                            DiscoverySuiteEmptyState(
                                title = context.getString(R.string.discovery_suite_no_widgets_title),
                                summary = context.getString(R.string.discovery_suite_no_widgets_summary),
                                action = context.getString(R.string.discovery_suite_manage),
                                renderConfig = renderConfig,
                                onActionClick = onSuiteClick
                            )
                        }
                    } else {
                        selectedSuite.widgets.forEach { widget ->
                            item(key = widget.id) {
                                DiscoverySuiteAnimatedWidgetContainer(widgetKey = widget.id) {
                                    DiscoverySuiteWidgetSection(
                                        widget = widget,
                                        books = widgetBooks[widget.id].orEmpty(),
                                        rankedBooks = rankedWidgetBooks[widget.id].orEmpty(),
                                        isLoading = widget.id in loadingWidgetIds,
                                        renderConfig = renderConfig,
                                        onBookClick = onBookClick,
                                        onBookPreview = onBookPreview,
                                        onTagClick = onTagClick,
                                        onRefreshWidget = onRefreshWidget,
                                        onHorizontalLoadMore = onHorizontalLoadMore,
                                        onRankedLoadMore = onRankedLoadMore,
                                        fragment = fragment,
                                        lifecycle = lifecycle
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
        SearchBookPreviewOverlay(
            state = previewState,
            renderConfig = baseRenderConfig,
            fragment = fragment,
            lifecycle = lifecycle,
            onDismissed = { previewState = null },
            onOpen = { book ->
                previewState = null
                onBookPreviewOpen(book)
            }
        )
    }
}

@Composable
private fun DiscoverySuiteAnimatedWidgetContainer(
    widgetKey: String,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    val progress = remember(widgetKey) { Animatable(if (AppConfig.isEInkMode) 1f else 0f) }
    LaunchedEffect(widgetKey) {
        if (AppConfig.isEInkMode) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 260, easing = DiscoverySuiteEmphasizedEasing)
            )
        }
    }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = 0.88f + 0.12f * progress.value
            translationY = with(density) { 10.dp.toPx() } * (1f - progress.value)
        }
    ) {
        content()
    }
}

@Composable
private fun DiscoverySuiteSearchBar(
    selectedSuiteId: String,
    suites: List<DiscoverySuite>,
    renderConfig: BookshelfListRenderConfig,
    onSearchClick: () -> Unit,
    onSuiteClick: () -> Unit,
    onSuiteSelect: (DiscoverySuite) -> Unit
) {
    val context = LocalContext.current
    val palette = renderConfig.palette
    val managementPalette = rememberAppManagementPalette()
    val radiusPx = with(LocalDensity.current) { 22.dp.toPx() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .height(44.dp)
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = radiusPx
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable(onClick = onSearchClick)
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SearchGlyph(color = palette.secondaryText)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = context.getString(R.string.search),
                color = palette.secondaryText,
                fontSize = 16.sp,
                fontFamily = palette.bodyFontFamily,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(28.dp)
                .background(
                    palette.borderColor?.let { Color(it) }?.copy(alpha = 0.50f)
                        ?: palette.secondaryText.copy(alpha = 0.18f)
                )
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(50.dp),
            contentAlignment = Alignment.Center
        ) {
            AppManagementMoreActionButton(
                actionsProvider = {
                    buildList {
                        add(
                            AppManagementMenuAction(
                                text = context.getString(R.string.discovery_suite_manage),
                                onClick = onSuiteClick
                            )
                        )
                        suites.forEach { suite ->
                            add(
                                AppManagementMenuAction(
                                    text = if (suite.id == selectedSuiteId) {
                                        "当前 ${suite.displayName}"
                                    } else {
                                        suite.displayName
                                    },
                                    onClick = { onSuiteSelect(suite) }
                                )
                            )
                        }
                    }
                },
                palette = managementPalette,
                contentDescription = context.getString(R.string.more_menu),
                tint = palette.primaryText,
                modifier = Modifier
                    .fillMaxSize()
            )
        }
    }
}

@Composable
private fun SearchGlyph(color: Color) {
    androidx.compose.foundation.Canvas(modifier = Modifier.size(21.dp)) {
        val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.4.dp.toPx())
        drawCircle(
            color = color,
            radius = 6.2.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(9.dp.toPx(), 9.dp.toPx()),
            style = stroke
        )
        drawLine(
            color = color,
            start = androidx.compose.ui.geometry.Offset(14.2.dp.toPx(), 14.2.dp.toPx()),
            end = androidx.compose.ui.geometry.Offset(19.dp.toPx(), 19.dp.toPx()),
            strokeWidth = 2.4.dp.toPx()
        )
    }
}

@Composable
private fun DiscoverySuiteEmptyState(
    title: String,
    summary: String,
    action: String,
    renderConfig: BookshelfListRenderConfig,
    onActionClick: () -> Unit
) {
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 96.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.titleFontFamily,
            color = palette.primaryText
        )
        Text(
            text = summary,
            fontSize = 14.sp,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText
        )
        Box(
            modifier = Modifier
                .height(42.dp)
                .clip(RoundedCornerShape(palette.actionRadius))
                .appSettingPanelBackground(
                    normalColor = palette.rowColor,
                    panelImage = renderConfig.panelImage,
                    borderColor = palette.borderColor,
                    radiusPx = with(LocalDensity.current) { palette.actionRadius.toPx() }
                )
                .clickable(onClick = onActionClick)
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = action,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.accent
            )
        }
    }
}

@Composable
private fun DiscoverySuiteWidgetSection(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    rankedBooks: Map<String, List<SearchBook>>,
    isLoading: Boolean,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    onTagClick: (DiscoverySuiteWidgetTarget) -> Unit,
    onRefreshWidget: (DiscoverySuiteWidget) -> Unit,
    onHorizontalLoadMore: (DiscoverySuiteWidget) -> Unit,
    onRankedLoadMore: (DiscoverySuiteWidget, DiscoverySuiteWidgetTarget) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val context = LocalContext.current
    val palette = renderConfig.palette
    val showTitle = widget.type == DiscoverySuiteWidgetType.RandomBooks.value
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)
    ) {
        if (showTitle) {
            Text(
                text = widget.displayTitle(context),
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.titleFontFamily,
                color = palette.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        when {
            widget.type == DiscoverySuiteWidgetType.TagBar.value -> {
                if (widget.targets.isEmpty()) {
                    Text(
                        text = LocalContext.current.getString(R.string.discovery_suite_widget_pending),
                        fontSize = 14.sp,
                        fontFamily = palette.bodyFontFamily,
                        color = palette.secondaryText
                    )
                } else {
                    DiscoverySuiteTagBarWidget(
                        widget = widget,
                        renderConfig = renderConfig,
                        onTagClick = onTagClick
                    )
                }
            }
            widget.type == DiscoverySuiteWidgetType.RankButtons.value -> {
                if (widget.targets.isEmpty()) {
                    Text(
                        text = LocalContext.current.getString(R.string.discovery_suite_widget_pending),
                        fontSize = 14.sp,
                        fontFamily = palette.bodyFontFamily,
                        color = palette.secondaryText
                    )
                } else {
                    DiscoverySuiteRankButtonsWidget(
                        widget = widget,
                        renderConfig = renderConfig,
                        onTagClick = onTagClick
                    )
                }
            }
            widget.type == DiscoverySuiteWidgetType.RankedList.value -> {
                if (widget.targets.isEmpty()) {
                    Text(
                        text = LocalContext.current.getString(R.string.discovery_suite_widget_pending),
                        fontSize = 14.sp,
                        fontFamily = palette.bodyFontFamily,
                        color = palette.secondaryText
                    )
                } else if (isLoading && rankedBooks.isEmpty()) {
                    Text(
                        text = LocalContext.current.getString(R.string.discovery_suite_widget_loading),
                        fontSize = 14.sp,
                        fontFamily = palette.bodyFontFamily,
                        color = palette.secondaryText
                    )
                } else {
                    DiscoverySuiteRankedListWidget(
                        widget = widget,
                        rankedBooks = rankedBooks,
                        renderConfig = renderConfig,
                        onBookClick = onBookClick,
                        onBookPreview = onBookPreview,
                        onLoadMore = { target -> onRankedLoadMore(widget, target) },
                        fragment = fragment,
                        lifecycle = lifecycle
                    )
                }
            }
            isLoading && books.isEmpty() -> Text(
                text = LocalContext.current.getString(R.string.discovery_suite_widget_loading),
                fontSize = 14.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
            books.isEmpty() -> Text(
                text = LocalContext.current.getString(R.string.discovery_suite_widget_pending),
                fontSize = 14.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
            widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value -> DiscoverySuiteHorizontalBooksWidget(
                widget = widget,
                books = books,
                renderConfig = renderConfig,
                onBookClick = onBookClick,
                onBookPreview = onBookPreview,
                onLoadMore = { onHorizontalLoadMore(widget) },
                fragment = fragment,
                lifecycle = lifecycle
            )
            widget.type == DiscoverySuiteWidgetType.WaterfallBooks.value -> DiscoverySuiteWaterfallBooksWidget(
                widget = widget,
                books = books,
                renderConfig = renderConfig,
                onBookClick = onBookClick,
                onBookPreview = onBookPreview,
                fragment = fragment,
                lifecycle = lifecycle
            )
            else -> DiscoverySuiteRandomBooksWidget(
                widget = widget,
                books = books,
                renderConfig = renderConfig,
                onBookClick = onBookClick,
                onBookPreview = onBookPreview,
                onRefreshClick = { onRefreshWidget(widget) },
                fragment = fragment,
                lifecycle = lifecycle
            )
        }
    }
}

@Composable
private fun DiscoverySuiteWaterfallBooksWidget(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val displayBooks = remember(widget.id, books) {
        books.take(WATERFALL_WIDGET_DISPLAY_COUNT)
    }
    val columns = remember(displayBooks) {
        displayBooks.withIndex().partition { it.index % 2 == 0 }.let { (left, right) ->
            listOf(left.map { it.value }, right.map { it.value })
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        columns.forEach { columnBooks ->
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                columnBooks.forEach { book ->
                    DiscoverySuiteWaterfallBookCard(
                        book = book,
                        renderConfig = renderConfig,
                        onBookClick = onBookClick,
                        onBookPreview = onBookPreview,
                        fragment = fragment,
                        lifecycle = lifecycle
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySuiteWaterfallBookCard(
    book: SearchBook,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val palette = renderConfig.palette
    var coverBounds by remember(book.displayKey()) { mutableStateOf<Rect?>(null) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.actionRadius))
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookPreview(book, coverBounds) }
            )
            .padding(bottom = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(BOOK_COVER_ASPECT_RATIO)
        ) {
            BookCoverImage(
                book = book,
                modifier = Modifier.fillMaxSize(),
                style = CoverImageView.CoverStyle.GRID,
                loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
                fragment = fragment,
                lifecycle = lifecycle,
                preferThumb = true,
                fillBounds = true,
                onBoundsChanged = { coverBounds = it }
            )
        }
        Text(
            text = book.name,
            modifier = Modifier.padding(top = 8.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 14.sp,
            lineHeight = 18.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.titleFontFamily,
            color = palette.primaryText
        )
        val meta = listOf(book.author, book.originName)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (meta.isNotBlank()) {
            Text(
                text = meta,
                modifier = Modifier.padding(top = 3.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
        }
    }
}

@Composable
private fun DiscoverySuiteHorizontalBooksWidget(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    onLoadMore: () -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val rowState = rememberLazyListState()
    val displayBooks = remember(widget.id, books) {
        books.take(HORIZONTAL_WIDGET_MAX_RENDER_COUNT)
    }
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = rowState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            displayBooks.isNotEmpty() && lastVisibleIndex >= displayBooks.lastIndex - 4
        }
    }
    LaunchedEffect(shouldLoadMore, displayBooks.size) {
        if (shouldLoadMore) {
            onLoadMore()
        }
    }
    LazyRow(
        state = rowState,
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 0.dp, end = 8.dp)
    ) {
        itemsIndexed(
            items = displayBooks,
            key = { _, book -> "${widget.id}|${book.suiteStableKey()}" }
        ) { _, book ->
            DiscoverySuiteCoverBookItem(
                book = book,
                rank = null,
                modifier = Modifier.width(74.dp),
                renderConfig = renderConfig,
                onBookClick = onBookClick,
                onBookPreview = onBookPreview,
                fragment = fragment,
                lifecycle = lifecycle
            )
        }
    }
}

@Composable
private fun DiscoverySuiteRandomBooksWidget(
    widget: DiscoverySuiteWidget,
    books: List<SearchBook>,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    onRefreshClick: () -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val displayBooks = remember(widget.id, books) {
        books.take(RANDOM_WIDGET_DISPLAY_COUNT)
    }
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        DiscoverySuiteCoverGrid(
            books = displayBooks,
            maxCount = RANDOM_WIDGET_DISPLAY_COUNT,
            rankStart = null,
            renderConfig = renderConfig,
            onBookClick = onBookClick,
            onBookPreview = onBookPreview,
            fragment = fragment,
            lifecycle = lifecycle
        )
        DiscoverySuiteRefreshButton(
            renderConfig = renderConfig,
            onClick = onRefreshClick
        )
    }
}

@Composable
private fun DiscoverySuiteTagBarWidget(
    widget: DiscoverySuiteWidget,
    renderConfig: BookshelfListRenderConfig,
    onTagClick: (DiscoverySuiteWidgetTarget) -> Unit
) {
    val palette = renderConfig.palette
    val radiusPx = with(LocalDensity.current) { palette.actionRadius.toPx() }
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 8.dp)
    ) {
        items(
            items = widget.targets,
            key = { "${it.sourceUrl}|${it.tagUrl}" }
        ) { target ->
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .appSettingPanelBackground(
                        normalColor = palette.rowColor,
                        panelImage = renderConfig.panelImage,
                        borderColor = palette.borderColor,
                        radiusPx = radiusPx
                    )
                    .clickable { onTagClick(target) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = target.title.compactTagTitle(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = palette.bodyFontFamily,
                    color = palette.primaryText
                )
            }
        }
    }
}

@Composable
private fun DiscoverySuiteRankButtonsWidget(
    widget: DiscoverySuiteWidget,
    renderConfig: BookshelfListRenderConfig,
    onTagClick: (DiscoverySuiteWidgetTarget) -> Unit
) {
    val palette = renderConfig.palette
    val radiusPx = with(LocalDensity.current) { palette.actionRadius.toPx() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        widget.targets.take(RANK_BUTTON_MAX_COUNT).chunked(3).forEach { rowTargets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowTargets.forEach { target ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(54.dp)
                            .appSettingPanelBackground(
                                normalColor = palette.rowColor,
                                panelImage = renderConfig.panelImage,
                                borderColor = palette.borderColor,
                                radiusPx = radiusPx
                            )
                            .clickable { onTagClick(target) }
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = target.title.compactTagTitle(),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = palette.bodyFontFamily,
                            color = palette.primaryText
                        )
                    }
                }
                repeat(3 - rowTargets.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySuiteRankedListWidget(
    widget: DiscoverySuiteWidget,
    rankedBooks: Map<String, List<SearchBook>>,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    onLoadMore: (DiscoverySuiteWidgetTarget) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val palette = renderConfig.palette
    val targets = remember(widget.targets) {
        widget.targets
            .filter { it.sourceUrl.isNotBlank() && it.tagUrl.isNotBlank() }
            .take(RANKED_LIST_MAX_TARGET_COUNT)
    }
    if (targets.isEmpty()) return
    var selectedTargetIndex by remember(widget.id, targets) { mutableStateOf(0) }
    val selectedTarget = targets[selectedTargetIndex.coerceIn(0, targets.lastIndex)]
    val selectedBooks = rankedBooks[selectedTarget.deckKey()].orEmpty()
    val pages = remember(selectedTarget.deckKey(), selectedBooks) {
        selectedBooks
            .chunked(RANKED_LIST_PAGE_BOOK_COUNT)
            .ifEmpty { listOf(emptyList()) }
    }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    LaunchedEffect(selectedTarget.deckKey()) {
        if (pagerState.currentPage != 0) {
            pagerState.scrollToPage(0)
        }
    }
    val shouldLoadMore by remember(selectedTarget.deckKey(), pages.size, selectedBooks.size) {
        derivedStateOf {
            selectedBooks.isNotEmpty() &&
                pagerState.currentPage >= pages.lastIndex - 2
        }
    }
    LaunchedEffect(selectedTarget.deckKey(), shouldLoadMore, pages.size, selectedBooks.size) {
        if (shouldLoadMore) {
            onLoadMore(selectedTarget)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.panelRadius))
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(22.dp),
                contentPadding = PaddingValues(end = 10.dp)
            ) {
                itemsIndexed(
                    items = targets,
                    key = { _, target -> target.deckKey() }
                ) { index, target ->
                    val selected = index == selectedTargetIndex
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                selectedTargetIndex = index
                            }
                            .padding(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = target.title.compactTagTitle(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = if (selected) 16.sp else 15.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                            fontFamily = palette.bodyFontFamily,
                            color = if (selected) palette.primaryText else palette.secondaryText
                        )
                        Box(
                            modifier = Modifier
                                .width(if (selected) 42.dp else 0.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (selected) palette.accent else Color.Transparent)
                        )
                    }
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(RANKED_LIST_ROW_HEIGHT * RANKED_LIST_PAGE_BOOK_COUNT)
        ) { page ->
            val books = pages.getOrNull(page).orEmpty()
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (books.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(RANKED_LIST_ROW_HEIGHT),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = LocalContext.current.getString(R.string.discovery_suite_widget_pending),
                            fontSize = 14.sp,
                            fontFamily = palette.bodyFontFamily,
                            color = palette.secondaryText
                        )
                    }
                } else {
                    books.forEachIndexed { index, book ->
                        DiscoverySuiteRankedListBookRow(
                            rank = page * RANKED_LIST_PAGE_BOOK_COUNT + index + 1,
                            book = book,
                            renderConfig = renderConfig,
                            onBookClick = onBookClick,
                            onBookPreview = onBookPreview,
                            fragment = fragment,
                            lifecycle = lifecycle
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySuiteRankedListBookRow(
    rank: Int,
    book: SearchBook,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val palette = renderConfig.palette
    var coverBounds by remember(book.displayKey()) { mutableStateOf<Rect?>(null) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RANKED_LIST_ROW_HEIGHT)
            .clip(RoundedCornerShape(palette.actionRadius))
            .combinedClickable(
                onClick = { onBookClick(book) },
                onLongClick = { onBookPreview(book, coverBounds) }
            )
            .padding(horizontal = 2.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = rank.toString(),
            modifier = Modifier.width(32.dp),
            fontSize = if (rank <= 3) 25.sp else 23.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.titleFontFamily,
            color = if (rank <= 3) palette.accent else palette.primaryText.copy(alpha = 0.88f)
        )
        Box(
                modifier = Modifier
                    .width(40.dp)
                    .aspectRatio(BOOK_COVER_ASPECT_RATIO)
                    .onGloballyPositioned { coordinates ->
                        coverBounds = coordinates.boundsInRoot()
                    }
        ) {
            BookCoverImage(
                book = book,
                modifier = Modifier.fillMaxSize(),
                style = CoverImageView.CoverStyle.COMPACT,
                loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
                fragment = fragment,
                lifecycle = lifecycle,
                preferThumb = true,
                fillBounds = true
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = book.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.titleFontFamily,
                color = palette.primaryText
            )
            val meta = listOf(book.author, book.originName, book.latestChapterTitle.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    modifier = Modifier.padding(top = 3.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    fontFamily = palette.bodyFontFamily,
                    color = palette.secondaryText
                )
            }
        }
    }
}

@Composable
private fun DiscoverySuiteCoverGrid(
    books: List<SearchBook>,
    maxCount: Int,
    rankStart: Int?,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val displayBooks = books.take(maxCount)
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        displayBooks.chunked(3).forEachIndexed { rowIndex, rowBooks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                rowBooks.forEachIndexed { columnIndex, book ->
                    val rank = rankStart?.plus(rowIndex * 3 + columnIndex)
                    DiscoverySuiteFlippingCoverBookItem(
                        book = book,
                        rank = rank,
                        modifier = Modifier.weight(1f),
                        renderConfig = renderConfig,
                        onBookClick = onBookClick,
                        onBookPreview = onBookPreview,
                        fragment = fragment,
                        lifecycle = lifecycle
                    )
                }
                repeat(3 - rowBooks.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun DiscoverySuiteFlippingCoverBookItem(
    book: SearchBook,
    rank: Int?,
    modifier: Modifier = Modifier,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    var frontBook by remember { mutableStateOf(book) }
    var backBook by remember { mutableStateOf<SearchBook?>(null) }
    val rotation = remember { Animatable(0f) }
    val cameraDistance = LocalDensity.current.density * 18f
    LaunchedEffect(book.displayKey()) {
        if (frontBook.displayKey() == book.displayKey()) {
            return@LaunchedEffect
        }
        if (AppConfig.isEInkMode) {
            frontBook = book
            backBook = null
            rotation.snapTo(0f)
            return@LaunchedEffect
        }
        backBook = book
        rotation.snapTo(0f)
        rotation.animateTo(
            targetValue = 180f,
            animationSpec = tween(durationMillis = 560, easing = DiscoverySuiteEmphasizedEasing)
        )
        frontBook = book
        backBook = null
        rotation.snapTo(0f)
    }
    val currentRotation = rotation.value
    val shownBook = if (currentRotation > 90f && backBook != null) backBook ?: frontBook else frontBook
    val visualRotation = if (currentRotation > 90f) currentRotation - 180f else currentRotation
    DiscoverySuiteCoverBookItem(
        book = shownBook,
        rank = rank,
        modifier = modifier.graphicsLayer {
            rotationY = visualRotation
            this.cameraDistance = cameraDistance
        },
        renderConfig = renderConfig,
        onBookClick = onBookClick,
        onBookPreview = onBookPreview,
        fragment = fragment,
        lifecycle = lifecycle
    )
}

@Composable
private fun DiscoverySuiteRefreshButton(
    renderConfig: BookshelfListRenderConfig,
    onClick: () -> Unit
) {
    val palette = renderConfig.palette
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = LocalContext.current.getString(R.string.discovery_suite_refresh_batch),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DiscoverySuiteCoverBookItem(
    book: SearchBook,
    rank: Int?,
    modifier: Modifier = Modifier,
    renderConfig: BookshelfListRenderConfig,
    onBookClick: (SearchBook) -> Unit,
    onBookPreview: (SearchBook, Rect?) -> Unit,
    fragment: Fragment,
    lifecycle: Lifecycle
) {
    val palette = renderConfig.palette
    var coverBounds by remember(book.displayKey()) { mutableStateOf<Rect?>(null) }
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .aspectRatio(BOOK_COVER_ASPECT_RATIO)
                .onGloballyPositioned { coordinates ->
                    coverBounds = coordinates.boundsInRoot()
                }
                .combinedClickable(
                    onClick = { onBookClick(book) },
                    onLongClick = { onBookPreview(book, coverBounds) }
                )
        ) {
            BookCoverImage(
                book = book,
                modifier = Modifier.fillMaxSize(),
                style = CoverImageView.CoverStyle.GRID,
                loadOnlyWifi = AppConfig.loadCoverOnlyWifi,
                fragment = fragment,
                lifecycle = lifecycle,
                preferThumb = true,
                fillBounds = true
            )
            rank?.let {
                Box(
                    modifier = Modifier
                        .padding(7.dp)
                        .size(22.dp)
                        .align(Alignment.TopStart)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(palette.rowColor).copy(alpha = 0.88f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = it.toString(),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = palette.bodyFontFamily,
                        color = palette.accent
                    )
                }
            }
        }
    }
}

private fun String.compactTagTitle(): String {
    val value = trim()
    val index = value.lastIndexOf(" - ")
    return if (index >= 0 && index + 3 < value.length) {
        value.substring(index + 3)
    } else {
        value
    }.take(12)
}

private fun SearchBook.displayKey(): String {
    return "$origin|$bookUrl|${coverUrl.orEmpty()}"
}

/**
 * 稳定的列表 item key，口径与加载侧 distinctBy { suiteDeckKey() } 完全一致：
 * bookUrl 非空用 origin|bookUrl，否则退回 origin|name|author，避免空 bookUrl 撞 key。
 * 不含 index / 可变展示字段，合并回填或去重移位时不会被 Compose 误判为新 item。
 */
private fun SearchBook.suiteStableKey(): String {
    return when {
        bookUrl.isNotBlank() -> "$origin|$bookUrl"
        author.isNotBlank() -> "$origin|$name|$author"
        else -> "$origin|$name"
    }
}

private fun BookshelfListRenderConfig.withSuiteOpacityMultiplier(
    context: Context,
    multiplier: Float
): BookshelfListRenderConfig {
    val safeMultiplier = multiplier.coerceIn(1f, 4f)
    if (safeMultiplier <= 1.001f) return this
    return copy(
        palette = palette.copy(
            rowColor = palette.rowColor.withAlphaMultiplier(safeMultiplier),
            rowPressedColor = palette.rowPressedColor.withAlphaMultiplier(safeMultiplier),
            borderColor = palette.borderColor?.withAlphaMultiplier(safeMultiplier)
        ),
        panelImage = UiCorner.panelImageDrawable(
            context = context,
            radius = palette.panelRadiusPx,
            alphaMultiplier = safeMultiplier
        )
    )
}

private fun Int.withAlphaMultiplier(multiplier: Float): Int {
    val alpha = android.graphics.Color.alpha(this)
    if (alpha >= 255) return this
    val nextAlpha = (alpha * multiplier).roundToInt().coerceIn(alpha, 255)
    return (this and 0x00ffffff) or (nextAlpha shl 24)
}

private const val RANDOM_WIDGET_DISPLAY_COUNT = 6
private const val WATERFALL_WIDGET_DISPLAY_COUNT = 24
private const val HORIZONTAL_WIDGET_MAX_RENDER_COUNT = 72
private const val RANK_BUTTON_MAX_COUNT = 9
private const val RANKED_LIST_MAX_TARGET_COUNT = 9
private const val RANKED_LIST_PAGE_BOOK_COUNT = 4
private val RANKED_LIST_ROW_HEIGHT = 68.dp

private fun DiscoverySuiteWidget.displayTitle(context: Context): String {
    val title = title.trim()
    val addWidgetTitle = context.getString(R.string.discovery_suite_add_widget)
    if (title.isBlank() || title == addWidgetTitle) {
        return when (type) {
            DiscoverySuiteWidgetType.TagBar.value -> context.getString(R.string.discovery_suite_default_tag_bar_title)
            DiscoverySuiteWidgetType.HorizontalBooks.value -> context.getString(R.string.discovery_suite_widget_type_horizontal_books)
            DiscoverySuiteWidgetType.RankedList.value -> context.getString(R.string.discovery_suite_widget_type_ranked_list)
            DiscoverySuiteWidgetType.WaterfallBooks.value -> context.getString(R.string.discovery_suite_widget_type_waterfall_books)
            else -> context.getString(R.string.discovery_suite_default_random_title)
        }
    }
    if (type == DiscoverySuiteWidgetType.RankedList.value &&
        title == context.getString(R.string.discovery_suite_default_random_title)
    ) {
        return context.getString(R.string.discovery_suite_widget_type_ranked_list)
    }
    if (type == DiscoverySuiteWidgetType.WaterfallBooks.value &&
        title == context.getString(R.string.discovery_suite_default_random_title)
    ) {
        return context.getString(R.string.discovery_suite_widget_type_waterfall_books)
    }
    return title
}

private fun DiscoverySuiteWidgetTarget.deckKey(): String {
    return "$sourceUrl\n$tagUrl"
}
