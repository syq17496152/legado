package io.legado.app.ui.widget.components

import io.legado.app.ui.widget.components.AppShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.help.HighlightStyle
import io.legado.app.help.HighlightStyle.Deco
import io.legado.app.help.HighlightStyle.FillShape
import io.legado.app.help.HighlightStyle.Kind
import io.legado.app.help.HighlightStyle.Shadow
import io.legado.app.help.HighlightStyle.Underline
import io.legado.app.help.HighlightStyles
import io.legado.app.ui.book.read.HighlightActionMenu
import java.util.Locale

/**
 * 高亮选色面板（task 12.2E，存量升级自 View 版 [io.legado.app.ui.book.read.HighlightStyleDialog]）。
 *
 * 规格：ui-standards §3.4 `HighlightStyleDialog`（task 12.2E）
 * - 容器：外层 BottomSheetDialogFragment 承载（BottomSheet 化），本组件渲染 Sheet 内容
 * - 预设色板：6 语义预设色格 **40dp×40dp**，3 列 2 行，格间距 8dp，容器 h16
 * - 选中态：当前样式命中预设时描边 `primary` 2dp
 * - 通道区：8 通道行（开关 + 取色色块 + 下划线线型切换），行高 ≥48dp
 * - 字体行：点击选字体 / 长按清除
 * - 点击即选即生效，无二级
 *
 * 受控组件：样式与显隐由 [style] 派生，动作回调（改样式/取色/选字体）由调用方提供。
 *
 * @param style 当前样式（宿主当前值）
 * @param onStyleChange 样式改动（开关/预设/线型/清除字体）
 * @param onPickColor 打开某通道取色器（dialogId 用 HL_*）
 * @param onPickFont 打开字体选择器（current 为当前字体路径）
 * @param fontDisplayName 当前字体的可读名（宿主算好传入；空=默认）
 * @param scrollable 自身是否滚动；嵌入已滚动容器（如 AppDialogFrame scrollContent=true）时
 * 必须传 false，避免 verticalScroll 嵌套收到无限高度约束导致测量崩溃
 */
@Composable
fun HighlightStyleSheet(
    style: HighlightStyle,
    onStyleChange: (HighlightStyle) -> Unit,
    onPickColor: (dialogId: Int, initial: Int, withAlpha: Boolean) -> Unit,
    onPickFont: (current: String) -> Unit,
    fontDisplayName: String,
    scrollable: Boolean = true,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (scrollable) {
                    Modifier.verticalScroll(rememberScrollState())
                } else {
                    Modifier
                }
            )
    ) {
        // ---------- 预设区：6 语义色格，3 列 2 行 ----------
        Text(
            text = stringResource(R.string.highlight_presets),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )
        HighlightStyles.presets.chunked(3).forEach { rowPresets ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                rowPresets.forEach { preset ->
                    HighlightPresetSwatch(
                        preset = preset,
                        selected = style == preset,
                        onClick = { onStyleChange(preset) }
                    )
                }
            }
        }
        HorizontalDivider(
            color = androidx.compose.ui.graphics.Color(
            io.legado.app.lib.theme.rememberThemeUiPalette().dividerColor
        ),
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // ---------- 通道区 ----------
        channels.forEach { ch ->
            HighlightChannelRow(
                channel = ch,
                style = style,
                onToggle = { on -> onStyleChange(ch.toggle(style, on)) },
                onExtra = { ch.onExtra?.let { onStyleChange(it(style)) } },
                onPickColor = { onPickColor(ch.dialogId, ch.color(style), ch.withAlpha) }
            )
            // B2-④：下划线启用时紧随其后显示「线宽 / 线距」滑条子行
            if (ch.dialogId == HighlightActionMenu.HL_UNDERLINE && ch.isOn(style)) {
                val u = style.underline ?: Underline()
                HighlightSliderSubRow(
                    labelRes = R.string.highlight_underline_width,
                    value = u.resolvedWidth,
                    min = 0.1f, max = 10f,
                    onValueChange = { onStyleChange(style.copy(underline = u.copy(width = it))) }
                )
                HighlightSliderSubRow(
                    labelRes = R.string.highlight_underline_distance,
                    value = u.resolvedDistance,
                    min = 0f, max = 20f,
                    onValueChange = { onStyleChange(style.copy(underline = u.copy(distance = it))) }
                )
            }
            // R1a：阴影启用时紧随其后显示「模糊半径」滑条子行
            if (ch.dialogId == HighlightActionMenu.HL_SHADOW && ch.isOn(style)) {
                val sd = style.shadow ?: Shadow()
                HighlightSliderSubRow(
                    labelRes = R.string.highlight_shadow_radius,
                    value = sd.radius,
                    min = 1f, max = 25f,
                    onValueChange = { onStyleChange(style.copy(shadow = sd.copy(radius = it))) }
                )
            }
        }
        HorizontalDivider(
            color = androidx.compose.ui.graphics.Color(
            io.legado.app.lib.theme.rememberThemeUiPalette().dividerColor
        ),
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // ---------- 字体行 ----------
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .combinedClickable(
                    onClick = { onPickFont(style.fontPath) },
                    onLongClick = {
                        if (style.fontPath.isNotEmpty()) onStyleChange(style.copy(fontPath = ""))
                    }
                )
                .padding(horizontal = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.highlight_font),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = fontDisplayName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(180.dp)
            )
        }
    }
}

/** 单色格：语义预设色 40dp×40dp 圆角，命中当前样式时描边 primary 2dp。 */
@Composable
private fun HighlightPresetSwatch(
    preset: HighlightStyle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val repColor = preset.fill.takeIf { it != 0 }
        ?: preset.textColor.takeIf { it != 0 }
        ?: preset.underline?.color?.takeIf { it != 0 }
        ?: preset.strike?.color?.takeIf { it != 0 }
        ?: preset.box?.color?.takeIf { it != 0 }
        ?: preset.emphasis?.color?.takeIf { it != 0 }
        ?: 0xFF888888.toInt()
    val shape = AppShapes.Chip
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(shape)
            .background(Color(repColor))
            .then(
                if (selected) {
                    Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape)
                } else {
                    Modifier.border(1.dp, androidx.compose.ui.graphics.Color(
            io.legado.app.lib.theme.rememberThemeUiPalette().dividerColor
        ), shape)
                }
            )
            .clickable(onClick = onClick)
    )
}

/** 通道描述（label 用资源 id，显隐/色值/切换逻辑以样式为纯函数派生）。 */
private data class ChannelInfo(
    val labelRes: Int,
    val dialogId: Int,
    val withAlpha: Boolean,
    val isOn: (HighlightStyle) -> Boolean,
    val color: (HighlightStyle) -> Int,
    val toggle: (HighlightStyle, Boolean) -> HighlightStyle,
    val underlineKind: ((HighlightStyle) -> Kind?)? = null,
    /** R1a：填充形状（行内显示当前形状名，点击 onExtra 循环切换） */
    val fillShapeOf: ((HighlightStyle) -> FillShape?)? = null,
    val onExtra: ((HighlightStyle) -> HighlightStyle)? = null
)

private val channels = listOf(
    ChannelInfo(R.string.highlight_bg_color, HighlightActionMenu.HL_FILL, true,
        { it.fill != 0 }, { it.fill },
        { s, on -> s.copy(fill = if (on) (if (s.fill != 0) s.fill else 0x80FFF176.toInt()) else 0) },
        fillShapeOf = { s -> s.resolvedFillShape },
        onExtra = { s -> s.copy(fillShape = nextFillShape(s.resolvedFillShape)) }),
    ChannelInfo(R.string.highlight_text_color, HighlightActionMenu.HL_TEXT, false,
        { it.textColor != 0 }, { it.textColor },
        { s, on -> s.copy(textColor = if (on) (if (s.textColor != 0) s.textColor else 0xFFE53935.toInt()) else 0) }),
    ChannelInfo(R.string.highlight_bold, -1, false,
        { it.bold }, { 0 }, { s, on -> s.copy(bold = on) }),
    ChannelInfo(R.string.highlight_italic, -1, false,
        { it.italic }, { 0 }, { s, on -> s.copy(italic = on) }),
    ChannelInfo(R.string.highlight_underline, HighlightActionMenu.HL_UNDERLINE, false,
        { it.underline != null }, { it.underline?.color ?: 0 },
        { s, on -> s.copy(underline = if (on) (s.underline ?: Underline()) else null) },
        underlineKind = { s -> s.underline?.kind },
        onExtra = { s -> s.copy(underline = (s.underline ?: Underline()).let { it.copy(kind = nextKind(it.kind)) }) }),
    ChannelInfo(R.string.highlight_strike, HighlightActionMenu.HL_STRIKE, false,
        { it.strike != null }, { it.strike?.color ?: 0 },
        { s, on -> s.copy(strike = if (on) (s.strike ?: Deco()) else null) }),
    ChannelInfo(R.string.highlight_box, HighlightActionMenu.HL_BOX, false,
        { it.box != null }, { it.box?.color ?: 0 },
        { s, on -> s.copy(box = if (on) (s.box ?: Deco()) else null) }),
    ChannelInfo(R.string.highlight_emphasis, HighlightActionMenu.HL_EMPHASIS, false,
        { it.emphasis != null }, { it.emphasis?.color ?: 0 },
        { s, on -> s.copy(emphasis = if (on) (s.emphasis ?: Deco()) else null) }),
    // R1a：文字阴影（颜色允许半透明；半径/偏移单位为 px，默认给一个可见的轻阴影供再调）
    ChannelInfo(R.string.highlight_shadow, HighlightActionMenu.HL_SHADOW, true,
        { it.shadow != null }, { it.shadow?.color ?: 0 },
        { s, on -> s.copy(shadow = if (on) (s.shadow ?: Shadow(radius = 6f, dy = 2f)) else null) })
)

/** 单通道行：开关 + 标签 + 线型切换 + 取色色块，行高 ≥48dp。 */
@Composable
private fun HighlightChannelRow(
    channel: ChannelInfo,
    style: HighlightStyle,
    onToggle: (Boolean) -> Unit,
    onExtra: () -> Unit,
    onPickColor: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 16.dp)
    ) {
        Checkbox(
            checked = channel.isOn(style),
            onCheckedChange = onToggle
        )
        Text(
            text = stringResource(channel.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        // 行内「额外项」：下划线线型 / R1a 填充形状（点击循环切换）
        val kind = channel.underlineKind?.invoke(style)
        val shape = channel.fillShapeOf?.invoke(style)
        val extraLabel = when {
            channel.isOn(style) && kind != null -> underlineKindLabel(kind)
            channel.isOn(style) && shape != null -> fillShapeLabel(shape)
            else -> null
        }
        if (extraLabel != null) {
            Text(
                text = extraLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(AppShapes.Chip)
                    .clickable(onClick = onExtra)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        if (channel.dialogId != -1 && channel.isOn(style)) {
            val c = channel.color(style)
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 20.dp)
                    .clip(AppShapes.Tiny)
                    .background(Color(if (c != 0) c else 0xFF888888.toInt()))
                    .clickable(onClick = onPickColor)
            )
        }
    }
}

/** B2-④：数值滑条子行（下划线线宽/线距）。取值范围与 `HighlightRuleStore.sanitizeRule` 既有域一致。 */
@Composable
private fun HighlightSliderSubRow(
    labelRes: Int,
    value: Float,
    min: Float,
    max: Float,
    onValueChange: (Float) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 16.dp)
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(60.dp)
        )
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onValueChange,
            valueRange = min..max,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = String.format(Locale.getDefault(), "%.1f", value),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(36.dp)
        )
    }
}

@Composable
private fun underlineKindLabel(kind: Kind): String = when (kind) {
    Kind.WAVY -> stringResource(R.string.highlight_underline_wavy)
    Kind.DASHED -> stringResource(R.string.highlight_underline_dashed)
    Kind.DOTTED -> stringResource(R.string.highlight_underline_dotted)
    Kind.DOUBLE -> stringResource(R.string.highlight_underline_double)
    else -> stringResource(R.string.highlight_underline_solid)
}

private fun nextKind(kind: Kind): Kind {
    val all = Kind.entries
    return all[(all.indexOf(kind) + 1) % all.size]
}

/** R1a：填充形状显示名 */
@Composable
private fun fillShapeLabel(shape: FillShape): String = when (shape) {
    FillShape.ROUNDED -> stringResource(R.string.highlight_shape_rounded)
    FillShape.MARKER -> stringResource(R.string.highlight_shape_marker)
    FillShape.HALF -> stringResource(R.string.highlight_shape_half)
    FillShape.BASELINE -> stringResource(R.string.highlight_shape_baseline)
    FillShape.PILL -> stringResource(R.string.highlight_shape_pill)
    else -> stringResource(R.string.highlight_shape_rectangle)
}

/** R1a：填充形状循环切换（矩形 → 圆角 → 荧光笔 → 半高 → 基线 → 胶囊） */
private fun nextFillShape(shape: FillShape): FillShape {
    val all = FillShape.entries
    return all[(all.indexOf(shape) + 1) % all.size]
}
