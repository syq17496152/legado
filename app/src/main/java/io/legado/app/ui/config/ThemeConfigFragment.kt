package io.legado.app.ui.config

import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePackageManager
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.postEvent
import io.legado.app.utils.startActivity

class ThemeConfigFragment : ComposeSettingFragment() {

    override val titleRes: Int = R.string.theme_setting

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // H6: 三点菜单改由 ConfigActivity 顶栏 AppDropdownMenu 承载（替代 MenuProvider 系统菜单）
        // topbar-icon-semantics-fix 3.1：DarkMode 恢复一级亮度图标（对齐原版 theme_config.xml showAsAction=always）
        (activity as? ConfigActivity)?.setConfigMenuActions(
            listOf(
                MenuAction(Icons.Default.DarkMode, getString(R.string.theme_mode), alwaysShow = true) {
                    AppConfig.isNightTheme = !AppConfig.isNightTheme
                    ThemeConfig.applyDayNight(requireContext())
                }
            )
        )
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        SettingChoiceSpec(
                            key = PreferKey.launcherIcon,
                            title = getString(R.string.change_icon),
                            summary = getString(R.string.change_icon_summary),
                            options = iconOptions(),
                            selectedValue = stringSetting(PreferKey.launcherIcon, DEFAULT_LAUNCHER_ICON),
                            visible = Build.VERSION.SDK_INT >= 26,
                            onSelected = { updateStringSetting(PreferKey.launcherIcon, it) }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.mainTransparentStatusBar,
                            title = getString(R.string.main_immersion_status_bar),
                            summary = getString(R.string.main_status_bar_immersion),
                            checked = booleanSetting(PreferKey.mainTransparentStatusBar, false),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.mainTransparentStatusBar, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.followDynamicColor,
                            title = "跟随系统动态色",
                            summary = "使用系统壁纸配色作为主题色来源（Android 12+，应用后生效）",
                            checked = booleanSetting(PreferKey.followDynamicColor, false),
                            visible = Build.VERSION.SDK_INT >= 31,
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.followDynamicColor, it)
                            }
                        ),
                        SettingSwitchSpec(
                            key = PreferKey.immersiveManageBar,
                            title = getString(R.string.manage_bar_immersion),
                            summary = getString(R.string.manage_bar_immersion_summary),
                            checked = booleanSetting(PreferKey.immersiveManageBar, true),
                            onCheckedChange = {
                                updateBooleanSetting(PreferKey.immersiveManageBar, it)
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_THEME_MANAGE,
                            title = getString(R.string.theme_list),
                            summary = getString(R.string.theme_list_summary),
                            onClick = { startActivity<ThemeManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_NAVIGATION_BAR_MANAGE,
                            title = getString(R.string.navigation_bar_manage),
                            summary = getString(R.string.navigation_bar_manage_summary),
                            onClick = { startActivity<NavigationBarManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_DISCOVERY_SUBSCRIPTION_SETTINGS,
                            title = getString(R.string.discovery_subscription_settings_title),
                            summary = getString(R.string.discovery_subscription_settings_summary),
                            onClick = {
                                startActivity<ConfigActivity> {
                                    putExtra("configTag", ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG)
                                }
                            }
                        ),
                        SettingActionSpec(
                            key = KEY_TOP_BAR_MANAGE,
                            title = getString(R.string.top_bar_manage),
                            summary = getString(R.string.top_bar_manage_summary),
                            onClick = { startActivity<TopBarManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_BOOK_INFO_MANAGE,
                            title = getString(R.string.book_info_manage),
                            summary = getString(R.string.book_info_manage_summary),
                            onClick = { startActivity<BookInfoManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_BUBBLE_MANAGE,
                            title = getString(R.string.bubble_manage),
                            summary = getString(R.string.bubble_manage_summary),
                            onClick = { startActivity<BubbleManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = KEY_SHARE_NOTE_TEMPLATE_MANAGE,
                            title = "摘录分享模板",
                            summary = "管理正文长按分享图片使用的 HTML 模板",
                            searchKeys = listOf("分享模板", "摘录模板", "笔记模板", "正文分享"),
                            onClick = { startActivity<ShareNoteTemplateManageActivity>() }
                        ),
                        SettingActionSpec(
                            key = ConfigTag.COVER_CONFIG,
                            title = getString(R.string.cover_config),
                            summary = getString(R.string.cover_config_summary),
                            onClick = {
                                startActivity<ConfigActivity> {
                                    putExtra("configTag", ConfigTag.COVER_CONFIG)
                                }
                            }
                        )
                    )
                )
            )
        )
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.launcherIcon -> LauncherIconHelp.changeIcon(
                stringSetting(PreferKey.launcherIcon, DEFAULT_LAUNCHER_ICON)
            )

            // AD-07 动态色开关：重应用当前激活主题（双 mode），使系统配色替换生效
            PreferKey.followDynamicColor -> {
                lifecycleScope.launch {
                    runCatching {
                        ThemePackageManager.reapplyCurrentTheme(requireContext())
                    }.onFailure { e ->
                        e.printOnDebug()
                        AppLog.put("reapply for dynamic color failed: ${e.message}")
                    }
                }
            }

            PreferKey.mainTransparentStatusBar,
            PreferKey.transparentStatusBar,
            PreferKey.immersiveManageBar,
            PreferKey.immNavigationBar -> recreateActivities()
        }
    }

    private fun iconOptions(): List<SettingChoiceOption> {
        val entries = resources.getStringArray(R.array.icon_names)
        val values = resources.getStringArray(R.array.icons)
        return values.mapIndexed { index, value ->
            SettingChoiceOption(
                value = value,
                label = entries.getOrElse(index) { value },
                iconName = value
            )
        }
    }

    private fun recreateActivities() {
        postEvent(EventBus.RECREATE, "")
    }

    companion object {
        private const val DEFAULT_LAUNCHER_ICON = "ic_launcher"
        private const val KEY_THEME_MANAGE = "theme_manage"
        private const val KEY_NAVIGATION_BAR_MANAGE = "navigation_bar_manage"
        private const val KEY_DISCOVERY_SUBSCRIPTION_SETTINGS = "discoverySubscriptionSettings"
        private const val KEY_TOP_BAR_MANAGE = "top_bar_manage"
        private const val KEY_BOOK_INFO_MANAGE = "book_info_manage"
        private const val KEY_BUBBLE_MANAGE = "bubble_manage"
        private const val KEY_SHARE_NOTE_TEMPLATE_MANAGE = "share_note_template_manage"
    }
}