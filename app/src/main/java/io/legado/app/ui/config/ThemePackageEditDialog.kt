package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.lib.theme.ThemeStore
import kotlinx.coroutines.launch
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.toMiuixPalette

/**
 * 主题包新增/编辑弹框（my-compose-full W5 专项，1.3 收官：
 * 原 AndroidAlertBuilder + DialogThemePackageEditBinding 表单 + setupXxx View 助手全量重写为 Compose）。
 *
 * 状态与持久化链在宿主 [ThemeManageActivity]（pending* Compose 状态 + saveThemeFromDialog），
 * 本弹框只做渲染与转发；第三方 ColorPickerDialog/FontSelectDialog/图片裁剪回调链零改动。
 */
class ThemePackageEditDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    private enum class EditTab(val titleRes: Int) {
        COLOR(R.string.theme_group_color),
        IMAGE(R.string.theme_group_image),
        INTERFACE(R.string.theme_group_interface),
        FONT(R.string.theme_group_font)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val host = activity as? ThemeManageActivity
        return androidx.compose.ui.platform.ComposeView(requireContext()).apply {
            setViewCompositionStrategy(androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                if (host == null) {
                    dismissAllowingStateLoss()
                    return@setContent
                }
                ThemePackageEditContent(host, onClose = { dismissAllowingStateLoss() })
            }
        }
    }
}

@Composable
private fun ThemePackageEditContent(host: ThemeManageActivity, onClose: () -> Unit) {
    val style = rememberAppDialogStyle()
    var name by remember { mutableStateOf(host.editNameValue) }
    var tab by remember { mutableIntStateOf(0) }
    val tabs = remember {
        listOf(
            EditTabTitle(R.string.theme_group_color),
            EditTabTitle(R.string.theme_group_image),
            EditTabTitle(R.string.theme_group_interface),
            EditTabTitle(R.string.theme_group_font)
        )
    }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.7f).dp

    AppDialogFrame(
        title = if (host.editingEntry == null) {
            stringResource(R.string.theme_manual_add)
        } else {
            stringResource(R.string.theme_edit)
        },
        content = {
            Column(modifier = Modifier.heightIn(max = maxHeight)) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    readOnly = host.editNameReadOnly,
                    textStyle = TextStyle(
                        color = style.primaryText,
                        fontSize = 15.sp,
                        fontFamily = style.bodyFontFamily
                    ),
                    cursorBrush = SolidColor(style.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(style.fieldSurface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    decorationBox = { innerTextField ->
                        Box {
                            if (name.isBlank()) {
                                Text(
                                    text = stringResource(
                                        if (host.isNightTheme) R.string.theme_night else R.string.theme_day
                                    ),
                                    color = style.secondaryText,
                                    fontSize = 15.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp, bottom = 8.dp)
                ) {
                    tabs.forEachIndexed { index, item ->
                        val selected = tab == index
                        Text(
                            text = stringResource(item.titleRes),
                            color = if (selected) style.accent else style.primaryText,
                            fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) style.fieldSurface else style.surface)
                                .clickable { tab = index }
                                .padding(vertical = 7.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    when (tab) {
                        0 -> ColorGroup(host)
                        1 -> ImageGroup(host)
                        2 -> InterfaceGroup(host)
                        else -> FontGroup(host)
                    }
                }
            }
        },
        actions = {
            val palette = style.toMiuixPalette()
            if (host.editingEntry?.source == ThemePackageManager.Source.LOCAL) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.theme_delete_local),
                    palette = palette,
                    onClick = {
                        val entry = host.editingEntry
                        if (entry?.source == ThemePackageManager.Source.LOCAL) {
                            host.lifecycleScope.launch {
                                host.confirmDeleteTheme(
                                    entry,
                                    host.getString(R.string.theme_delete_local_confirm)
                                ) {
                                    ThemePackageManager.deleteLocal(entry)
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        host.loadThemes()
                                        onClose()
                                    }
                                }
                            }
                        }
                    }
                )
            }
            LegadoMiuixActionButton(
                text = stringResource(R.string.dialog_cancel),
                palette = palette,
                onClick = { onClose() }
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.dialog_confirm),
                palette = palette,
                onClick = {
                    if (host.saveThemeFromDialog(name)) {
                        onClose()
                    }
                }
            )
        }
    )
}

private data class EditTabTitle(val titleRes: Int)

@Composable
private fun ColorGroup(host: ThemeManageActivity) {
    val style = rememberAppDialogStyle()
    ThemeOptionRow(
        title = stringResource(R.string.theme_color_primary),
        value = host.editPrimaryValue,
        swatchColor = swatchOrNull(host.editPrimaryValue, ThemeStore.primaryColor(host)),
        onClick = { host.openColorPicker(ThemeManageActivity.colorPrimary, host.editPrimaryValue, ThemeStore.primaryColor(host)) }
    )
    ThemeOptionRow(
        title = stringResource(R.string.theme_color_accent),
        value = host.editAccentValue,
        swatchColor = swatchOrNull(host.editAccentValue, ThemeStore.accentColor(host)),
        onClick = { host.openColorPicker(ThemeManageActivity.colorAccent, host.editAccentValue, ThemeStore.accentColor(host)) }
    )
    ThemeOptionRow(
        title = stringResource(R.string.theme_color_background),
        value = host.editBackgroundValue,
        swatchColor = swatchOrNull(host.editBackgroundValue, ThemeStore.backgroundColor(host)),
        onClick = { host.openColorPicker(ThemeManageActivity.colorBackground, host.editBackgroundValue, ThemeStore.backgroundColor(host)) }
    )
    ThemeOptionRow(
        title = stringResource(R.string.theme_color_bottom_background),
        value = host.editBottomValue,
        swatchColor = swatchOrNull(host.editBottomValue, ThemeStore.bottomBackground(host)),
        onClick = { host.openColorPicker(ThemeManageActivity.colorBottomBackground, host.editBottomValue, ThemeStore.bottomBackground(host)) }
    )
    OptionalColorRow(
        title = stringResource(R.string.theme_color_card),
        value = host.pendingCardColor,
        target = ThemeManageActivity.colorCard,
        host = host
    )
    OptionalColorRow(
        title = stringResource(R.string.theme_color_muted),
        value = host.pendingMutedColor,
        target = ThemeManageActivity.colorMuted,
        host = host
    )
    OptionalColorRow(
        title = stringResource(R.string.theme_color_search_field_background),
        value = host.pendingSearchFieldBackgroundColor,
        target = ThemeManageActivity.colorSearchFieldBackground,
        host = host
    )
    OptionalColorRow(
        title = stringResource(R.string.theme_color_tab_background),
        value = host.pendingTabBackgroundColor,
        target = ThemeManageActivity.colorTabBackground,
        host = host
    )
    OptionalColorRow(
        title = stringResource(R.string.theme_color_shelf),
        value = host.pendingShelfColor,
        target = ThemeManageActivity.colorShelf,
        host = host
    )
}

@Composable
private fun ImageGroup(host: ThemeManageActivity) {
    ThemeOptionRow(
        title = stringResource(R.string.theme_image_main_background),
        value = host.imageValueText(ThemeManageActivity.ThemeImageTarget.MAIN),
        swatchColor = null,
        onClick = { host.showImageActions(ThemeManageActivity.ThemeImageTarget.MAIN) }
    )
    ThemeOptionRow(
        title = stringResource(R.string.theme_image_book_info_background),
        value = host.imageValueText(ThemeManageActivity.ThemeImageTarget.BOOK_INFO),
        swatchColor = null,
        onClick = { host.showImageActions(ThemeManageActivity.ThemeImageTarget.BOOK_INFO) }
    )
    ThemeOptionRow(
        title = stringResource(R.string.theme_image_panel_background),
        value = host.imageValueText(ThemeManageActivity.ThemeImageTarget.PANEL),
        swatchColor = null,
        onClick = { host.showImageActions(ThemeManageActivity.ThemeImageTarget.PANEL) }
    )
    ThemeOptionRow(
        title = stringResource(R.string.theme_image_panel_background_mode),
        value = host.panelBackgroundModeText(host.pendingPanelBackgroundScaleType),
        swatchColor = null,
        onClick = {
            val modes = listOf(ThemeConfig.PANEL_BG_CROP, ThemeConfig.PANEL_BG_FIT)
            host.showComposeChoiceListDialog(
                host.getString(R.string.theme_image_panel_background_mode),
                modes.map { host.panelBackgroundModeText(it) }
            ) { index ->
                host.pendingPanelBackgroundScaleType = modes[index]
            }
        }
    )
}

@Composable
private fun InterfaceGroup(host: ThemeManageActivity) {
    val style = rememberAppDialogStyle()
    ThemeOptionRow(
        title = stringResource(R.string.ui_corner_scale),
        value = host.cornerScaleText(),
        swatchColor = null,
        onClick = {
            host.showComposeNumberPickerDialog(
                title = host.getString(R.string.ui_corner_scale),
                value = (host.pendingUiCornerScale * 10).toInt(),
                minValue = 0,
                maxValue = 30,
                isDecimalMode = true,
                customText = host.getString(R.string.btn_default_s),
                onValue = { host.pendingUiCornerScale = (it / 10f).coerceIn(0f, 3f) },
                onCustom = { host.pendingUiCornerScale = 1f }
            )
        }
    )
    ThemeOptionRow(
        title = stringResource(R.string.ui_layout_alpha),
        value = host.getString(R.string.ui_layout_alpha_value, host.pendingUiLayoutAlpha),
        swatchColor = null,
        onClick = {
            host.showComposeNumberPickerDialog(
                title = host.getString(R.string.ui_layout_alpha),
                value = host.pendingUiLayoutAlpha,
                minValue = 0,
                maxValue = 100,
                customText = host.getString(R.string.btn_default_s),
                onValue = { host.pendingUiLayoutAlpha = it.coerceIn(0, 100) },
                onCustom = { host.pendingUiLayoutAlpha = 100 }
            )
        }
    )
    ThemeOptionRow(
        title = stringResource(R.string.dialog_alpha),
        value = host.getString(R.string.ui_layout_alpha_value, host.pendingDialogAlpha),
        swatchColor = null,
        onClick = {
            host.showComposeNumberPickerDialog(
                title = host.getString(R.string.dialog_alpha),
                value = host.pendingDialogAlpha,
                minValue = 0,
                maxValue = 100,
                customText = host.getString(R.string.btn_default_s),
                onValue = { host.pendingDialogAlpha = it.coerceIn(0, 100) },
                onCustom = { host.pendingDialogAlpha = 100 }
            )
        }
    )
    OptionalColorRow(
        title = stringResource(R.string.theme_panel_border_color),
        value = host.pendingPanelBorderColor,
        target = ThemeManageActivity.colorPanelBorder,
        host = host,
        disabledText = host.getString(R.string.disable)
    )
    ThemeOptionRow(
        title = stringResource(R.string.theme_panel_border_alpha),
        value = host.getString(R.string.ui_layout_alpha_value, host.pendingPanelBorderAlpha),
        swatchColor = null,
        onClick = {
            host.showComposeNumberPickerDialog(
                title = host.getString(R.string.theme_panel_border_alpha),
                value = host.pendingPanelBorderAlpha,
                minValue = 0,
                maxValue = 100,
                customText = host.getString(R.string.btn_default_s),
                onValue = { host.pendingPanelBorderAlpha = it.coerceIn(0, 100) },
                onCustom = { host.pendingPanelBorderAlpha = 100 }
            )
        }
    )
    OptionalIntRow(
        title = stringResource(R.string.theme_card_shadow),
        value = host.pendingCardShadow,
        minValue = 0,
        maxValue = 24,
        host = host
    ) { host.pendingCardShadow = it }
    OptionalFloatRow(
        title = stringResource(R.string.theme_card_background_blur),
        value = host.pendingCardBackgroundBlur,
        host = host
    ) { host.pendingCardBackgroundBlur = it }
    ThemeOptionRow(
        title = stringResource(R.string.ui_corner_search_follow),
        value = host.getString(if (host.pendingUiCornerSearchFollow) R.string.enable else R.string.disable),
        swatchColor = null,
        onClick = { host.pendingUiCornerSearchFollow = !host.pendingUiCornerSearchFollow }
    )
    ThemeOptionRow(
        title = stringResource(R.string.ui_corner_reply_follow),
        value = host.getString(if (host.pendingUiCornerReplyFollow) R.string.enable else R.string.disable),
        swatchColor = null,
        onClick = { host.pendingUiCornerReplyFollow = !host.pendingUiCornerReplyFollow }
    )
}

@Composable
private fun FontGroup(host: ThemeManageActivity) {
    ThemeOptionRow(
        title = stringResource(R.string.font_scale),
        value = if (host.pendingFontScale == 0) {
            host.getString(R.string.btn_default_s)
        } else {
            "%.1f".format(java.util.Locale.US, host.pendingFontScale / 10f)
        },
        swatchColor = null,
        onClick = {
            host.showComposeNumberPickerDialog(
                title = host.getString(R.string.font_scale),
                value = if (host.pendingFontScale == 0) 10 else host.pendingFontScale,
                minValue = 8,
                maxValue = 16,
                customText = host.getString(R.string.btn_default_s),
                onValue = { host.pendingFontScale = it.coerceIn(8, 16) },
                onCustom = { host.pendingFontScale = 0 }
            )
        }
    )
    ThemeOptionRow(
        title = stringResource(R.string.ui_font),
        value = host.uiFontDisplayName(host.pendingUiFontPath),
        swatchColor = null,
        onClick = { host.openFontPicker(ThemeManageActivity.FontTarget.UI) }
    )
    OptionalColorRow(
        title = stringResource(R.string.theme_ui_font_color),
        value = host.pendingUiFontColor,
        target = ThemeManageActivity.colorUiFont,
        host = host
    )
    ThemeOptionRow(
        title = stringResource(R.string.title_font),
        value = host.uiFontDisplayName(host.pendingTitleFontPath),
        swatchColor = null,
        onClick = { host.openFontPicker(ThemeManageActivity.FontTarget.TITLE) }
    )
    OptionalColorRow(
        title = stringResource(R.string.theme_title_font_color),
        value = host.pendingTitleFontColor,
        target = ThemeManageActivity.colorTitleFont,
        host = host
    )
}

@Composable
private fun OptionalColorRow(
    title: String,
    value: String?,
    target: Int,
    host: ThemeManageActivity,
    disabledText: String? = null
) {
    val normalized = host.normalizeOptionalColor(value)
    ThemeOptionRow(
        title = title,
        value = normalized ?: disabledText ?: host.getString(R.string.theme_value_follow_default),
        swatchColor = normalized?.let { runCatching { it.toColorInt() }.getOrNull() }?.let { Color(it) },
        onClick = {
            host.showComposeChoiceListDialog(
                title,
                listOf(
                    host.getString(R.string.theme_value_follow_default),
                    host.getString(R.string.select_color)
                )
            ) { index ->
                if (index == 0) {
                    host.setOptionalColor(target, null)
                } else {
                    host.openColorPicker(
                        target,
                        host.optionalColorForTarget(target),
                        host.optionalColorFallback(target)
                    )
                }
            }
        },
        onLongClick = { host.setOptionalColor(target, null) }
    )
}

@Composable
private fun OptionalIntRow(
    title: String,
    value: Int?,
    minValue: Int,
    maxValue: Int,
    host: ThemeManageActivity,
    onChanged: (Int?) -> Unit
) {
    ThemeOptionRow(
        title = title,
        value = value?.toString() ?: host.getString(R.string.theme_value_follow_default),
        swatchColor = null,
        onClick = {
            host.showComposeNumberPickerDialog(
                title = title,
                value = value ?: minValue,
                minValue = minValue,
                maxValue = maxValue,
                customText = host.getString(R.string.btn_default_s),
                onValue = { onChanged(it.coerceIn(minValue, maxValue)) },
                onCustom = { onChanged(null) }
            )
        }
    )
}

@Composable
private fun OptionalFloatRow(
    title: String,
    value: Float?,
    host: ThemeManageActivity,
    onChanged: (Float?) -> Unit
) {
    ThemeOptionRow(
        title = title,
        value = value?.let { "%.1f".format(java.util.Locale.US, it) }
            ?: host.getString(R.string.theme_value_follow_default),
        swatchColor = null,
        onClick = {
            host.showComposeNumberPickerDialog(
                title = title,
                value = ((value ?: 0f) * 10f).toInt(),
                minValue = 0,
                maxValue = 250,
                isDecimalMode = true,
                customText = host.getString(R.string.btn_default_s),
                onValue = { onChanged((it / 10f).coerceIn(0f, 25f)) },
                onCustom = { onChanged(null) }
            )
        }
    )
}

/**
 * 通用配置行（原 ItemThemePackageOptionBinding 视觉：标题+色板+值）。
 */
@Composable
private fun ThemeOptionRow(
    title: String,
    value: String,
    swatchColor: Color?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val style = rememberAppDialogStyle()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(style.fieldSurface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(
            text = title,
            color = style.primaryText,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (swatchColor != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(swatchColor)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            color = style.secondaryText,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    Spacer(modifier = Modifier.height(6.dp))
}

private fun swatchOrNull(value: String, fallbackColor: Int): Color? {
    val color = runCatching { value.toColorInt() }.getOrNull() ?: return null
    return Color(color)
}
