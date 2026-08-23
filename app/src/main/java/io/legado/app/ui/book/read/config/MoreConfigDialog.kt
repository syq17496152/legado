package io.legado.app.ui.book.read.config

import android.annotation.SuppressLint
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import io.legado.app.R
import io.legado.app.base.BasePrefDialogFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.lib.theme.dialogSurfaceBackground
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.dpToPx
import io.legado.app.utils.postEvent
import io.legado.app.utils.removePref

class MoreConfigDialog : BasePrefDialogFragment() {
    private val readPreferTag = "readPreferenceFragment"

    override fun onStart() {
        super.onStart()
        dialog?.window?.applyReaderBottomSheetWindow(
            height = minOf(
                (resources.displayMetrics.heightPixels * 0.68f).toInt(),
                520.dpToPx()
            ).coerceAtLeast(360.dpToPx())
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        (activity as ReadBookActivity).bottomDialog++
        return FrameLayout(requireContext()).apply {
            background = requireContext().dialogSurfaceBackground
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            id = R.id.tag1
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        var preferenceFragment = childFragmentManager.findFragmentByTag(readPreferTag)
        if (preferenceFragment == null) preferenceFragment = ReadPreferenceFragment()
        childFragmentManager.beginTransaction()
            .replace(view.id, preferenceFragment, readPreferTag)
            .commit()
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        (activity as ReadBookActivity).bottomDialog--
    }

    class ReadPreferenceFragment : ComposeSettingFragment() {

        private val slopSquare by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }

        override val titleRes: Int = R.string.setting

        override val applyActivityTitle: Boolean = false

        override val autoOpenTargetItem: Boolean = false

        override val drawPanelImage: Boolean = false

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            if (!CanvasRecorderFactory.isSupport) {
                removePref(PreferKey.optimizeRender)
            }
        }

        override fun buildPageSpec(): SettingPageSpec {
            return SettingPageSpec(
                titleRes = titleRes,
                sections = listOf(
                    SettingSectionSpec(
                        items = listOf(
                            choice(
                                key = PreferKey.screenOrientation,
                                title = getString(R.string.screen_direction),
                                entriesRes = R.array.screen_direction_title,
                                valuesRes = R.array.screen_direction_value,
                                defaultValue = "0"
                            ),
                            choice(
                                key = PreferKey.keepLight,
                                title = getString(R.string.keep_light),
                                entriesRes = R.array.screen_time_out,
                                valuesRes = R.array.screen_time_out_value,
                                defaultValue = "0"
                            ),
                            switch(
                                key = PreferKey.hideStatusBar,
                                title = getString(R.string.pt_hide_status_bar),
                                defaultValue = false
                            ),
                            switch(
                                key = PreferKey.hideNavigationBar,
                                title = getString(R.string.pt_hide_navigation_bar),
                                defaultValue = false
                            ),
                            switch(
                                key = PreferKey.readBodyToLh,
                                title = getString(R.string.read_body_to_lh),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.paddingDisplayCutouts,
                                title = getString(R.string.padding_display_cutouts),
                                defaultValue = false
                            ),
                            choice(
                                key = PreferKey.doublePageHorizontal,
                                title = getString(R.string.double_page_horizontal),
                                entriesRes = R.array.double_page_title,
                                valuesRes = R.array.double_page_value,
                                defaultValue = "0"
                            ),
                            choice(
                                key = PreferKey.progressBarBehavior,
                                title = getString(R.string.progress_bar_behavior),
                                entriesRes = R.array.progress_bar_behavior_title,
                                valuesRes = R.array.progress_bar_behavior_value,
                                defaultValue = "page"
                            ),
                            switch(
                                key = PreferKey.useZhLayout,
                                title = getString(R.string.use_zh_layout),
                                defaultValue = false
                            ),
                            switch(
                                key = PreferKey.textFullJustify,
                                title = getString(R.string.text_full_justify),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.textBottomJustify,
                                title = getString(R.string.text_bottom_justify),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.adaptSpecialStyle,
                                title = getString(R.string.adapt_special_style),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.mouseWheelPage,
                                title = getString(R.string.mouse_wheel_page),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.volumeKeyPage,
                                title = getString(R.string.volume_key_page),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.volumeKeyPageOnPlay,
                                title = getString(R.string.volume_key_page_on_play),
                                defaultValue = false
                            ),
                            switch(
                                key = PreferKey.keyPageOnLongPress,
                                title = getString(R.string.key_page_on_long_press),
                                defaultValue = false
                            ),
                            SettingActionSpec(
                                key = PreferKey.pageTouchSlop,
                                title = getString(R.string.page_touch_slop_title),
                                summary = getString(R.string.page_touch_slop_summary, slopSquare.toString()),
                                onClick = ::showPageTouchSlopDialog
                            ),
                            SettingActionSpec(
                                key = PreferKey.pageTouchClick,
                                title = getString(R.string.page_touch_click_title),
                                summary = getString(R.string.page_touch_click_summary),
                                onClick = ::showPageTouchClickDialog
                            ),
                            SettingActionSpec(
                                key = PreferKey.readMenuAlpha,
                                title = getString(R.string.read_menu_alpha),
                                summary = getString(R.string.ui_layout_alpha_value, AppConfig.readMenuAlpha),
                                onClick = ::showReadMenuAlphaDialog
                            ),
                            switch(
                                key = PreferKey.autoChangeSource,
                                title = getString(R.string.auto_change_source),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.textSelectAble,
                                title = getString(R.string.selectText),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.showBrightnessView,
                                title = getString(R.string.show_brightness_view),
                                defaultValue = true
                            ),
                            switch(
                                key = PreferKey.noAnimScrollPage,
                                title = getString(R.string.no_anim_scroll_page),
                                defaultValue = false
                            ),
                            choice(
                                key = PreferKey.clickImgWay,
                                title = getString(R.string.click_image_way),
                                entriesRes = R.array.click_image_way_title,
                                valuesRes = R.array.click_image_way_value,
                                defaultValue = "0"
                            ),
                            switch(
                                key = PreferKey.optimizeRender,
                                title = getString(R.string.enable_optimize_render),
                                defaultValue = false,
                                visible = CanvasRecorderFactory.isSupport
                            ),
                            SettingActionSpec(
                                key = KEY_CLICK_REGIONAL_CONFIG,
                                title = getString(R.string.click_regional_config),
                                onClick = {
                                    (activity as? ReadBookActivity)?.showClickRegionalConfig()
                                }
                            ),
                            switch(
                                key = KEY_DISABLE_RETURN_KEY,
                                title = getString(R.string.disable_return_key),
                                defaultValue = false
                            ),
                            SettingActionSpec(
                                key = KEY_CUSTOM_PAGE_KEY,
                                title = getString(R.string.custom_page_key),
                                onClick = {
                                    PageKeyDialog(requireContext()).show()
                                }
                            ),
                            switch(
                                key = PreferKey.expandTextMenu,
                                title = getString(R.string.expand_text_menu),
                                defaultValue = false
                            ),
                            SettingActionSpec(
                                key = PreferKey.contentSelectMenuConfig,
                                title = getString(R.string.content_select_menu_config),
                                summary = getString(R.string.content_select_menu_config_summary),
                                onClick = {
                                    ContentSelectMenuConfigDialog()
                                        .show(parentFragmentManager, "contentSelectMenuConfig")
                                }
                            ),
                            switch(
                                key = PreferKey.showReadTitleAddition,
                                title = getString(R.string.show_read_title_addition),
                                defaultValue = true
                            )
                        )
                    )
                )
            )
        }

        override fun onSettingPreferenceChanged(key: String) {
            when (key) {
                PreferKey.readBodyToLh -> activity?.recreate()
                PreferKey.hideStatusBar -> {
                    ReadBookConfig.hideStatusBar = booleanSetting(PreferKey.hideStatusBar, false)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.hideNavigationBar -> {
                    ReadBookConfig.hideNavigationBar = booleanSetting(PreferKey.hideNavigationBar, false)
                    postEvent(EventBus.UP_CONFIG, arrayListOf(0, 2))
                }

                PreferKey.keepLight -> postEvent(key, true)
                PreferKey.textSelectAble -> postEvent(key, booleanSetting(key, true))
                PreferKey.screenOrientation -> {
                    (activity as? ReadBookActivity)?.setOrientation()
                }

                PreferKey.textFullJustify,
                PreferKey.textBottomJustify,
                PreferKey.useZhLayout,
                PreferKey.adaptSpecialStyle-> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(5))
                }

                PreferKey.showBrightnessView -> {
                    postEvent(PreferKey.showBrightnessView, "")
                }

                PreferKey.expandTextMenu -> {
                    (activity as? ReadBookActivity)?.textActionMenu?.upMenu()
                }
                PreferKey.contentSelectActions,
                PreferKey.contentSelectDefaultOpen -> {
                    (activity as? ReadBookActivity)?.textActionMenu?.upMenu()
                }

                PreferKey.doublePageHorizontal -> {
                    ChapterProvider.upLayout()
                    ReadBook.loadContent(false)
                }

                PreferKey.showReadTitleAddition,
                PreferKey.readMenuAlpha -> {
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }

                PreferKey.progressBarBehavior -> {
                    postEvent(EventBus.UP_SEEK_BAR, true)
                }

                PreferKey.noAnimScrollPage -> {
                    ReadBook.callBack?.get()?.upPageAnim()
                }

                PreferKey.optimizeRender -> {
                    ChapterProvider.upStyle()
                    ReadBook.callBack?.get()?.upPageAnim(true)
                    ReadBook.loadContent(false)
                }

                PreferKey.paddingDisplayCutouts -> {
                    postEvent(EventBus.UP_CONFIG, arrayListOf(2))
                }
            }
        }

        private fun switch(
            key: String,
            title: String,
            defaultValue: Boolean,
            visible: Boolean = true
        ): SettingSwitchSpec {
            return SettingSwitchSpec(
                key = key,
                title = title,
                checked = booleanSetting(key, defaultValue),
                visible = visible,
                onCheckedChange = { updateBooleanSetting(key, it) }
            )
        }

        private fun choice(
            key: String,
            title: String,
            entriesRes: Int,
            valuesRes: Int,
            defaultValue: String
        ): SettingChoiceSpec {
            val options = choiceOptions(entriesRes, valuesRes)
            val selectedValue = stringSetting(key, defaultValue)
            return SettingChoiceSpec(
                key = key,
                title = title,
                summary = choiceLabel(options, selectedValue),
                options = options,
                selectedValue = selectedValue,
                onSelected = { updateStringSetting(key, it) }
            )
        }

        private fun choiceOptions(
            entriesRes: Int,
            valuesRes: Int
        ): List<SettingChoiceOption> {
            val entries = resources.getStringArray(entriesRes)
            val values = resources.getStringArray(valuesRes)
            return values.mapIndexed { index, value ->
                SettingChoiceOption(
                    value = value,
                    label = entries.getOrElse(index) { value }
                )
            }
        }

        private fun choiceLabel(
            options: List<SettingChoiceOption>,
            selectedValue: String
        ): String {
            return options.firstOrNull { it.value == selectedValue }
                ?.label
                ?.toString()
                ?: selectedValue
        }

        private fun showPageTouchSlopDialog() {
            showComposeNumberPickerDialog(
                title = getString(R.string.page_touch_slop_dialog_title),
                value = AppConfig.pageTouchSlop,
                minValue = 0,
                maxValue = 9999,
                onValue = {
                    AppConfig.pageTouchSlop = it
                    postEvent(EventBus.UP_CONFIG, arrayListOf(4))
                }
            )
        }

        private fun showPageTouchClickDialog() {
            showComposeNumberPickerDialog(
                title = getString(R.string.page_touch_click_dialog_title),
                value = AppConfig.pageTouchClick,
                minValue = 0,
                maxValue = 399,
                onValue = {
                    AppConfig.pageTouchClick = it
                    postEvent(EventBus.UP_CONFIG, arrayListOf(12))
                }
            )
        }

        private fun showReadMenuAlphaDialog() {
            showComposeNumberPickerDialog(
                title = getString(R.string.read_menu_alpha),
                value = AppConfig.readMenuAlpha,
                minValue = 35,
                maxValue = 100,
                customText = getString(R.string.btn_default_s),
                onCustom = {
                    AppConfig.readMenuAlpha = 100
                    refreshSettings()
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                },
                onValue = {
                    AppConfig.readMenuAlpha = it.coerceIn(35, 100)
                    refreshSettings()
                    postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
                }
            )
        }

        companion object {
            private const val KEY_CLICK_REGIONAL_CONFIG = "clickRegionalConfig"
            private const val KEY_CUSTOM_PAGE_KEY = "customPageKey"
            private const val KEY_DISABLE_RETURN_KEY = "disableReturnKey"
        }
    }
}
