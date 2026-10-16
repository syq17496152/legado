package io.legado.app.lib.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.widget.EditText
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.widget.TitleBar
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.dpToPx
import java.io.File

private fun Context.baseSystemTypeface(): Typeface {
    return when (AppConfig.systemTypefaces) {
        1 -> Typeface.SERIF
        2 -> Typeface.MONOSPACE
        else -> Typeface.SANS_SERIF
    }
}

fun Context.uiTypeface(): Typeface {
    val fontPath = AppConfig.uiFontPath
    if (fontPath.isNotBlank()) {
        loadUiTypeface(fontPath)?.let {
            return it
        }
    }
    return baseSystemTypeface()
}

fun Context.titleTypeface(): Typeface {
    val fontPath = AppConfig.titleFontPath
    if (fontPath.isNotBlank()) {
        loadUiTypeface(fontPath)?.let {
            return it
        }
    }
    return baseSystemTypeface()
}

fun Context.loadUiTypeface(fontPath: String): Typeface? {
    if (fontPath.isBlank()) {
        return null
    }
    return UiTypefaceCache.get(this, fontPath)
}

/**
 * 主题字体族下发（Compose 侧单源，NEW-71）。
 *
 * 「界面字体」→ 正文/标签族；「标题字体」→ display/headline/titleLarge 族。
 * 字体来源与 View 体系同源（[uiTypeface] / [titleTypeface]）：`AppConfig.uiFontPath` /
 * `titleFontPath`（自定义字体文件）与 `AppConfig.systemTypefaces`（无衬线/衬线/等宽）。
 *
 * 原先该映射只存在于 `ui/widget/compose/LegadoComposeTheme.kt`，导致「只包 `LegadoTheme`」
 * 的宿主（含 `setLegadoContent` 全页路径）拿不到主题字体 ⇒ Compose 文本与 View 文本字体口径
 * 不一致。现由 `LegadoTheme`（唯一主题入口）统一调用，消除「漏包字体层」这一整类缺陷。
 */
fun Typography.withThemeFontFamilies(body: FontFamily, title: FontFamily): Typography = copy(
    displayLarge = displayLarge.copy(fontFamily = title),
    displayMedium = displayMedium.copy(fontFamily = title),
    displaySmall = displaySmall.copy(fontFamily = title),
    headlineLarge = headlineLarge.copy(fontFamily = title),
    headlineMedium = headlineMedium.copy(fontFamily = title),
    headlineSmall = headlineSmall.copy(fontFamily = title),
    titleLarge = titleLarge.copy(fontFamily = title),
    titleMedium = titleMedium.copy(fontFamily = body),
    titleSmall = titleSmall.copy(fontFamily = body),
    bodyLarge = bodyLarge.copy(fontFamily = body),
    bodyMedium = bodyMedium.copy(fontFamily = body),
    bodySmall = bodySmall.copy(fontFamily = body),
    labelLarge = labelLarge.copy(fontFamily = body),
    labelMedium = labelMedium.copy(fontFamily = body),
    labelSmall = labelSmall.copy(fontFamily = body)
)

/**
 * 读取当前主题的「界面字体 / 标题字体」族。
 * `rememberThemeUiPalette().signature` 变更（主题包 / 日夜 / 字体设置变更）时重建。
 */
@Composable
fun rememberThemeFontFamilies(): Pair<FontFamily, FontFamily> {
    val context = LocalContext.current
    val themeSignature = rememberThemeUiPalette().signature
    val bodyFamily = remember(context, themeSignature) { FontFamily(context.uiTypeface()) }
    val titleFamily = remember(context, themeSignature) { FontFamily(context.titleTypeface()) }
    return bodyFamily to titleFamily
}

private object UiTypefaceCache {

    private val cache = linkedMapOf<String, Typeface>()

    fun get(context: Context, fontPath: String): Typeface? {
        cache[fontPath]?.let { return it }
        return runCatching {
            when {
                fontPath.startsWith("content://", ignoreCase = true) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                    context.contentResolver.openFileDescriptor(android.net.Uri.parse(fontPath), "r")?.use {
                        Typeface.Builder(it.fileDescriptor).build()
                    }
                }
                fontPath.startsWith("content://", ignoreCase = true) -> {
                    RealPathUtil.getPath(context, android.net.Uri.parse(fontPath))?.let {
                        Typeface.createFromFile(it)
                    }
                }
                File(fontPath).exists() -> Typeface.createFromFile(fontPath)
                else -> null
            }
        }.getOrNull().also {
            if (it != null) {
                cache[fontPath] = it
                if (cache.size > 8) {
                    cache.remove(cache.keys.first())
                }
            }
        }
    }
}

fun View.applyUiTypefaceDeep(typeface: Typeface) {
    when (this) {
        is TextView -> {
            if (this.typeface != typeface) {
                this.typeface = typeface
            }
        }
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiTypefaceDeep(typeface)
            }
        }
    }
}

fun View.applyUiBodyTypefaceDeep(typeface: Typeface) {
    when (this) {
        is TitleBar -> return
        is TextView -> {
            if (getTag(R.id.ui_title_typeface_role) == true) return
            if (this.typeface != typeface) {
                this.typeface = typeface
            }
        }
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiBodyTypefaceDeep(typeface)
            }
        }
    }
}

fun TextView.applyUiTitleTypeface(context: Context) {
    setTag(R.id.ui_title_typeface_role, true)
    val colorBeforeTypeface = currentTextColor
    typeface = context.titleTypeface()
    val titleColor = context.titleTextColor
    val defaultTitleColors = setOf(
        context.primaryTextColor,
        context.getPrimaryTextColor(!AppConfig.isNightTheme),
        ThemeStore.textColorPrimary(context)
    )
    if (colorBeforeTypeface in defaultTitleColors) {
        setTextColor(titleColor)
    }
}

fun TextView.applyUiMenuItemTypeface(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
}

fun View.applyUiMenuTypefaceDeep(context: Context) {
    when (this) {
        is TextView -> applyUiMenuItemTypeface(context)
        is ViewGroup -> {
            for (index in 0 until childCount) {
                getChildAt(index).applyUiMenuTypefaceDeep(context)
            }
        }
    }
}

fun TextView.applyUiLabelStyle(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 14f
    setTextColor(context.primaryTextColor)
}

fun TextView.applyUiSectionTitleStyle(context: Context) {
    applyUiTitleTypeface(context)
    textSize = 15f
    setTextColor(context.titleTextColor)
}

fun TextView.applyUiSubtleButtonStyle(context: Context) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 14f
    minHeight = 40.dpToPx()
    setTextColor(context.primaryTextColor)
}

fun EditText.applyUiInputStyle(context: Context, minLines: Int = 1) {
    setTag(R.id.ui_title_typeface_role, false)
    typeface = context.uiTypeface()
    textSize = 15f
    this.minLines = minLines
    maxLines = if (minLines > 1) 8 else 2
    setSingleLine(minLines == 1)
    minHeight = if (minLines > 1) 92.dpToPx() else 44.dpToPx()
    val horizontal = 12.dpToPx()
    val vertical = if (minLines > 1) 10.dpToPx() else 8.dpToPx()
    setPadding(horizontal, vertical, horizontal, vertical)
}