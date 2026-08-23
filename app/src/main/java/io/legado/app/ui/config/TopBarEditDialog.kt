package io.legado.app.ui.config

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.help.config.TopBarConfig
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixPalette
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import java.util.Locale

class TopBarEditDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Management

    private var onNameChanged: ((String) -> Unit)? = null
    private var onStyleChanged: ((String) -> Unit)? = null
    private var onShowStyleSelector: (() -> Unit)? = null
    private var onShowCornerScalePicker: ((Float?) -> Unit)? = null
    private var onShowColorPicker: ((Int, Int) -> Unit)? = null
    private var onShowWallpaperSelector: (() -> Unit)? = null
    private var onShowWallpaperAlphaPicker: ((Int) -> Unit)? = null
    private var onShowFilterDefaultSelector: ((Boolean) -> Unit)? = null
    private var onToggleFilterToggleHidden: ((Boolean) -> Unit)? = null
    private var onShowTagBarAlphaPicker: ((Int) -> Unit)? = null
    private var onShowTagSelectedAlphaPicker: ((Int) -> Unit)? = null
    private var onToggleSearchInDefaultStyle: ((Boolean) -> Unit)? = null
    private var onSave: ((String) -> Unit)? = null
    private var onCancel: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val args = arguments ?: Bundle()
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val callbacksReady = onSave != null &&
                    onShowStyleSelector != null &&
                    onShowCornerScalePicker != null &&
                    onShowColorPicker != null &&
                    onShowWallpaperSelector != null &&
                    onShowWallpaperAlphaPicker != null &&
                    onShowFilterDefaultSelector != null &&
                    onToggleFilterToggleHidden != null &&
                    onShowTagBarAlphaPicker != null &&
                    onShowTagSelectedAlphaPicker != null &&
                    onToggleSearchInDefaultStyle != null
                LaunchedEffect(callbacksReady) {
                    if (!callbacksReady) {
                        dismissAllowingStateLoss()
                    }
                }
                val style = rememberAppDialogStyle()
                CompositionLocalProvider(
                    LocalTextStyle provides LocalTextStyle.current.copy(
                        fontFamily = style.bodyFontFamily
                    )
                ) {
                    TopBarEditDialogContent(
                        args = args,
                        style = style,
                        onNameChanged = { name ->
                            onNameChanged?.invoke(name)
                        },
                        onShowStyleSelector = {
                            onShowStyleSelector?.invoke() ?: dismissAllowingStateLoss()
                        },
                        onShowCornerScalePicker = {
                            onShowCornerScalePicker?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onShowColorPicker = { target, color ->
                            onShowColorPicker?.invoke(target, color) ?: dismissAllowingStateLoss()
                        },
                        onShowWallpaperSelector = {
                            onShowWallpaperSelector?.invoke() ?: dismissAllowingStateLoss()
                        },
                        onShowWallpaperAlphaPicker = {
                            onShowWallpaperAlphaPicker?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onShowFilterDefaultSelector = {
                            onShowFilterDefaultSelector?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onToggleFilterToggleHidden = {
                            onToggleFilterToggleHidden?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onShowTagBarAlphaPicker = {
                            onShowTagBarAlphaPicker?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onShowTagSelectedAlphaPicker = {
                            onShowTagSelectedAlphaPicker?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onToggleSearchInDefaultStyle = {
                            onToggleSearchInDefaultStyle?.invoke(it) ?: dismissAllowingStateLoss()
                        },
                        onSave = { name ->
                            val saveCallback = onSave
                            if (saveCallback != null) {
                                dismissAllowingStateLoss()
                                saveCallback(name)
                            } else {
                                dismissAllowingStateLoss()
                            }
                        },
                        onCancel = {
                            dismissAllowingStateLoss()
                            onCancel?.invoke()
                        }
                    )
                }
            }
        }
    }

    companion object {
        fun create(
            config: TopBarConfig.Config,
            onNameChanged: (String) -> Unit,
            onStyleChanged: (String) -> Unit,
            onShowStyleSelector: () -> Unit,
            onShowCornerScalePicker: (Float?) -> Unit,
            onShowColorPicker: (Int, Int) -> Unit,
            onShowWallpaperSelector: () -> Unit,
            onShowWallpaperAlphaPicker: (Int) -> Unit,
            onShowFilterDefaultSelector: (Boolean) -> Unit,
            onToggleFilterToggleHidden: (Boolean) -> Unit,
            onShowTagBarAlphaPicker: (Int) -> Unit,
            onShowTagSelectedAlphaPicker: (Int) -> Unit,
            onToggleSearchInDefaultStyle: (Boolean) -> Unit,
            onSave: (String) -> Unit,
            onCancel: () -> Unit
        ): TopBarEditDialog {
            return TopBarEditDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_NAME, config.name)
                    putString(ARG_STYLE, config.style)
                    putBoolean(ARG_IS_NIGHT, config.isNightMode)
                    putFloat(ARG_CORNER_SCALE, config.cornerScale ?: 1f)
                    putInt(ARG_BG_COLOR, config.backgroundColor ?: 0)
                    putBoolean(ARG_HAS_BG_COLOR, config.backgroundColor != null)
                    putString(ARG_WALLPAPER_PATH, config.wallpaperPath)
                    putInt(ARG_WALLPAPER_ALPHA, config.wallpaperAlpha)
                    putBoolean(ARG_FILTER_EXPANDED, config.expandFiltersByDefault)
                    putBoolean(ARG_HIDE_FILTER_TOGGLE, config.hideFilterToggleWhenExpanded)
                    putInt(ARG_TAG_BAR_COLOR, config.tagBarColor ?: 0)
                    putBoolean(ARG_HAS_TAG_BAR_COLOR, config.tagBarColor != null)
                    putInt(ARG_TAG_BAR_ALPHA, config.tagBarAlpha)
                    putInt(ARG_TAG_SELECTED_COLOR, config.tagSelectedColor ?: 0)
                    putBoolean(ARG_HAS_TAG_SELECTED_COLOR, config.tagSelectedColor != null)
                    putInt(ARG_TAG_SELECTED_ALPHA, config.tagSelectedAlpha)
                    putBoolean(ARG_SHOW_SEARCH, config.showSearchInDefaultStyle)
                }
                this.onNameChanged = onNameChanged
                this.onStyleChanged = onStyleChanged
                this.onShowStyleSelector = onShowStyleSelector
                this.onShowCornerScalePicker = onShowCornerScalePicker
                this.onShowColorPicker = onShowColorPicker
                this.onShowWallpaperSelector = onShowWallpaperSelector
                this.onShowWallpaperAlphaPicker = onShowWallpaperAlphaPicker
                this.onShowFilterDefaultSelector = onShowFilterDefaultSelector
                this.onToggleFilterToggleHidden = onToggleFilterToggleHidden
                this.onShowTagBarAlphaPicker = onShowTagBarAlphaPicker
                this.onShowTagSelectedAlphaPicker = onShowTagSelectedAlphaPicker
                this.onToggleSearchInDefaultStyle = onToggleSearchInDefaultStyle
                this.onSave = onSave
                this.onCancel = onCancel
            }
        }

        const val ARG_NAME = "name"
        const val ARG_STYLE = "style"
        const val ARG_IS_NIGHT = "isNight"
        const val ARG_CORNER_SCALE = "cornerScale"
        const val ARG_BG_COLOR = "bgColor"
        const val ARG_HAS_BG_COLOR = "hasBgColor"
        const val ARG_WALLPAPER_PATH = "wallpaperPath"
        const val ARG_WALLPAPER_ALPHA = "wallpaperAlpha"
        const val ARG_FILTER_EXPANDED = "filterExpanded"
        const val ARG_HIDE_FILTER_TOGGLE = "hideFilterToggle"
        const val ARG_TAG_BAR_COLOR = "tagBarColor"
        const val ARG_HAS_TAG_BAR_COLOR = "hasTagBarColor"
        const val ARG_TAG_BAR_ALPHA = "tagBarAlpha"
        const val ARG_TAG_SELECTED_COLOR = "tagSelectedColor"
        const val ARG_HAS_TAG_SELECTED_COLOR = "hasTagSelectedColor"
        const val ARG_TAG_SELECTED_ALPHA = "tagSelectedAlpha"
        const val ARG_SHOW_SEARCH = "showSearchInDefaultStyle"
    }
}

@Composable
private fun TopBarEditDialogContent(
    args: Bundle,
    style: AppDialogStyle,
    onNameChanged: (String) -> Unit,
    onShowStyleSelector: () -> Unit,
    onShowCornerScalePicker: (Float?) -> Unit,
    onShowColorPicker: (Int, Int) -> Unit,
    onShowWallpaperSelector: () -> Unit,
    onShowWallpaperAlphaPicker: (Int) -> Unit,
    onShowFilterDefaultSelector: (Boolean) -> Unit,
    onToggleFilterToggleHidden: (Boolean) -> Unit,
    onShowTagBarAlphaPicker: (Int) -> Unit,
    onShowTagSelectedAlphaPicker: (Int) -> Unit,
    onToggleSearchInDefaultStyle: (Boolean) -> Unit,
    onSave: (String) -> Unit,
    onCancel: () -> Unit
) {
    val palette = style.toMiuixPalette()
    var name by rememberSaveable { mutableStateOf(args.getString(TopBarEditDialog.ARG_NAME).orEmpty()) }
    val currentStyle = args.getString(TopBarEditDialog.ARG_STYLE)
        ?: TopBarConfig.STYLE_DEFAULT
    val isRegular = currentStyle == TopBarConfig.STYLE_REGULAR
    val cornerScale = args.getFloat(TopBarEditDialog.ARG_CORNER_SCALE, 1f)
    val hasBgColor = args.getBoolean(TopBarEditDialog.ARG_HAS_BG_COLOR)
    val bgColor = args.getInt(TopBarEditDialog.ARG_BG_COLOR)
    val wallpaperPath = args.getString(TopBarEditDialog.ARG_WALLPAPER_PATH)
    val wallpaperAlpha = args.getInt(TopBarEditDialog.ARG_WALLPAPER_ALPHA, 100)
    val filterExpanded = args.getBoolean(TopBarEditDialog.ARG_FILTER_EXPANDED)
    val hideFilterToggle = args.getBoolean(TopBarEditDialog.ARG_HIDE_FILTER_TOGGLE)
    val showSearchInDefaultStyle = args.getBoolean(TopBarEditDialog.ARG_SHOW_SEARCH)
    val hasTagBarColor = args.getBoolean(TopBarEditDialog.ARG_HAS_TAG_BAR_COLOR)
    val tagBarColor = args.getInt(TopBarEditDialog.ARG_TAG_BAR_COLOR)
    val tagBarAlpha = args.getInt(TopBarEditDialog.ARG_TAG_BAR_ALPHA, 100)
    val hasTagSelectedColor = args.getBoolean(TopBarEditDialog.ARG_HAS_TAG_SELECTED_COLOR)
    val tagSelectedColor = args.getInt(TopBarEditDialog.ARG_TAG_SELECTED_COLOR)
    val tagSelectedAlpha = args.getInt(TopBarEditDialog.ARG_TAG_SELECTED_ALPHA, 100)

    AppDialogFrame(
        title = stringResource(R.string.top_bar_edit),
        scrollContent = true,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Name input
                TopBarEditTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        onNameChanged(it)
                    },
                    label = stringResource(R.string.top_bar_name),
                    style = style
                )
                // Style selector
                TopBarEditOptionRow(
                    title = stringResource(R.string.top_bar_style),
                    value = topBarStyleLabelResource(currentStyle),
                    palette = palette,
                    style = style,
                    onClick = onShowStyleSelector
                )
                if (isRegular) {
                    // Corner scale
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_corner_scale),
                        value = String.format(
                            Locale.ROOT,
                            "%.1f",
                            cornerScale.coerceIn(0f, 3f)
                        ),
                        palette = palette,
                        style = style,
                        onClick = { onShowCornerScalePicker(cornerScale) }
                    )
                    // Background color
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_background_color),
                        value = colorLabelResource(hasBgColor, bgColor),
                        colorPreview = if (hasBgColor) bgColor else null,
                        palette = palette,
                        style = style,
                        onClick = { onShowColorPicker(5104, bgColor) }
                    )
                    // Wallpaper
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_wallpaper),
                        value = wallpaperLabelResource(wallpaperPath),
                        palette = palette,
                        style = style,
                        onClick = onShowWallpaperSelector
                    )
                    // Wallpaper alpha
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_wallpaper_alpha),
                        value = "$wallpaperAlpha%",
                        palette = palette,
                        style = style,
                        onClick = { onShowWallpaperAlphaPicker(wallpaperAlpha) }
                    )
                    // Filter default
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_filter_default),
                        value = filterDefaultLabelResource(filterExpanded),
                        palette = palette,
                        style = style,
                        onClick = { onShowFilterDefaultSelector(filterExpanded) }
                    )
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_filter_toggle_button),
                        value = filterToggleLabelResource(hideFilterToggle),
                        palette = palette,
                        style = style,
                        onClick = { onToggleFilterToggleHidden(!hideFilterToggle) }
                    )
                    // Tag bar color
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_tag_bar_color),
                        value = colorLabelResource(hasTagBarColor, tagBarColor),
                        colorPreview = if (hasTagBarColor) tagBarColor else null,
                        palette = palette,
                        style = style,
                        onClick = { onShowColorPicker(5101, tagBarColor) }
                    )
                    // Tag bar alpha
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_tag_bar_alpha),
                        value = "$tagBarAlpha%",
                        palette = palette,
                        style = style,
                        onClick = { onShowTagBarAlphaPicker(tagBarAlpha) }
                    )
                    // Tag selected color
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_tag_selected_color),
                        value = colorLabelResource(hasTagSelectedColor, tagSelectedColor),
                        colorPreview = if (hasTagSelectedColor) tagSelectedColor else null,
                        palette = palette,
                        style = style,
                        onClick = { onShowColorPicker(5102, tagSelectedColor) }
                    )
                    // Tag selected alpha
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_tag_selected_alpha),
                        value = "$tagSelectedAlpha%",
                        palette = palette,
                        style = style,
                        onClick = { onShowTagSelectedAlphaPicker(tagSelectedAlpha) }
                    )
                } else {
                    // Default style: search button toggle
                    TopBarEditOptionRow(
                        title = stringResource(R.string.search),
                        value = if (showSearchInDefaultStyle) "On" else "Off",
                        palette = palette,
                        style = style,
                        onClick = { onToggleSearchInDefaultStyle(!showSearchInDefaultStyle) }
                    )
                    // Default style: tag bar color
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_tag_bar_color),
                        value = colorLabelResource(hasTagBarColor, tagBarColor),
                        colorPreview = if (hasTagBarColor) tagBarColor else null,
                        palette = palette,
                        style = style,
                        onClick = { onShowColorPicker(5101, tagBarColor) }
                    )
                    // Tag bar alpha
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_tag_bar_alpha),
                        value = "$tagBarAlpha%",
                        palette = palette,
                        style = style,
                        onClick = { onShowTagBarAlphaPicker(tagBarAlpha) }
                    )
                    // Tag selected color
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_tag_selected_color),
                        value = colorLabelResource(hasTagSelectedColor, tagSelectedColor),
                        colorPreview = if (hasTagSelectedColor) tagSelectedColor else null,
                        palette = palette,
                        style = style,
                        onClick = { onShowColorPicker(5102, tagSelectedColor) }
                    )
                    // Tag selected alpha
                    TopBarEditOptionRow(
                        title = stringResource(R.string.top_bar_tag_selected_alpha),
                        value = "$tagSelectedAlpha%",
                        palette = palette,
                        style = style,
                        onClick = { onShowTagSelectedAlphaPicker(tagSelectedAlpha) }
                    )
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = { onSave(name) },
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun TopBarEditTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    style: AppDialogStyle
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            color = style.secondaryText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(style.actionRadius),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = style.primaryText,
                unfocusedTextColor = style.primaryText,
                focusedContainerColor = style.fieldSurface,
                unfocusedContainerColor = style.fieldSurface,
                cursorColor = style.accent,
                focusedBorderColor = style.accent.copy(alpha = 0.55f),
                unfocusedBorderColor = style.stroke
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = style.primaryText,
                fontFamily = style.bodyFontFamily
            )
        )
    }
}

@Composable
private fun TopBarEditOptionRow(
    title: String,
    value: String,
    palette: LegadoMiuixPalette,
    style: AppDialogStyle,
    onClick: () -> Unit,
    colorPreview: Int? = null
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                color = style.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (colorPreview != null) {
                Surface(
                    modifier = Modifier
                        .width(18.dp)
                        .height(18.dp),
                    shape = RoundedCornerShape(50),
                    color = Color(colorPreview),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp
                ) {}
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(
                text = value,
                color = style.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun topBarStyleLabelResource(style: String): String {
    return stringResource(
        when (style) {
            TopBarConfig.STYLE_REGULAR -> R.string.top_bar_style_regular
            else -> R.string.top_bar_style_default
        }
    )
}

@Composable
private fun colorLabelResource(hasColor: Boolean, color: Int): String {
    return if (hasColor) {
        "#${Integer.toHexString(color).takeLast(6).uppercase(Locale.ROOT)}"
    } else {
        stringResource(R.string.top_bar_follow_theme)
    }
}

@Composable
private fun wallpaperLabelResource(path: String?): String {
    return if (path.isNullOrBlank()) {
        stringResource(R.string.theme_image_value_unselected)
    } else {
        stringResource(R.string.theme_image_selected)
    }
}

@Composable
private fun filterDefaultLabelResource(expanded: Boolean): String {
    return stringResource(
        if (expanded) R.string.top_bar_filter_default_expanded
        else R.string.top_bar_filter_default_collapsed
    )
}

@Composable
private fun filterToggleLabelResource(hiddenWhenExpanded: Boolean): String {
    return stringResource(
        if (hiddenWhenExpanded) R.string.top_bar_filter_toggle_hide_when_expanded
        else R.string.top_bar_filter_toggle_show
    )
}
