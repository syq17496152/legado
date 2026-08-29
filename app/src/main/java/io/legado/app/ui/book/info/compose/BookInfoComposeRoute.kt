package io.legado.app.ui.book.info.compose

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.ImageView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.releaseComposeImage
import androidx.compose.ui.zIndex
import androidx.core.text.HtmlCompat
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BookInfoQuickActionConfig
import io.legado.app.help.config.BookInfoQuickActionItem
import io.legado.app.help.config.BookInfoQuickActionType
import io.legado.app.help.book.BookCloudEntryMode
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.webView.WebJsExtensions.Companion.getInjectionString
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.composeActionRadius
import io.legado.app.lib.theme.composePanelRadius
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.ui.association.OnLineImportActivity
import io.legado.app.ui.book.info.BookInfoUseWebHost
import io.legado.app.ui.widget.image.CoverImageView
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.openUrl
import io.noties.markwon.Markwon
import io.noties.markwon.html.HtmlPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.theme.subtitleLarge
import io.legado.app.ui.theme.subtitleLargeX

@Immutable
data class BookInfoChapterUi(
    val index: Int,
    val title: String,
    val isVolume: Boolean = false
)

@Immutable
data class BookInfoUiState(
    val bookUrl: String = "",
    val sourceUrl: String = "",
    val name: String = "",
    val author: String = "",
    val originName: String = "",
    val latestChapterTitle: String = "",
    val readTimeText: String = "",
    val coverPath: String? = null,
    val intro: String = "",
    val kinds: List<String> = emptyList(),
    val groupText: String = "",
    val tocText: String = "",
    val chapterCount: Int = 0,
    val chapterPreview: List<BookInfoChapterUi> = emptyList(),
    val currentChapterIndex: Int = -1,
    val currentChapterTitle: String = "",
    val currentChapterPreview: List<BookInfoChapterUi> = emptyList(),
    val aiImageCount: Int = 0,
    val aiImagePaths: List<String> = emptyList(),
    val inBookshelf: Boolean = false,
    val hasCustomButton: Boolean = false,
    val hasSourceLogin: Boolean = false,
    val hasBookSource: Boolean = false,
    val canUpdate: Boolean = true,
    val cloudEntryMode: BookCloudEntryMode = BookCloudEntryMode.CACHE_PACKAGE,
    val loading: Boolean = false
)

@Immutable
data class BookInfoActions(
    val onBack: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onRefreshToc: () -> Unit = {},
    val onRead: () -> Unit = {},
    val onShelf: () -> Unit = {},
    val onChangeCover: () -> Unit = {},
    val onPreviewCover: () -> Unit = {},
    val onAuthorClick: () -> Unit = {},
    val onAuthorLongClick: () -> Unit = {},
    val onNameClick: () -> Unit = {},
    val onNameLongClick: () -> Unit = {},
    val onEditBookInfo: () -> Unit = {},
    val onChangeSource: () -> Unit = {},
    val onEditSource: () -> Unit = {},
    val onChangeGroup: () -> Unit = {},
    val onOpenToc: () -> Unit = {},
    val onOpenChapter: (BookInfoChapterUi) -> Unit = {},
    val onOpenAiGallery: () -> Unit = {},
    val onCustomButton: () -> Unit = {},
    val onLogin: () -> Unit = {},
    val onCloudBackup: () -> Unit = {},
    val onOpenLibraryContainer: () -> Unit = {},
    val onAllowUpdateChanged: (Boolean) -> Unit = {},
    val onSetSourceVariable: () -> Unit = {},
    val onSetBookVariable: () -> Unit = {},
    val onCopyBookUrl: () -> Unit = {},
    val onCopyTocUrl: () -> Unit = {},
    val onClearCache: () -> Unit = {},
    val onSetupWebIntro: (WebView) -> Unit = {},
    val onRefreshEnabledChanged: (Boolean) -> Unit = {},
    val onQuickActionsChanged: () -> Unit = {}
)

@Immutable
data class BookInfoComposeColors(
    val background: Color,
    val contentBackground: Color,
    val contentTop: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val primaryText: Color,
    val secondaryText: Color,
    val accent: Color,
    val accentContainer: Color,
    val metricTop: Color,
    val metricBottom: Color,
    val metricText: Color,
    val metricSecondaryText: Color,
    val metricHighlight: Color,
    val actionText: Color,
    val scrim: Color
)

@Immutable
data class BookInfoComposeMetrics(
    val panelRadius: Dp,
    val actionRadius: Dp
)

@Immutable
data class BookInfoComposeStyle(
    val colors: BookInfoComposeColors,
    val metrics: BookInfoComposeMetrics
)

private enum class BookInfoMetricPreviewType {
    Source,
    Toc,
    Gallery
}

private data class BookInfoMetricPreview(
    val type: BookInfoMetricPreviewType,
    val anchorBounds: Rect
)

@Stable
fun bookInfoComposeStyle(context: Context, coverColor: Int? = null): BookInfoComposeStyle {
    val night = AppConfig.isNightTheme
    val coverAccent = coverColor ?: context.accentColor
    val accent = if (night) {
        coverAccent.coverTone(saturation = 0.58f, value = 0.72f)
    } else {
        coverAccent.coverTone(saturation = 0.50f, value = 0.62f)
    }
    val pageBackground = if (night) {
        coverAccent.coverTone(saturation = 0.34f, value = 0.09f)
    } else {
        coverAccent.coverTone(saturation = 0.24f, value = 0.86f)
    }
    val contentBackground = if (night) {
        coverAccent.coverTone(saturation = 0.30f, value = 0.13f)
    } else {
        coverAccent.coverTone(saturation = 0.30f, value = 0.82f)
    }
    val contentTop = if (night) {
        coverAccent.coverTone(saturation = 0.42f, value = 0.20f)
    } else {
        coverAccent.coverTone(saturation = 0.38f, value = 0.68f)
    }
    val surface = if (night) {
        coverAccent.coverTone(saturation = 0.26f, value = 0.18f)
    } else {
        coverAccent.coverTone(saturation = 0.20f, value = 0.86f)
    }
    val variant = if (night) {
        coverAccent.coverTone(saturation = 0.28f, value = 0.22f)
    } else {
        coverAccent.coverTone(saturation = 0.26f, value = 0.76f)
    }
    val accentContainer = if (night) {
        coverAccent.coverTone(saturation = 0.38f, value = 0.24f)
    } else {
        coverAccent.coverTone(saturation = 0.32f, value = 0.72f)
    }
    val metricTop = if (night) {
        coverAccent.coverTone(saturation = 0.34f, value = 0.24f)
    } else {
        coverAccent.coverTone(saturation = 0.30f, value = 0.80f)
    }
    val metricBottom = if (night) {
        coverAccent.coverTone(saturation = 0.30f, value = 0.18f)
    } else {
        coverAccent.coverTone(saturation = 0.36f, value = 0.68f)
    }
    val actionText = if (ColorUtils.isColorLight(accent)) 0xff202124.toInt() else 0xffffffff.toInt()
    val scrim = if (night) {
        coverAccent.coverTone(saturation = 0.52f, value = 0.07f)
    } else {
        coverAccent.coverTone(saturation = 0.44f, value = 0.18f)
    }
    val primaryText = context.primaryTextColor
    val secondaryText = context.secondaryTextColor
    return BookInfoComposeStyle(
        colors = BookInfoComposeColors(
            background = Color(pageBackground),
            contentBackground = Color(contentBackground),
            contentTop = Color(contentTop),
            surface = Color(surface),
            surfaceVariant = Color(variant),
            primaryText = Color(primaryText),
            secondaryText = Color(secondaryText),
            accent = Color(accent),
            accentContainer = Color(accentContainer),
            metricTop = Color(metricTop),
            metricBottom = Color(metricBottom),
            metricText = Color(primaryText),
            metricSecondaryText = Color(secondaryText),
            metricHighlight = Color(if (night) 0x3dffffff else 0x80ffffff),
            actionText = Color(actionText),
            scrim = Color(scrim)
        ),
        metrics = BookInfoComposeMetrics(
            panelRadius = context.composePanelRadius(),
            actionRadius = context.composeActionRadius()
        )
    )
}

private fun Int.coverTone(saturation: Float, value: Float): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(this, hsv)
    hsv[1] = saturation.coerceIn(0f, 1f)
    hsv[2] = value.coerceIn(0f, 1f)
    return android.graphics.Color.HSVToColor(hsv)
}

@Composable
fun BookInfoComposeRoute(
    state: BookInfoUiState,
    actions: BookInfoActions,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var coverColor by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.coverPath) {
        loadCoverThemeColor(context, state.coverPath)?.let { color ->
            coverColor = color
        }
    }
    val style = remember(context, coverColor) { bookInfoComposeStyle(context, coverColor) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showQuickActionEditor by remember { mutableStateOf(false) }
    var showCloudEntrySelector by remember { mutableStateOf(false) }
    var quickActionConfigVersion by remember { mutableStateOf(0) }
    var metricPreview by remember { mutableStateOf<BookInfoMetricPreview?>(null) }
    var metricPreviewVisible by remember { mutableStateOf(false) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    val pageScrollState = rememberScrollState()
    val refreshAtTop by remember {
        derivedStateOf { pageScrollState.value == 0 }
    }
    LaunchedEffect(refreshAtTop) {
        actions.onRefreshEnabledChanged(refreshAtTop)
    }
    val hasWebIntro = state.intro.startsWith("<useweb>", ignoreCase = true)
    var webIntroExpandPages by remember(state.bookUrl, state.intro) { mutableStateOf(2) }
    val shouldExpandWebIntro by remember(hasWebIntro) {
        derivedStateOf {
            hasWebIntro &&
                pageScrollState.maxValue > 0 &&
                pageScrollState.value >= pageScrollState.maxValue - 96
        }
    }
    LaunchedEffect(shouldExpandWebIntro) {
        if (shouldExpandWebIntro && webIntroExpandPages < 36) {
            webIntroExpandPages += 2
        }
    }
    LaunchedEffect(metricPreviewVisible, metricPreview) {
        if (!metricPreviewVisible && metricPreview != null) {
            delay(190)
            if (!metricPreviewVisible) {
                metricPreview = null
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { rootSize = it }
            .background(
                Brush.verticalGradient(
                    0f to style.colors.contentTop,
                    0.34f to style.colors.contentBackground,
                    1f to style.colors.background
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(pageScrollState),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(548.dp)
            ) {
                BookInfoCoverBackdrop(
                    coverPath = state.coverPath,
                    style = style,
                    scrollOffset = pageScrollState.value,
                    modifier = Modifier.fillMaxSize()
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 18.dp)
                        .padding(bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    BookInfoPosterHero(state, actions, style)
                    BookInfoStatusStrip(
                        state = state,
                        actions = actions,
                        style = style,
                        configVersion = quickActionConfigVersion,
                        onSelectCloudEntry = { showCloudEntrySelector = true },
                        onPreviewStart = {
                            metricPreview = it
                            metricPreviewVisible = true
                        },
                        onPreviewEnd = {
                            metricPreviewVisible = false
                        }
                    )
                }
            }
            BookInfoContentPanel(style = style) {
                BookInfoIntroPanel(
                    intro = state.intro,
                    state = state,
                    actions = actions,
                    style = style,
                    webIntroExpandPages = webIntroExpandPages
                )
            }
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(116.dp)
                    .background(style.colors.contentBackground)
            )
        }
        BookInfoBottomActions(
            state = state,
            actions = actions,
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
        )
        BookInfoTopGradient(
            style = style,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )
        BookInfoFloatingTopBar(
            style = style,
            onBack = actions.onBack,
            onMore = { showMoreMenu = true },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        )
        if (state.loading) {
            CircularProgressIndicator(
                modifier = Modifier
                    .statusBarsPadding()
                    .align(Alignment.TopEnd)
                    .padding(top = 58.dp, end = 24.dp)
                    .size(22.dp),
                color = style.colors.accent,
                strokeWidth = 2.dp
            )
        }
        if (showMoreMenu) {
            BookInfoMoreActionSheet(
                state = state,
                style = style,
                actions = actions,
                onEditQuickActions = {
                    showMoreMenu = false
                    showQuickActionEditor = true
                },
                onDismiss = { showMoreMenu = false }
            )
        }
        if (showQuickActionEditor) {
            BookInfoQuickActionEditDialog(
                state = state,
                style = style,
                onDismiss = { showQuickActionEditor = false },
                onSaved = {
                    quickActionConfigVersion += 1
                    actions.onQuickActionsChanged()
                }
            )
        }
        if (showCloudEntrySelector) {
            BookInfoCloudEntrySelectorDialog(
                state = state,
                style = style,
                actions = actions,
                onDismiss = { showCloudEntrySelector = false }
            )
        }
        metricPreview?.let { preview ->
            BookInfoMetricPreviewOverlay(
                preview = preview,
                state = state,
                style = style,
                rootSize = rootSize,
                visible = metricPreviewVisible,
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(4f)
            )
        }
    }
}

@Composable
private fun BookInfoStatusStrip(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle,
    configVersion: Int,
    onSelectCloudEntry: () -> Unit,
    onPreviewStart: (BookInfoMetricPreview) -> Unit,
    onPreviewEnd: () -> Unit
) {
    val configuredItems = remember(configVersion) {
        BookInfoQuickActionConfig.load()
    }
    val configuredActions = mutableListOf<BookInfoQuickActionUi>()
    configuredItems.forEach { item ->
        item.toQuickActionUi(
            state = state,
            actions = actions,
            onSelectCloudEntry = onSelectCloudEntry
        )?.let(configuredActions::add)
    }
    val quickActions = configuredActions.takeIf { it.isNotEmpty() }
        ?: defaultBookInfoQuickActions(state, actions, onSelectCloudEntry)
    val pages = remember(quickActions) { quickActions.chunked(3) }
    val pagerState = rememberPagerState(pageCount = { pages.size })
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth()
        ) { page ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                pages[page].forEach { quickAction ->
                    BookInfoMetricBox(
                        label = quickAction.label,
                        value = quickAction.value,
                        suffix = quickAction.suffix,
                        modifier = Modifier.weight(1f),
                        style = style,
                        onLongPressStart = { bounds ->
                            quickAction.previewType?.let {
                                onPreviewStart(BookInfoMetricPreview(it, bounds))
                            }
                        },
                        onPressEnd = onPreviewEnd,
                        onClick = quickAction.onClick
                    )
                }
                repeat(3 - pages[page].size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
        if (pages.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                pages.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == pagerState.currentPage) 7.dp else 5.dp)
                            .clip(CircleShape)
                            .background(
                                if (index == pagerState.currentPage) {
                                    style.colors.accent
                                } else {
                                    style.colors.metricSecondaryText.copy(alpha = 0.42f)
                                }
                            )
                    )
                }
            }
        }
    }
}

private data class BookInfoQuickActionUi(
    val label: String,
    val value: String,
    val suffix: String = "",
    val previewType: BookInfoMetricPreviewType? = null,
    val onClick: () -> Unit
)

@Composable
private fun BookInfoQuickActionItem.toQuickActionUi(
    state: BookInfoUiState,
    actions: BookInfoActions,
    onSelectCloudEntry: () -> Unit
): BookInfoQuickActionUi? {
    if (!enabled) return null
    val customAlias = BookInfoQuickActionConfig.customButtonAlias(state.sourceUrl)
    return when (type) {
        BookInfoQuickActionType.SOURCE -> BookInfoQuickActionUi(
            label = alias.ifBlank { "书源" },
            value = state.originName.cleanBookInfoValue(),
            previewType = BookInfoMetricPreviewType.Source,
            onClick = actions.onChangeSource
        )
        BookInfoQuickActionType.TOC -> BookInfoQuickActionUi(
            label = alias.ifBlank { "目录" },
            value = if (state.chapterCount > 0) "${state.chapterCount}" else stringResource(R.string.view_toc),
            suffix = if (state.chapterCount > 0) "章" else "",
            previewType = BookInfoMetricPreviewType.Toc,
            onClick = actions.onOpenToc
        )
        BookInfoQuickActionType.GALLERY -> BookInfoQuickActionUi(
            label = alias.ifBlank { "图库" },
            value = if (state.aiImageCount > 0) "${state.aiImageCount}" else stringResource(R.string.ai_image_gallery_empty),
            previewType = BookInfoMetricPreviewType.Gallery,
            onClick = actions.onOpenAiGallery
        )
        BookInfoQuickActionType.GROUP -> BookInfoQuickActionUi(
            label = alias.ifBlank { stringResource(R.string.group) },
            value = state.groupText.cleanBookInfoValue().ifBlank { stringResource(R.string.no_group) },
            onClick = actions.onChangeGroup
        )
        BookInfoQuickActionType.CLOUD -> BookInfoQuickActionUi(
            label = alias.ifBlank { stringResource(R.string.book_cloud_entry_mode) },
            value = when (state.cloudEntryMode) {
                BookCloudEntryMode.CACHE_PACKAGE -> stringResource(R.string.book_cloud_cache_package_mode)
                BookCloudEntryMode.LIBRARY_CHAPTER -> stringResource(R.string.book_cloud_library_chapter_mode)
            },
            onClick = onSelectCloudEntry
        )
        BookInfoQuickActionType.CUSTOM_BUTTON -> {
            if (!state.hasCustomButton) return null
            BookInfoQuickActionUi(
                label = stringResource(R.string.custom_button),
                value = customAlias.ifBlank { alias.ifBlank { stringResource(R.string.custom_button) } },
                onClick = actions.onCustomButton
            )
        }
        BookInfoQuickActionType.EDIT_INFO -> BookInfoQuickActionUi(
            label = alias.ifBlank { stringResource(R.string.edit) },
            value = stringResource(R.string.book_info_edit),
            onClick = actions.onEditBookInfo
        )
        BookInfoQuickActionType.SHELF -> BookInfoQuickActionUi(
            label = alias.ifBlank { stringResource(R.string.bookshelf) },
            value = if (state.inBookshelf) {
                stringResource(R.string.remove_from_bookshelf)
            } else {
                stringResource(R.string.add_to_bookshelf)
            },
            onClick = actions.onShelf
        )
        BookInfoQuickActionType.READ -> BookInfoQuickActionUi(
            label = alias.ifBlank { stringResource(R.string.reading) },
            value = stringResource(R.string.reading),
            onClick = actions.onRead
        )
    }
}

@Composable
private fun defaultBookInfoQuickActions(
    state: BookInfoUiState,
    actions: BookInfoActions,
    onSelectCloudEntry: () -> Unit
): List<BookInfoQuickActionUi> {
    return listOfNotNull(
        BookInfoQuickActionItem(BookInfoQuickActionType.SOURCE).toQuickActionUi(state, actions, onSelectCloudEntry),
        BookInfoQuickActionItem(BookInfoQuickActionType.TOC).toQuickActionUi(state, actions, onSelectCloudEntry),
        BookInfoQuickActionItem(BookInfoQuickActionType.GALLERY).toQuickActionUi(state, actions, onSelectCloudEntry)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookInfoMetricBox(
    label: String,
    value: String,
    suffix: String = "",
    modifier: Modifier = Modifier,
    style: BookInfoComposeStyle,
    onLongPressStart: (Rect) -> Unit,
    onPressEnd: () -> Unit,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(style.metrics.actionRadius)
    var bounds by remember { mutableStateOf<Rect?>(null) }
    Box(
        modifier = modifier
            .height(74.dp)
            .shadow(2.dp, shape, clip = false)
            .clip(shape)
            .background(style.colors.metricTop.copy(alpha = 0.96f))
            .onGloballyPositioned { bounds = it.boundsInRoot() }
            .pointerInput(onClick, onLongPressStart, onPressEnd) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { bounds?.let(onLongPressStart) },
                    onPress = {
                        try {
                            awaitRelease()
                        } finally {
                            onPressEnd()
                        }
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = value,
                    color = style.colors.metricText,
                    fontSize = MaterialTheme.typography.subtitleLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (suffix.isNotBlank()) {
                    Text(
                        text = suffix,
                        color = style.colors.metricSecondaryText,
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        maxLines = 1
                    )
                }
            }
            Text(
                text = label,
                color = style.colors.metricSecondaryText,
                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun BookInfoMetricPreviewOverlay(
    preview: BookInfoMetricPreview,
    state: BookInfoUiState,
    style: BookInfoComposeStyle,
    rootSize: IntSize,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (rootSize.width <= 0 || rootSize.height <= 0) return
    val density = LocalDensity.current
    val visibleState = remember(preview) {
        MutableTransitionState(false).apply { targetState = true }
    }
    LaunchedEffect(visible) {
        visibleState.targetState = visible
    }
    val transition = updateTransition(visibleState, label = "bookInfoMetricPreview")
    val preferredHeight = when (preview.type) {
        BookInfoMetricPreviewType.Source -> 188.dp
        BookInfoMetricPreviewType.Toc -> 318.dp
        BookInfoMetricPreviewType.Gallery -> 238.dp
    }
    val paddingPx = with(density) { 16.dp.toPx() }
    val gapPx = with(density) { 10.dp.toPx() }
    val rootWidthPx = rootSize.width.toFloat()
    val rootHeightPx = rootSize.height.toFloat()
    val availableWidthPx = (rootWidthPx - paddingPx * 2)
        .coerceAtLeast(with(density) { 180.dp.toPx() })
    val availableHeightPx = (rootHeightPx - paddingPx * 2)
        .coerceAtLeast(with(density) { 120.dp.toPx() })
    val preferredHeightPx = with(density) { preferredHeight.toPx() }
        .coerceAtMost(availableHeightPx)
    val popupWidthPx = availableWidthPx
        .coerceAtMost(with(density) { 340.dp.toPx() })
    val anchor = preview.anchorBounds
    val maxY = (rootHeightPx - preferredHeightPx - paddingPx).coerceAtLeast(paddingPx)
    val aboveY = anchor.top - preferredHeightPx - gapPx
    val belowY = anchor.bottom + gapPx
    val shouldPlaceAbove = aboveY >= paddingPx || rootHeightPx - belowY < preferredHeightPx
    val rawY = if (shouldPlaceAbove && aboveY >= paddingPx) aboveY else belowY
    val y = rawY.coerceIn(paddingPx, maxY)
    val maxX = (rootWidthPx - popupWidthPx - paddingPx).coerceAtLeast(paddingPx)
    val x = (anchor.center.x - popupWidthPx / 2f).coerceIn(paddingPx, maxX)
    val animatedX by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 170, easing = FastOutSlowInEasing) },
        label = "metricPreviewX"
    ) { shown -> if (shown) x else anchor.left }
    val animatedY by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 170, easing = FastOutSlowInEasing) },
        label = "metricPreviewY"
    ) { shown -> if (shown) y else anchor.top }
    val animatedWidth by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 170, easing = FastOutSlowInEasing) },
        label = "metricPreviewWidth"
    ) { shown -> if (shown) popupWidthPx else anchor.width }
    val animatedHeight by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 170, easing = FastOutSlowInEasing) },
        label = "metricPreviewHeight"
    ) { shown -> if (shown) preferredHeightPx else anchor.height }
    val contentAlpha by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 120, easing = FastOutSlowInEasing) },
        label = "metricPreviewAlpha"
    ) { shown -> if (shown) 1f else 0f }
    val previewProgress by transition.animateFloat(
        transitionSpec = { tween(durationMillis = 170, easing = FastOutSlowInEasing) },
        label = "metricPreviewProgress"
    ) { shown -> if (shown) 1f else 0f }
    Box(modifier = modifier) {
        BookInfoMetricPreviewCard(
            preview = preview,
            state = state,
            style = style,
            contentAlpha = contentAlpha,
            contentReady = visible && previewProgress > 0.96f,
            modifier = Modifier
                .offset { IntOffset(animatedX.roundToInt(), animatedY.roundToInt()) }
                .width(with(density) { animatedWidth.toDp() })
                .height(with(density) { animatedHeight.toDp() })
        )
    }
}

@Composable
private fun BookInfoMetricPreviewCard(
    preview: BookInfoMetricPreview,
    state: BookInfoUiState,
    style: BookInfoComposeStyle,
    contentAlpha: Float,
    contentReady: Boolean,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(style.metrics.panelRadius)
    Box(
        modifier = modifier
            .shadow(14.dp, shape, clip = false)
            .clip(shape)
            .background(style.colors.surface.copy(alpha = 0.97f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .graphicsLayer { alpha = contentAlpha }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when (preview.type) {
                BookInfoMetricPreviewType.Source -> BookInfoSourcePreview(state, style)
                BookInfoMetricPreviewType.Toc -> BookInfoTocMetricPreview(state, style)
                BookInfoMetricPreviewType.Gallery -> {
                    BookInfoGalleryMetricPreview(state, style, contentReady)
                }
            }
        }
    }
}

@Composable
private fun BookInfoSourcePreview(
    state: BookInfoUiState,
    style: BookInfoComposeStyle
) {
    BookInfoPreviewTitle("书源详情", style)
    BookInfoPreviewLine("书源", state.originName.cleanBookInfoValue(), style)
    BookInfoPreviewLine("书名", state.name, style)
    BookInfoPreviewLine("作者", state.author, style)
}

@Composable
private fun BookInfoTocMetricPreview(
    state: BookInfoUiState,
    style: BookInfoComposeStyle
) {
    BookInfoPreviewTitle("目录预览", style)
    val currentTitle = state.currentChapterTitle.ifBlank { state.tocText.cleanBookInfoValue() }
    if (currentTitle.isNotBlank()) {
        BookInfoPreviewLine("当前", currentTitle, style)
    }
    val chapters = state.currentChapterPreview.ifEmpty { state.chapterPreview.take(8) }
    if (chapters.isEmpty()) {
        Text(
            text = stringResource(R.string.error_load_toc),
            color = style.colors.secondaryText,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize
        )
    } else {
        chapters.forEach { chapter ->
            val selected = chapter.index == state.currentChapterIndex
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "#${chapter.index + 1}",
                    color = if (selected) style.colors.accent else style.colors.secondaryText,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Text(
                    text = chapter.title,
                    color = if (selected) style.colors.primaryText else style.colors.secondaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun BookInfoGalleryMetricPreview(
    state: BookInfoUiState,
    style: BookInfoComposeStyle,
    contentReady: Boolean
) {
    BookInfoPreviewTitle("图库预览", style)
    if (state.aiImagePaths.isEmpty()) {
        Text(
            text = stringResource(R.string.ai_image_gallery_empty),
            color = style.colors.secondaryText,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize
        )
    } else {
        Text(
            text = "共 ${state.aiImageCount} 张",
            color = style.colors.secondaryText,
            fontSize = 12.5.sp
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            state.aiImagePaths.take(3).forEach { path ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(0.78f)
                        .clip(RoundedCornerShape(style.metrics.actionRadius))
                        .background(style.colors.surfaceVariant.copy(alpha = 0.72f))
                ) {
                    if (contentReady) {
                        BookInfoPreviewImage(
                            path = path,
                            style = style,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookInfoPreviewTitle(
    text: String,
    style: BookInfoComposeStyle
) {
    Text(
        text = text,
        color = style.colors.primaryText,
        fontSize = MaterialTheme.typography.bodyLarge.fontSize,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun BookInfoPreviewLine(
    label: String,
    value: String,
    style: BookInfoComposeStyle
) {
    if (value.isBlank()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = label,
            color = style.colors.accent,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = style.colors.primaryText,
            fontSize = 13.5.sp,
            lineHeight = 18.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookInfoMoreActionSheet(
    state: BookInfoUiState,
    style: BookInfoComposeStyle,
    actions: BookInfoActions,
    onEditQuickActions: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showCloudOptions by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = style.metrics.panelRadius, topEnd = style.metrics.panelRadius),
        containerColor = style.colors.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(if (showCloudOptions) R.string.book_cloud_entry_mode else R.string.more),
                color = style.colors.primaryText,
                fontSize = MaterialTheme.typography.subtitleLargeX.fontSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            if (showCloudOptions) {
                BookInfoToggleActionItem(
                    text = stringResource(R.string.book_cloud_cache_package_mode),
                    checked = state.cloudEntryMode == BookCloudEntryMode.CACHE_PACKAGE,
                    style = style
                ) {
                    onDismiss()
                    actions.onCloudBackup()
                }
                BookInfoToggleActionItem(
                    text = stringResource(R.string.book_cloud_library_chapter_mode),
                    checked = state.cloudEntryMode == BookCloudEntryMode.LIBRARY_CHAPTER,
                    style = style
                ) {
                    onDismiss()
                    actions.onOpenLibraryContainer()
                }
                BookInfoMoreActionItem(stringResource(R.string.back), style) {
                    showCloudOptions = false
                }
            } else {
                if (state.hasSourceLogin) {
                    BookInfoMoreActionItem(stringResource(R.string.login), style) {
                        onDismiss()
                        actions.onLogin()
                    }
                }
                BookInfoMoreActionItem(stringResource(R.string.book_cloud_entry_mode), style) {
                    showCloudOptions = true
                }
                BookInfoMoreActionItem(stringResource(R.string.book_info_quick_action_edit), style) {
                    onEditQuickActions()
                }
                if (state.hasBookSource) {
                    BookInfoToggleActionItem(
                        text = stringResource(R.string.allow_update),
                        checked = state.canUpdate,
                        style = style
                    ) {
                        actions.onAllowUpdateChanged(!state.canUpdate)
                    }
                }
                BookInfoMoreActionItem(stringResource(R.string.group_select), style) {
                    onDismiss()
                    actions.onChangeGroup()
                }
                BookInfoMoreActionItem(stringResource(R.string.book_info_edit), style) {
                    onDismiss()
                    actions.onEditBookInfo()
                }
                BookInfoMoreActionItem(stringResource(R.string.copy_book_url), style) {
                    onDismiss()
                    actions.onCopyBookUrl()
                }
                BookInfoMoreActionItem(stringResource(R.string.copy_toc_url), style) {
                    onDismiss()
                    actions.onCopyTocUrl()
                }
                BookInfoMoreActionItem(stringResource(R.string.set_source_variable), style) {
                    onDismiss()
                    actions.onSetSourceVariable()
                }
                BookInfoMoreActionItem(stringResource(R.string.set_book_variable), style) {
                    onDismiss()
                    actions.onSetBookVariable()
                }
                BookInfoMoreActionItem(
                    text = stringResource(R.string.clear_cache),
                    style = style,
                    danger = true
                ) {
                    onDismiss()
                    actions.onClearCache()
                }
                if (state.hasCustomButton) {
                    BookInfoMoreActionItem(stringResource(R.string.custom_button), style) {
                        onDismiss()
                        actions.onCustomButton()
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

@Composable
private fun BookInfoMoreActionItem(
    text: String,
    style: BookInfoComposeStyle,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    Text(
        text = text,
        color = if (danger) Color(0xffd64545) else style.colors.primaryText,
        fontSize = MaterialTheme.typography.bodySecondary.fontSize,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(style.colors.surfaceVariant.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp)
    )
}

@Composable
private fun BookInfoToggleActionItem(
    text: String,
    checked: Boolean,
    style: BookInfoComposeStyle,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(style.colors.surfaceVariant.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = style.colors.primaryText,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(CircleShape)
                .then(
                    if (checked) {
                        Modifier.background(style.colors.accent)
                    } else {
                        Modifier.background(style.colors.secondaryText.copy(alpha = 0.22f))
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.94f))
                )
            }
        }
    }
}

@Composable
private fun BookInfoCloudEntrySelectorDialog(
    state: BookInfoUiState,
    style: BookInfoComposeStyle,
    actions: BookInfoActions,
    onDismiss: () -> Unit
) {
    BookInfoCenterDialog(onDismiss = onDismiss, style = style) {
        Text(
            text = stringResource(R.string.book_cloud_entry_mode),
            color = style.colors.primaryText,
            fontSize = MaterialTheme.typography.subtitleLargeX.fontSize,
            fontWeight = FontWeight.SemiBold
        )
        BookInfoCloudEntryModeRow(
            title = stringResource(R.string.book_cloud_cache_package_mode),
            summary = "从云端备份包上传、下载、用缓存入架",
            checked = state.cloudEntryMode == BookCloudEntryMode.CACHE_PACKAGE,
            style = style
        ) {
            onDismiss()
            actions.onCloudBackup()
        }
        BookInfoCloudEntryModeRow(
            title = stringResource(R.string.book_cloud_library_chapter_mode),
            summary = "阅读页显示云按钮，按当前章节切换书库正文",
            checked = state.cloudEntryMode == BookCloudEntryMode.LIBRARY_CHAPTER,
            style = style
        ) {
            onDismiss()
            actions.onOpenLibraryContainer()
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel), color = style.colors.secondaryText)
            }
        }
    }
}

@Composable
private fun BookInfoCloudEntryModeRow(
    title: String,
    summary: String,
    checked: Boolean,
    style: BookInfoComposeStyle,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(style.colors.surfaceVariant.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                color = if (checked) style.colors.accent else style.colors.primaryText,
                fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = summary,
                color = style.colors.secondaryText,
                fontSize = 12.5.sp,
                lineHeight = 17.sp
            )
        }
        if (checked) {
            Text(
                text = "✓",
                color = style.colors.accent,
                fontSize = MaterialTheme.typography.subtitleLarge.fontSize,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun BookInfoCenterDialog(
    onDismiss: () -> Unit,
    style: BookInfoComposeStyle,
    content: @Composable ColumnScope.() -> Unit
) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.82f).dp
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 520.dp)
                .heightIn(max = maxHeight)
                .shadow(10.dp, RoundedCornerShape(style.metrics.panelRadius), clip = false)
                .clip(RoundedCornerShape(style.metrics.panelRadius))
                .background(style.colors.surface)
                .padding(horizontal = 18.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun BookInfoQuickActionEditDialog(
    state: BookInfoUiState,
    style: BookInfoComposeStyle,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var draft by remember { mutableStateOf(BookInfoQuickActionConfig.load()) }
    var customAlias by remember(state.sourceUrl) {
        mutableStateOf(BookInfoQuickActionConfig.customButtonAlias(state.sourceUrl))
    }
    BookInfoCenterDialog(onDismiss = onDismiss, style = style) {
        Text(
            text = stringResource(R.string.book_info_quick_action_edit),
            color = style.colors.primaryText,
            fontSize = MaterialTheme.typography.subtitleLargeX.fontSize,
            fontWeight = FontWeight.SemiBold
        )
        draft.forEachIndexed { index, item ->
            BookInfoQuickActionEditRow(
                item = item,
                index = index,
                total = draft.size,
                state = state,
                customAlias = customAlias,
                style = style,
                onToggle = {
                    draft = draft.toMutableList().also { list ->
                        list[index] = item.copy(enabled = !item.enabled)
                    }
                },
                onAliasChange = { alias ->
                    if (item.type == BookInfoQuickActionType.CUSTOM_BUTTON) {
                        customAlias = alias
                    } else {
                        draft = draft.toMutableList().also { list ->
                            list[index] = item.copy(alias = alias)
                        }
                    }
                },
                onMoveUp = {
                    if (index > 0) {
                        draft = draft.toMutableList().also { list ->
                            val moved = list.removeAt(index)
                            list.add(index - 1, moved)
                        }
                    }
                },
                onMoveDown = {
                    if (index < draft.lastIndex) {
                        draft = draft.toMutableList().also { list ->
                            val moved = list.removeAt(index)
                            list.add(index + 1, moved)
                        }
                    }
                }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    draft = BookInfoQuickActionConfig.defaults()
                    customAlias = ""
                }
            ) {
                Text(text = stringResource(R.string.reset), color = style.colors.secondaryText)
            }
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.cancel), color = style.colors.secondaryText)
            }
            TextButton(
                onClick = {
                    BookInfoQuickActionConfig.save(draft)
                    BookInfoQuickActionConfig.saveCustomButtonAlias(state.sourceUrl, customAlias)
                    onSaved()
                    onDismiss()
                }
            ) {
                Text(text = stringResource(R.string.action_save), color = style.colors.accent)
            }
        }
    }
}

@Composable
private fun BookInfoQuickActionEditRow(
    item: BookInfoQuickActionItem,
    index: Int,
    total: Int,
    state: BookInfoUiState,
    customAlias: String,
    style: BookInfoComposeStyle,
    onToggle: () -> Unit,
    onAliasChange: (String) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val aliasValue = if (item.type == BookInfoQuickActionType.CUSTOM_BUTTON) customAlias else item.alias
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(style.colors.surfaceVariant.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Checkbox(
                checked = item.enabled,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = style.colors.accent,
                    uncheckedColor = style.colors.secondaryText
                )
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.type.editorTitle(),
                    color = style.colors.primaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontWeight = FontWeight.Medium
                )
                if (item.type == BookInfoQuickActionType.CUSTOM_BUTTON && state.sourceUrl.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.book_info_quick_action_source_alias_hint),
                        color = style.colors.secondaryText,
                        fontSize = MaterialTheme.typography.labelSmall.fontSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            TextButton(onClick = onMoveUp, enabled = index > 0) {
                Text(text = stringResource(R.string.move_up))
            }
            TextButton(onClick = onMoveDown, enabled = index < total - 1) {
                Text(text = stringResource(R.string.move_down))
            }
        }
        OutlinedTextField(
            value = aliasValue,
            onValueChange = onAliasChange,
            singleLine = true,
            label = { Text(stringResource(R.string.book_info_quick_action_alias)) },
            placeholder = { Text(item.type.defaultAliasPlaceholder()) },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = style.colors.accent,
                focusedLabelColor = style.colors.accent,
                cursorColor = style.colors.accent,
                focusedTextColor = style.colors.primaryText,
                unfocusedTextColor = style.colors.primaryText,
                unfocusedBorderColor = style.colors.secondaryText.copy(alpha = 0.42f),
                unfocusedLabelColor = style.colors.secondaryText
            )
        )
    }
}

@Composable
private fun BookInfoQuickActionType.editorTitle(): String {
    return when (this) {
        BookInfoQuickActionType.SOURCE -> "书源"
        BookInfoQuickActionType.TOC -> "目录"
        BookInfoQuickActionType.GALLERY -> "图库"
        BookInfoQuickActionType.GROUP -> stringResource(R.string.group)
        BookInfoQuickActionType.CLOUD -> stringResource(R.string.book_cloud_entry_mode)
        BookInfoQuickActionType.CUSTOM_BUTTON -> stringResource(R.string.custom_button)
        BookInfoQuickActionType.EDIT_INFO -> stringResource(R.string.book_info_edit)
        BookInfoQuickActionType.SHELF -> stringResource(R.string.bookshelf)
        BookInfoQuickActionType.READ -> stringResource(R.string.reading)
    }
}

@Composable
private fun BookInfoQuickActionType.defaultAliasPlaceholder(): String {
    return when (this) {
        BookInfoQuickActionType.SOURCE -> "书源"
        BookInfoQuickActionType.TOC -> "目录"
        BookInfoQuickActionType.GALLERY -> "图库"
        BookInfoQuickActionType.GROUP -> stringResource(R.string.group)
        BookInfoQuickActionType.CLOUD -> stringResource(R.string.book_cloud_entry_mode)
        BookInfoQuickActionType.CUSTOM_BUTTON -> stringResource(R.string.custom_button)
        BookInfoQuickActionType.EDIT_INFO -> stringResource(R.string.book_info_edit)
        BookInfoQuickActionType.SHELF -> stringResource(R.string.bookshelf)
        BookInfoQuickActionType.READ -> stringResource(R.string.reading)
    }
}

@Composable
private fun BookInfoFloatingTopBar(
    style: BookInfoComposeStyle,
    onBack: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        BookInfoTopIcon(
            iconRes = R.drawable.ic_back,
            contentDescription = stringResource(R.string.back),
            style = style,
            onClick = onBack
        )
        Spacer(modifier = Modifier.weight(1f))
        BookInfoTopIcon(
            iconRes = R.drawable.ic_more_vert,
            contentDescription = stringResource(R.string.more),
            style = style,
            onClick = onMore
        )
    }
}

@Composable
private fun BookInfoTopGradient(
    style: BookInfoComposeStyle,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(132.dp)
            .background(
                Brush.verticalGradient(
                    0f to style.colors.scrim.copy(alpha = 0.38f),
                    0.58f to style.colors.scrim.copy(alpha = 0.10f),
                    1f to Color.Transparent
                )
            )
    )
}

@Composable
private fun BookInfoTopIcon(
    iconRes: Int,
    contentDescription: String,
    style: BookInfoComposeStyle,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(style.colors.scrim.copy(alpha = 0.30f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun BookInfoCoverBackdrop(
    coverPath: String?,
    style: BookInfoComposeStyle,
    scrollOffset: Int,
    modifier: Modifier = Modifier
) {
    val blurRadius = (scrollOffset / 72f).coerceIn(0f, 10f).dp
    val imageDarkenAlpha = (0.15f + scrollOffset / 1800f).coerceIn(0.15f, 0.34f)
    val parallaxOffset = scrollOffset * 0.22f
    Box(modifier = modifier.background(style.colors.contentTop)) {
        BookInfoBackdropImage(
            path = coverPath,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationY = parallaxOffset
                    scaleX = 1.06f
                    scaleY = 1.06f
                }
                .blur(blurRadius)
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(style.colors.scrim.copy(alpha = imageDarkenAlpha))
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to style.colors.scrim.copy(alpha = 0.22f),
                        0.36f to style.colors.scrim.copy(alpha = 0.04f),
                        0.70f to style.colors.contentTop.copy(alpha = 0.62f),
                        0.90f to style.colors.contentTop.copy(alpha = 0.90f),
                        1f to style.colors.contentTop
                    )
                )
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookInfoPosterHero(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 232.dp)
            .padding(top = 22.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        BookCoverImage(
            path = state.coverPath,
            name = state.name,
            author = state.author,
            sourceOrigin = null,
            modifier = Modifier
                .width(126.dp)
                .aspectRatio(0.75f)
                .combinedClickable(
                    onClick = actions.onChangeCover,
                    onLongClick = actions.onPreviewCover
                ),
            style = CoverImageView.CoverStyle.DETAIL,
            fillBounds = true
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = state.name.ifBlank { stringResource(R.string.book_name) },
                color = Color.White,
                fontSize = MaterialTheme.typography.headlineMedium.fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = actions.onNameClick,
                    onLongClick = actions.onNameLongClick
                )
            )
            Text(
                text = state.author.ifBlank { stringResource(R.string.author) },
                color = Color.White.copy(alpha = 0.82f),
                fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.combinedClickable(
                    onClick = actions.onAuthorClick,
                    onLongClick = actions.onAuthorLongClick
                )
            )
            if (state.latestChapterTitle.isNotBlank()) {
                Text(
                    text = state.latestChapterTitle,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 12.5.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.readTimeText.isNotBlank()) {
                Text(
                    text = state.readTimeText,
                    color = Color.White.copy(alpha = 0.68f),
                    fontSize = 12.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (state.kinds.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.kinds.take(6).forEach { kind ->
                        BookInfoPosterChip(kind, style)
                    }
                }
            }
        }
    }
}

@Composable
private fun BookInfoPosterChip(
    text: String,
    style: BookInfoComposeStyle
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = MaterialTheme.typography.bodySmall.fontSize,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(style.colors.scrim.copy(alpha = 0.28f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    )
}

@Composable
private fun BookInfoContentPanel(
    style: BookInfoComposeStyle,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    0f to style.colors.contentTop,
                    0.24f to style.colors.contentBackground,
                    1f to style.colors.contentBackground
                )
            )
            .padding(top = 10.dp)
    ) {
        content()
    }
}

@Composable
private fun BookInfoIntroPanel(
    intro: String,
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle,
    webIntroExpandPages: Int
) {
    val displayIntro = intro.ifBlank { stringResource(R.string.intro_show_null) }
    val isWebIntro = intro.startsWith("<useweb>", ignoreCase = true)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = if (isWebIntro) 0.dp else 22.dp,
                end = if (isWebIntro) 0.dp else 22.dp,
                bottom = 12.dp
            ),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (isWebIntro) {
            BookInfoIntroContent(
                rawIntro = intro,
                state = state,
                actions = actions,
                style = style,
                webIntroExpandPages = webIntroExpandPages
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(style.metrics.panelRadius))
                    .background(style.colors.surface.copy(alpha = 0.28f))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                BookInfoIntroContent(
                    rawIntro = displayIntro,
                    state = state,
                    actions = actions,
                    style = style,
                    webIntroExpandPages = webIntroExpandPages
                )
            }
        }
    }
}

@Composable
private fun BookInfoBottomActions(
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to style.colors.contentBackground.copy(alpha = 0.90f),
                    1f to style.colors.contentBackground
                )
            )
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BookInfoActionButton(
                text = if (state.inBookshelf) {
                    stringResource(R.string.remove_from_bookshelf)
                } else {
                    stringResource(R.string.add_to_bookshelf)
                },
                primary = false,
                style = style,
                onClick = actions.onShelf,
                modifier = Modifier.weight(1f)
            )
            BookInfoActionButton(
                text = stringResource(R.string.reading),
                primary = true,
                style = style,
                onClick = actions.onRead,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BookInfoActionButton(
    text: String,
    primary: Boolean,
    style: BookInfoComposeStyle,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = if (primary) style.colors.accent else style.colors.accentContainer
    val textColor = if (primary) style.colors.actionText else style.colors.primaryText
    Box(
        modifier = modifier
            .height(52.dp)
            .shadow(if (primary) 7.dp else 4.dp, RoundedCornerShape(style.metrics.actionRadius), clip = false)
            .clip(RoundedCornerShape(style.metrics.actionRadius))
            .background(background)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BookInfoBackdropImage(
    path: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier.background(Color.Transparent),
        factory = {
            ImageView(it).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { imageView ->
            val target = path?.takeIf { it.isNotBlank() }
            val tag = target ?: R.drawable.image_cover_default
            if (imageView.tag != tag) {
                imageView.tag = tag
                if (target == null) {
                    ImageLoader.load(context, R.drawable.image_cover_default)
                        .centerCrop()
                        .into(imageView)
                } else {
                    val request = ImageLoader.load(context, target)
                        .error(R.drawable.image_cover_default)
                    val currentDrawable = imageView.drawable
                    if (currentDrawable != null) {
                        request.placeholder(currentDrawable)
                    } else {
                        request.placeholder(R.drawable.image_cover_default)
                    }
                    request.centerCrop().into(imageView)
                }
            }
        },
        onRelease = { it.releaseComposeImage() }
    )
}

@Composable
private fun BookInfoPreviewImage(
    path: String?,
    style: BookInfoComposeStyle,
    modifier: Modifier = Modifier
) {
    BookCoverImage(
        path = path,
        name = null,
        author = null,
        sourceOrigin = null,
        modifier = modifier,
        style = CoverImageView.CoverStyle.PREVIEW,
        fillBounds = true
    )
}

@Composable
private fun BookInfoRichIntro(
    rawIntro: String,
    style: BookInfoComposeStyle
) {
    val context = LocalContext.current
    val markwon = remember(context) {
        Markwon.builder(context)
            .usePlugin(HtmlPlugin.create())
            .build()
    }
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = {
            TextView(it).apply {
                includeFontPadding = true
                textSize = 13.5f
                setLineSpacing(4f, 1f)
                setTextIsSelectable(true)
            }
        },
        update = { textView ->
            textView.setTextColor(style.colors.primaryText.toArgb())
            if (textView.tag != rawIntro) {
                textView.tag = rawIntro
                when {
                    rawIntro.startsWith("<md>", ignoreCase = true) -> {
                        markwon.setMarkdown(textView, rawIntro.extractWrappedIntro(4))
                    }

                    rawIntro.startsWith("<usehtml>", ignoreCase = true) -> {
                        textView.text = HtmlCompat.fromHtml(
                            rawIntro.extractWrappedIntro(9),
                            HtmlCompat.FROM_HTML_MODE_LEGACY
                        )
                    }

                    else -> {
                        textView.text = rawIntro
                    }
                }
            }
        }
    )
}

@Composable
private fun BookInfoIntroContent(
    rawIntro: String,
    state: BookInfoUiState,
    actions: BookInfoActions,
    style: BookInfoComposeStyle,
    webIntroExpandPages: Int
) {
    if (rawIntro.startsWith("<useweb>", ignoreCase = true)) {
        BookInfoWebIntro(
            rawIntro = rawIntro,
            bookUrl = state.bookUrl,
            actions = actions,
            style = style,
            expandPages = webIntroExpandPages
        )
    } else {
        BookInfoRichIntro(rawIntro, style)
    }
}

@Composable
@SuppressLint("SetJavaScriptEnabled")
private fun BookInfoWebIntro(
    rawIntro: String,
    bookUrl: String,
    actions: BookInfoActions,
    style: BookInfoComposeStyle,
    expandPages: Int
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val pageHeight = configuration.screenHeightDp.dp.coerceAtLeast(520.dp)
    val html = remember(rawIntro) { rawIntro.extractWrappedIntro(8) }
    val baseUrl = remember(bookUrl) {
        bookUrl
            .takeIf { it.startsWith("http", ignoreCase = true) }
            ?.substringBefore(",")
    }
    val textColor = style.colors.primaryText.toCssHex()
    val themeCss = remember(style) {
        bookInfoUseWebThemeCss(style)
    }
    val transparentHtml = remember(html, textColor, themeCss) {
        """
            <html>
            <head>
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <style>
                html, body {
                  background: transparent !important;
                  color: $textColor;
                  margin: 0;
                  padding: 0;
                  font-size: 14px;
                  line-height: 1.72;
                  word-break: break-word;
                  -webkit-user-select: text !important;
                  user-select: text !important;
                }
                body * {
                  -webkit-user-select: text !important;
                  user-select: text !important;
                }
                img, video, iframe {
                  max-width: 100%;
                  height: auto;
                }
              </style>
            </head>
            <body>$html<style id="legado-book-info-theme">$themeCss</style></body>
            </html>
        """.trimIndent()
    }
    val loadKey = remember(baseUrl, transparentHtml) { "${baseUrl.orEmpty()}\n$transparentHtml" }
    var contentHeightPx by remember(loadKey) { mutableStateOf(0) }
    val webHeight = remember(contentHeightPx, expandPages, density, pageHeight) {
        if (contentHeightPx > 0) {
            val contentHeight = with(density) { contentHeightPx.toDp() }
            val requestedHeight = pageHeight * expandPages.coerceAtLeast(1).toFloat()
            if (contentHeight < requestedHeight) {
                contentHeight.coerceAtLeast(1.dp)
            } else {
                requestedHeight
            }
        } else {
            pageHeight
        }
    }
    val loadToken = remember { AtomicLong(0L) }
    val webContainer = remember(context) {
        FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
    val webView = remember(context) {
        WebView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            settings.apply {
                javaScriptEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                domStorageEnabled = true
                loadsImagesAutomatically = true
                blockNetworkImage = false
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(true)
                mediaPlaybackRequiresUserGesture = false
                allowContentAccess = true
                builtInZoomControls = false
                displayZoomControls = false
                textZoom = 100
            }
            BookInfoUseWebHost.configure(this)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            isLongClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
    }
    DisposableEffect(webContainer, webView) {
        onDispose {
            loadToken.set(Long.MIN_VALUE)
            BookInfoUseWebHost.clearPopups(webContainer)
            webView.webChromeClient = null
            webView.webViewClient = WebViewClient()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webContainer.removeAllViews()
            webView.stopLoading()
            webView.destroy()
        }
    }
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(webHeight),
        factory = {
            webContainer.apply {
                (parent as? ViewGroup)?.removeView(this)
                if (webView.parent !== this) {
                    (webView.parent as? ViewGroup)?.removeView(webView)
                    addView(
                        webView,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                webView.setTag(R.id.tag, null)
                webView.onResume()
                actions.onSetupWebIntro(webView)
                BookInfoUseWebHost.attachPopupSupport(
                    container = this,
                    webView = webView,
                    configurePopupWebView = actions.onSetupWebIntro
                )
            }
        },
        update = { container ->
            if (webView.parent !== container) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                container.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
            actions.onSetupWebIntro(webView)
            BookInfoUseWebHost.attachPopupSupport(
                container = container,
                webView = webView,
                configurePopupWebView = actions.onSetupWebIntro
            )
            webView.webViewClient = BookInfoIntroWebViewClient(
                context = context,
                currentToken = { loadToken.get() },
                isTokenActive = { token -> token > 0L && loadToken.get() == token }
            ) { token, heightPx ->
                if (loadToken.get() == token && kotlin.math.abs(heightPx - contentHeightPx) > 8) {
                    contentHeightPx = heightPx
                }
            }
            webView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            webView.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    val token = loadToken.get()
                    scheduleBookInfoWebIntroHeightMeasure(
                        webView = webView,
                        token = token,
                        isTokenActive = { activeToken -> activeToken > 0L && loadToken.get() == activeToken },
                        delays = longArrayOf(120L, 360L, 720L)
                    ) { activeToken, heightPx ->
                        if (loadToken.get() == activeToken && kotlin.math.abs(heightPx - contentHeightPx) > 8) {
                            contentHeightPx = heightPx
                        }
                    }
                }
                false
            }
            if (webView.getTag(R.id.tag) != loadKey) {
                val token = loadToken.incrementAndGet()
                webView.setTag(R.id.tag, loadKey)
                webView.stopLoading()
                webView.loadDataWithBaseURL(
                    baseUrl,
                    transparentHtml,
                    "text/html",
                    "utf-8",
                    baseUrl
                )
                webView.post {
                    if (token > 0L && loadToken.get() == token) {
                        webView.requestLayout()
                        webView.invalidate()
                    }
                }
            }
        }
    )
    LaunchedEffect(loadKey, webView) {
        val token = loadToken.get()
        scheduleBookInfoWebIntroHeightMeasure(
            webView = webView,
            token = token,
            isTokenActive = { activeToken -> activeToken > 0L && loadToken.get() == activeToken },
            delays = longArrayOf(300L, 900L)
        ) { activeToken, heightPx ->
            if (loadToken.get() == activeToken && kotlin.math.abs(heightPx - contentHeightPx) > 8) {
                contentHeightPx = heightPx
            }
        }
    }
}

private class BookInfoIntroWebViewClient(
    private val context: Context,
    private val currentToken: () -> Long,
    private val isTokenActive: (Long) -> Boolean,
    private val onContentHeight: (Long, Int) -> Unit
) : WebViewClient() {
    private val jsStr = getInjectionString

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val uri = request?.url ?: return true
        return when (uri.scheme) {
            "http", "https" -> false
            "legado", "yuedu" -> {
                context.startActivity(Intent(context, OnLineImportActivity::class.java).apply {
                    data = uri
                })
                true
            }

            else -> {
                context.openUrl(uri)
                true
            }
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        val token = currentToken()
        if (isTokenActive(token)) {
            runCatching { view?.evaluateJavascript(jsStr, null) }
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view ?: return
        val token = currentToken()
        if (!isTokenActive(token)) return
        runCatching { view.evaluateJavascript(jsStr, null) }
        scheduleBookInfoWebIntroHeightMeasure(
            webView = view,
            token = token,
            isTokenActive = isTokenActive,
            delays = longArrayOf(0L, 120L, 360L, 720L, 1200L),
            onContentHeight = onContentHeight
        )
    }
}

private fun scheduleBookInfoWebIntroHeightMeasure(
    webView: WebView,
    token: Long,
    isTokenActive: (Long) -> Boolean,
    delays: LongArray,
    onContentHeight: (Long, Int) -> Unit
) {
    if (!isTokenActive(token)) return
    delays.forEach { delayMillis ->
        webView.postDelayed({
            if (!isTokenActive(token) || webView.handler == null || !webView.isAttachedToWindow) {
                return@postDelayed
            }
            measureBookInfoWebIntroHeight(webView, token, isTokenActive, onContentHeight)
        }, delayMillis)
    }
}

private fun measureBookInfoWebIntroHeight(
    webView: WebView,
    token: Long,
    isTokenActive: (Long) -> Boolean,
    onContentHeight: (Long, Int) -> Unit
) {
    if (!isTokenActive(token)) return
    runCatching {
        webView.evaluateJavascript(
            """
                (function() {
                  var body = document.body;
                  var doc = document.documentElement;
                  var contentBottom = 0;
                  if (body) {
                    Array.prototype.forEach.call(body.children || [], function(el) {
                      var style = window.getComputedStyle ? window.getComputedStyle(el) : null;
                      if (style && (style.display === 'none' || style.visibility === 'hidden')) return;
                      var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
                      if (!rect) return;
                      contentBottom = Math.max(contentBottom, rect.bottom + window.pageYOffset);
                    });
                  }
                  var documentHeight = Math.max(
                    body ? body.scrollHeight || 0 : 0,
                    body ? body.offsetHeight || 0 : 0,
                    doc ? doc.clientHeight || 0 : 0,
                    doc ? doc.scrollHeight || 0 : 0,
                    doc ? doc.offsetHeight || 0 : 0
                  );
                  return contentBottom > 1 ? contentBottom : documentHeight;
                })();
            """.trimIndent()
        ) { result ->
            if (!isTokenActive(token)) return@evaluateJavascript
            val cssHeight = result
                ?.trim()
                ?.trim('"')
                ?.toFloatOrNull()
                ?: 0f
            val density = webView.resources.displayMetrics.density
            val jsHeightPx = (cssHeight * density).roundToInt()
            val fallbackHeightPx = (webView.contentHeight * density).roundToInt()
            val heightPx = when {
                jsHeightPx > 1 -> jsHeightPx
                fallbackHeightPx > 1 -> fallbackHeightPx
                else -> return@evaluateJavascript
            }
            onContentHeight(token, heightPx)
        }
    }
}

private fun Color.toCssHex(): String {
    return "#%06X".format(0xFFFFFF and toArgb())
}

private fun Color.toCssRgba(alpha: Float = this.alpha): String {
    val color = copy(alpha = alpha.coerceIn(0f, 1f)).toArgb()
    val red = android.graphics.Color.red(color)
    val green = android.graphics.Color.green(color)
    val blue = android.graphics.Color.blue(color)
    val finalAlpha = android.graphics.Color.alpha(color) / 255f
    return "rgba($red,$green,$blue,${String.format(Locale.US, "%.3f", finalAlpha)})"
}

private fun bookInfoUseWebThemeCss(style: BookInfoComposeStyle): String {
    val night = AppConfig.isNightTheme
    val pageBackground = "transparent"
    val cardBackground = style.colors.surface.copy(alpha = if (night) 0.92f else 0.86f).toCssRgba()
    val subtleBackground = style.colors.surfaceVariant.copy(alpha = if (night) 0.82f else 0.72f).toCssRgba()
    val primaryText = style.colors.primaryText.toCssHex()
    val secondaryText = style.colors.secondaryText.toCssHex()
    val mutedText = style.colors.secondaryText.copy(alpha = if (night) 0.78f else 0.86f).toCssRgba()
    val border = style.colors.accentContainer.copy(alpha = if (night) 0.42f else 0.48f).toCssRgba()
    val divider = style.colors.accentContainer.copy(alpha = if (night) 0.28f else 0.34f).toCssRgba()
    val accent = style.colors.accent.toCssHex()
    val accentSoft = style.colors.accent.copy(alpha = if (night) 0.18f else 0.14f).toCssRgba()
    val cardShadow = if (night) {
        "0 14px 30px rgba(0,0,0,0.22)"
    } else {
        "0 10px 24px rgba(40,48,64,0.10)"
    }
    val hoverShadow = if (night) {
        "0 18px 36px rgba(0,0,0,0.28)"
    } else {
        "0 14px 30px rgba(40,48,64,0.14)"
    }
    val scheme = if (night) "dark" else "light"
    return """
        :root, body {
          color-scheme: $scheme !important;
          --bg-body: $pageBackground !important;
          --bg-soft: $subtleBackground !important;
          --card-bg: $cardBackground !important;
          --text-primary: $primaryText !important;
          --text-secondary: $secondaryText !important;
          --text-muted: $mutedText !important;
          --border-light: $border !important;
          --border-divider: $divider !important;
          --accent: $accent !important;
          --accent-soft: $accentSoft !important;
          --shadow-sm: $cardShadow !important;
          --shadow-md: $hoverShadow !important;
        }
        html, body {
          background: transparent !important;
        }
    """.trimIndent()
}

private suspend fun loadCoverThemeColor(context: Context, coverPath: String?): Int? {
    if (coverPath.isNullOrBlank()) return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = ImageLoader.loadBitmap(context, coverPath)
                .submit(48, 72)
                .get()
            bitmap.extractThemeColor()
        }.getOrNull()
    }
}

private fun Bitmap.extractThemeColor(): Int? {
    if (width <= 0 || height <= 0) return null
    var red = 0L
    var green = 0L
    var blue = 0L
    var weightSum = 0L
    val stepX = (width / 12).coerceAtLeast(1)
    val stepY = (height / 16).coerceAtLeast(1)
    val centerLeft = width * 0.18f
    val centerRight = width * 0.82f
    val centerTop = height * 0.12f
    val centerBottom = height * 0.88f
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = getPixel(x, y)
            val alpha = android.graphics.Color.alpha(pixel)
            val r = android.graphics.Color.red(pixel)
            val g = android.graphics.Color.green(pixel)
            val b = android.graphics.Color.blue(pixel)
            val max = maxOf(r, g, b)
            val min = minOf(r, g, b)
            val brightness = (r + g + b) / 3
            val chroma = max - min
            if (alpha > 180 && brightness in 34..232 && chroma > 12) {
                val centerWeight = if (
                    x in centerLeft.toInt()..centerRight.toInt() &&
                    y in centerTop.toInt()..centerBottom.toInt()
                ) 3L else 1L
                val colorWeight = centerWeight * (1L + chroma / 36L)
                red += r * colorWeight
                green += g * colorWeight
                blue += b * colorWeight
                weightSum += colorWeight
            }
            x += stepX
        }
        y += stepY
    }
    if (weightSum <= 0L) return null
    val avg = android.graphics.Color.rgb(
        (red / weightSum).toInt().coerceIn(0, 255),
        (green / weightSum).toInt().coerceIn(0, 255),
        (blue / weightSum).toInt().coerceIn(0, 255)
    )
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(avg, hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.28f).coerceAtMost(0.72f)
    hsv[2] = hsv[2].coerceIn(0.34f, 0.76f)
    return android.graphics.Color.HSVToColor(hsv)
}

private fun String.cleanBookInfoValue(): String {
    return substringAfter("：")
        .substringAfter(":")
        .trim()
        .ifBlank { this }
}

private fun String.extractWrappedIntro(prefixLength: Int): String {
    val endIndex = lastIndexOf("<").takeIf { it > prefixLength } ?: length
    return substring(prefixLength.coerceAtMost(length), endIndex.coerceAtLeast(prefixLength).coerceAtMost(length))
}
