package io.legado.app.ui.widget.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Functions
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.book.read.ReaderUiState

/**
 * 阅读器菜单层（S5 骨架，AD-01，直接替换 read_menu）
 *
 * 点击中屏浮现的菜单层：scrim 遮罩 + 顶栏 + 亮度区 + 悬浮按钮行 + 章节行 + 工具网格。
 * 完整复刻原 [io.legado.app.ui.book.read.ReadMenu] 全部功能入口。
 * 正文 ReadView 保持 XML 垫底，本层由 composeSheetHost 承载叠加其上。
 * 纯 UI 壳组件，业务逻辑经 [MenuLayerAction] 回调外抛，不在此处实现业务。
 */
data class MenuLayerState(
    val title: String = "",
    val chapterTitle: String = "",
    val chapterUrl: String = "",
    val sourceName: String = "",
    val isLocalBook: Boolean = false,
    val isLocalTxt: Boolean = false,
    val isEpub: Boolean = false,
    val hasCustomButton: Boolean = false,
    val brightnessAuto: Boolean = false,
    val brightness: Int = 0,
    val seekMax: Int = 0,
    val seekProgress: Int = 0,
    val isAutoPage: Boolean = false,
    val isNightTheme: Boolean = false,
    val canPrev: Boolean = false,
    val canNext: Boolean = false,
    // 阅读器独立配色（原 ReadMenu upColorConfig 计算值，非全局主题色）
    val menuBg: Int = 0,
    val menuText: Int = 0,
    val menuAccent: Int = 0,
    // 更多菜单 checkable 状态
    val useReplaceRule: Boolean = false,
    val sameTitleRemoved: Boolean = false,
    val reSegment: Boolean = false,
    val delRubyTag: Boolean = false,
    val delHTag: Boolean = false,
)

data class MenuLayerAction(
    val onBack: () -> Unit = {},
    val onTitleClick: () -> Unit = {},
    val onChapterClick: () -> Unit = {},
    val onCustomBtn: () -> Unit = {},
    val onSourceLogin: () -> Unit = {},
    val onSourcePay: () -> Unit = {},
    val onSourceEdit: () -> Unit = {},
    val onSourceDisable: () -> Unit = {},
    val onMoreChangeSource: () -> Unit = {},
    val onMoreRefresh: () -> Unit = {},
    val onMoreRefreshAfter: () -> Unit = {},
    val onMoreRefreshAll: () -> Unit = {},
    val onMoreDownload: () -> Unit = {},
    val onMoreBookmark: () -> Unit = {},
    val onMoreHighlightRule: () -> Unit = {},
    val onMoreEditContent: () -> Unit = {},
    val onMorePageAnim: () -> Unit = {},
    val onMoreReverseContent: () -> Unit = {},
    val onMoreSimulatedReading: () -> Unit = {},
    val onMoreReplaceRuleToggle: () -> Unit = {},
    val onMoreSameTitleRemoved: () -> Unit = {},
    val onMoreReSegment: () -> Unit = {},
    val onMoreDelRubyTag: () -> Unit = {},
    val onMoreDelHTag: () -> Unit = {},
    val onMoreImageStyle: () -> Unit = {},
    val onMoreUpdateToc: () -> Unit = {},
    val onMoreEffectiveReplaces: () -> Unit = {},
    val onMoreLog: () -> Unit = {},
    val onMoreHelp: () -> Unit = {},
    val onMoreSetCharset: () -> Unit = {},
    val onMoreTocRegex: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onAutoPage: () -> Unit = {},
    val onReplaceRule: () -> Unit = {},
    val onNightTheme: () -> Unit = {},
    val onPrevChapter: () -> Unit = {},
    val onNextChapter: () -> Unit = {},
    val onSeekStart: () -> Unit = {},
    val onSeek: (Int) -> Unit = {},
    val onSeekEnd: () -> Unit = {},
    val onCatalog: () -> Unit = {},
    val onReadAloud: () -> Unit = {},
    val onReadAloudLong: () -> Unit = {},
    val onFont: () -> Unit = {},
    val onSetting: () -> Unit = {},
    val onBrightnessAuto: () -> Unit = {},
    val onBrightnessChange: (Int) -> Unit = {},
    val onBrightnessPosAdjust: () -> Unit = {},
    val onScrimClick: () -> Unit = {},
)

@Composable
fun MenuLayer(
    uiState: ReaderUiState,
    state: MenuLayerState,
    action: MenuLayerAction,
) {
    // 阅读器独立配色（非全局主题色）：顶栏/底栏背景、前景文字、高亮强调
    val menuBg = Color(state.menuBg)
    val menuText = Color(state.menuText)
    val menuAccent = Color(state.menuAccent)
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = uiState.menuVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // scrim 遮罩（点击收起菜单）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f))
                        .clickable { action.onScrimClick() }
                )
                // 顶栏
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(menuBg)
                        .statusBarsPadding()
                ) {
                    AnimatedVisibility(
                        visible = uiState.menuVisible,
                        enter = slideInVertically(initialOffsetY = { -it }),
                        exit = slideOutVertically(targetOffsetY = { -it }),
                    ) {
                        MenuTitleBar(state, action, menuBg, menuText, menuAccent)
                    }
                }
                // 亮度区（左侧垂直）
                AnimatedVisibility(
                    visible = uiState.menuVisible,
                    enter = slideInVertically(initialOffsetY = { -it }),
                    exit = slideOutVertically(targetOffsetY = { -it }),
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                ) {
                    MenuBrightnessColumn(state, action, menuBg, menuText, menuAccent)
                }
                // 底栏
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(menuBg)
                        .navigationBarsPadding()
                ) {
                    AnimatedVisibility(
                        visible = uiState.menuVisible,
                        enter = slideInVertically(initialOffsetY = { it }),
                        exit = slideOutVertically(targetOffsetY = { it }),
                    ) {
                        MenuBottomBar(state, action, menuText, menuAccent)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuTitleBar(
    state: MenuLayerState,
    action: MenuLayerAction,
    menuBg: Color,
    menuText: Color,
    menuAccent: Color,
) {
    var sourceMenuOpen by remember { mutableStateOf(false) }
    var moreMenuOpen by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 4.dp)
        ) {
            IconButton(onClick = action.onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.back),
                    tint = menuText,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { action.onTitleClick() }
            ) {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = menuText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = state.chapterTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = menuText.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { action.onChapterClick() },
                )
                // 章节网页地址（原 tv_chapter_url，仅在线书显示）
                if (state.chapterUrl.isNotBlank()) {
                    Text(
                        text = state.chapterUrl,
                        style = MaterialTheme.typography.labelSmall,
                        color = menuText.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // 自定义按钮
            if (state.hasCustomButton) {
                IconButton(onClick = action.onCustomBtn) {
                    Image(
                        painter = painterResource(R.drawable.ic_custom),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(menuAccent),
                    )
                }
            }
            // 换源
            Box {
                TextButton(onClick = { sourceMenuOpen = true }) {
                    Text(
                        text = if (state.isLocalBook) stringResource(R.string.book_source) else state.sourceName,
                        color = menuText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                }
                if (!state.isLocalBook) {
                    AppDropdownMenu(
                        expanded = sourceMenuOpen,
                        onDismiss = { sourceMenuOpen = false },
                        actions = listOf(
                            MenuAction(
                                icon = Icons.Default.Login,
                                title = stringResource(R.string.login),
                                onClick = action.onSourceLogin
                            ),
                            MenuAction(
                                icon = Icons.Default.Paid,
                                title = stringResource(R.string.chapter_pay),
                                onClick = action.onSourcePay
                            ),
                            MenuAction(
                                icon = Icons.Default.Edit,
                                title = stringResource(R.string.edit_source),
                                onClick = action.onSourceEdit
                            ),
                            MenuAction(
                                icon = Icons.Default.Block,
                                title = stringResource(R.string.disable_source),
                                onClick = action.onSourceDisable
                            )
                        )
                    )
                }
            }
            // 更多
            Box {
                IconButton(onClick = { moreMenuOpen = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.more),
                        tint = menuText,
                    )
                }
                AppDropdownMenu(
                    expanded = moreMenuOpen,
                    onDismiss = { moreMenuOpen = false },
                    actions = buildList {
                        // 在线书：换源 / 刷新（本章/之后/全部）/ 离线缓存
                        if (!state.isLocalBook) {
                            add(
                                MenuAction(
                                    icon = Icons.Default.SwapHoriz,
                                    title = stringResource(R.string.change_source),
                                    onClick = action.onMoreChangeSource
                                )
                            )
                            add(
                                MenuAction(
                                    icon = Icons.Default.Refresh,
                                    title = stringResource(R.string.refresh),
                                    onClick = action.onMoreRefresh
                                )
                            )
                            add(
                                MenuAction(
                                    icon = Icons.Default.Refresh,
                                    title = stringResource(R.string.menu_refresh_after),
                                    onClick = action.onMoreRefreshAfter
                                )
                            )
                            add(
                                MenuAction(
                                    icon = Icons.Default.Refresh,
                                    title = stringResource(R.string.menu_refresh_all),
                                    onClick = action.onMoreRefreshAll
                                )
                            )
                            add(
                                MenuAction(
                                    icon = Icons.Default.Download,
                                    title = stringResource(R.string.action_download),
                                    onClick = action.onMoreDownload
                                )
                            )
                        }
                        add(
                            MenuAction(
                                icon = Icons.Default.BookmarkAdd,
                                title = stringResource(R.string.bookmark_add),
                                onClick = action.onMoreBookmark
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.Highlight,
                                title = stringResource(R.string.highlight_rule_manage),
                                onClick = action.onMoreHighlightRule
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.Edit,
                                title = stringResource(R.string.edit_content),
                                onClick = action.onMoreEditContent
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.AutoAwesome,
                                title = stringResource(R.string.book_page_anim),
                                onClick = action.onMorePageAnim
                            )
                        )
                        if (!state.isLocalBook) {
                            add(
                                MenuAction(
                                    icon = Icons.Default.SwapVert,
                                    title = stringResource(R.string.reverse_content),
                                    onClick = action.onMoreReverseContent
                                )
                            )
                        }
                        add(
                            MenuAction(
                                icon = Icons.Default.PlayCircle,
                                title = stringResource(R.string.simulated_reading),
                                onClick = action.onMoreSimulatedReading
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.FilterList,
                                title = stringResource(R.string.replace_rule_title),
                                checked = state.useReplaceRule,
                                onClick = action.onMoreReplaceRuleToggle
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.DeleteSweep,
                                title = stringResource(R.string.same_title_removed),
                                checked = state.sameTitleRemoved,
                                onClick = action.onMoreSameTitleRemoved
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.Notes,
                                title = stringResource(R.string.re_segment),
                                checked = state.reSegment,
                                onClick = action.onMoreReSegment
                            )
                        )
                        if (state.isEpub) {
                            add(
                                MenuAction(
                                    icon = Icons.Default.RemoveCircle,
                                    title = stringResource(R.string.del_ruby_tag),
                                    checked = state.delRubyTag,
                                    onClick = action.onMoreDelRubyTag
                                )
                            )
                            add(
                                MenuAction(
                                    icon = Icons.Default.RemoveCircle,
                                    title = stringResource(R.string.del_h_tag),
                                    checked = state.delHTag,
                                    onClick = action.onMoreDelHTag
                                )
                            )
                        }
                        add(
                            MenuAction(
                                icon = Icons.Default.Image,
                                title = stringResource(R.string.image_style),
                                onClick = action.onMoreImageStyle
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.Refresh,
                                title = stringResource(R.string.update_toc),
                                onClick = action.onMoreUpdateToc
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.Rule,
                                title = stringResource(R.string.effective_replaces),
                                onClick = action.onMoreEffectiveReplaces
                            )
                        )
                        if (state.isLocalTxt) {
                            add(
                                MenuAction(
                                    icon = Icons.Default.Functions,
                                    title = stringResource(R.string.txt_toc_rule),
                                    onClick = action.onMoreTocRegex
                                )
                            )
                        }
                        if (state.isLocalBook) {
                            add(
                                MenuAction(
                                    icon = Icons.Default.Settings,
                                    title = stringResource(R.string.set_charset),
                                    onClick = action.onMoreSetCharset
                                )
                            )
                        }
                        add(
                            MenuAction(
                                icon = Icons.Default.BugReport,
                                title = stringResource(R.string.log),
                                onClick = action.onMoreLog
                            )
                        )
                        add(
                            MenuAction(
                                icon = Icons.Default.Help,
                                title = stringResource(R.string.help),
                                onClick = action.onMoreHelp
                            )
                        )
                    }
                )
            }
        }
        HorizontalDivider(
            color = menuText.copy(alpha = 0.15f),
            thickness = 0.5.dp,
        )
    }
}

@Composable
private fun MenuBrightnessColumn(
    state: MenuLayerState,
    action: MenuLayerAction,
    menuBg: Color,
    menuText: Color,
    menuAccent: Color,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .background(menuBg.copy(alpha = 0.9f))
            .padding(vertical = 8.dp)
            .width(40.dp),
    ) {
        IconButton(onClick = action.onBrightnessAuto) {
            Image(
                painter = painterResource(R.drawable.ic_brightness_auto),
                contentDescription = stringResource(R.string.brightness_auto),
                colorFilter = ColorFilter.tint(
                    if (state.brightnessAuto) menuAccent
                    else menuText.copy(alpha = 0.5f)
                ),
                modifier = Modifier.size(22.dp),
            )
        }
        // 垂直亮度滑条（旋转实现）
        Box(modifier = Modifier.size(width = 40.dp, height = 220.dp)) {
            Slider(
                value = state.brightness.toFloat(),
                onValueChange = { action.onBrightnessChange(it.toInt()) },
                valueRange = 0f..255f,
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(-90f),
            )
        }
        IconButton(onClick = action.onBrightnessPosAdjust) {
            Image(
                painter = painterResource(R.drawable.ic_swap_horiz),
                contentDescription = stringResource(R.string.adjust_pos),
                colorFilter = ColorFilter.tint(menuText.copy(alpha = 0.5f)),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun MenuBottomBar(
    state: MenuLayerState,
    action: MenuLayerAction,
    menuText: Color,
    menuAccent: Color,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = menuText.copy(alpha = 0.15f),
            thickness = 0.5.dp,
        )
        // 悬浮按钮行（搜索/自动翻页/替换/夜间）
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
        ) {
            MenuFloatingButton(
                icon = {
                    Image(
                        painterResource(R.drawable.ic_search),
                        null,
                        colorFilter = ColorFilter.tint(menuText),
                    )
                },
                contentDescription = stringResource(R.string.search_content),
                onClick = action.onSearch,
            )
            MenuFloatingButton(
                icon = {
                    Image(
                        painterResource(
                            if (state.isAutoPage) R.drawable.ic_auto_page_stop else R.drawable.ic_auto_page
                        ),
                        null,
                        colorFilter = ColorFilter.tint(menuText),
                    )
                },
                contentDescription = stringResource(R.string.auto_next_page),
                onClick = action.onAutoPage,
            )
            MenuFloatingButton(
                icon = {
                    Image(
                        painterResource(R.drawable.ic_find_replace),
                        null,
                        colorFilter = ColorFilter.tint(menuText),
                    )
                },
                contentDescription = stringResource(R.string.replace_rule_title),
                onClick = action.onReplaceRule,
            )
            MenuFloatingButton(
                icon = {
                    Image(
                        painterResource(if (state.isNightTheme) R.drawable.ic_daytime else R.drawable.ic_brightness),
                        null,
                        colorFilter = ColorFilter.tint(menuText),
                    )
                },
                contentDescription = stringResource(R.string.dark_theme),
                onClick = action.onNightTheme,
            )
        }
        // 章节行（上一章/进度/下一章）
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = 8.dp),
        ) {
            TextButton(onClick = action.onPrevChapter, enabled = state.canPrev) {
                Text(stringResource(R.string.previous_chapter), color = menuText)
            }
            Slider(
                value = state.seekProgress.toFloat(),
                onValueChange = { },
                onValueChangeFinished = { },
                enabled = false,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = action.onNextChapter, enabled = state.canNext) {
                Text(stringResource(R.string.next_chapter), color = menuText)
            }
        }
        // 工具网格（目录/朗读/界面/设置）
        Row(
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
        ) {
            MenuToolItem(
                icon = {
                    Image(
                        painterResource(R.drawable.ic_toc),
                        null,
                        colorFilter = ColorFilter.tint(menuText),
                    )
                },
                label = stringResource(R.string.chapter_list),
                onClick = action.onCatalog,
                tint = menuText,
            )
            MenuToolItem(
                icon = {
                    Image(
                        painterResource(R.drawable.ic_read_aloud),
                        null,
                        colorFilter = ColorFilter.tint(menuText),
                    )
                },
                label = stringResource(R.string.read_aloud),
                onClick = action.onReadAloud,
                tint = menuText,
            )
            MenuToolItem(
                icon = {
                    Image(
                        painterResource(R.drawable.ic_interface_setting),
                        null,
                        colorFilter = ColorFilter.tint(menuText),
                    )
                },
                label = stringResource(R.string.interface_setting),
                onClick = action.onFont,
                tint = menuText,
            )
            MenuToolItem(
                icon = {
                    Image(
                        painterResource(R.drawable.ic_settings),
                        null,
                        colorFilter = ColorFilter.tint(menuText),
                    )
                },
                label = stringResource(R.string.setting),
                onClick = action.onSetting,
                tint = menuText,
            )
        }
    }
}

@Composable
private fun MenuFloatingButton(
    icon: @Composable () -> Unit,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(52.dp)
            .clickable(onClick = onClick)
            .semantics {
                this.contentDescription = contentDescription
                role = Role.Button
            },
    ) {
        icon()
    }
}

@Composable
private fun MenuToolItem(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier
            .width(64.dp)
            .heightIn(min = 48.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
            .semantics { role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(22.dp)) { icon() }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
