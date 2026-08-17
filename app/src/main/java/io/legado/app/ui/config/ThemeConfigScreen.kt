package io.legado.app.ui.config

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.ui.theme.ThemeSync
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.AppEditDialog
import io.legado.app.ui.widget.components.AppMenuSheet
import io.legado.app.ui.widget.components.AppModalBottomSheet
import io.legado.app.ui.widget.components.AppNumberPickerDialog
import io.legado.app.ui.widget.components.ColorPickerSheet
import io.legado.app.ui.widget.components.EditField
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsCard
import io.legado.app.ui.widget.components.SettingsClickRow
import io.legado.app.ui.widget.components.SettingsColorRow
import io.legado.app.ui.widget.components.SettingsSection
import io.legado.app.ui.widget.components.SettingsToggleRow
import io.legado.app.constant.AppConst
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString

/**
 * L-E2 主题设置（S2 配置页，主题架构 v2 全面重设计）。
 *
 * - 主题区：themeConfig.json 主题瓦片网格（MoRealm 瓦片/MD3-DIY 手机模型预览思路），
 *   点击即应用（applyConfig→applyDayNight→ThemeSync 即时全局换肤），长按删除/分享
 * - 日/夜主题组：色行（ColorPickerSheet 预置色板+HSL 自定义活预览）/背景图/沉浸导航栏，
 *   当前模式组实时生效（applyTheme→bump，无页面重建）；非当前组保存后 toast 提示
 * - 通用区：系统栏沉浸/栏阴影/字号缩放/封面与欢迎页入口
 *
 * 状态读取以 ThemeSync.version 为签名（Archive 签名式失效思路）：
 * applyTheme bump 后本页各组色值自动重读。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ThemeConfigScreen(
    onApplyConfig: (ThemeConfig.Config) -> Unit,
    onDeleteConfig: (ThemeConfig.Config) -> Unit,
    onShareConfig: (ThemeConfig.Config) -> Unit,
    onImportClick: () -> Unit,
    onColorChange: (isNightGroup: Boolean, key: String, color: Int) -> Unit,
    onTransparentNavBarChange: (isNightGroup: Boolean, checked: Boolean) -> Unit,
    onBgImageClick: (isNightGroup: Boolean) -> Unit,
    onBgImageDelete: (isNightGroup: Boolean) -> Unit,
    onBlurringChange: (isNightGroup: Boolean, value: Int) -> Unit,
    onSaveTheme: (isNightGroup: Boolean, name: String) -> Unit,
    onTransparentStatusBarChange: (Boolean) -> Unit,
    onImmNavigationBarChange: (Boolean) -> Unit,
    onElevationChange: (Int) -> Unit,
    onFontScaleChange: (Int) -> Unit,
    onCoverConfigClick: () -> Unit,
    onWelcomeConfigClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    // 主题签名：applyTheme/recreateActivities 后 bump，全页重读当前值（活预览数据源）
    val themeVersion = ThemeSync.version
    // 主题列表本地版本：导入/删除/保存后由 Fragment bump 刷新瓦片
    var listVersion by remember { mutableStateOf(0) }

    // 弹层状态
    var colorSheetKey by remember { mutableStateOf<String?>(null) }
    var colorSheetNight by remember { mutableStateOf(false) }
    var bgActionSheetNight by remember { mutableStateOf<Boolean?>(null) }
    var blurringSheetNight by remember { mutableStateOf<Boolean?>(null) }
    var saveDialogNight by remember { mutableStateOf<Boolean?>(null) }
    var elevationDialog by remember { mutableStateOf(false) }
    var fontScaleDialog by remember { mutableStateOf(false) }

    val isNightNow = remember(themeVersion) { AppConfig.isNightTheme }

    fun currentColor(key: String, defRes: Int): Int =
        context.getPrefInt(key, context.getCompatColor(defRes))

    val dayColors = remember(themeVersion) {
        ThemeGroupColors(
            primary = currentColor("colorPrimary", R.color.md_brown_500),
            accent = currentColor("colorAccent", R.color.md_red_600),
            background = currentColor("colorBackground", R.color.md_grey_100),
            bottomBackground = currentColor("colorBottomBackground", R.color.md_grey_200)
        )
    }
    val nightColors = remember(themeVersion) {
        ThemeGroupColors(
            primary = currentColor("colorPrimaryNight", R.color.md_blue_grey_600),
            accent = currentColor("colorAccentNight", R.color.md_deep_orange_800),
            background = currentColor("colorBackgroundNight", R.color.md_grey_900),
            bottomBackground = currentColor("colorBottomBackgroundNight", R.color.md_grey_850)
        )
    }
    val dayTransparentNavBar = remember(themeVersion) { context.getPrefBoolean("transparentNavBar", false) }
    val nightTransparentNavBar = remember(themeVersion) { context.getPrefBoolean("transparentNavBarNight", false) }
    val dayBgImage = remember(themeVersion) { context.getPrefString("backgroundImage") }
    val nightBgImage = remember(themeVersion) { context.getPrefString("backgroundImageNight") }
    val transparentStatusBar = remember(themeVersion) { AppConfig.isTransparentStatusBar }
    val immNavigationBar = remember(themeVersion) { AppConfig.immNavigationBar }
    val elevation = remember(themeVersion) { AppConfig.elevation }
    val fontScale = remember(themeVersion) { context.getPrefInt("fontScale", 0) }
    val themeConfigs = remember(listVersion) { ThemeConfig.configList.toList() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
    ) {
        // ---------- 主题（themeConfig.json 瓦片网格） ----------
        SettingsSection(title = stringResource(R.string.theme_list_section)) {
            themeConfigs.chunked(3).forEach { rowConfigs ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowConfigs.forEach { config ->
                        ThemePreviewCard(
                            config = config,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                onApplyConfig(config)
                                listVersion++
                            },
                            onDelete = {
                                onDeleteConfig(config)
                                listVersion++
                            },
                            onShare = { onShareConfig(config) }
                        )
                    }
                    // 补位占位，保持同行等宽
                    repeat(3 - rowConfigs.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsClickRow(
                    icon = Icons.Filled.Slideshow,
                    title = stringResource(R.string.import_from_clipboard),
                    onClick = {
                        onImportClick()
                        listVersion++
                    }
                )
                SettingsClickRow(
                    icon = Icons.Filled.Dashboard,
                    title = stringResource(R.string.save_theme_config),
                    subtitle = stringResource(R.string.save_day_theme_summary),
                    onClick = { saveDialogNight = false }
                )
                SettingsClickRow(
                    icon = Icons.Filled.Dashboard,
                    title = stringResource(R.string.save_theme_config),
                    subtitle = stringResource(R.string.save_night_theme_summary),
                    onClick = { saveDialogNight = true }
                )
            }
        }

        // ---------- 日间主题 ----------
        SettingsSection(
            title = stringResource(R.string.day) +
                if (!isNightNow) stringResource(R.string.current_mode_suffix) else ""
        ) {
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsColorRow(
                    title = stringResource(R.string.primary),
                    subtitle = stringResource(R.string.day_color_primary),
                    color = Color(dayColors.primary),
                    icon = Icons.Filled.Palette,
                    onClick = {
                        colorSheetKey = "colorPrimary"
                        colorSheetNight = false
                    }
                )
                SettingsColorRow(
                    title = stringResource(R.string.accent),
                    subtitle = stringResource(R.string.day_color_accent),
                    color = Color(dayColors.accent),
                    icon = Icons.Filled.Colorize,
                    onClick = {
                        colorSheetKey = "colorAccent"
                        colorSheetNight = false
                    }
                )
                SettingsColorRow(
                    title = stringResource(R.string.background_color),
                    subtitle = stringResource(R.string.day_background_color),
                    color = Color(dayColors.background),
                    icon = Icons.Filled.Wallpaper,
                    onClick = {
                        colorSheetKey = "colorBackground"
                        colorSheetNight = false
                    }
                )
                SettingsColorRow(
                    title = stringResource(R.string.navbar_color),
                    subtitle = stringResource(R.string.day_navbar_color),
                    color = Color(dayColors.bottomBackground),
                    icon = Icons.Filled.Navigation,
                    onClick = {
                        colorSheetKey = "colorBottomBackground"
                        colorSheetNight = false
                    }
                )
                SettingsClickRow(
                    icon = Icons.Filled.Image,
                    title = stringResource(R.string.background_image),
                    value = dayBgImage ?: stringResource(R.string.select_image),
                    onClick = { bgActionSheetNight = false }
                )
                SettingsToggleRow(
                    icon = Icons.Filled.Navigation,
                    title = stringResource(R.string.immersion_nav_bar),
                    subtitle = stringResource(R.string.day_nav_bar_immersion),
                    checked = dayTransparentNavBar,
                    onCheckedChange = { onTransparentNavBarChange(false, it) }
                )
            }
        }

        // ---------- 夜间主题 ----------
        SettingsSection(
            title = stringResource(R.string.night) +
                if (isNightNow) stringResource(R.string.current_mode_suffix) else ""
        ) {
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsColorRow(
                    title = stringResource(R.string.primary),
                    subtitle = stringResource(R.string.night_primary),
                    color = Color(nightColors.primary),
                    icon = Icons.Filled.Palette,
                    onClick = {
                        colorSheetKey = "colorPrimaryNight"
                        colorSheetNight = true
                    }
                )
                SettingsColorRow(
                    title = stringResource(R.string.accent),
                    subtitle = stringResource(R.string.night_accent),
                    color = Color(nightColors.accent),
                    icon = Icons.Filled.Colorize,
                    onClick = {
                        colorSheetKey = "colorAccentNight"
                        colorSheetNight = true
                    }
                )
                SettingsColorRow(
                    title = stringResource(R.string.background_color),
                    subtitle = stringResource(R.string.night_background_color),
                    color = Color(nightColors.background),
                    icon = Icons.Filled.Wallpaper,
                    onClick = {
                        colorSheetKey = "colorBackgroundNight"
                        colorSheetNight = true
                    }
                )
                SettingsColorRow(
                    title = stringResource(R.string.navbar_color),
                    subtitle = stringResource(R.string.night_navbar_color),
                    color = Color(nightColors.bottomBackground),
                    icon = Icons.Filled.Navigation,
                    onClick = {
                        colorSheetKey = "colorBottomBackgroundNight"
                        colorSheetNight = true
                    }
                )
                SettingsClickRow(
                    icon = Icons.Filled.Image,
                    title = stringResource(R.string.background_image),
                    value = nightBgImage ?: stringResource(R.string.select_image),
                    onClick = { bgActionSheetNight = true }
                )
                SettingsToggleRow(
                    icon = Icons.Filled.Navigation,
                    title = stringResource(R.string.immersion_nav_bar),
                    subtitle = stringResource(R.string.night_nav_bar_immersion),
                    checked = nightTransparentNavBar,
                    onCheckedChange = { onTransparentNavBarChange(true, it) }
                )
            }
        }

        // ---------- 通用 ----------
        SettingsSection(title = stringResource(R.string.theme_general_section)) {
            SettingsCard(modifier = Modifier.fillMaxWidth()) {
                SettingsToggleRow(
                    icon = Icons.Filled.Layers,
                    title = stringResource(R.string.immersion_status_bar),
                    subtitle = stringResource(R.string.status_bar_immersion),
                    checked = transparentStatusBar,
                    onCheckedChange = onTransparentStatusBarChange
                )
                SettingsToggleRow(
                    icon = Icons.Filled.Navigation,
                    title = stringResource(R.string.imm_navigation_bar),
                    subtitle = stringResource(R.string.imm_navigation_bar_s),
                    checked = immNavigationBar,
                    onCheckedChange = onImmNavigationBarChange
                )
                SettingsClickRow(
                    icon = Icons.Filled.Layers,
                    title = stringResource(R.string.bar_elevation),
                    value = elevation.toString(),
                    onClick = { elevationDialog = true }
                )
                SettingsClickRow(
                    icon = Icons.Filled.TextFields,
                    title = stringResource(R.string.font_scale),
                    onClick = { fontScaleDialog = true }
                )
                SettingsClickRow(
                    icon = Icons.Filled.Dashboard,
                    title = stringResource(R.string.cover_config),
                    subtitle = stringResource(R.string.cover_config_summary),
                    onClick = onCoverConfigClick
                )
                SettingsClickRow(
                    icon = Icons.Filled.Slideshow,
                    title = stringResource(R.string.welcome_style),
                    subtitle = stringResource(R.string.welcome_style_summary),
                    onClick = onWelcomeConfigClick
                )
            }
        }
    }

    // ---------- 弹层族 ----------

    colorSheetKey?.let { key ->
        val initial = if (colorSheetNight) nightColors else dayColors
        val initialColor = when (key) {
            "colorPrimary", "colorPrimaryNight" -> initial.primary
            "colorAccent", "colorAccentNight" -> initial.accent
            "colorBackground", "colorBackgroundNight" -> initial.background
            else -> initial.bottomBackground
        }
        val title = when (key) {
            "colorPrimary", "colorPrimaryNight" -> stringResource(R.string.primary)
            "colorAccent", "colorAccentNight" -> stringResource(R.string.accent)
            "colorBackground", "colorBackgroundNight" -> stringResource(R.string.background_color)
            else -> stringResource(R.string.navbar_color)
        }
        ColorPickerSheet(
            title = title,
            initialColor = initialColor,
            onConfirm = { color ->
                onColorChange(colorSheetNight, key, color)
                colorSheetKey = null
            },
            onDismiss = { colorSheetKey = null }
        )
    }

    bgActionSheetNight?.let { isNight ->
        val hasImage = (if (isNight) nightBgImage else dayBgImage) != null
        AppMenuSheet(
            title = stringResource(R.string.background_image),
            actions = buildList {
                add(
                    MenuAction(
                        icon = Icons.Filled.Layers,
                        title = stringResource(R.string.background_image_blurring),
                        onClick = {
                            bgActionSheetNight = null
                            blurringSheetNight = isNight
                        }
                    )
                )
                add(
                    MenuAction(
                        icon = Icons.Filled.Image,
                        title = stringResource(R.string.select_image),
                        onClick = {
                            bgActionSheetNight = null
                            onBgImageClick(isNight)
                        }
                    )
                )
                if (hasImage) {
                    add(
                        MenuAction(
                            icon = Icons.Filled.Dashboard,
                            title = stringResource(R.string.delete),
                            onClick = {
                                bgActionSheetNight = null
                                onBgImageDelete(isNight)
                            }
                        )
                    )
                }
            },
            onDismiss = { bgActionSheetNight = null }
        )
    }

    blurringSheetNight?.let { isNight ->
        val prefKey = if (isNight) "backgroundImageNightBlurring" else "backgroundImageBlurring"
        var value by remember(isNight) {
            mutableStateOf(context.getPrefInt(prefKey, 0).toFloat())
        }
        AppModalBottomSheet(onDismiss = { blurringSheetNight = null }) {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.background_image_blurring),
                    style = MaterialTheme.typography.titleMedium
                )
                Slider(
                    value = value,
                    onValueChange = { value = it },
                    onValueChangeFinished = { onBlurringChange(isNight, value.toInt()) },
                    valueRange = 0f..100f,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
                Text(
                    text = value.toInt().toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }

    saveDialogNight?.let { isNight ->
        AppEditDialog(
            title = stringResource(R.string.theme_name),
            fields = listOf(EditField(label = stringResource(R.string.theme_name))),
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            onConfirm = { values ->
                val name = values.firstOrNull()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    onSaveTheme(isNight, name)
                    listVersion++
                }
                saveDialogNight = null
            },
            onDismiss = { saveDialogNight = null }
        )
    }

    if (elevationDialog) {
        AppNumberPickerDialog(
            title = stringResource(R.string.bar_elevation),
            value = elevation,
            range = 0..32,
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            neutralText = stringResource(R.string.btn_default_s),
            onNeutral = {
                elevationDialog = false
                onElevationChange(AppConst.sysElevation)
            },
            onConfirm = {
                elevationDialog = false
                onElevationChange(it)
            },
            onDismiss = { elevationDialog = false }
        )
    }

    if (fontScaleDialog) {
        AppNumberPickerDialog(
            title = stringResource(R.string.font_scale),
            value = fontScale.takeIf { it in 8..16 } ?: 10,
            range = 8..16,
            confirmText = stringResource(R.string.ok),
            cancelText = stringResource(R.string.cancel),
            neutralText = stringResource(R.string.btn_default_s),
            onNeutral = {
                fontScaleDialog = false
                onFontScaleChange(0)
            },
            onConfirm = {
                fontScaleDialog = false
                onFontScaleChange(it)
            },
            onDismiss = { fontScaleDialog = false }
        )
    }
}

/** 一组主题色（日/夜各一组）的当前 pref 值快照 */
private data class ThemeGroupColors(
    val primary: Int,
    val accent: Int,
    val background: Int,
    val bottomBackground: Int
)

/**
 * 主题预览瓦片（MoRealm 瓦片 + MD3-DIY 手机模型混合思路）：
 * 背景区=config 背景色，顶部条=主色，底部条=导航栏色，中部三圆=主色/强调色/导航栏色；
 * 点击应用主题，长按弹出删除/分享菜单。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ThemePreviewCard(
    config: ThemeConfig.Config,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val shareText = stringResource(R.string.share)
    val deleteText = stringResource(R.string.delete)
    val bgColor = config.backgroundColor.toColorInt()
    val primaryC = config.primaryColor.toColorInt()
    val accentC = config.accentColor.toColorInt()
    val bottomC = config.bottomBackground.toColorInt()

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { menuExpanded = true }
                )
        ) {
            // 预览区：模拟页面（bg 底 + primary 顶条 + 三色圆 + bottom 底条）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .background(Color(bgColor))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .background(Color(primaryC))
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleSwatch(primaryC)
                    Spacer(modifier = Modifier.width(6.dp))
                    CircleSwatch(accentC)
                    Spacer(modifier = Modifier.width(6.dp))
                    CircleSwatch(bottomC)
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .background(Color(bottomC))
                )
            }
            // 名称行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (config.isNightTheme) {
                    Icons.Filled.DarkMode
                } else {
                    Icons.Filled.LightMode
                }.let {
                    androidx.compose.material3.Icon(
                        imageVector = it,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = config.themeName,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        AppDropdownMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            actions = listOf(
                MenuAction(
                    icon = Icons.Filled.Image,
                    title = shareText,
                    onClick = {
                        menuExpanded = false
                        onShare()
                    }
                ),
                MenuAction(
                    icon = Icons.Filled.Dashboard,
                    title = deleteText,
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    }
                )
            )
        )
    }
}

@Composable
private fun CircleSwatch(color: Int) {
    Box(
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape)
            .background(Color(color))
            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
    )
}
