package io.legado.app.ui.adapter

import android.os.Bundle
import android.content.DialogInterface
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.rememberAppDialogStyle

internal const val SOURCE_FOLDER_PANEL_ANIMATION_MS = 160
internal const val SOURCE_FOLDER_PANEL_DISMISS_MS = SOURCE_FOLDER_PANEL_ANIMATION_MS + 20L

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

internal data class SourceFolderConfigOption(
    val label: String,
    val value: Int
)

private data class SourceFolderConfigOptions(
    val groupStyles: List<SourceFolderConfigOption>,
    val groupModes: List<SourceFolderConfigOption>,
    val layouts: List<SourceFolderConfigOption>,
    val sorts: List<SourceFolderConfigOption>
)

internal data class SourceFolderConfigTexts(
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

internal data class SourceFolderConfigSpec(
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

internal data class SourceFolderSelectItem(
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
                // 宿主主题作用域前置（G-37 / 铁律 S1）：内容根必须包 `LegadoTheme`
                LegadoTheme {
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
