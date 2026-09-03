package io.legado.app.ui.config.theme.compose

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.help.config.ThemePaletteExtractor
import io.legado.app.ui.theme.LegadoTypography
import io.legado.app.ui.widget.components.ThemeSpec
import io.legado.app.ui.widget.components.toM3Scheme
import io.legado.app.utils.ColorUtils
import java.util.Locale

/**
 * AD-05 Compose 主题编辑器 4 区界面：① 色板 ② 壁纸 ③ 质感滑杆 ④ 预览画布。
 *
 * 预览硬性门禁（评审强制项）：[ThemePreviewCanvas] 只消费 ViewModel 传入的临时色参数
 * （state 派生的 ThemeSpec/卡片色/壁纸位图），禁止 context.backgroundColor 等
 * pref 直读穿透——本文件不 import io.legado.app.lib.theme 的颜色扩展。
 */
@Composable
fun ThemeEditorScreen(
    viewModel: ThemeEditorViewModel,
    onSelectWallpaper: () -> Unit,
    onCloseRequest: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var editingSlot by remember { mutableStateOf<ThemeColorSlot?>(null) }

    LaunchedEffect(state.applySuccess) {
        if (state.applySuccess) {
            onCloseRequest()
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        DayNightTabs(
            isNight = state.isNight,
            onSwitch = { viewModel.switchMode(it) }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = EditorMaxContentHeight)
                .verticalScroll(rememberScrollState())
        ) {
            SuggestPaletteSection(
                state = state,
                onApply = { viewModel.applySuggestedPalette() }
            )
            ColorPaletteSection(state) { slot ->
                editingSlot = slot
            }
            WallpaperSection(
                state = state,
                onSelect = onSelectWallpaper,
                onBlurChange = { viewModel.updateWallpaperBlur(it) },
                onClear = { viewModel.clearWallpaper() }
            )
            TextureSlidersSection(
                state = state,
                onChange = { kind, value -> viewModel.updateSlider(kind, value) },
                onFollowDefault = { viewModel.resetSliderToDefault(it) }
            )
            ThemePreviewSection(state)
            Spacer(modifier = Modifier.height(4.dp))
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.End
        ) {
            Text(
                text = stringResource(R.string.cancel),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onCloseRequest() }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            )
            Text(
                text = stringResource(R.string.theme_apply),
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { viewModel.apply() }
                    .padding(horizontal = 22.dp, vertical = 10.dp)
            )
        }
    }

    editingSlot?.let { slot ->
        val current = state.colorOf(slot)
        val allowFollowDefault = state.isOptionalSlot(slot)
        SimpleColorPickerDialog(
            title = stringResource(slot.titleRes()),
            initialColor = ThemePaletteExtractor.parseHexOrNull(current)?.let { Color(it) }
                ?: Color.Gray,
            allowFollowDefault = allowFollowDefault && current == null,
            onDismiss = { editingSlot = null },
            onConfirm = { hex ->
                if (hex == null) {
                    viewModel.clearOptionalColor(slot)
                } else {
                    viewModel.updateColor(slot, hex)
                }
                editingSlot = null
            }
        )
    }
}

private val EditorMaxContentHeight = 460.dp

@Composable
private fun DayNightTabs(
    isNight: Boolean,
    onSwitch: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TabButton(
            text = stringResource(R.string.theme_day),
            selected = !isNight,
            onClick = { onSwitch(false) },
            modifier = Modifier.weight(1f)
        )
        TabButton(
            text = stringResource(R.string.theme_night),
            selected = isNight,
            onClick = { onSwitch(true) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

/** ① 色板区：4 固定色 + 5 可选色 */
@Composable
private fun ColorPaletteSection(
    state: ThemeEditorState,
    onEdit: (ThemeColorSlot) -> Unit
) {
    SectionCard(title = stringResource(R.string.theme_group_color)) {
        state.fixedSlots().forEach { slot ->
            ColorRow(
                label = stringResource(slot.titleRes()),
                hex = state.colorOf(slot),
                allowFollowDefault = false,
                onEdit = { onEdit(slot) },
                onFollowDefault = null
            )
        }
        state.optionalSlots().forEach { slot ->
            ColorRow(
                label = stringResource(slot.titleRes()),
                hex = state.colorOf(slot),
                allowFollowDefault = true,
                onEdit = { onEdit(slot) },
                onFollowDefault = { onEdit(slot) }
            )
        }
    }
}

@Composable
private fun ColorRow(
    label: String,
    hex: String?,
    allowFollowDefault: Boolean,
    onEdit: () -> Unit,
    onFollowDefault: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onEdit() }
            .padding(horizontal = 4.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(
                    hex?.let { ThemePaletteExtractor.parseHexOrNull(it)?.let { c -> Color(c) } }
                        ?: Color.Transparent
                )
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = hex ?: stringResource(R.string.theme_value_follow_default),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (allowFollowDefault && onFollowDefault != null && hex != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.theme_value_follow_default),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onFollowDefault() }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}

/** ② 壁纸区：选图入口 + 模糊度滑杆 */
@Composable
private fun WallpaperSection(
    state: ThemeEditorState,
    onSelect: () -> Unit,
    onBlurChange: (Int) -> Unit,
    onClear: () -> Unit
) {
    SectionCard(title = stringResource(R.string.theme_image_main_background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onSelect() }
                .padding(horizontal = 4.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = state.wallpaperPath?.let { path ->
                    path.substringAfterLast('/').ifBlank { stringResource(R.string.theme_image_selected) }
                } ?: stringResource(R.string.theme_image_select),
                color = if (state.wallpaperPath != null) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.primary
                },
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.wallpaperPath != null) {
                Text(
                    text = stringResource(R.string.theme_image_delete),
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onClear() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
        if (state.wallpaperPath != null) {
            SliderRow(
                label = stringResource(R.string.theme_image_blur),
                valueText = state.wallpaperBlur.toString(),
                value = state.wallpaperBlur.toFloat(),
                valueRange = 0f..25f,
                steps = 24,
                onValueChange = { onBlurChange(it.toInt()) }
            )
        }
    }
}

/** ③ 质感滑杆区：圆角 / 透明度 / 阴影 / 字体缩放（阴影与字体缩放支持"跟随默认"档） */
@Composable
private fun TextureSlidersSection(
    state: ThemeEditorState,
    onChange: (ThemeSliderKind, Float) -> Unit,
    onFollowDefault: (ThemeSliderKind) -> Unit
) {
    SectionCard(title = stringResource(R.string.theme_group_interface)) {
        SliderRow(
            label = stringResource(R.string.ui_corner_scale),
            valueText = "x${"%.1f".format(Locale.US, state.uiCornerScale)}",
            value = state.uiCornerScale,
            valueRange = 0f..3f,
            steps = 29,
            onValueChange = { onChange(ThemeSliderKind.CORNER_SCALE, it) }
        )
        SliderRow(
            label = stringResource(R.string.ui_layout_alpha),
            valueText = "${state.uiLayoutAlpha}%",
            value = state.uiLayoutAlpha.toFloat(),
            valueRange = 0f..100f,
            steps = 99,
            onValueChange = { onChange(ThemeSliderKind.LAYOUT_ALPHA, it) }
        )
        SliderRow(
            label = stringResource(R.string.theme_card_shadow),
            valueText = state.cardShadow?.toString()
                ?: stringResource(R.string.theme_value_follow_default),
            value = state.cardShadow?.toFloat() ?: 12f,
            valueRange = 0f..24f,
            steps = 23,
            onValueChange = { onChange(ThemeSliderKind.CARD_SHADOW, it) },
            onFollowDefault = if (state.cardShadow != null) {
                { onFollowDefault(ThemeSliderKind.CARD_SHADOW) }
            } else {
                null
            }
        )
        SliderRow(
            label = stringResource(R.string.font_scale),
            valueText = state.fontScale?.let { "x${"%.1f".format(Locale.US, it / 10f)}" }
                ?: stringResource(R.string.theme_value_follow_default),
            value = state.fontScale?.toFloat() ?: 12f,
            valueRange = 8f..16f,
            steps = 7,
            onValueChange = { onChange(ThemeSliderKind.FONT_SCALE, it) },
            onFollowDefault = if (state.fontScale != null) {
                { onFollowDefault(ThemeSliderKind.FONT_SCALE) }
            } else {
                null
            }
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onFollowDefault: (() -> Unit)? = null
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = valueText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (onFollowDefault != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.theme_value_follow_default),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onFollowDefault() }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                )
            }
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                thumbColor = MaterialTheme.colorScheme.primary
            )
        )
    }
}

/**
 * ④ 预览画布区（硬性门禁）：主页/阅读/设置三页 mock（顶部页选择行切换），
 * 全部颜色由 state 参数化派生 → 嵌套 MaterialTheme(临时 colorScheme)，全程不写 pref。
 */
@Composable
private fun ThemePreviewSection(state: ThemeEditorState) {
    var previewPage by remember { mutableStateOf(0) }
    SectionCard(title = stringResource(R.string.theme_preview)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = previewPage == 0,
                onClick = { previewPage = 0 },
                label = { Text(text = stringResource(R.string.bookshelf), fontSize = 12.sp) }
            )
            FilterChip(
                selected = previewPage == 1,
                onClick = { previewPage = 1 },
                label = { Text(text = stringResource(R.string.reading), fontSize = 12.sp) }
            )
            FilterChip(
                selected = previewPage == 2,
                onClick = { previewPage = 2 },
                label = { Text(text = stringResource(R.string.setting), fontSize = 12.sp) }
            )
        }
        when (previewPage) {
            0 -> ThemePreviewCanvas(
                spec = state.buildPreviewSpec(),
                cardColor = state.previewCardColor(),
                mutedColor = state.previewMutedColor(),
                cornerScale = state.uiCornerScale,
                cardAlpha = state.uiLayoutAlpha.coerceIn(0, 100) / 100f,
                wallpaper = state.wallpaperBitmap
            )
            1 -> ReadingPreviewCanvas(spec = state.buildPreviewSpec())
            2 -> SettingsPreviewCanvas(spec = state.buildPreviewSpec())
        }
    }
}

@Composable
private fun ThemePreviewCanvas(
    spec: ThemeSpec,
    cardColor: Color,
    mutedColor: Color,
    cornerScale: Float,
    cardAlpha: Float,
    wallpaper: androidx.compose.ui.graphics.ImageBitmap?
) {
    val radius = (12 * cornerScale.coerceIn(0f, 3f)).dp
    MaterialTheme(
        colorScheme = spec.toM3Scheme(),
        typography = LegadoTypography
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(radius))
                .background(spec.background)
        ) {
            // 顶栏 mock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(spec.background)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Legado",
                    color = spec.textPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .background(spec.primary, CircleShape)
                )
            }
            // 壁纸 + 卡片 mock
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp)
            ) {
                if (wallpaper != null) {
                    Image(
                        bitmap = wallpaper,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(radius))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(96.dp)
                            .clip(RoundedCornerShape(radius))
                            .background(mutedColor)
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                        .fillMaxWidth(0.7f)
                        .clip(RoundedCornerShape(radius))
                        .background(cardColor.copy(alpha = cardAlpha.coerceIn(0.32f, 1f)))
                        .padding(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(spec.textPrimary.copy(alpha = 0.7f))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.4f)
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(spec.textSecondary)
                    )
                }
            }
            // 列表行 mock x3
            repeat(3) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(radius / 2f))
                            .background(mutedColor)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.55f)
                                .height(7.dp)
                                .clip(RoundedCornerShape(3.5.dp))
                                .background(spec.textPrimary.copy(alpha = 0.75f))
                        )
                        Spacer(modifier = Modifier.height(5.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.35f)
                                .height(5.dp)
                                .clip(RoundedCornerShape(2.5.dp))
                                .background(spec.textSecondary)
                        )
                    }
                    Box(
                        modifier = Modifier
                            .width(34.dp)
                            .height(14.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(spec.primary.copy(alpha = 0.85f))
                    )
                }
            }
        }
    }
}

/** 阅读 mock：整块背景 + 居中两段正文（16sp/13sp）+ 底部页码，颜色全部来自 state 派生 spec */
@Composable
private fun ReadingPreviewCanvas(spec: ThemeSpec) {
    MaterialTheme(
        colorScheme = spec.toM3Scheme(),
        typography = LegadoTypography
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(spec.background)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "这里是正文预览效果，展示当前主题的主要文字颜色与字号。",
                    color = spec.textPrimary,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
                Text(
                    text = "次要文字颜色更浅，用于注释与补充说明。",
                    color = spec.textSecondary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp
                )
            }
            Text(
                text = "128",
                color = spec.textSecondary,
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            )
        }
    }
}

/** 设置 mock：2 个设置行（图标占位 + 标题/副标题 + Switch，checkedTrackColor 用临时 colorScheme.secondary） */
@Composable
private fun SettingsPreviewCanvas(spec: ThemeSpec) {
    MaterialTheme(
        colorScheme = spec.toM3Scheme(),
        typography = LegadoTypography
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(spec.background)
        ) {
            listOf(true, false).forEach { checked ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(spec.textSecondary.copy(alpha = 0.35f))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (checked) "已开启的设置项" else "未开启的设置项",
                            color = spec.textPrimary,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "设置项的副标题说明文字",
                            color = spec.textSecondary,
                            fontSize = 11.sp
                        )
                    }
                    Switch(
                        checked = checked,
                        onCheckedChange = null,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.secondary
                        )
                    )
                }
            }
        }
    }
}

/** 壁纸提取建议色区 */
@Composable
private fun SuggestPaletteSection(
    state: ThemeEditorState,
    onApply: () -> Unit
) {
    val palette = state.suggestions ?: return
    val candidate = if (state.isNight) palette.night else palette.day
    SectionCard(title = stringResource(R.string.theme_editor_suggest_palette)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(candidate.primary, candidate.accent, candidate.background, candidate.card)
                .forEach { hex ->
                    Box(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(24.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                ThemePaletteExtractor.parseHexOrNull(hex)?.let { Color(it) }
                                    ?: Color.Transparent
                            )
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(6.dp))
                    )
                }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = stringResource(R.string.theme_apply),
                color = MaterialTheme.colorScheme.primary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onApply() }
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

/**
 * 简化取色弹窗：预设色板网格 + hex 输入（任务允许的简化实现）。
 */
@Composable
private fun SimpleColorPickerDialog(
    title: String,
    initialColor: Color,
    allowFollowDefault: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit
) {
    var hexInput by remember {
        mutableStateOf(ThemePaletteExtractor.toHex6(initialColor.toArgb()))
    }
    var previewColor by remember { mutableStateOf(initialColor) }

    fun applyHexInput(text: String) {
        hexInput = text
        ThemePaletteExtractor.parseHexOrNull(text)?.let { argb ->
            previewColor = Color(argb)
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                PresetColorGrid(
                    onPick = { argb ->
                        applyHexInput(ThemePaletteExtractor.toHex6(argb))
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(previewColor)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { applyHexInput(it) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        label = { Text(text = stringResource(R.string.theme_editor_hex_color)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            cursorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (allowFollowDefault) {
                        Text(
                            text = stringResource(R.string.theme_value_follow_default),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onConfirm(null) }
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = stringResource(R.string.cancel),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                    Text(
                        text = stringResource(R.string.ok),
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                val argb = ThemePaletteExtractor.parseHexOrNull(hexInput)
                                if (argb != null) {
                                    onConfirm(ThemePaletteExtractor.toHex6(argb))
                                } else {
                                    onDismiss()
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetColorGrid(
    onPick: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PresetColors.chunked(6).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowColors.forEach { argb ->
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(argb))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                            .clickable { onPick(argb) }
                    )
                }
            }
        }
    }
}

/** 取色器预设色样（工具色板数据，非主题语义槽） */
private val PresetColors: List<Int> = listOf(
    0xFFE53935, 0xFFD81B60, 0xFF8E24AA, 0xFF5E35B1, 0xFF3949AB, 0xFF1E88E5,
    0xFF039BE5, 0xFF00ACC1, 0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33,
    0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E, 0xFF6D4C41, 0xFF546E7A,
    0xFF212121, 0xFF757575, 0xFFBDBDBD, 0xFFEEEEEE, 0xFFFAFAFA, 0xFFFFFFFF
).map { it.toInt() }

/** 槽位标题资源 */
private fun ThemeColorSlot.titleRes(): Int {
    return when (this) {
        ThemeColorSlot.PRIMARY -> R.string.theme_color_primary
        ThemeColorSlot.ACCENT -> R.string.theme_color_accent
        ThemeColorSlot.BACKGROUND -> R.string.theme_color_background
        ThemeColorSlot.BOTTOM_BACKGROUND -> R.string.theme_color_bottom_background
        ThemeColorSlot.CARD -> R.string.theme_color_card
        ThemeColorSlot.MUTED -> R.string.theme_color_muted
        ThemeColorSlot.SEARCH_FIELD -> R.string.theme_color_search_field_background
        ThemeColorSlot.TAB_BACKGROUND -> R.string.theme_color_tab_background
        ThemeColorSlot.SHELF -> R.string.theme_color_shelf
    }
}

private fun ThemeEditorState.fixedSlots(): List<ThemeColorSlot> = listOf(
    ThemeColorSlot.PRIMARY,
    ThemeColorSlot.ACCENT,
    ThemeColorSlot.BACKGROUND,
    ThemeColorSlot.BOTTOM_BACKGROUND
)

private fun ThemeEditorState.optionalSlots(): List<ThemeColorSlot> = listOf(
    ThemeColorSlot.CARD,
    ThemeColorSlot.MUTED,
    ThemeColorSlot.SEARCH_FIELD,
    ThemeColorSlot.TAB_BACKGROUND,
    ThemeColorSlot.SHELF
)

private fun ThemeEditorState.isOptionalSlot(slot: ThemeColorSlot): Boolean {
    return slot !in fixedSlots()
}

private fun ThemeEditorState.colorOf(slot: ThemeColorSlot): String? {
    return when (slot) {
        ThemeColorSlot.PRIMARY -> primaryColor
        ThemeColorSlot.ACCENT -> accentColor
        ThemeColorSlot.BACKGROUND -> backgroundColor
        ThemeColorSlot.BOTTOM_BACKGROUND -> bottomBackground
        ThemeColorSlot.CARD -> cardColor
        ThemeColorSlot.MUTED -> mutedColor
        ThemeColorSlot.SEARCH_FIELD -> searchFieldBackgroundColor
        ThemeColorSlot.TAB_BACKGROUND -> tabBackgroundColor
        ThemeColorSlot.SHELF -> shelfColor
    }
}

/**
 * 预览参数化派生（门禁核心）：ThemeSpec 语义与 buildLegadoColorScheme 对齐
 * （primary=accent 操作色 / secondary=primary），文字色由背景明暗派生，不读任何 pref。
 */
private fun ThemeEditorState.buildPreviewSpec(): ThemeSpec {
    val bgArgb = ThemePaletteExtractor.parseHexOrNull(backgroundColor)
        ?: (if (isNight) 0xFF16181C.toInt() else 0xFFF6F7F9.toInt())
    val accentArgb = ThemePaletteExtractor.parseHexOrNull(accentColor) ?: bgArgb
    val primaryArgb = ThemePaletteExtractor.parseHexOrNull(primaryColor) ?: accentArgb
    val isLight = !isNight && ColorUtils.isColorLight(bgArgb)
    val textPrimary = if (isLight) 0xDD1C1B1F.toInt() else 0xFFE6E1E5.toInt()
    val textSecondary = if (isLight) 0x9948474D.toInt() else 0x99C7C5CA.toInt()
    return ThemeSpec(
        primary = Color(accentArgb),
        secondary = Color(primaryArgb),
        accent = Color(accentArgb),
        background = Color(bgArgb),
        textPrimary = Color(textPrimary),
        textSecondary = Color(textSecondary),
        isLight = isLight
    )
}

private fun ThemeEditorState.previewCardColor(): Color {
    val spec = buildPreviewSpec()
    val custom = cardColor?.let { ThemePaletteExtractor.parseHexOrNull(it) }
    val argb = custom ?: ColorUtils.blendColors(
        spec.background.toArgb(),
        spec.textPrimary.toArgb(),
        if (spec.isLight) 0.06f else 0.10f
    )
    return Color(argb)
}

private fun ThemeEditorState.previewMutedColor(): Color {
    val spec = buildPreviewSpec()
    val custom = mutedColor?.let { ThemePaletteExtractor.parseHexOrNull(it) }
    val argb = custom ?: ColorUtils.blendColors(
        spec.background.toArgb(),
        spec.textPrimary.toArgb(),
        if (spec.isLight) 0.03f else 0.06f
    )
    return Color(argb)
}
