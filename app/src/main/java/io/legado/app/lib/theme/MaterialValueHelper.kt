@file:Suppress("unused")

package io.legado.app.lib.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import androidx.fragment.app.Fragment
import io.legado.app.R
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.dpToPx

/**
 * @author Karim Abou Zeid (kabouzeid)
 */
@ColorInt
fun Context.getPrimaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_primary_text)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getSecondaryTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_secondary)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

@ColorInt
fun Context.getPrimaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    }
}

@ColorInt
fun Context.getSecondaryDisabledTextColor(dark: Boolean): Int {
    return if (dark) {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_light
        )
    } else {
        ContextCompat.getColor(
            this,
            androidx.appcompat.R.color.secondary_text_disabled_material_dark
        )
    }
}

val Context.primaryColor: Int
    get() = ThemeStore.primaryColor(this)

val Context.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(this)

val Context.accentColor: Int
    get() = ThemeStore.accentColor(this)

val Context.backgroundColor: Int
    get() = ThemeStore.backgroundColor(this)

val Context.bottomBackground: Int
    get() = ThemeStore.bottomBackground(this)

val Context.primaryTextColor: Int
    get() = AppConfig.uiFontColor.toThemeTextColorOrNull()
        ?: defaultThemeTextColor(AppConfig.isNightTheme)

val Context.titleTextColor: Int
    get() = AppConfig.titleFontColor.toThemeTextColorOrNull()
        ?: defaultThemeTextColor(AppConfig.isNightTheme)

val Context.transparentNavBar: Boolean
    get() = ThemeStore.transparentNavBar(this)

val Context.secondaryTextColor: Int
    get() = AppConfig.uiFontColor.toThemeTextColorOrNull()
        ?.let { ColorUtils.withAlpha(it, 0.72f) }
        ?: ColorUtils.withAlpha(defaultThemeTextColor(AppConfig.isNightTheme), 0.72f)

val Context.primaryDisabledTextColor: Int
    get() = getPrimaryDisabledTextColor(!AppConfig.isNightTheme)

val Context.secondaryDisabledTextColor: Int
    get() = getSecondaryDisabledTextColor(!AppConfig.isNightTheme)

val Fragment.primaryColor: Int
    get() = ThemeStore.primaryColor(requireContext())

val Fragment.primaryColorDark: Int
    get() = ThemeStore.primaryColorDark(requireContext())

val Fragment.accentColor: Int
    get() = ThemeStore.accentColor(requireContext())

val Fragment.backgroundColor: Int
    get() = ThemeStore.backgroundColor(requireContext())

val Fragment.bottomBackground: Int
    get() = ThemeStore.bottomBackground(requireContext())

val Fragment.primaryTextColor: Int
    get() = AppConfig.uiFontColor.toThemeTextColorOrNull()
        ?: defaultThemeTextColor(AppConfig.isNightTheme)

val Fragment.secondaryTextColor: Int
    get() = AppConfig.uiFontColor.toThemeTextColorOrNull()
        ?.let { ColorUtils.withAlpha(it, 0.72f) }
        ?: ColorUtils.withAlpha(defaultThemeTextColor(AppConfig.isNightTheme), 0.72f)

val Fragment.primaryDisabledTextColor: Int
    get() = requireContext().getPrimaryDisabledTextColor(!AppConfig.isNightTheme)

val Fragment.secondaryDisabledTextColor: Int
    get() = requireContext().getSecondaryDisabledTextColor(!AppConfig.isNightTheme)

val Context.buttonDisabledColor: Int
    get() = if (AppConfig.isNightTheme) {
        ContextCompat.getColor(this, R.color.md_dark_disabled)
    } else {
        ContextCompat.getColor(this, R.color.md_light_disabled)
    }

val Context.isDarkTheme: Boolean
    get() = AppConfig.isNightTheme

val Fragment.isDarkTheme: Boolean
    get() = requireContext().isDarkTheme

/** 文字画在 primary 色表面（如深色 Toolbar）时的专用字色：按 primary 实际亮度选黑白（保持旧链语义） */
val Context.onPrimarySurfaceTextColor: Int
    get() = getPrimaryTextColor(!ColorUtils.isColorLight(ThemeStore.primaryColor(this)))

/** accent/任意底色上的自适应前景色：底色亮选黑，底色暗选白（统一收敛硬编码白字点） */
fun Context.onAccentFor(@ColorInt bgColor: Int): Int {
    return if (ColorUtils.isColorLight(bgColor)) {
        ContextCompat.getColor(this, R.color.md_light_primary_text)
    } else {
        ContextCompat.getColor(this, R.color.md_dark_primary_text)
    }
}

val Context.elevation: Float
    @SuppressLint("PrivateResource")
    get() {
        return if (AppConfig.elevation < 0) {
            ThemeUtils.resolveFloat(
                this,
                android.R.attr.elevation,
                resources.getDimension(com.google.android.material.R.dimen.design_appbar_elevation)
            )
        } else {
            AppConfig.elevation.toFloat().dpToPx()
        }
    }

val Context.filletBackground: GradientDrawable
    get() {
        val background = GradientDrawable()
        background.cornerRadius = 3f.dpToPx()
        background.setColor(backgroundColor)
        return background
    }

val Context.dialogSurfaceBackground: GradientDrawable
    get() = UiCorner.opaqueRounded(backgroundColor, UiCorner.panelRadius(this))

@ColorInt
fun String?.toThemeTextColorOrNull(): Int? {
    val raw = this?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val withoutPrefix = raw
        .removePrefix("#")
        .removePrefix("0x")
        .removePrefix("0X")
    val candidate = if (
        withoutPrefix.length in setOf(6, 8) &&
        withoutPrefix.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    ) {
        "#$withoutPrefix"
    } else {
        raw
    }
    return kotlin.runCatching { candidate.toColorInt() }.getOrNull()
}

@ColorInt
fun defaultThemeTextColor(isNightTheme: Boolean): Int {
    return if (isNightTheme) Color.WHITE else Color.BLACK
}

fun defaultThemeTextColorHex(isNightTheme: Boolean): String {
    return if (isNightTheme) "#FFFFFF" else "#000000"
}