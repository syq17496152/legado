package io.legado.app.ui.book.info.compose

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.book.BookCloudEntryMode
import io.legado.app.help.book.BookHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.BookInfoComponentConfig
import io.legado.app.help.config.BookInfoComponentType
import io.legado.app.ui.book.toc.TocChapterRow
import io.legado.app.ui.book.toc.TocVolumeHeaderRow
import io.legado.app.ui.book.toc.collapsedVolumeIndexesFor
import io.legado.app.ui.book.toc.isChapterCached
import io.legado.app.ui.book.toc.visibleChapters
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.subtitleLargeX
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppShapes
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TagChip
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.BookCoverImage
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.image.CoverImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 书籍详情页「现代样式」（book-info-modern-compose）。
 *
 * 定位（design §1.1）：**内容效率型**——卡片化组件块竖向顺序渲染 + 简介/目录一体化 +
 * 目录面板，与沉浸式（视觉冲击型）是定位差异而非优劣替代。
 *
 * 结构约束：
 * - 组件块顺序与显隐由 [BookInfoComponentConfig] 驱动（本项目内**唯一**消费方，AD-03）；
 * - 取色一律走 `rememberAppManagementPalette()` 与全站组件族，零硬编码色号（AD-07）；
 * - 封面取色主导（用户 2026-09-22 裁决）：封面主色只作点缀，正文文字一律主题文本色，
 *   对比度不达标时自动降级为纯装饰并回退 `palette.settings.accent`（design §1.2 可读性兜底）。
 */
@Composable
fun BookInfoModernRoute(
    state: BookInfoUiState,
    actions: BookInfoActions,
    book: Book? = null,
    chapters: List<BookChapter> = emptyList(),
    extraMenuActions: List<MenuAction> = emptyList(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val palette = rememberAppManagementPalette()
    // 组件块序列：读配置 → 过滤未启用（顺序即渲染顺序）
    val blocks = remember { BookInfoComponentConfig.load().filter { it.enabled } }
    var coverColor by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(state.coverPath) {
        coverColor = loadCoverThemeColor(context, state.coverPath)
    }
    val accents = remember(coverColor, palette.settings.page, palette.settings.accent) {
        modernAccents(coverColor, palette.settings.page, palette.settings.accent)
    }
    var detailTab by remember { mutableStateOf(ModernDetailTab.INTRO) }
    var showTopMenu by remember { mutableStateOf(false) }
    val pageScrollState = rememberScrollState()
    // 下拉刷新仅在最顶部启用（避免与内容滚动抢手势），与沉浸式同口径
    val refreshAtTop by remember {
        androidx.compose.runtime.derivedStateOf { pageScrollState.value == 0 }
    }
    LaunchedEffect(refreshAtTop) {
        actions.onRefreshEnabledChanged(refreshAtTop)
    }
    val menuActions = modernMenuActions(
        state = state,
        actions = actions,
        extraMenuActions = extraMenuActions,
        onDismiss = { showTopMenu = false }
    )

    Scaffold(
        modifier = modifier,
        containerColor = palette.settings.page,
        topBar = {
            GlassTopAppBar(
                // design §1.2：顶栏标题固定为「书籍信息」，书名由 HEADER 卡片承载
                title = stringResource(R.string.book_info),
                navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                onNavClick = actions.onBack,
                actions = {
                    Box {
                        IconButton(onClick = { showTopMenu = true }) {
                            Icon(
                                // 不指定 tint：跟随顶栏 LocalContentColor（对比度由 GlassTopAppBar 统一推导）
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.more_menu)
                            )
                        }
                        AppDropdownMenu(
                            expanded = showTopMenu,
                            onDismiss = { showTopMenu = false },
                            actions = menuActions
                        )
                    }
                }
            )
        },
        bottomBar = {
            ModernBottomBar(state = state, actions = actions, palette = palette, accents = accents)
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(pageScrollState)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                blocks.forEach { item ->
                    when (item.type) {
                        BookInfoComponentType.HEADER -> ModernHeaderCard(
                            state = state,
                            actions = actions,
                            palette = palette,
                            accents = accents
                        )

                        BookInfoComponentType.META -> ModernMetaCard(
                            state = state,
                            actions = actions,
                            palette = palette,
                            accents = accents
                        )

                        BookInfoComponentType.DETAIL -> ModernDetailCard(
                            state = state,
                            actions = actions,
                            palette = palette,
                            accents = accents,
                            selectedTab = detailTab,
                            onTabChange = { detailTab = it }
                        )

                        BookInfoComponentType.CATALOG -> ModernCatalogCard(
                            state = state,
                            book = book,
                            chapters = chapters,
                            actions = actions,
                            palette = palette,
                            accents = accents
                        )

                        // 无图时不渲染整块（避免空占位）
                        BookInfoComponentType.AI_IMAGES -> if (state.aiImagePaths.isNotEmpty()) {
                            ModernAiImagesCard(
                                state = state,
                                actions = actions,
                                palette = palette,
                                accents = accents
                            )
                        }

                        // ACTIONS 由底部固定操作栏承载，不作为滚动块渲染
                        BookInfoComponentType.ACTIONS -> Unit
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
            if (state.loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                    color = accents.accent,
                    trackColor = Color.Transparent
                )
            }
        }
    }
}

/** 详情区分段页签（简介 / 目录） */
private enum class ModernDetailTab { INTRO, TOC }

/**
 * 现代样式点缀色（封面取色主导 + 可读性兜底，design §1.2）。
 *
 * @property accent 可用于强调（文字/图标/强调块）的颜色，对比度达标
 * @property decoration 仅作装饰（光晕/描边/薄染底）的颜色，允许低对比
 */
private data class ModernAccents(
    val accent: Color,
    val decoration: Color
)

/** 正文与非强调元素的固定对比度门槛（design §1.2 可读性兜底） */
private const val ModernMinContrast = 4.5

private fun modernAccents(coverColor: Int?, page: Color, fallbackAccent: Color): ModernAccents {
    if (coverColor == null) {
        return ModernAccents(fallbackAccent, fallbackAccent.copy(alpha = 0.22f))
    }
    // 明度归一：浅色主题取封面主色的深阶、深色主题取其亮阶，避免与页面底色同明度
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(coverColor, hsv)
    hsv[1] = hsv[1].coerceAtLeast(0.32f)
    hsv[2] = if (AppConfig.isNightTheme) 0.82f else 0.44f
    val normalized = Color(android.graphics.Color.HSVToColor(hsv))
    val contrast = androidx.core.graphics.ColorUtils.calculateContrast(
        normalized.toArgb(),
        page.toArgb()
    )
    return if (contrast >= ModernMinContrast) {
        ModernAccents(normalized, normalized.copy(alpha = 0.22f))
    } else {
        // 对比度不足：封面色降级为纯装饰，强调回退主题强调色
        ModernAccents(fallbackAccent, normalized.copy(alpha = 0.22f))
    }
}

/**
 * 顶栏溢出菜单全集：对齐经典页 `buildMenuActions` 清单
 * （source 变量类 / 复制类 / 云端入口 / 自定义按钮），封面/切源类动作由卡片承载。
 */
@Composable
private fun modernMenuActions(
    state: BookInfoUiState,
    actions: BookInfoActions,
    extraMenuActions: List<MenuAction>,
    onDismiss: () -> Unit
): List<MenuAction> {
    val dismiss: (() -> Unit) -> () -> Unit = { action ->
        {
            onDismiss()
            action()
        }
    }
    return buildList {
        if (state.hasCustomButton) {
            add(MenuAction(title = stringResource(R.string.custom_button), onClick = dismiss(actions.onCustomButton)))
        }
        if (state.inBookshelf) {
            add(MenuAction(title = stringResource(R.string.book_info_edit), onClick = dismiss(actions.onEditBookInfo)))
        }
        addAll(extraMenuActions)
        add(MenuAction(title = stringResource(R.string.refresh), onClick = dismiss(actions.onRefresh)))
        if (state.hasSourceLogin) {
            add(MenuAction(title = stringResource(R.string.login), onClick = dismiss(actions.onLogin)))
        }
        if (state.hasBookSource) {
            add(MenuAction(title = stringResource(R.string.change_source), onClick = dismiss(actions.onChangeSource)))
            add(MenuAction(title = stringResource(R.string.set_source_variable), onClick = dismiss(actions.onSetSourceVariable)))
            add(MenuAction(title = stringResource(R.string.set_book_variable), onClick = dismiss(actions.onSetBookVariable)))
            add(
                MenuAction(
                    title = stringResource(R.string.allow_update),
                    checked = state.canUpdate,
                    onClick = { actions.onAllowUpdateChanged(!state.canUpdate) }
                )
            )
        }
        add(MenuAction(title = stringResource(R.string.copy_book_url), onClick = dismiss(actions.onCopyBookUrl)))
        add(MenuAction(title = stringResource(R.string.copy_toc_url), onClick = dismiss(actions.onCopyTocUrl)))
        add(
            MenuAction(
                title = stringResource(R.string.book_cloud_cache_package_mode),
                checked = state.cloudEntryMode == BookCloudEntryMode.CACHE_PACKAGE,
                onClick = dismiss(actions.onCloudBackup)
            )
        )
        add(
            MenuAction(
                title = stringResource(R.string.book_cloud_library_chapter_mode),
                checked = state.cloudEntryMode == BookCloudEntryMode.LIBRARY_CHAPTER,
                onClick = dismiss(actions.onOpenLibraryContainer)
            )
        )
        add(
            MenuAction(
                title = stringResource(R.string.clear_cache),
                tint = androidx.compose.material3.MaterialTheme.colorScheme.error,
                onClick = dismiss(actions.onClearCache)
            )
        )
    }
}

/** HEADER 块：封面 + 书名 + 作者 + 最新章节 + 类型标签 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModernHeaderCard(
    state: BookInfoUiState,
    actions: BookInfoActions,
    palette: AppManagementPalette,
    accents: ModernAccents
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            BookCoverImage(
                path = state.coverPath,
                name = state.name,
                author = state.author,
                sourceOrigin = null,
                modifier = Modifier
                    .width(92.dp)
                    .aspectRatio(0.75f)
                    .clip(AppShapes.Card)
                    .combinedClickable(
                        onClick = actions.onChangeCover,
                        onLongClick = actions.onPreviewCover
                    ),
                style = CoverImageView.CoverStyle.DETAIL,
                fillBounds = true
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = state.name.ifBlank { stringResource(R.string.book_name) },
                    color = palette.settings.primaryText,
                    fontSize = MaterialTheme.typography.subtitleLargeX.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = palette.settings.titleFontFamily,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.combinedClickable(
                        onClick = actions.onNameClick,
                        onLongClick = actions.onNameLongClick
                    )
                )
                Text(
                    text = state.author.ifBlank { stringResource(R.string.author) },
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.combinedClickable(
                        onClick = actions.onAuthorClick,
                        onLongClick = actions.onAuthorLongClick
                    )
                )
                if (state.latestChapterTitle.isNotBlank()) {
                    // 未读口径与沉浸式一致：已读位置之后的章节数，从未读过则不显示徽标
                    val newChapterCount = if (state.currentChapterIndex >= 0) {
                        (state.chapterCount - 1 - state.currentChapterIndex).coerceAtLeast(0)
                    } else {
                        0
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = actions.onOpenLatestChapter,
                                onLongClick = actions.onOpenToc
                            )
                    ) {
                        Text(
                            text = state.latestChapterTitle,
                            color = palette.settings.secondaryText,
                            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (newChapterCount > 0) {
                            Text(
                                text = stringResource(
                                    R.string.book_info_new_chapters_badge,
                                    newChapterCount
                                ),
                                color = palette.settings.onAccent,
                                fontSize = MaterialTheme.typography.labelSmall.fontSize,
                                modifier = Modifier
                                    .clip(AppShapes.Capsule)
                                    .background(palette.settings.danger)
                                    .padding(horizontal = 6.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
                if (state.kinds.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        state.kinds.take(6).forEach { kind ->
                            TagChip(
                                text = kind,
                                color = accents.accent.copy(alpha = 0.14f),
                                contentColor = accents.accent
                            )
                        }
                    }
                }
            }
        }
    }
}

/** META 块：分组 / 来源 / 目录入口 */
@Composable
private fun ModernMetaCard(
    state: BookInfoUiState,
    actions: BookInfoActions,
    palette: AppManagementPalette,
    accents: ModernAccents
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
    ) {
        ModernMetaRow(
            iconRes = R.drawable.ic_groups,
            label = stringResource(R.string.group),
            value = state.groupText,
            actionText = stringResource(R.string.group_select),
            palette = palette,
            accents = accents,
            onAction = actions.onChangeGroup
        )
        ModernMetaDivider(palette)
        ModernMetaRow(
            iconRes = R.drawable.ic_web_outline,
            label = stringResource(R.string.book_source),
            value = state.originName,
            actionText = stringResource(R.string.change_source),
            palette = palette,
            accents = accents,
            onAction = actions.onChangeSource
        )
        ModernMetaDivider(palette)
        ModernMetaRow(
            iconRes = R.drawable.ic_toc,
            label = stringResource(R.string.book_info_tab_toc),
            value = state.tocText,
            actionText = stringResource(R.string.view_toc),
            palette = palette,
            accents = accents,
            onAction = actions.onOpenToc
        )
    }
}

@Composable
private fun ModernMetaRow(
    iconRes: Int,
    label: String,
    value: String,
    actionText: String,
    palette: AppManagementPalette,
    accents: ModernAccents,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // heightIn(min) 对齐管理族行高基线，大字号缩放下不挤压
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = accents.accent,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = palette.settings.secondaryText,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = value,
            color = palette.settings.primaryText,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = actionText,
            color = accents.accent,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            maxLines = 1,
            modifier = Modifier
                .clip(AppShapes.Button)
                .clickable(onClick = onAction)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun ModernMetaDivider(palette: AppManagementPalette) {
    HorizontalDivider(
        color = palette.settings.divider,
        thickness = 0.5.dp
    )
}

/** DETAIL 块：简介 / 目录分段切换 + 目录预览 */
@Composable
private fun ModernDetailCard(
    state: BookInfoUiState,
    actions: BookInfoActions,
    palette: AppManagementPalette,
    accents: ModernAccents,
    selectedTab: ModernDetailTab,
    onTabChange: (ModernDetailTab) -> Unit
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(14.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModernSegment(
                text = stringResource(R.string.book_intro),
                selected = selectedTab == ModernDetailTab.INTRO,
                palette = palette,
                accents = accents,
                onClick = { onTabChange(ModernDetailTab.INTRO) },
                modifier = Modifier.weight(1f)
            )
            ModernSegment(
                text = stringResource(R.string.book_info_tab_toc),
                selected = selectedTab == ModernDetailTab.TOC,
                palette = palette,
                accents = accents,
                onClick = { onTabChange(ModernDetailTab.TOC) },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        when (selectedTab) {
            // 空简介不留空白区：给出一句占位说明而非空面板
            ModernDetailTab.INTRO -> if (state.intro.isBlank()) {
                Text(
                    text = stringResource(R.string.book_info_intro_empty),
                    color = palette.settings.secondaryText,
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize
                )
            } else {
                ModernIntroContent(
                    rawIntro = state.intro,
                    state = state,
                    actions = actions,
                    palette = palette
                )
            }

            ModernDetailTab.TOC -> {
                if (state.currentChapterPreview.isEmpty()) {
                    Text(
                        text = stringResource(R.string.error_load_toc),
                        color = palette.settings.secondaryText,
                        fontSize = MaterialTheme.typography.bodySecondary.fontSize
                    )
                } else {
                    state.currentChapterPreview.forEach { chapter ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 34.dp)
                                .clickable { actions.onOpenChapter(chapter) },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = chapter.title,
                                color = if (chapter.index == state.currentChapterIndex) {
                                    accents.accent
                                } else {
                                    palette.settings.primaryText
                                },
                                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                                fontWeight = if (chapter.index == state.currentChapterIndex) {
                                    FontWeight.SemiBold
                                } else {
                                    FontWeight.Normal
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = actions.onOpenToc,
                        shape = AppShapes.Button,
                        border = androidx.compose.foundation.BorderStroke(1.dp, accents.accent),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = accents.accent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = stringResource(R.string.view_toc),
                            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernSegment(
    text: String,
    selected: Boolean,
    palette: AppManagementPalette,
    accents: ModernAccents,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .heightIn(min = 38.dp)
            .clip(AppShapes.Button)
            .background(
                if (selected) accents.accent.copy(alpha = 0.14f) else Color(palette.settings.rowPressed)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) accents.accent else palette.settings.secondaryText,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 简介渲染四分支：`<useweb>` 复用沉浸式的 WebView 实现；`<usehtml>` 走 HTML；
 * `<md>` 走 Markwon（**补齐图片与表格插件**，AD-04）；其余按纯文本。
 */
@Composable
private fun ModernIntroContent(
    rawIntro: String,
    state: BookInfoUiState,
    actions: BookInfoActions,
    palette: AppManagementPalette
) {
    val context = LocalContext.current
    if (rawIntro.startsWith("<useweb>", ignoreCase = true)) {
        // 复用沉浸式 WebView 简介（含 JS 测高与主题 CSS 注入），仅可见性由 private 提升为 internal
        BookInfoWebIntro(
            rawIntro = rawIntro,
            bookUrl = state.bookUrl,
            actions = actions,
            style = bookInfoComposeStyle(context, null),
            expandPages = 2
        )
        return
    }
    // 简介图片按屏宽限制解码尺寸（同经典页口径），避免大图 OOM
    val imageWidthPx = remember(context) {
        (context.resources.displayMetrics.widthPixels - 120).coerceAtLeast(240)
    }
    val markwon = remember(context, imageWidthPx) {
        io.noties.markwon.Markwon.builder(context)
            .usePlugin(
                io.noties.markwon.image.glide.GlideImagesPlugin.create(
                    com.bumptech.glide.Glide.with(context)
                        .applyDefaultRequestOptions(
                            com.bumptech.glide.request.RequestOptions()
                                .override(imageWidthPx)
                                .encodeQuality(88)
                        )
                )
            )
            .usePlugin(io.noties.markwon.html.HtmlPlugin.create())
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context))
            .build()
    }
    val textColor = palette.settings.primaryText
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                includeFontPadding = true
                textSize = 14f
                setLineSpacing(4f, 1f)
                setTextIsSelectable(true)
            }
        },
        update = { textView ->
            textView.setTextColor(textColor.toArgb())
            if (textView.tag != rawIntro) {
                textView.tag = rawIntro
                when {
                    rawIntro.startsWith("<md>", ignoreCase = true) -> {
                        markwon.setMarkdown(textView, rawIntro.substring(4))
                    }

                    rawIntro.startsWith("<usehtml>", ignoreCase = true) -> {
                        textView.text = androidx.core.text.HtmlCompat.fromHtml(
                            rawIntro.substring(9),
                            androidx.core.text.HtmlCompat.FROM_HTML_MODE_LEGACY
                        )
                    }

                    else -> textView.text = rawIntro
                }
            }
        }
    )
}

/** CATALOG 块：目录面板（搜索 + 页码 + 目录行复用目录页既有实现） */
@Composable
private fun ModernCatalogCard(
    state: BookInfoUiState,
    book: Book?,
    chapters: List<BookChapter>,
    actions: BookInfoActions,
    palette: AppManagementPalette,
    accents: ModernAccents
) {
    var query by remember { mutableStateOf("") }
    val matched = remember(chapters, query) {
        if (query.isBlank()) {
            chapters
        } else {
            chapters.filter { it.title.contains(query, ignoreCase = true) }
        }
    }
    // 卷折叠：初始折叠态与目录页同口径（仅当前卷展开）；折叠过滤复用目录页既有实现
    var collapsedVolumes by remember(chapters) {
        mutableStateOf(collapsedVolumeIndexesFor(chapters, state.currentChapterIndex))
    }
    val filtered = remember(matched, query, collapsedVolumes) {
        visibleChapters(matched, query, collapsedVolumes)
    }
    val cacheFiles by produceState<Set<String>>(
        initialValue = emptySet(),
        book,
        chapters
    ) {
        value = if (book == null) {
            emptySet()
        } else {
            withContext(Dispatchers.IO) { BookHelp.getChapterFiles(book) }
        }
    }
    // 缓存状态判定复用目录页既有实现；仅对过滤后列表计算，口径与目录页一致
    val cacheMap by produceState<Map<String, Boolean>>(
        initialValue = emptyMap(),
        filtered,
        cacheFiles
    ) {
        val snapshot = filtered
        val files = cacheFiles
        value = if (snapshot.isEmpty()) {
            emptyMap()
        } else {
            withContext(Dispatchers.IO) {
                snapshot.associate { it.primaryStr() to isChapterCached(book, it, files) }
            }
        }
    }
    val listState = rememberLazyListState()
    val currentPosition = remember(filtered, state.currentChapterIndex) {
        filtered.indexOfFirst { it.index == state.currentChapterIndex }.takeIf { it >= 0 } ?: 0
    }
    LaunchedEffect(filtered) {
        if (filtered.isNotEmpty() && query.isBlank()) {
            listState.scrollToItem(currentPosition.coerceIn(0, filtered.lastIndex))
        }
    }
    val hasVolumes = remember(filtered) { filtered.any { it.isVolume } }

    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.book_info_tab_toc),
                color = palette.settings.primaryText,
                fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.settings.titleFontFamily,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(
                    R.string.book_info_catalog_page,
                    (currentPosition + 1).coerceAtMost(filtered.size.coerceAtLeast(1)),
                    filtered.size
                ),
                color = accents.accent,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        androidx.compose.material3.OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp),
            placeholder = {
                Text(
                    text = stringResource(R.string.search),
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize
                )
            },
            singleLine = true,
            shape = AppShapes.Search
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (filtered.isEmpty()) {
            Text(
                text = stringResource(R.string.error_load_toc),
                color = palette.settings.secondaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize
            )
        } else {
            // 固定高度：外层为 verticalScroll，嵌套列表必须限高（避免无限高约束崩溃）
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
            ) {
                itemsIndexed(
                    items = filtered,
                    key = { _, chapter -> "${chapter.bookUrl}|${chapter.index}|${chapter.url}" }
                ) { _, chapter ->
                    if (chapter.isVolume) {
                        TocVolumeHeaderRow(
                            palette = palette,
                            // 简化说明：目录面板用原始章节名，标题替换规则由完整目录页承担
                            title = chapter.title,
                            collapsed = collapsedVolumes.contains(chapter.index),
                            onClick = {
                                collapsedVolumes = if (collapsedVolumes.contains(chapter.index)) {
                                    collapsedVolumes - chapter.index
                                } else {
                                    collapsedVolumes + chapter.index
                                }
                            }
                        )
                    } else {
                        TocChapterRow(
                            palette = palette,
                            book = book,
                            chapter = chapter,
                            title = chapter.title,
                            cached = cacheMap[chapter.primaryStr()] ?: true,
                            countWords = AppConfig.tocCountWords,
                            indented = hasVolumes,
                            isNew = book != null &&
                                chapter.index > book.durChapterIndex &&
                                chapter.index <= book.lastChapterIndex,
                            onClick = {
                                actions.onOpenChapter(
                                    BookInfoChapterUi(chapter.index, chapter.title, chapter.isVolume)
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/** AI_IMAGES 块：本书图库入口与缩略图（无图时整块由调用方跳过） */
@Composable
private fun ModernAiImagesCard(
    state: BookInfoUiState,
    actions: BookInfoActions,
    palette: AppManagementPalette,
    accents: ModernAccents
) {
    AppManagementCard(
        palette = palette,
        modifier = Modifier.fillMaxWidth(),
        insidePadding = PaddingValues(14.dp),
        onClick = actions.onOpenAiGallery
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.book_info_component_ai_images),
                color = palette.settings.primaryText,
                fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.settings.titleFontFamily,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = state.aiImageCount.toString(),
                color = accents.accent,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                maxLines = 1
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            state.aiImagePaths.forEach { path ->
                BookCoverImage(
                    path = path,
                    name = state.name,
                    author = state.author,
                    sourceOrigin = null,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(AppShapes.Card),
                    style = CoverImageView.CoverStyle.PREVIEW,
                    forcePath = true,
                    fillBounds = true
                )
            }
        }
    }
}

/** 底部操作栏：移出/加入书架 + 阅读（高度与内边距对齐经典页现有底栏规格） */
@Composable
private fun ModernBottomBar(
    state: BookInfoUiState,
    actions: BookInfoActions,
    palette: AppManagementPalette,
    accents: ModernAccents
) {
    val readProgressText = if (state.chapterCount > 0 && state.currentChapterIndex >= 0) {
        stringResource(
            R.string.book_info_read_progress,
            state.currentChapterIndex + 1,
            ((state.currentChapterIndex + 1) * 100 / state.chapterCount).coerceIn(0, 100)
        )
    } else {
        null
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(palette.settings.bottomBar))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = actions.onShelf,
                shape = AppShapes.Button,
                border = androidx.compose.foundation.BorderStroke(1.dp, accents.accent),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = accents.accent
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(
                    text = if (state.inBookshelf) {
                        stringResource(R.string.remove_from_bookshelf)
                    } else {
                        stringResource(R.string.add_to_bookshelf)
                    },
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Button(
                onClick = actions.onRead,
                shape = AppShapes.Button,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accents.accent,
                    contentColor = palette.settings.onAccent
                ),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text(
                    // 有阅读进度时主按钮带出「第 N 章 · XX%」，与沉浸式同口径
                    text = readProgressText ?: stringResource(R.string.reading),
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}