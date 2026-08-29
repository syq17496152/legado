package io.legado.app.ui.adapter

import android.os.Bundle
import android.content.DialogInterface
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSlider
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.subtitleLarge

private const val SOURCE_FOLDER_PANEL_ANIMATION_MS = 160
private const val SOURCE_FOLDER_PANEL_DISMISS_MS = SOURCE_FOLDER_PANEL_ANIMATION_MS + 20L

/**
 * 书源/订阅源布局配置弹框（Compose 版，对齐书架布局 BookshelfConfigDialog 样式）
 *
 * 配置项（原 SourceFolderAdapter.showConfigDialog / dialog_source_folder_config.xml 迁移）：
 *  - 分组样式 groupStyle（列表/按类型/按分组，sourceGroupStyle）
 *  - 展示模式 groupMode（标签/分组，sourceGroupMode）
 *  - 视图模式 layout（列表/紧凑列表/Grid2-6，sourceLayout）
 *  - 排序 sort（Manual/名称/启用/类型/分组/URL，书源存 bookSourceSort、订阅存 rssSort）
 *  - 间距 margin（0-60，sourceMargin）
 *
 * 应用时统一写入 AppConfig，仅在实际变更时回调 onConfigChanged 触发调用方刷新。
 */
data class SourceFolderConfigValues(
    val groupStyle: Int,
    val groupMode: Int,
    val layout: Int,
    val sort: Int,
    val sortAscending: Boolean,
    val margin: Int
)

private data class SourceFolderConfigOption(
    val label: String,
    val value: Int
)

private data class SourceFolderConfigOptions(
    val groupStyles: List<SourceFolderConfigOption>,
    val groupModes: List<SourceFolderConfigOption>,
    val layouts: List<SourceFolderConfigOption>,
    val sorts: List<SourceFolderConfigOption>
)

private data class SourceFolderConfigTexts(
    val title: String,
    val layoutTitle: String,
    val groupStyleLabel: String,
    val groupModeLabel: String,
    val layoutLabel: String,
    val sortLabel: String,
    val sortAscendingLabel: String,
    val marginTitle: String,
    val marginLabel: String,
    val cancelLabel: String,
    val applyLabel: String
)

private data class SourceFolderConfigSpec(
    val panelHorizontalPadding: Dp = 16.dp,
    val panelVerticalPadding: Dp = 14.dp,
    val contentMaxHeight: Dp = 460.dp,
    val sectionPadding: Dp = 10.dp,
    val sectionGap: Dp = 8.dp,
    val tileHeight: Dp = 64.dp,
    val choiceHeight: Dp = 38.dp,
    val popupWidth: Dp = 304.dp,
    val gridGap: Dp = 8.dp,
    val compactGap: Dp = 6.dp
)

private data class SourceFolderSelectItem(
    val key: String,
    val label: String,
    val options: List<SourceFolderConfigOption>,
    val selectedValue: Int,
    val onSelected: (Int) -> Unit
)

class SourceFolderConfigDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form
    override val dialogGravity: Int = Gravity.CENTER
    override val dialogWindowAnimations: Int = R.style.AnimDialogCenter

    private var isBookSource = false
    private var showGroupStyle = true
    private var initialValues = SourceFolderConfigValues(
        groupStyle = 0,
        groupMode = 0,
        layout = 0,
        sort = 0,
        sortAscending = true,
        margin = 12
    )
    private var onConfigChanged: (() -> Unit)? = null
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
                val options = remember { buildSourceFolderConfigOptions() }
                val texts = remember { buildSourceFolderConfigTexts() }
                var values by remember { mutableStateOf(initialValues) }
                SourceFolderConfigPanel(
                    texts = texts,
                    style = style,
                    content = {
                        SourceFolderConfigContent(
                            values = values,
                            options = options,
                            texts = texts,
                            style = style,
                            showGroupStyle = showGroupStyle,
                            isBookSource = isBookSource,
                            onValuesChange = { next ->
                                val previous = values
                                values = next
                                // 边距滑条实时预览（rss-classic-layout-align：对齐书架体验）
                                if (next.margin != previous.margin) {
                                    previewMarginChanged = true
                                    onPreviewMarginChange?.invoke(next.margin.coerceIn(0, 60))
                                }
                            }
                        )
                    },
                    actions = {
                        SourceFolderFooterActions(
                            style = style,
                            cancelLabel = texts.cancelLabel,
                            applyLabel = texts.applyLabel,
                            onCancel = { dismissAllowingStateLoss() },
                            onApply = {
                                applied = true
                                applyConfig(values)
                                dismissAllowingStateLoss()
                            }
                        )
                    }
                )
            }
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        // 取消/dismiss 且滑条预览过：还原初始边距（未应用不残留）
        if (!applied && previewMarginChanged) {
            onPreviewMarginChange?.invoke(initialValues.margin.coerceIn(0, 60))
        }
    }

    private fun applyConfig(values: SourceFolderConfigValues) {
        var changed = false
        if (showGroupStyle && AppConfig.sourceGroupStyle != values.groupStyle) {
            AppConfig.sourceGroupStyle = values.groupStyle
            changed = true
        }
        if (AppConfig.sourceGroupMode != values.groupMode) {
            AppConfig.sourceGroupMode = values.groupMode
            changed = true
        }
        if (AppConfig.sourceLayout != values.layout) {
            AppConfig.sourceLayout = values.layout
            changed = true
        }
        if (isBookSource) {
            if (AppConfig.bookSourceSort != values.sort) {
                AppConfig.bookSourceSort = values.sort
                changed = true
            }
        } else {
            if (AppConfig.rssSort != values.sort) {
                AppConfig.rssSort = values.sort
                changed = true
            }
        }
        // 升降序接入（rss-classic-layout-align S2-a：原死配置补 UI 写入口）
        if (!isBookSource && AppConfig.rssSortAscending != values.sortAscending) {
            AppConfig.rssSortAscending = values.sortAscending
            changed = true
        }
        if (AppConfig.sourceMargin != values.margin) {
            AppConfig.sourceMargin = values.margin
            changed = true
        }
        if (changed) {
            onConfigChanged?.invoke()
        }
    }

    private fun buildSourceFolderConfigOptions(): SourceFolderConfigOptions {
        // rss-classic-layout-align S2：显式 value 映射（防 mapIndexed 隐式赋值错位）；
        // 移除「列表/紧凑列表」摆设项（订阅源固定卡片，sourceLayout 0/1 无列表实现），保留 自动/Grid2-6；
        // 排序补第 7 项「更新时间」（RssFragment sortSources 已实现 6->lastUpdateTime 分支）
        return SourceFolderConfigOptions(
            groupStyles = resources.getStringArray(R.array.source_group_style_new)
                .mapIndexed { index, label -> SourceFolderConfigOption(label, index) },
            groupModes = resources.getStringArray(R.array.source_group_mode_items)
                .mapIndexed { index, label -> SourceFolderConfigOption(label, index) },
            layouts = listOf(
                getString(R.string.source_layout_auto) to 0,
                getString(R.string.layout_grid2) to 2,
                getString(R.string.layout_grid3) to 3,
                getString(R.string.layout_grid4) to 4,
                getString(R.string.layout_grid5) to 5,
                getString(R.string.layout_grid6) to 6
            ).map { (label, value) -> SourceFolderConfigOption(label, value) },
            sorts = listOf(
                getString(R.string.source_sort_0) to 0,
                getString(R.string.source_sort_1) to 1,
                getString(R.string.source_sort_2) to 2,
                getString(R.string.source_sort_3) to 3,
                getString(R.string.source_sort_4) to 4,
                getString(R.string.source_sort_5) to 5,
                getString(R.string.source_sort_6) to 6
            ).map { (label, value) -> SourceFolderConfigOption(label, value) }
        )
    }

    private fun buildSourceFolderConfigTexts(): SourceFolderConfigTexts {
        return SourceFolderConfigTexts(
            title = getString(R.string.source_folder_config),
            layoutTitle = getString(R.string.view),
            groupStyleLabel = getString(R.string.group_style),
            groupModeLabel = getString(R.string.source_group_mode),
            layoutLabel = getString(R.string.source_layout),
            sortLabel = getString(R.string.source_sort_label),
            sortAscendingLabel = getString(R.string.source_sort_ascending),
            marginTitle = getString(R.string.margin),
            marginLabel = getString(R.string.margin),
            cancelLabel = getString(android.R.string.cancel),
            applyLabel = getString(android.R.string.ok)
        )
    }

    companion object {
        /**
         * 创建订阅/书源布局配置弹框。
         *
         * @param isBookSource 书源时排序写入 bookSourceSort，订阅源写入 rssSort
         * @param showGroupStyle 是否显示分组样式选项（管理页固定平铺时传 false 隐藏）
         * @param onConfigChanged 配置变更回调（任意配置变更即触发，用于调用方刷新视图）
         */
        fun create(
            isBookSource: Boolean,
            showGroupStyle: Boolean = true,
            onPreviewMarginChange: ((Int) -> Unit)? = null,
            onConfigChanged: () -> Unit
        ): SourceFolderConfigDialog {
            return SourceFolderConfigDialog().apply {
                this.isBookSource = isBookSource
                this.showGroupStyle = showGroupStyle
                this.onConfigChanged = onConfigChanged
                this.onPreviewMarginChange = onPreviewMarginChange
                initialValues = SourceFolderConfigValues(
                    groupStyle = AppConfig.sourceGroupStyle,
                    groupMode = AppConfig.sourceGroupMode,
                    layout = AppConfig.sourceLayout,
                    sort = if (isBookSource) AppConfig.bookSourceSort else AppConfig.rssSort,
                    sortAscending = AppConfig.rssSortAscending,
                    margin = AppConfig.sourceMargin
                )
            }
        }
    }
}

@Composable
private fun SourceFolderConfigContent(
    values: SourceFolderConfigValues,
    options: SourceFolderConfigOptions,
    texts: SourceFolderConfigTexts,
    style: AppDialogStyle,
    showGroupStyle: Boolean,
    isBookSource: Boolean,
    onValuesChange: (SourceFolderConfigValues) -> Unit
) {
    val spec = SourceFolderConfigSpec()
    val selectItems = buildList {
        if (showGroupStyle) {
            add(
                SourceFolderSelectItem(
                    key = "groupStyle",
                    label = texts.groupStyleLabel,
                    options = options.groupStyles,
                    selectedValue = values.groupStyle,
                    onSelected = { onValuesChange(values.copy(groupStyle = it)) }
                )
            )
        }
        add(
            SourceFolderSelectItem(
                key = "groupMode",
                label = texts.groupModeLabel,
                options = options.groupModes,
                selectedValue = values.groupMode,
                onSelected = { onValuesChange(values.copy(groupMode = it)) }
            )
        )
        add(
            SourceFolderSelectItem(
                key = "layout",
                label = texts.layoutLabel,
                options = options.layouts,
                selectedValue = values.layout,
                onSelected = { onValuesChange(values.copy(layout = it)) }
            )
        )
        add(
            SourceFolderSelectItem(
                key = "sort",
                label = texts.sortLabel,
                options = options.sorts,
                selectedValue = values.sort,
                onSelected = { onValuesChange(values.copy(sort = it)) }
            )
        )
    }
    SourceFolderConfigSection(
        title = texts.layoutTitle,
        style = style,
        spec = spec
    ) {
        SourceFolderOptionGrid(
            items = selectItems,
            style = style,
            spec = spec
        )
        // 升降序开关（rss-classic-layout-align S2-a）：仅订阅排序（书源排序无此键消费）
        if (!isBookSource) {
            Spacer(modifier = Modifier.height(spec.compactGap))
            SourceFolderAscendingRow(
                label = texts.sortAscendingLabel,
                checked = values.sortAscending,
                style = style,
                onCheckedChange = { onValuesChange(values.copy(sortAscending = it)) }
            )
        }
    }
    SourceFolderConfigSection(
        title = texts.marginTitle,
        style = style,
        spec = spec
    ) {
        SourceFolderSliderRow(
            title = texts.marginLabel,
            value = values.margin,
            range = 0..60,
            style = style,
            onValueChange = { onValuesChange(values.copy(margin = it)) }
        )
    }
}

@Composable
private fun SourceFolderConfigPanel(
    texts: SourceFolderConfigTexts,
    style: AppDialogStyle,
    content: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    val spec = SourceFolderConfigSpec()
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
private fun SourceFolderConfigSection(
    title: String,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec,
    content: @Composable () -> Unit
) {
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
private fun SourceFolderOptionGrid(
    items: List<SourceFolderSelectItem>,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec
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
            delay(SOURCE_FOLDER_PANEL_DISMISS_MS)
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
                    SourceFolderSelectTile(
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
            SourceFolderChoicePopupPanel(
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
private fun SourceFolderSelectTile(
    item: SourceFolderSelectItem,
    expanded: Boolean,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val selected = item.options.firstOrNull { it.value == item.selectedValue }
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "sourceFolderSelectTileArrow"
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
private fun SourceFolderChoicePopupPanel(
    visible: Boolean,
    item: SourceFolderSelectItem,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec,
    onSelected: (Int) -> Unit
) {
    val progress by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = SOURCE_FOLDER_PANEL_ANIMATION_MS),
        label = "sourceFolderChoicePopup"
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
                        SourceFolderChoiceChip(
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
private fun SourceFolderChoiceChip(
    option: SourceFolderConfigOption,
    selected: Boolean,
    style: AppDialogStyle,
    spec: SourceFolderConfigSpec,
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
private fun SourceFolderAscendingRow(
    label: String,
    checked: Boolean,
    style: AppDialogStyle,
    onCheckedChange: (Boolean) -> Unit
) {
    // 升降序开关行（rss-classic-layout-align S2-a）：style 配色，无硬编码色
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(style.actionRadius),
        color = style.surface,
        contentColor = style.primaryText,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .clickable { onCheckedChange(!checked) }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                color = style.primaryText,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (checked) "↑" else "↓",
                color = style.accent,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(end = 8.dp)
            )
            androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun SourceFolderSliderRow(
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
private fun SourceFolderFooterActions(
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
