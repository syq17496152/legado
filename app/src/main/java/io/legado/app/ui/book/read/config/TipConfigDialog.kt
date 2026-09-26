package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.verticalScroll
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.help.config.AdvancedTitleConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ReadTipConfig
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.AppThemedStepperSlider
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.ui.config.AdvancedTitleManageActivity
import io.legado.app.utils.hexString
import io.legado.app.utils.observeEvent
import io.legado.app.utils.postEvent
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary

/**
 * 页眉页脚配色的对话框 id（宿主 Activity 依据该 id 回传选色结果）。
 *
 * R13（B3）合并后，原先的 `TipConfigDialog` 弹窗已并入「版面设置」（`PaddingConfigDialog`）——
 * 其入口与内容体都不再需要独立弹窗；但这两个 id 仍是宿主回传契约的一部分，故提升为文件级常量。
 */
internal const val TIP_COLOR = 7897
internal const val TIP_DIVIDER_COLOR = 7898

/**
 * 页眉页脚/标题设置内容体。
 *
 * R13（B3）：由 `TipConfigDialog` 改为 **internal**，供「版面设置」弹窗
 * （`PaddingConfigDialog` 合并后）在同一弹窗内复用 —— 避免两处各写一份渲染（口径分裂来源）。
 */
@Composable
internal fun TipConfigContent(
    style: AppDialogStyle,
    colorRefreshTick: Int,
    onShowAdvancedTitleConfig: () -> Unit,
    onShowSelector: (String, List<String>, (Int) -> Unit) -> Unit,
    onShowTipColorPicker: () -> Unit,
    onShowTipDividerColorPicker: () -> Unit,
    onColorChanged: () -> Unit,
    /** R12（B3）：打开「自定义模板」编辑器（可视化插入占位符 + 实时预览） */
    onShowTemplateEditor: (title: String, template: String, onSave: (String) -> Unit) -> Unit
) {
    val context = LocalContext.current
    val miuixPalette = style.toMiuixPalette()
    var titleMode by rememberSaveable { mutableIntStateOf(ReadBookConfig.titleMode) }
    var titleSize by rememberSaveable { mutableIntStateOf(ReadBookConfig.titleSize) }
    var titleTopSpacing by rememberSaveable { mutableIntStateOf(ReadBookConfig.titleTopSpacing) }
    var titleBottomSpacing by rememberSaveable {
        mutableIntStateOf(ReadBookConfig.titleBottomSpacing)
    }
    var headerMode by rememberSaveable { mutableIntStateOf(ReadTipConfig.headerMode) }
    var footerMode by rememberSaveable { mutableIntStateOf(ReadTipConfig.footerMode) }
    var headerLeft by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipHeaderLeft) }
    var headerMiddle by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipHeaderMiddle) }
    var headerRight by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipHeaderRight) }
    var footerLeft by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipFooterLeft) }
    var footerMiddle by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipFooterMiddle) }
    var footerRight by rememberSaveable { mutableIntStateOf(ReadTipConfig.tipFooterRight) }
    // R 批 §3.3.2：页眉返回按钮开关（默认关 ⇒ 旧行为零变化）
    var showHeaderBackButton by rememberSaveable {
        mutableStateOf(ReadTipConfig.showHeaderBackButton)
    }
    // R12：模板编辑令牌 —— 编辑保存后自增，令下方模板行（读取 ReadTipConfig）重新取值。
    // 模板串本身不另存一份状态（避免与配置双源），只用令牌驱动重读。
    var templateTick by rememberSaveable { mutableIntStateOf(0) }
    val headerModes = remember(context) { ReadTipConfig.getHeaderModes(context) }
    val footerModes = remember(context) { ReadTipConfig.getFooterModes(context) }
    val tipNames = ReadTipConfig.tipNames
    val tipValues = ReadTipConfig.tipValues.toList()
    val titleModeOptions = listOf(
        stringResource(R.string.title_left),
        stringResource(R.string.title_center),
        stringResource(R.string.advanced_title_mode_label),
        stringResource(R.string.title_hide)
    )
    fun titleModeToUiIndex(mode: Int): Int {
        return when (mode) {
            AdvancedTitleConfig.TITLE_MODE_ADVANCED -> 2
            2 -> 3
            else -> mode
        }.coerceIn(0, titleModeOptions.lastIndex)
    }
    fun uiIndexToTitleMode(index: Int): Int {
        return when (index) {
            2 -> AdvancedTitleConfig.TITLE_MODE_ADVANCED
            3 -> 2
            else -> index
        }
    }
    fun tipName(value: Int): String {
        val index = tipValues.indexOf(value)
        return tipNames.getOrElse(index) { tipNames[ReadTipConfig.none] }
    }

    /** 槽位名（页眉/页脚 + 左中右），供自定义模板编辑入口标识「在编辑哪个槽位」 */
    fun slotLabel(slot: Int): String {
        val area = when (slot) {
            ReadTipConfig.SLOT_HEADER_LEFT,
            ReadTipConfig.SLOT_HEADER_MIDDLE,
            ReadTipConfig.SLOT_HEADER_RIGHT -> context.getString(R.string.header)
            else -> context.getString(R.string.footer)
        }
        val position = when (slot) {
            ReadTipConfig.SLOT_HEADER_LEFT, ReadTipConfig.SLOT_FOOTER_LEFT -> R.string.left
            ReadTipConfig.SLOT_HEADER_MIDDLE, ReadTipConfig.SLOT_FOOTER_MIDDLE -> R.string.middle
            else -> R.string.right
        }
        return "$area · ${context.getString(position)}"
    }

    /** 当前为「自定义模板」的槽位（多个槽位可同时自定义 ⇒ 各自独立模板） */
    val customSlotList = listOf(
        headerLeft, headerMiddle, headerRight, footerLeft, footerMiddle, footerRight
    ).mapIndexedNotNull { slot, value -> slot.takeIf { value == ReadTipConfig.custom } }
    fun clearRepeat(value: Int) {
        if (value == ReadTipConfig.none) return
        // R12：custom 不是「唯一槽位语义」，多个槽位可同时自定义 ⇒ 不参与去重清除
        if (value == ReadTipConfig.custom) return
        if (headerLeft == value) { headerLeft = ReadTipConfig.none; ReadTipConfig.tipHeaderLeft = ReadTipConfig.none }
        if (headerMiddle == value) { headerMiddle = ReadTipConfig.none; ReadTipConfig.tipHeaderMiddle = ReadTipConfig.none }
        if (headerRight == value) { headerRight = ReadTipConfig.none; ReadTipConfig.tipHeaderRight = ReadTipConfig.none }
        if (footerLeft == value) { footerLeft = ReadTipConfig.none; ReadTipConfig.tipFooterLeft = ReadTipConfig.none }
        if (footerMiddle == value) { footerMiddle = ReadTipConfig.none; ReadTipConfig.tipFooterMiddle = ReadTipConfig.none }
        if (footerRight == value) { footerRight = ReadTipConfig.none; ReadTipConfig.tipFooterRight = ReadTipConfig.none }
    }
    fun chooseTip(title: String, onAssign: (Int) -> Unit) {
        onShowSelector(title, tipNames) { index ->
            val value = tipValues.getOrElse(index) { ReadTipConfig.none }
            clearRepeat(value)
            onAssign(value)
            postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ReaderSheetDefaults.SectionGap)
    ) {
        // 标题设置
        TipSection(style = style) {
            TipCompactSlider(
                label = stringResource(R.string.title_font_size),
                value = titleSize,
                range = 0..20,
                style = style
            ) { titleSize = it; ReadBookConfig.titleSize = it; postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5)) }
            TipCompactSlider(
                label = stringResource(R.string.title_margin_top),
                value = titleTopSpacing,
                range = 0..100,
                style = style
            ) { titleTopSpacing = it; ReadBookConfig.titleTopSpacing = it; postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5)) }
            TipCompactSlider(
                label = stringResource(R.string.title_margin_bottom),
                value = titleBottomSpacing,
                range = 0..100,
                style = style
            ) { titleBottomSpacing = it; ReadBookConfig.titleBottomSpacing = it; postEvent(EventBus.UP_CONFIG, arrayListOf(8, 5)) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                titleModeOptions.forEachIndexed { index, label ->
                    LegadoMiuixChoiceRow(
                        text = label,
                        selected = titleModeToUiIndex(titleMode) == index,
                        palette = miuixPalette,
                        onClick = {
                            val newMode = uiIndexToTitleMode(index)
                            titleMode = newMode
                            ReadBookConfig.titleMode = newMode
                            postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                            if (newMode == AdvancedTitleConfig.TITLE_MODE_ADVANCED) {
                                onShowAdvancedTitleConfig()
                            }
                        },
                        modifier = Modifier.weight(1f),
                        minHeight = 32.dp,
                        compact = true,
                        showSelectedMark = false
                    )
                }
            }
        }
        // 页眉
        TipPlacementSection(
            title = stringResource(R.string.header),
            showLabel = headerModes[headerMode].orEmpty(),
            leftLabel = tipName(headerLeft),
            middleLabel = tipName(headerMiddle),
            rightLabel = tipName(headerRight),
            style = style,
            onShowClick = {
                val keys = headerModes.keys.toList()
                onShowSelector(context.getString(R.string.header), headerModes.values.toList()) { index ->
                    headerMode = keys.getOrElse(index) { 0 }
                    ReadTipConfig.headerMode = headerMode
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }
            },
            onLeftClick = { chooseTip(context.getString(R.string.left)) { headerLeft = it; ReadTipConfig.tipHeaderLeft = it } },
            onMiddleClick = { chooseTip(context.getString(R.string.middle)) { headerMiddle = it; ReadTipConfig.tipHeaderMiddle = it } },
            onRightClick = { chooseTip(context.getString(R.string.right)) { headerRight = it; ReadTipConfig.tipHeaderRight = it } }
        )
        // R 批 §3.3.2：页眉返回按钮（默认关）—— 与「页眉」同组，紧随其后
        TipSection(style = style) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.read_header_back_button),
                        color = style.primaryText,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                    Text(
                        text = stringResource(R.string.read_header_back_button_hint),
                        color = style.secondaryText,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Switch(
                    checked = showHeaderBackButton,
                    onCheckedChange = {
                        showHeaderBackButton = it
                        ReadTipConfig.showHeaderBackButton = it
                        postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = style.accent)
                )
            }
        }
        // 页脚
        TipPlacementSection(
            title = stringResource(R.string.footer),
            showLabel = footerModes[footerMode].orEmpty(),
            leftLabel = tipName(footerLeft),
            middleLabel = tipName(footerMiddle),
            rightLabel = tipName(footerRight),
            style = style,
            onShowClick = {
                val keys = footerModes.keys.toList()
                onShowSelector(context.getString(R.string.footer), footerModes.values.toList()) { index ->
                    footerMode = keys.getOrElse(index) { 0 }
                    ReadTipConfig.footerMode = footerMode
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }
            },
            onLeftClick = { chooseTip(context.getString(R.string.left)) { footerLeft = it; ReadTipConfig.tipFooterLeft = it } },
            onMiddleClick = { chooseTip(context.getString(R.string.middle)) { footerMiddle = it; ReadTipConfig.tipFooterMiddle = it } },
            onRightClick = { chooseTip(context.getString(R.string.right)) { footerRight = it; ReadTipConfig.tipFooterRight = it } }
        )
        // R12：自定义模板编辑入口（仅当有槽位选为「自定义模板」时出现）
        if (customSlotList.isNotEmpty()) {
            TipSection(style = style) {
                Text(
                    text = stringResource(R.string.tip_template_edit_hint),
                    color = style.accent,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth()
                )
                customSlotList.forEach { slot ->
                    val template = remember(templateTick, slot) { ReadTipConfig.slotTemplate(slot) }
                    TipValueRow(
                        title = slotLabel(slot),
                        value = template.ifBlank { stringResource(R.string.tip_template_empty) },
                        style = style,
                        onClick = {
                            onShowTemplateEditor(slotLabel(slot), template) { newTemplate ->
                                ReadTipConfig.setSlotTemplate(slot, newTemplate)
                                templateTick++
                                postEvent(EventBus.UP_CONFIG, arrayListOf(2, 6))
                            }
                        }
                    )
                }
            }
        }
        // 颜色
        TipColorSection(
            colorRefreshTick = colorRefreshTick,
            style = style,
            onTipColorClick = {
                onShowSelector(context.getString(R.string.text_color), ReadTipConfig.tipColorNames) { index ->
                    when (index) {
                        0 -> { ReadTipConfig.tipColor = 0; onColorChanged(); postEvent(EventBus.UP_CONFIG, arrayListOf(2)) }
                        1 -> onShowTipColorPicker()
                    }
                }
            },
            onDividerColorClick = {
                onShowSelector(context.getString(R.string.tip_divider_color), ReadTipConfig.tipDividerColorNames) { index ->
                    when (index) {
                        0, 1 -> { ReadTipConfig.tipDividerColor = index - 1; onColorChanged(); postEvent(EventBus.UP_CONFIG, arrayListOf(2)) }
                        2 -> onShowTipDividerColorPicker()
                    }
                }
            }
        )
    }
}

@Composable
private fun TipSection(
    style: AppDialogStyle,
    content: @Composable () -> Unit
) {
    ReaderSectionCard(
        style = style,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        content()
    }
}

@Composable
private fun TipCompactSlider(
    label: String,
    value: Int,
    range: IntRange,
    style: AppDialogStyle,
    onValueChange: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = style.primaryText,
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = value.toString(),
            color = style.accent,
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.width(32.dp)
        )
        AppThemedStepperSlider(
            value = value.coerceIn(range),
            range = range,
            onValueChange = { onValueChange(it.coerceIn(range)) },
            palette = style.toMiuixPalette(),
            trackHeight = 28.dp,
            thumbSize = 22.dp,
            endpointWidth = 24.dp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TipPlacementSection(
    title: String,
    showLabel: String,
    leftLabel: String,
    middleLabel: String,
    rightLabel: String,
    style: AppDialogStyle,
    onShowClick: () -> Unit,
    onLeftClick: () -> Unit,
    onMiddleClick: () -> Unit,
    onRightClick: () -> Unit
) {
    TipSection(style = style) {
        TipValueRow(
            title = stringResource(R.string.show_hide),
            value = showLabel,
            style = style,
            onClick = onShowClick
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            TipCompactValue(
                title = stringResource(R.string.left),
                value = leftLabel,
                style = style,
                modifier = Modifier.weight(1f),
                onClick = onLeftClick
            )
            TipCompactValue(
                title = stringResource(R.string.middle),
                value = middleLabel,
                style = style,
                modifier = Modifier.weight(1f),
                onClick = onMiddleClick
            )
            TipCompactValue(
                title = stringResource(R.string.right),
                value = rightLabel,
                style = style,
                modifier = Modifier.weight(1f),
                onClick = onRightClick
            )
        }
    }
}

@Composable
private fun TipColorSection(
    colorRefreshTick: Int,
    style: AppDialogStyle,
    onTipColorClick: () -> Unit,
    onDividerColorClick: () -> Unit
) {
    val tipColorLabel = remember(colorRefreshTick) { tipColorText() }
    val dividerColorLabel = remember(colorRefreshTick) { tipDividerColorText() }
    TipSection(style = style) {
        TipValueRow(
            title = stringResource(R.string.text_color),
            value = tipColorLabel,
            style = style,
            onClick = onTipColorClick
        )
        TipValueRow(
            title = stringResource(R.string.tip_divider_color),
            value = dividerColorLabel,
            style = style,
            onClick = onDividerColorClick
        )
    }
}

@Composable
private fun TipValueRow(
    title: String,
    value: String,
    style: AppDialogStyle,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.actionRadius))
            .background(style.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = value,
                color = style.accent,
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TipCompactValue(
    title: String,
    value: String,
    style: AppDialogStyle,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(style.actionRadius))
            .background(style.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            color = style.secondaryText,
            fontSize = MaterialTheme.typography.labelSmall.fontSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            color = style.primaryText,
            fontSize = MaterialTheme.typography.bodySmall.fontSize,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun tipColorText(): String {
    val names = ReadTipConfig.tipColorNames
    val color = ReadTipConfig.tipColor
    return if (color == 0) {
        names.first()
    } else {
        "#${color.hexString}"
    }
}

private fun tipDividerColorText(): String {
    val names = ReadTipConfig.tipDividerColorNames
    return when (val color = ReadTipConfig.tipDividerColor) {
        -1, 0 -> names[color + 1]
        else -> "#${color.hexString}"
    }
}
