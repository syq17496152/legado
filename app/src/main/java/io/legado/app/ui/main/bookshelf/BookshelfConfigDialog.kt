package io.legado.app.ui.main.bookshelf

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.subtitleLarge

internal const val BOOKSHELF_PANEL_ANIMATION_MS = 160
internal const val BOOKSHELF_PANEL_DISMISS_MS = BOOKSHELF_PANEL_ANIMATION_MS + 20L

data class BookshelfConfigValues(
    val groupStyle: Int,
    val showUnread: Boolean,
    /** R16（B3）：未读颜色强调（默认关） */
    val unreadEmphasis: Boolean = false,
    val showLastUpdateTime: Boolean,
    val showWaitUpCount: Boolean,
    val showFastScroller: Boolean,
    val returnToTopAfterRead: Boolean,
    val layout: Int,
    val sort: Int,
    val showBookname: Int,
    val listItemStyle: Int,
    val listIntroLines: Int,
    val margin: Int
)

internal data class BookshelfConfigOption(
    val label: String,
    val value: Int
)

private data class BookshelfConfigOptions(
    val groupStyles: List<BookshelfConfigOption>,
    val layouts: List<BookshelfConfigOption>,
    val sorts: List<BookshelfConfigOption>,
    val bookNameModes: List<BookshelfConfigOption>,
    val listItemStyles: List<BookshelfConfigOption>,
    val listIntroLines: List<BookshelfConfigOption>
)

private data class BookshelfConfigTexts(
    val title: String,
    val viewTitle: String,
    val groupStyleLabel: String,
    val layoutLabel: String,
    val showTitle: String,
    val sortLabel: String,
    val bookNameLabel: String,
    val listItemStyleLabel: String,
    val listIntroLinesLabel: String,
    val marginTitle: String,
    val marginLabel: String,
    val cancelLabel: String,
    val applyLabel: String
)

internal data class BookshelfPremiumSpec(
    val panelHorizontalPadding: Dp = 16.dp,
    val panelVerticalPadding: Dp = 14.dp,
    val contentMaxHeight: Dp = 460.dp,
    val sectionPadding: Dp = 10.dp,
    val sectionGap: Dp = 8.dp,
    val tileHeight: Dp = 64.dp,
    val switchTileHeight: Dp = 46.dp,
    val choiceHeight: Dp = 38.dp,
    val popupWidth: Dp = 304.dp,
    val gridGap: Dp = 8.dp,
    val compactGap: Dp = 6.dp
)

internal data class BookshelfSelectItem(
    val key: String,
    val label: String,
    val options: List<BookshelfConfigOption>,
    val selectedValue: Int,
    val onSelected: (Int) -> Unit
)

internal data class BookshelfSwitchItem(
    val key: String,
    val label: String,
    val summaryLabel: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit
)

class BookshelfConfigDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form
    override val dialogGravity: Int = Gravity.CENTER
    override val dialogWindowAnimations: Int = R.style.AnimDialogCenter

    private var initialValues = BookshelfConfigValues(
        groupStyle = 0,
        showUnread = false,
        showLastUpdateTime = false,
        showWaitUpCount = false,
        showFastScroller = false,
        returnToTopAfterRead = true,
        layout = 0,
        sort = 0,
        showBookname = 0,
        listItemStyle = 0,
        listIntroLines = 2,
        margin = 12
    )
    private var onApply: ((BookshelfConfigValues) -> Unit)? = null
    private var onPreviewMarginChange: ((Int) -> Unit)? = null
    private var previewMarginChanged = false
    private var applied = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                // 宿主主题作用域前置（G-37 / 铁律 S1）：内容根必须包 `LegadoTheme`
                LegadoTheme {
                    val style = rememberAppDialogStyle()
                    val options = remember { buildBookshelfConfigOptions() }
                    val texts = remember { buildBookshelfConfigTexts() }
                    var values by remember { mutableStateOf(initialValues) }
                    BookshelfConfigPanel(
                        texts = texts,
                        style = style,
                        content = {
                            BookshelfConfigContent(
                                values = values,
                                options = options,
                                texts = texts,
                                style = style,
                                onValuesChange = { nextValues ->
                                    val previousValues = values
                                    values = nextValues
                                    if (nextValues.margin != previousValues.margin) {
                                        previewMarginChanged = true
                                        onPreviewMarginChange?.invoke(nextValues.margin.coerceIn(0, 60))
                                    }
                                }
                            )
                        },
                        actions = {
                            BookshelfFooterActions(
                                style = style,
                                cancelLabel = texts.cancelLabel,
                                applyLabel = texts.applyLabel,
                                onCancel = { dismissAllowingStateLoss() },
                                onApply = {
                                    applied = true
                                    dismissAllowingStateLoss()
                                    onApply?.invoke(values)
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (!applied && previewMarginChanged) {
            onPreviewMarginChange?.invoke(initialValues.margin.coerceIn(0, 60))
        }
    }

    private fun buildBookshelfConfigOptions(): BookshelfConfigOptions {
        return BookshelfConfigOptions(
            groupStyles = resources.getStringArray(R.array.group_style)
                .mapIndexed { index, label -> BookshelfConfigOption(label, index) },
            layouts = listOf(
                getString(R.string.layout_list),
                getString(R.string.layout_list_compact),
                getString(R.string.layout_grid2),
                getString(R.string.layout_grid3),
                getString(R.string.layout_grid4),
                getString(R.string.layout_grid5),
                getString(R.string.layout_grid6)
            ).mapIndexed { index, label -> BookshelfConfigOption(label, index) },
            sorts = listOf(
                getString(R.string.bookshelf_px_0),
                getString(R.string.bookshelf_px_1),
                getString(R.string.bookshelf_px_2),
                getString(R.string.bookshelf_px_3),
                getString(R.string.bookshelf_px_4),
                getString(R.string.bookshelf_px_5)
            ).mapIndexed { index, label -> BookshelfConfigOption(label, index) },
            // K7 语义修复（config-needs-restart-fix）：值映射对齐渲染语义
            // 0=隐藏(渲染无书名) / 1=下方显示 / 2=封面遮罩
            bookNameModes = listOf(
                getString(R.string.hide),
                getString(R.string.show),
                getString(R.string.overlay)
            ).mapIndexed { index, label -> BookshelfConfigOption(label, index) },
            listItemStyles = listOf(
                getString(R.string.bookshelf_list_style_classic),
                getString(R.string.bookshelf_list_style_rounded_card)
            ).mapIndexed { index, label -> BookshelfConfigOption(label, index) },
            listIntroLines = listOf(
                BookshelfConfigOption(getString(R.string.bookshelf_list_intro_lines_0), 0),
                BookshelfConfigOption(getString(R.string.bookshelf_list_intro_lines_1), 1),
                BookshelfConfigOption(getString(R.string.bookshelf_list_intro_lines_2), 2),
                BookshelfConfigOption(getString(R.string.bookshelf_list_intro_lines_3), 3)
            )
        )
    }

    private fun buildBookshelfConfigTexts(): BookshelfConfigTexts {
        return BookshelfConfigTexts(
            title = getString(R.string.bookshelf_layout),
            viewTitle = getString(R.string.view),
            groupStyleLabel = getString(R.string.group_style),
            layoutLabel = getString(R.string.view),
            showTitle = getString(R.string.show),
            sortLabel = getString(R.string.sort),
            bookNameLabel = getString(R.string.book_name),
            listItemStyleLabel = getString(R.string.bookshelf_list_style),
            listIntroLinesLabel = getString(R.string.bookshelf_list_intro_lines),
            marginTitle = getString(R.string.margin),
            marginLabel = getString(R.string.margin),
            cancelLabel = getString(android.R.string.cancel),
            applyLabel = getString(android.R.string.ok)
        )
    }

    companion object {
        fun create(
            initialValues: BookshelfConfigValues,
            onPreviewMarginChange: ((Int) -> Unit)? = null,
            onApply: (BookshelfConfigValues) -> Unit
        ): BookshelfConfigDialog {
            return BookshelfConfigDialog().apply {
                this.initialValues = initialValues
                this.onPreviewMarginChange = onPreviewMarginChange
                this.onApply = onApply
            }
        }
    }
}

@Composable
private fun BookshelfConfigContent(
    values: BookshelfConfigValues,
    options: BookshelfConfigOptions,
    texts: BookshelfConfigTexts,
    style: AppDialogStyle,
    onValuesChange: (BookshelfConfigValues) -> Unit
) {
    val spec = BookshelfPremiumSpec()
    val selectItems = buildList {
        add(
            BookshelfSelectItem(
                key = "group",
                label = texts.groupStyleLabel,
                options = options.groupStyles,
                selectedValue = values.groupStyle,
                onSelected = { onValuesChange(values.copy(groupStyle = it)) }
            )
        )
        add(
            BookshelfSelectItem(
                key = "layout",
                label = texts.layoutLabel,
                options = options.layouts,
                selectedValue = values.layout,
                onSelected = { onValuesChange(values.copy(layout = it)) }
            )
        )
        add(
            BookshelfSelectItem(
                key = "sort",
                label = texts.sortLabel,
                options = options.sorts,
                selectedValue = values.sort,
                onSelected = { onValuesChange(values.copy(sort = it)) }
            )
        )
        if (values.layout >= 2) {
            add(
                BookshelfSelectItem(
                    key = "bookName",
                    label = texts.bookNameLabel,
                    options = options.bookNameModes,
                    selectedValue = values.showBookname,
                    onSelected = { onValuesChange(values.copy(showBookname = it)) }
                )
            )
        } else {
            add(
                BookshelfSelectItem(
                    key = "listItemStyle",
                    label = texts.listItemStyleLabel,
                    options = options.listItemStyles,
                    selectedValue = values.listItemStyle,
                    onSelected = { onValuesChange(values.copy(listItemStyle = it)) }
                )
            )
            add(
                BookshelfSelectItem(
                    key = "listIntroLines",
                    label = texts.listIntroLinesLabel,
                    options = options.listIntroLines,
                    selectedValue = values.listIntroLines,
                    onSelected = { onValuesChange(values.copy(listIntroLines = it)) }
                )
            )
        }
    }
    val switchItems = listOf(
        BookshelfSwitchItem(
            key = "unread",
            label = stringResource(R.string.show_unread),
            summaryLabel = "未读",
            checked = values.showUnread,
            onCheckedChange = { onValuesChange(values.copy(showUnread = it)) }
        ),
        // R16（B3）：未读颜色强调（默认关；开启后未读书籍标题用主题强调色）
        BookshelfSwitchItem(
            key = "unreadEmphasis",
            label = stringResource(R.string.bookshelf_unread_emphasis),
            summaryLabel = stringResource(R.string.bookshelf_unread_emphasis_short),
            checked = values.unreadEmphasis,
            onCheckedChange = { onValuesChange(values.copy(unreadEmphasis = it)) }
        ),
        BookshelfSwitchItem(
            key = "updateTime",
            label = stringResource(R.string.show_last_update_time),
            summaryLabel = "更新",
            checked = values.showLastUpdateTime,
            onCheckedChange = { onValuesChange(values.copy(showLastUpdateTime = it)) }
        ),
        BookshelfSwitchItem(
            key = "waitUp",
            label = stringResource(R.string.show_wait_up_count),
            summaryLabel = "追更",
            checked = values.showWaitUpCount,
            onCheckedChange = { onValuesChange(values.copy(showWaitUpCount = it)) }
        ),
        BookshelfSwitchItem(
            key = "fastScroller",
            label = stringResource(R.string.show_bookshelf_fast_scroller),
            summaryLabel = "快滑",
            checked = values.showFastScroller,
            onCheckedChange = { onValuesChange(values.copy(showFastScroller = it)) }
        ),
        BookshelfSwitchItem(
            key = "returnToTopAfterRead",
            label = stringResource(R.string.bookshelf_return_to_top_after_read),
            summaryLabel = stringResource(R.string.bookshelf_return_to_top_after_read_short),
            checked = values.returnToTopAfterRead,
            onCheckedChange = { onValuesChange(values.copy(returnToTopAfterRead = it)) }
        )
    )
    ConfigSection(
        title = texts.viewTitle,
        style = style,
        spec = spec
    ) {
        BookshelfOptionGrid(
            items = selectItems,
            style = style,
            spec = spec
        )
    }
    ConfigSection(
        title = texts.showTitle,
        style = style,
        spec = spec
    ) {
        BookshelfDisplaySummaryCard(
            title = texts.showTitle,
            items = switchItems,
            style = style,
            spec = spec
        )
    }
    ConfigSection(
        title = texts.marginTitle,
        style = style,
        spec = spec
    ) {
        BookshelfSliderRow(
            title = texts.marginLabel,
            value = values.margin,
            range = 0..60,
            style = style,
            onValueChange = { onValuesChange(values.copy(margin = it)) }
        )
    }
}

@Composable
private fun BookshelfConfigPanel(
    texts: BookshelfConfigTexts,
    style: AppDialogStyle,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    val spec = BookshelfPremiumSpec()
    CompositionLocalProvider(
        LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = style.bodyFontFamily)
    ) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 18.dp),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(
                horizontal = spec.panelHorizontalPadding,
                vertical = spec.panelVerticalPadding
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
            ) {
                Text(
                    text = texts.title,
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.subtitleLarge.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = style.titleFontFamily,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = spec.contentMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(spec.sectionGap)
                ) {
                    content()
                }
                Spacer(modifier = Modifier.height(10.dp))
                actions()
            }
        }
    }
}

@Composable
private fun ConfigSection(
    title: String,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    visible: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!visible) return
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.fieldSurface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(spec.sectionPadding)) {
            Text(
                text = title,
                color = style.accent,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = spec.compactGap),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            content()
        }
    }
}
