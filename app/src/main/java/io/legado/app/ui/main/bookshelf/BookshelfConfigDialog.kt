package io.legado.app.ui.main.bookshelf

import android.content.DialogInterface
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSlider
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.subtitleLarge

private const val BOOKSHELF_PANEL_ANIMATION_MS = 160
private const val BOOKSHELF_PANEL_DISMISS_MS = BOOKSHELF_PANEL_ANIMATION_MS + 20L

data class BookshelfConfigValues(
    val groupStyle: Int,
    val showUnread: Boolean,
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

private data class BookshelfConfigOption(
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

private data class BookshelfPremiumSpec(
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

private data class BookshelfSelectItem(
    val key: String,
    val label: String,
    val options: List<BookshelfConfigOption>,
    val selectedValue: Int,
    val onSelected: (Int) -> Unit
)

private data class BookshelfSwitchItem(
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

@Composable
private fun BookshelfOptionGrid(
    items: List<BookshelfSelectItem>,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec
) {
    var expandedKey by remember { mutableStateOf<String?>(null) }
    var panelVisible by remember { mutableStateOf(false) }
    var transitionVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val expandedItem = items.firstOrNull { it.key == expandedKey }
    fun dismissPanel() {
        val version = ++transitionVersion
        panelVisible = false
        scope.launch {
            delay(BOOKSHELF_PANEL_DISMISS_MS)
            if (transitionVersion == version) {
                expandedKey = null
            }
        }
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(spec.gridGap)
    ) {
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spec.gridGap)
            ) {
                rowItems.forEach { item ->
                    BookshelfSelectTile(
                        item = item,
                        expanded = item.key == expandedKey,
                        style = style,
                        spec = spec,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (expandedKey == item.key) {
                                dismissPanel()
                            } else {
                                transitionVersion++
                                panelVisible = false
                                expandedKey = item.key
                            }
                        }
                    )
                }
                if (rowItems.size < 2) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
    expandedItem?.let { item ->
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { dismissPanel() },
            properties = PopupProperties(focusable = true)
        ) {
            LaunchedEffect(item.key) {
                panelVisible = true
            }
            BookshelfChoicePopupPanel(
                visible = panelVisible,
                item = item,
                style = style,
                spec = spec,
                onSelected = {
                    item.onSelected(it)
                    dismissPanel()
                }
            )
        }
    }
}

@Composable
private fun BookshelfSelectTile(
    item: BookshelfSelectItem,
    expanded: Boolean,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val selected = item.options.firstOrNull { it.value == item.selectedValue }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "bookshelfSelectTileArrow"
    )
    Surface(
        modifier = modifier
            .height(spec.tileHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = if (expanded) style.accent.copy(alpha = 0.10f) else style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label,
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = selected?.label.orEmpty(),
                    color = if (expanded) style.accent else style.primaryText,
                    fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                    lineHeight = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_expand_more),
                contentDescription = null,
                tint = if (expanded) style.accent else style.secondaryText,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
}

@Composable
private fun BookshelfChoicePopupPanel(
    visible: Boolean,
    item: BookshelfSelectItem,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    onSelected: (Int) -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = BOOKSHELF_PANEL_ANIMATION_MS),
        label = "bookshelfChoicePopup"
    )
    val columns = 2
    Surface(
        modifier = Modifier
            .width(spec.popupWidth)
            .graphicsLayer {
                alpha = progress
                scaleX = 0.96f + 0.04f * progress
                scaleY = 0.96f + 0.04f * progress
                translationY = (1f - progress) * 12f
            },
        shape = RoundedCornerShape(style.panelRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = item.label,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontFamily = style.titleFontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            item.options.chunked(columns).forEach { rowOptions ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    rowOptions.forEach { option ->
                        BookshelfChoiceChip(
                            option = option,
                            selected = option.value == item.selectedValue,
                            style = style,
                            spec = spec,
                            modifier = Modifier.weight(1f),
                            onClick = { onSelected(option.value) }
                        )
                    }
                    repeat(columns - rowOptions.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun BookshelfChoiceChip(
    option: BookshelfConfigOption,
    selected: Boolean,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier
            .height(spec.choiceHeight)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(style.actionRadius),
        color = if (selected) style.accent.copy(alpha = 0.14f) else style.fieldSurface,
        contentColor = if (selected) style.accent else style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = option.label,
                color = if (selected) style.accent else style.primaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun BookshelfDisplaySummaryCard(
    title: String,
    items: List<BookshelfSwitchItem>,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec
) {
    var expanded by remember { mutableStateOf(false) }
    var panelVisible by remember { mutableStateOf(false) }
    var transitionVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val activeItems = items.filter { it.checked }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = BOOKSHELF_PANEL_ANIMATION_MS),
        label = "bookshelfDisplayArrow"
    )
    fun dismissPanel() {
        val version = ++transitionVersion
        panelVisible = false
        scope.launch {
            delay(BOOKSHELF_PANEL_DISMISS_MS)
            if (transitionVersion == version) {
                expanded = false
            }
        }
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (expanded) {
                    dismissPanel()
                } else {
                    transitionVersion++
                    expanded = true
                }
            },
        shape = RoundedCornerShape(style.actionRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = style.secondaryText,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (activeItems.isEmpty()) {
                        BookshelfSummaryChip(
                            text = "未启用",
                            active = false,
                            style = style
                        )
                    } else {
                        activeItems.forEach { item ->
                            BookshelfSummaryChip(
                                text = item.summaryLabel,
                                active = true,
                                style = style
                            )
                        }
                    }
                }
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_expand_more),
                contentDescription = null,
                tint = if (expanded) style.accent else style.secondaryText,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = rotation }
            )
        }
    }
    if (expanded) {
        Popup(
            alignment = Alignment.Center,
            onDismissRequest = { dismissPanel() },
            properties = PopupProperties(focusable = true)
        ) {
            LaunchedEffect(Unit) {
                panelVisible = true
            }
            BookshelfDisplayPopupPanel(
                visible = panelVisible,
                title = title,
                items = items,
                style = style,
                spec = spec,
                onDismiss = { dismissPanel() }
            )
        }
    }
}

@Composable
private fun BookshelfSummaryChip(
    text: String,
    active: Boolean,
    style: AppDialogStyle
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) style.accent.copy(alpha = 0.13f) else style.fieldSurface,
        contentColor = if (active) style.accent else style.secondaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = text,
            color = if (active) style.accent else style.secondaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun BookshelfDisplayPopupPanel(
    visible: Boolean,
    title: String,
    items: List<BookshelfSwitchItem>,
    style: AppDialogStyle,
    spec: BookshelfPremiumSpec,
    onDismiss: () -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = BOOKSHELF_PANEL_ANIMATION_MS),
        label = "bookshelfDisplayPopup"
    )
    Surface(
        modifier = Modifier
            .width(spec.popupWidth)
            .graphicsLayer {
                alpha = progress
                scaleX = 0.96f + 0.04f * progress
                scaleY = 0.96f + 0.04f * progress
                translationY = (1f - progress) * 12f
            },
        shape = RoundedCornerShape(style.panelRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 12.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontFamily = style.titleFontFamily,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            items.forEach { item ->
                BookshelfDisplaySwitchRow(
                    item = item,
                    style = style
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(android.R.string.ok),
                    palette = style.toMiuixPalette(),
                    onClick = onDismiss,
                    primary = true,
                    minWidth = 76.dp,
                    minHeight = 36.dp,
                    insidePadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    cornerRadius = style.actionRadius
                )
            }
        }
    }
}

@Composable
private fun BookshelfDisplaySwitchRow(
    item: BookshelfSwitchItem,
    style: AppDialogStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(RoundedCornerShape(style.actionRadius))
            .clickable { item.onCheckedChange(!item.checked) }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = item.label,
            modifier = Modifier.weight(1f),
            color = if (item.checked) style.primaryText else style.secondaryText,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = if (item.checked) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        // 开关视觉统一（ui-theme-governance-polish P1）：私有 MiniSwitch 迁移至公共
        // LegadoMiuixSwitch compact 模式（38x22 无阴影）+ stroke 描边，取色走统一基线
        LegadoMiuixSwitch(
            checked = item.checked,
            onCheckedChange = item.onCheckedChange,
            palette = style.toMiuixPalette(),
            stroke = style.stroke,
            compact = true
        )
    }
}

@Composable
private fun BookshelfSliderRow(
    title: String,
    value: Int,
    range: IntRange,
    style: AppDialogStyle,
    onValueChange: (Int) -> Unit
) {
    val palette = style.toMiuixPalette()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = style.accent.copy(alpha = 0.12f),
                    contentColor = style.accent,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {
                    Text(
                        text = value.toString(),
                        color = style.accent,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp)
                    )
                }
            }
            LegadoMiuixSlider(
                value = value.toFloat(),
                onValueChange = {
                    onValueChange(it.roundToInt().coerceIn(range.first, range.last))
                },
                palette = palette,
                modifier = Modifier.height(28.dp),
                valueRange = range.first.toFloat()..range.last.toFloat(),
                steps = (range.last - range.first - 1).coerceAtLeast(0)
            )
        }
    }
}

@Composable
private fun BookshelfFooterActions(
    style: AppDialogStyle,
    cancelLabel: String,
    applyLabel: String,
    onCancel: () -> Unit,
    onApply: () -> Unit
) {
    val palette = style.toMiuixPalette()
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        LegadoMiuixActionButton(
            text = cancelLabel,
            palette = palette,
            onClick = onCancel,
            modifier = Modifier.width(92.dp),
            cornerRadius = style.actionRadius
        )
        Spacer(modifier = Modifier.width(10.dp))
        LegadoMiuixActionButton(
            text = applyLabel,
            palette = palette,
            onClick = onApply,
            modifier = Modifier.width(108.dp),
            primary = true,
            cornerRadius = style.actionRadius
        )
    }
}
