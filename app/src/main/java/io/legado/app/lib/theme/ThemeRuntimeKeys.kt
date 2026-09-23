package io.legado.app.lib.theme

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.defaultSharedPreferences

object ThemeRuntimeKeys {

    private const val legacyNightMigratedKey = "themeNightExtMigrated"

    // 日夜键拆分前，这些字段日夜共用旧键。首次升级时把旧值复制到夜间键，
    // 否则夜间模式的字号/字体/颜色/透明度等会全部回落默认值。
    private val legacyNightPairs = listOf(
        PreferKey.fontScale to PreferKey.fontScaleN,
        PreferKey.uiFontPath to PreferKey.uiFontPathN,
        PreferKey.titleFontPath to PreferKey.titleFontPathN,
        PreferKey.uiFontColor to PreferKey.uiFontColorN,
        PreferKey.titleFontColor to PreferKey.titleFontColorN,
        PreferKey.uiCornerScale to PreferKey.uiCornerScaleN,
        PreferKey.uiLayoutAlpha to PreferKey.uiLayoutAlphaN,
        PreferKey.dialogAlpha to PreferKey.dialogAlphaN,
        PreferKey.uiCornerSearchFollow to PreferKey.uiCornerSearchFollowN,
        PreferKey.uiCornerReplyFollow to PreferKey.uiCornerReplyFollowN,
        PreferKey.themeCardColor to PreferKey.themeCardColorN,
        PreferKey.themeMutedColor to PreferKey.themeMutedColorN,
        PreferKey.themeSearchFieldBackgroundColor to PreferKey.themeSearchFieldBackgroundColorN,
        PreferKey.themeTabBackgroundColor to PreferKey.themeTabBackgroundColorN,
        PreferKey.themeShelfColor to PreferKey.themeShelfColorN,
        PreferKey.themeCardShadow to PreferKey.themeCardShadowN,
        PreferKey.themeCardBackgroundBlur to PreferKey.themeCardBackgroundBlurN
    )

    fun migrateLegacyNightValues(context: Context) {
        val prefs = context.defaultSharedPreferences
        if (prefs.getBoolean(legacyNightMigratedKey, false)) return
        val all = prefs.all
        val editor = prefs.edit()
        legacyNightPairs.forEach { (dayKey, nightKey) ->
            if (!all.containsKey(nightKey)) {
                when (val value = all[dayKey]) {
                    is Int -> editor.putInt(nightKey, value)
                    is Boolean -> editor.putBoolean(nightKey, value)
                    is String -> editor.putString(nightKey, value)
                    is Float -> editor.putFloat(nightKey, value)
                    is Long -> editor.putLong(nightKey, value)
                }
            }
        }
        editor.putBoolean(legacyNightMigratedKey, true)
        editor.commit()
    }

    private const val themeFirstInstallMigratedKey = "themeFirstInstallFlagMigrated"

    /**
     * 首启标志位迁移（AD-17 / A3.5b）。
     *
     * 背景：`App.kt` 原用「`dNThemeName` 是否为空」判定首装 → **只用过日间主题、从不设夜间主题的
     * 存量用户会被误判为首装**，进而被强制切夜间 + 改顶栏样式（灾难性覆盖用户主题）。
     *
     * 迁移口径（哨兵键 + 幂等，与 [migrateLegacyNightValues] 同范式）：
     *  · `themeMode` / `dNThemeName` / `dThemeName` 任一已非空 ⇒ 存量用户 ⇒ 写 `false`
     *  · 三者全为空 ⇒ 真首装 ⇒ 写 `true`
     */
    fun migrateThemeFirstInstallFlag(context: Context) {
        val prefs = context.defaultSharedPreferences
        if (prefs.getBoolean(themeFirstInstallMigratedKey, false)) return
        val isLegacyUser = listOf(
            PreferKey.themeMode, PreferKey.dNThemeName, PreferKey.dThemeName
        ).any { !prefs.getString(it, null).isNullOrBlank() }
        prefs.edit()
            .putBoolean(PreferKey.themeFirstInstallDone, !isLegacyUser)
            .putBoolean(themeFirstInstallMigratedKey, true)
            .commit()
    }

    private const val appearanceKitAutoApplyMigratedKey = "appearanceKitAutoApplyFlagMigrated"

    /**
     * 「首装自动套用暗夜紫套件」一次性标记的**存量回填**（2026-09-24，主题设置体系失守修复配套）。
     *
     * 背景：`App.kt` 原先每次启动都套用套件（判据 `theme_first_install_done` 长期为 true）。
     * 修复后改为「一次性」，但**已配置过的存量用户**若升级后被判为「尚未自动套用过」，
     * 会在升级后首启**再被套件覆盖一次**（主题/布局/顶栏包被改回）。
     *
     * 回填口径（哨兵键 + 幂等，与 [migrateThemeFirstInstallFlag] 同范式）：
     *  · `currentAppearanceKitId` / `dNThemeName` / `dThemeName` 任一非空 ⇒ 用户已配置 ⇒ 标记已套用过
     *  · 三者全空 ⇒ 真首装 ⇒ 不写标记，保留「首装自动套用一次」的行为
     */
    fun migrateAppearanceKitAutoApplyFlag(context: Context) {
        val prefs = context.defaultSharedPreferences
        if (prefs.getBoolean(appearanceKitAutoApplyMigratedKey, false)) return
        val hasUserConfig = listOf(
            PreferKey.currentAppearanceKitId, PreferKey.dNThemeName, PreferKey.dThemeName
        ).any { !prefs.getString(it, null).isNullOrBlank() }
        prefs.edit().apply {
            if (hasUserConfig) putBoolean(PreferKey.appearanceKitAutoApplyDone, true)
            putBoolean(appearanceKitAutoApplyMigratedKey, true)
        }.commit()
    }

    fun fontScale(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.fontScaleN else PreferKey.fontScale

    fun uiFontPath(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiFontPathN else PreferKey.uiFontPath

    fun titleFontPath(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.titleFontPathN else PreferKey.titleFontPath

    fun uiFontColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiFontColorN else PreferKey.uiFontColor

    fun titleFontColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.titleFontColorN else PreferKey.titleFontColor

    fun uiCornerScale(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiCornerScaleN else PreferKey.uiCornerScale

    fun uiLayoutAlpha(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiLayoutAlphaN else PreferKey.uiLayoutAlpha

    fun dialogAlpha(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.dialogAlphaN else PreferKey.dialogAlpha

    fun uiCornerSearchFollow(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiCornerSearchFollowN else PreferKey.uiCornerSearchFollow

    fun uiCornerReplyFollow(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.uiCornerReplyFollowN else PreferKey.uiCornerReplyFollow

    fun themeCardColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeCardColorN else PreferKey.themeCardColor

    fun themeMutedColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeMutedColorN else PreferKey.themeMutedColor

    fun themeSearchFieldBackgroundColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeSearchFieldBackgroundColorN else PreferKey.themeSearchFieldBackgroundColor

    fun themeTabBackgroundColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeTabBackgroundColorN else PreferKey.themeTabBackgroundColor

    fun themeShelfColor(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeShelfColorN else PreferKey.themeShelfColor

    fun themeCardShadow(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeCardShadowN else PreferKey.themeCardShadow

    fun themeCardBackgroundBlur(isNight: Boolean = AppConfig.isNightTheme): String =
        if (isNight) PreferKey.themeCardBackgroundBlurN else PreferKey.themeCardBackgroundBlur

    fun activeColorKey(key: String, isNight: Boolean = AppConfig.isNightTheme): String {
        return when (key) {
            PreferKey.themeCardColor, PreferKey.themeCardColorN -> themeCardColor(isNight)
            PreferKey.themeMutedColor, PreferKey.themeMutedColorN -> themeMutedColor(isNight)
            PreferKey.themeSearchFieldBackgroundColor,
            PreferKey.themeSearchFieldBackgroundColorN -> themeSearchFieldBackgroundColor(isNight)
            PreferKey.themeTabBackgroundColor, PreferKey.themeTabBackgroundColorN -> themeTabBackgroundColor(isNight)
            PreferKey.themeShelfColor, PreferKey.themeShelfColorN -> themeShelfColor(isNight)
            else -> key
        }
    }

    fun allKeys(): Set<String> = setOf(
        PreferKey.fontScale,
        PreferKey.fontScaleN,
        PreferKey.uiFontPath,
        PreferKey.uiFontPathN,
        PreferKey.titleFontPath,
        PreferKey.titleFontPathN,
        PreferKey.uiFontColor,
        PreferKey.uiFontColorN,
        PreferKey.titleFontColor,
        PreferKey.titleFontColorN,
        PreferKey.uiCornerScale,
        PreferKey.uiCornerScaleN,
        PreferKey.uiLayoutAlpha,
        PreferKey.uiLayoutAlphaN,
        PreferKey.dialogAlpha,
        PreferKey.dialogAlphaN,
        PreferKey.uiCornerSearchFollow,
        PreferKey.uiCornerSearchFollowN,
        PreferKey.uiCornerReplyFollow,
        PreferKey.uiCornerReplyFollowN,
        PreferKey.themeCardColor,
        PreferKey.themeCardColorN,
        PreferKey.themeMutedColor,
        PreferKey.themeMutedColorN,
        PreferKey.themeSearchFieldBackgroundColor,
        PreferKey.themeSearchFieldBackgroundColorN,
        PreferKey.themeTabBackgroundColor,
        PreferKey.themeTabBackgroundColorN,
        PreferKey.themeShelfColor,
        PreferKey.themeShelfColorN,
        PreferKey.themeCardShadow,
        PreferKey.themeCardShadowN,
        PreferKey.themeCardBackgroundBlur,
        PreferKey.themeCardBackgroundBlurN
    )
}