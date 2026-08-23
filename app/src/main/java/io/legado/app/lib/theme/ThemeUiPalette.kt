package io.legado.app.lib.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import java.io.File

data class ThemeUiPalette(
    @param:ColorInt val cardColor: Int,
    @param:ColorInt val mutedColor: Int,
    @param:ColorInt val searchFieldBackgroundColor: Int,
    @param:ColorInt val tabBackgroundColor: Int,
    @param:ColorInt val shelfColor: Int,
    @param:ColorInt val dividerColor: Int,
    val hasCustomCardColor: Boolean,
    val hasCustomMutedColor: Boolean,
    val signature: String
)

private val themeUiColorKeys = listOf(
    PreferKey.themeCardColor,
    PreferKey.themeCardColorN,
    PreferKey.themeMutedColor,
    PreferKey.themeMutedColorN,
    PreferKey.themeSearchFieldBackgroundColor,
    PreferKey.themeSearchFieldBackgroundColorN,
    PreferKey.themeTabBackgroundColor,
    PreferKey.themeTabBackgroundColorN,
    PreferKey.themeShelfColor,
    PreferKey.themeShelfColorN
)
private val themeUiShapeKeys = listOf(
    PreferKey.panelBorderColor,
    PreferKey.panelBorderColorN,
    PreferKey.panelBorderAlpha,
    PreferKey.panelBorderAlphaN,
    PreferKey.panelBgImage,
    PreferKey.panelBgImageN,
    PreferKey.panelBgScaleType,
    PreferKey.panelBgScaleTypeN,
    PreferKey.uiCornerScale,
    PreferKey.uiCornerScaleN,
    PreferKey.uiCornerSearchFollow,
    PreferKey.uiCornerSearchFollowN,
    PreferKey.uiCornerReplyFollow,
    PreferKey.uiCornerReplyFollowN,
    PreferKey.uiLayoutAlpha,
    PreferKey.uiLayoutAlphaN,
    PreferKey.uiCornerEffectLevel,
    PreferKey.dialogAlpha,
    PreferKey.dialogAlphaN,
    PreferKey.themeCardShadow,
    PreferKey.themeCardShadowN,
    PreferKey.themeCardBackgroundBlur,
    PreferKey.themeCardBackgroundBlurN,
    PreferKey.bookCoverShadow
)
private val themeUiTypographyKeys = listOf(
    PreferKey.fontScale,
    PreferKey.fontScaleN,
    PreferKey.uiFontPath,
    PreferKey.uiFontPathN,
    PreferKey.titleFontPath,
    PreferKey.titleFontPathN,
    PreferKey.uiFontColor,
    PreferKey.uiFontColorN,
    PreferKey.titleFontColor,
    PreferKey.titleFontColorN
)
private val themeUiDependencyKeySet = (
    themeUiColorKeys + themeUiShapeKeys + themeUiTypographyKeys + listOf(
        PreferKey.themeMode,
        PreferKey.cPrimary,
        PreferKey.cAccent,
        PreferKey.cBackground,
        PreferKey.cBBackground,
        PreferKey.cNPrimary,
        PreferKey.cNAccent,
        PreferKey.cNBackground,
        PreferKey.cNBBackground
    )
).toSet()

fun Context.themeUiPalette(): ThemeUiPalette {
    val customCardColor = themeColorOrNull(PreferKey.themeCardColor)
    val customMutedColor = themeColorOrNull(PreferKey.themeMutedColor)
    return ThemeUiPalette(
        cardColor = customCardColor ?: themeCardColorOrDefault(),
        mutedColor = customMutedColor ?: themeMutedColorOrDefault(),
        searchFieldBackgroundColor = themeSearchFieldBackgroundColorOrDefault(),
        tabBackgroundColor = themeTabBackgroundColorOrDefault(),
        shelfColor = themeShelfColorOrDefault(),
        dividerColor = themeDividerColorOrDefault(),
        hasCustomCardColor = customCardColor != null,
        hasCustomMutedColor = customMutedColor != null,
        signature = themeUiSignature()
    )
}

@Composable
fun rememberThemeUiPalette(): ThemeUiPalette {
    val context = LocalContext.current
    var signature by remember(context) {
        mutableStateOf(context.themeUiSignature())
    }
    DisposableEffect(context) {
        val defaultPrefs = context.defaultSharedPreferences
        val themeStorePrefs = ThemeStore.prefs(context)
        val defaultListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != null && key in themeUiDependencyKeySet) {
                signature = context.themeUiSignature()
            }
        }
        val themeStoreListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == ThemeStorePrefKeys.VALUES_CHANGED) {
                signature = context.themeUiSignature()
            }
        }
        defaultPrefs.registerOnSharedPreferenceChangeListener(defaultListener)
        themeStorePrefs.registerOnSharedPreferenceChangeListener(themeStoreListener)
        onDispose {
            defaultPrefs.unregisterOnSharedPreferenceChangeListener(defaultListener)
            themeStorePrefs.unregisterOnSharedPreferenceChangeListener(themeStoreListener)
        }
    }
    return remember(context, signature) {
        context.themeUiPalette()
    }
}

fun Context.themeUiSignature(): String {
    val themeMode = getPrefString(PreferKey.themeMode, "0")
    val rawPrefs = themeUiColorKeys.joinToString("|") { key ->
        "${key}=${getPrefString(key).orEmpty()}"
    }
    val shapePrefs = listOf(
        "border=${getPrefString(PreferKey.panelBorderColor).orEmpty()}",
        "borderN=${getPrefString(PreferKey.panelBorderColorN).orEmpty()}",
        "borderAlpha=${getPrefInt(PreferKey.panelBorderAlpha, 100)}",
        "borderAlphaN=${getPrefInt(PreferKey.panelBorderAlphaN, 100)}",
        "corner=${getPrefString(ThemeRuntimeKeys.uiCornerScale(), "1").orEmpty()}",
        "searchFollow=${AppConfig.uiCornerSearchFollow}",
        "replyFollow=${AppConfig.uiCornerReplyFollow}",
        "layoutAlpha=${AppConfig.uiLayoutAlpha}",
        "dialogAlpha=${AppConfig.dialogAlpha}",
        "cardShadow=${getPrefInt(ThemeRuntimeKeys.themeCardShadow(), -1)}",
        "cardBackgroundBlur=${getPrefInt(ThemeRuntimeKeys.themeCardBackgroundBlur(), -1)}",
        "bookCoverShadow=${AppConfig.bookCoverShadow}",
        "fontScale=${getPrefInt(ThemeRuntimeKeys.fontScale(), 0)}",
        "uiFont=${getPrefString(ThemeRuntimeKeys.uiFontPath()).orEmpty()}",
        "titleFont=${getPrefString(ThemeRuntimeKeys.titleFontPath()).orEmpty()}",
        "uiFontColor=${getPrefString(ThemeRuntimeKeys.uiFontColor()).orEmpty()}",
        "titleFontColor=${getPrefString(ThemeRuntimeKeys.titleFontColor()).orEmpty()}",
        "panelImage=${themePanelImageSignature()}"
    ).joinToString("|")
    val computedColors = listOf(
        "card=${themeCardColorOrDefault()}",
        "muted=${themeMutedColorOrDefault()}",
        "search=${themeSearchFieldBackgroundColorOrDefault()}",
        "tab=${themeTabBackgroundColorOrDefault()}",
        "shelf=${themeShelfColorOrDefault()}",
        "divider=${themeDividerColorOrDefault()}"
    ).joinToString("|")
    return "mode=$themeMode|night=${AppConfig.isNightTheme}|eInk=${AppConfig.isEInkMode}|themeStore=${ThemeStore.valuesChanged(this)}|$rawPrefs|$shapePrefs|$computedColors"
}

private fun Context.themePanelImageSignature(): String {
    val imageKey = if (AppConfig.isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage
    val scaleKey = if (AppConfig.isNightTheme) PreferKey.panelBgScaleTypeN else PreferKey.panelBgScaleType
    val path = getPrefString(imageKey).orEmpty()
    val mode = getPrefString(scaleKey).orEmpty()
    val fileKey = path.takeUnless { it.isBlank() || it.startsWith("http", ignoreCase = true) }
        ?.let(::File)
        ?.takeIf { it.exists() }
        ?.let { "${it.absolutePath}:${it.length()}:${it.lastModified()}" }
        ?: path
    return "$fileKey|$mode|${UiCorner.layoutAlpha()}"
}

@ColorInt
fun Context.themeCardColorOrDefault(): Int {
    return themeColorOrDefault(PreferKey.themeCardColor, R.color.background_card)
}

@ColorInt
fun Context.themeMutedColorOrDefault(): Int {
    return themeColorOrDefault(PreferKey.themeMutedColor, R.color.background_menu)
}

@ColorInt
fun Context.themeSearchFieldBackgroundColorOrDefault(): Int {
    return themeColorOrDefault(PreferKey.themeSearchFieldBackgroundColor, R.color.background_menu)
}

@ColorInt
fun Context.themeSearchFieldBackgroundColorOrNull(): Int? {
    return themeColorOrNull(PreferKey.themeSearchFieldBackgroundColor)
}

@ColorInt
fun Context.themeTabBackgroundColorOrDefault(): Int {
    return themeColorOrDefault(PreferKey.themeTabBackgroundColor, R.color.background_menu)
}

@ColorInt
fun Context.themeShelfColorOrDefault(): Int {
    return themeColorOrNull(PreferKey.themeShelfColor) ?: backgroundColor
}

@ColorInt
fun Context.themeDividerColorOrDefault(): Int {
    if (!hasCustomThemeSurfaceColors()) {
        return ContextCompat.getColor(this, R.color.bg_divider_line)
    }
    val surface = themeCardColorOrDefault()
    val edge = if (ColorUtils.isColorLight(surface)) Color.BLACK else Color.WHITE
    return ColorUtils.withAlpha(edge, if (ColorUtils.isColorLight(surface)) 0.10f else 0.16f)
}

fun Context.hasCustomThemeSurfaceColors(): Boolean {
    return themeColorOrNull(PreferKey.themeCardColor) != null ||
        themeColorOrNull(PreferKey.themeMutedColor) != null
}

@ColorInt
fun Context.themeColorOrDefault(key: String, @ColorRes defaultColor: Int): Int {
    return themeColorOrNull(key) ?: ContextCompat.getColor(this, defaultColor)
}

@ColorInt
fun Context.themeColorOrNull(key: String): Int? {
    return getPrefString(ThemeRuntimeKeys.activeColorKey(key)).toThemeColorIntOrNull()
}

@ColorInt
private fun String?.toThemeColorIntOrNull(): Int? {
    val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalized = raw.normalizedThemeColorString() ?: return null
    return runCatching {
        normalized.toColorInt()
    }.getOrNull()?.let {
        Color.rgb(Color.red(it), Color.green(it), Color.blue(it))
    }
}

private fun String.normalizedThemeColorString(): String? {
    val value = trim()
    val withoutPrefix = value
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    val isHex = withoutPrefix.isNotEmpty() && withoutPrefix.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    if (!isHex) {
        return value
    }
    val argb = when (withoutPrefix.length) {
        3 -> "FF" + withoutPrefix.map { "$it$it" }.joinToString("")
        4 -> withoutPrefix.map { "$it$it" }.joinToString("")
        6 -> "FF$withoutPrefix"
        8 -> withoutPrefix
        else -> return null
    }
    return "#${argb.uppercase()}"
}