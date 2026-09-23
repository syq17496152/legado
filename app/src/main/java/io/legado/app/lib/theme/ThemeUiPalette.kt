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
    return themeColorOrNull(PreferKey.themeCardColor) ?: deriveSurfaceColor(cardSurfaceStep)
}

@ColorInt
fun Context.themeMutedColorOrDefault(): Int {
    return themeColorOrNull(PreferKey.themeMutedColor) ?: deriveSurfaceColor(secondarySurfaceStep)
}

@ColorInt
fun Context.themeSearchFieldBackgroundColorOrDefault(): Int {
    return themeColorOrNull(PreferKey.themeSearchFieldBackgroundColor)
        ?: deriveSurfaceColor(secondarySurfaceStep)
}

@ColorInt
fun Context.themeSearchFieldBackgroundColorOrNull(): Int? {
    return themeColorOrNull(PreferKey.themeSearchFieldBackgroundColor)
}

@ColorInt
fun Context.themeTabBackgroundColorOrDefault(): Int {
    return themeColorOrNull(PreferKey.themeTabBackgroundColor) ?: deriveSurfaceColor(secondarySurfaceStep)
}

/**
 * 面 token「运行时推导」层 —— 取色三层优先级「用户自定义 key → **运行时推导** → R.color 兜底」
 * （`ui-standards/color.md` §一）的中间层。
 *
 * ⚠ 该层此前**缺失**：未自定义面时直接落静态 `R.color`（`background_card`/`background_menu` 均为固定灰，
 * 与主题背景色无关）⇒ **改主题色时标签 / 芯片 / Tab / 次级表面颜色不跟随**
 * （2026-09-23 用户实证：书架与订阅源「标签」布局模式下的标签颜色不随主题变化；
 * 亦即 K3 判据「T2 自定义主题色态必须变色」失守）。
 *
 * 推导口径：以当前生效主题的背景色（`ThemeStore.backgroundColor`）为基准做明暗阶梯
 * （夜间提亮 / 日间压暗）。出厂默认主题下阶梯结果与既有静态灰资源逐通道一致（±1 色阶，肉眼无差）
 * ⇒ T1 默认 / T4 夜间观感零变化；用户改主题背景色后各面随主题联动 ⇒ T2 / T3 生效。
 */
private val Context.cardSurfaceStep: Int
    get() = SurfaceLadder.cardStep(AppConfig.isNightTheme)

/** 次级面阶梯（chip / Tab / 搜索框 / 弱化底）：日间压暗一阶（md_grey_200）、夜间提亮两阶（md_grey_800） */
private val Context.secondarySurfaceStep: Int
    get() = SurfaceLadder.secondaryStep(AppConfig.isNightTheme)

private fun Context.deriveSurfaceColor(step: Int): Int {
    // 直读 ThemeStore（不用 Context.backgroundColor 扩展：配了背景图时后者返回透明，会把面推导成透明色）
    return SurfaceLadder.derive(ThemeStore.backgroundColor(this), AppConfig.isNightTheme, step)
}

/**
 * 面 token「运行时推导」纯运算（**无 Android 依赖**，可单测）。
 *
 * ⚠ 不得改用 `ColorUtils.blendColors` / `android.graphics.Color`：JVM 单测里 android.jar 是 stub
 * （方法恒返回 0），混色会静默变 0 导致推导面失效。此处用位运算自实现，保真且可断言。
 *
 * 阶梯口径与出厂默认主题对齐，故 T1 默认 / T4 夜间观感零变化；换主题背景色则各面联动。
 */
internal object SurfaceLadder {

    /** 日间阶梯步长（压暗） */
    const val DAY_ALPHA = 0.030f

    /** 夜间阶梯步长（提亮） */
    const val NIGHT_ALPHA = 0.075f

    /** 阶梯混色目标通道值（推导基元，非取色：明暗方向的端点，不参与任何直接渲染） */
    private const val DARK_CHANNEL = 0x00
    private const val LIGHT_CHANNEL = 0xFF

    /** 卡片面阶梯：日间与背景同阶；夜间较背景高一阶 */
    fun cardStep(night: Boolean): Int = if (night) 1 else 0

    /** 次级面阶梯（chip / Tab / 搜索框）：日间压暗一阶、夜间提亮两阶 */
    fun secondaryStep(night: Boolean): Int = if (night) 2 else 1

    fun derive(baseColor: Int, night: Boolean, step: Int): Int {
        if (step <= 0) return baseColor
        val alpha = ((if (night) NIGHT_ALPHA else DAY_ALPHA) * step).coerceIn(0f, 1f)
        val inverse = 1f - alpha
        val target = if (night) LIGHT_CHANNEL else DARK_CHANNEL
        val a = baseColor ushr 24 and 0xFF
        val r = ((baseColor shr 16 and 0xFF) * inverse + target * alpha).toInt()
        val g = ((baseColor shr 8 and 0xFF) * inverse + target * alpha).toInt()
        val b = ((baseColor and 0xFF) * inverse + target * alpha).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }
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