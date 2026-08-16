package io.legado.app.base

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isNightMode
import io.legado.app.utils.sysConfiguration
import java.util.*


@Suppress("unused")
object AppContextWrapper {

    @SuppressLint("ObsoleteSdkInt")
    fun wrap(context: Context): Context {
        val resources: Resources = context.resources
        val configuration: Configuration = resources.configuration
        val targetLocale = getSetLocale(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(targetLocale)
            configuration.setLocales(LocaleList(targetLocale))
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = targetLocale
        }
        configuration.fontScale = getFontScale(context)
        // 修复主题模式：App 手动设置深/浅色（themeMode）时强制 uiMode 与 App 主题同步。
        // 根因：BaseActivity.setTheme(AppTheme_Dark/Light) 不修改系统 uiMode，
        // values-night/colors.xml 只随系统真实夜间模式生效，导致「App 深色+系统浅色」时
        // @color/primaryText 等解析为 values 浅色版（黑色）→ View 页面样式不跟随 App 主题。
        // 保留 type bit（UI_MODE_TYPE_*），仅翻转 night bit。
        // ⚠️ 此处禁止访问 AppConfig（其静态初始化依赖 appCtx，而 wrap() 在 attachBaseContext
        // 阶段 appCtx 尚未注入，会抛 ExceptionInInitializerError），直接用传入 context 读
        // SharedPreferences，判定逻辑与 AppConfig.isNightTheme 保持一致（themeMode 默认 "2"=夜间）。
        val themeMode = context.getPrefString(PreferKey.themeMode, "2")
        val nightBit = when (themeMode) {
            "1", "3" -> Configuration.UI_MODE_NIGHT_NO
            "2" -> Configuration.UI_MODE_NIGHT_YES
            else -> if (sysConfiguration.isNightMode) {
                Configuration.UI_MODE_NIGHT_YES
            } else {
                Configuration.UI_MODE_NIGHT_NO
            }
        }
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightBit
        return context.createConfigurationContext(configuration)
    }

    fun getFontScale(context: Context): Float {
        var fontScale = context.getPrefInt(PreferKey.fontScale) / 10f
        if (fontScale !in 0.8f..1.6f) {
            fontScale = sysConfiguration.fontScale
        }
        return fontScale
    }

    /**
     * 当前系统语言
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun getSystemLocale(): Locale {
        val locale: Locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { //7.0有多语言设置获取顶部的语言
            locale = sysConfiguration.locales.get(0)
        } else {
            @Suppress("DEPRECATION")
            locale = sysConfiguration.locale
        }
        return locale
    }

    /**
     * 当前App语言
     */
    @SuppressLint("ObsoleteSdkInt")
    private fun getAppLocale(context: Context): Locale {
        val locale: Locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locale = context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            locale = context.resources.configuration.locale
        }
        return locale

    }

    /**
     * 当前设置语言
     */
    private fun getSetLocale(context: Context): Locale {
        return when (context.getPrefString(PreferKey.language)) {
            "zh" -> Locale.SIMPLIFIED_CHINESE
            "tw" -> Locale.TRADITIONAL_CHINESE
            "en" -> Locale.ENGLISH
            else -> getSystemLocale()
        }
    }

    /**
     * 判断App语言和设置语言是否相同
     */
    fun isSameWithSetting(context: Context): Boolean {
        val locale = getAppLocale(context)
        val language = locale.language
        val country = locale.country
        val pfLocale = getSetLocale(context)
        val pfLanguage = pfLocale.language
        val pfCountry = pfLocale.country
        return language == pfLanguage && country == pfCountry
    }

}