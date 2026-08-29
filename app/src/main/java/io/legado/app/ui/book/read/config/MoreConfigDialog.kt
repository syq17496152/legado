package io.legado.app.ui.book.read.config

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.model.ReadBook
import io.legado.app.ui.book.read.ReadBookActivity
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSwitchRow
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.showComposeSingleChoiceDialog
import io.legado.app.utils.canvasrecorder.CanvasRecorderFactory
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref

/**
 * 更多阅读设置（偏好面板型弹框迁移 ComposeDialogFragment + AppDialogFrame，设置行按原交互语义重组）
 */
class MoreConfigDialog() : ComposeDialogFragment() {

    override val dialogTheme: Int = R.style.Theme_Legado_ComposeDialog_Bottom
    override val dialogWidth: Int = ViewGroup.LayoutParams.MATCH_PARENT
    override val dialogHeight: Int = ViewGroup.LayoutParams.WRAP_CONTENT
    override val dialogGravity: Int = Gravity.BOTTOM
    override val dialogWindowAnimations: Int = R.style.AnimDialogBottom

    private val slopSquare by lazy { ViewConfiguration.get(requireContext()).scaledTouchSlop }
    private var registeredBottomDialog = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CanvasRecorderFactory.isSupport) {
            removePref(PreferKey.optimizeRender)
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setBackgroundDrawableResource(android.R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        if (registeredBottomDialog) {
            (activity as? ReadBookActivity)?.let { it.bottomDialog-- }
            registeredBottomDialog = false
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val readActivity = activity as? ReadBookActivity
        val bottomDialog = readActivity?.bottomDialog ?: 0
        val alreadyRegistered = registeredBottomDialog
        val shouldDismissForExistingDialog = !alreadyRegistered && bottomDialog > 0
        if (!alreadyRegistered && readActivity != null) {
            readActivity.bottomDialog = bottomDialog + 1
            registeredBottomDialog = true
        }
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            if (shouldDismissForExistingDialog) {
                post { dismissAllowingStateLoss() }
            }
            setContent {
                if (!shouldDismissForExistingDialog) {
                    LegadoComposeTheme {
                        MoreConfigPanel()
                    }
                }
            }
        }
    }

    private fun booleanSetting(key: String, defaultValue: Boolean): Boolean {
        return requireContext().getPrefBoolean(key, defaultValue)
    }

    private fun stringSetting(key: String, defaultValue: String): String {
        return requireContext().getPrefString(key, defaultValue) ?: defaultValue
    }

    private fun updateBooleanSetting(key: String, value: Boolean) {
        requireContext().putPrefBoolean(key, value)
    }

    private fun updateStringSetting(key: String, value: String) {
        requireContext().putPrefString(key, value)
    }

    private fun handleSettingChanged(key: String) {
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

    private fun showReadMenuAlphaDialog(onUpdated: (Int) -> Unit) {
        showComposeNumberPickerDialog(
            title = getString(R.string.read_menu_alpha),
            value = AppConfig.readMenuAlpha,
            minValue = 35,
            maxValue = 100,
            customText = getString(R.string.btn_default_s),
            onCustom = {
                AppConfig.readMenuAlpha = 100
                onUpdated(AppConfig.readMenuAlpha)
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            },
            onValue = {
                AppConfig.readMenuAlpha = it.coerceIn(35, 100)
                onUpdated(AppConfig.readMenuAlpha)
                postEvent(EventBus.UPDATE_READ_ACTION_BAR, true)
            }
        )
    }

    @Composable
    private fun MoreConfigPanel() {
        AppDialogFrame(
            title = stringResource(R.string.setting),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ChoiceRow(
                        key = PreferKey.screenOrientation,
                        title = getString(R.string.screen_direction),
                        entriesRes = R.array.screen_direction_title,
                        valuesRes = R.array.screen_direction_value,
                        defaultValue = "0"
                    )
                    ChoiceRow(
                        key = PreferKey.keepLight,
                        title = getString(R.string.keep_light),
                        entriesRes = R.array.screen_time_out,
                        valuesRes = R.array.screen_time_out_value,
                        defaultValue = "0"
                    )
                    SwitchRow(
                        key = PreferKey.hideStatusBar,
                        title = getString(R.string.pt_hide_status_bar),
                        defaultValue = false
                    )
                    SwitchRow(
                        key = PreferKey.hideNavigationBar,
                        title = getString(R.string.pt_hide_navigation_bar),
                        defaultValue = false
                    )
                    SwitchRow(
                        key = PreferKey.readBodyToLh,
                        title = getString(R.string.read_body_to_lh),
                        defaultValue = true
                    )
                    SwitchRow(
                        key = PreferKey.paddingDisplayCutouts,
                        title = getString(R.string.padding_display_cutouts),
                        defaultValue = false
                    )
                    ChoiceRow(
                        key = PreferKey.doublePageHorizontal,
                        title = getString(R.string.double_page_horizontal),
                        entriesRes = R.array.double_page_title,
                        valuesRes = R.array.double_page_value,
                        defaultValue = "0"
                    )
                    ChoiceRow(
                        key = PreferKey.progressBarBehavior,
                        title = getString(R.string.progress_bar_behavior),
                        entriesRes = R.array.progress_bar_behavior_title,
                        valuesRes = R.array.progress_bar_behavior_value,
                        defaultValue = "page"
                    )
                    SwitchRow(
                        key = PreferKey.useZhLayout,
                        title = getString(R.string.use_zh_layout),
                        defaultValue = false
                    )
                    SwitchRow(
                        key = PreferKey.textFullJustify,
                        title = getString(R.string.text_full_justify),
                        defaultValue = true
                    )
                    SwitchRow(
                        key = PreferKey.textBottomJustify,
                        title = getString(R.string.text_bottom_justify),
                        defaultValue = true
                    )
                    SwitchRow(
                        key = PreferKey.adaptSpecialStyle,
                        title = getString(R.string.adapt_special_style),
                        defaultValue = true
                    )
                    SwitchRow(
                        key = PreferKey.mouseWheelPage,
                        title = getString(R.string.mouse_wheel_page),
                        defaultValue = true
                    )
                    SwitchRow(
                        key = PreferKey.volumeKeyPage,
                        title = getString(R.string.volume_key_page),
                        defaultValue = true
                    )
                    SwitchRow(
                        key = PreferKey.volumeKeyPageOnPlay,
                        title = getString(R.string.volume_key_page_on_play),
                        defaultValue = false
                    )
                    SwitchRow(
                        key = PreferKey.keyPageOnLongPress,
                        title = getString(R.string.key_page_on_long_press),
                        defaultValue = false
                    )
                    SettingActionRow(
                        title = getString(R.string.page_touch_slop_title),
                        summary = getString(R.string.page_touch_slop_summary, slopSquare.toString()),
                        onClick = ::showPageTouchSlopDialog
                    )
                    SettingActionRow(
                        title = getString(R.string.page_touch_click_title),
                        summary = getString(R.string.page_touch_click_summary),
                        onClick = ::showPageTouchClickDialog
                    )
                    var readMenuAlpha by remember { mutableIntStateOf(AppConfig.readMenuAlpha) }
                    SettingActionRow(
                        title = getString(R.string.read_menu_alpha),
                        summary = getString(R.string.ui_layout_alpha_value, readMenuAlpha),
                        onClick = {
                            showReadMenuAlphaDialog(onUpdated = { readMenuAlpha = it })
                        }
                    )
                    SwitchRow(
                        key = PreferKey.autoChangeSource,
                        title = getString(R.string.auto_change_source),
                        defaultValue = true
                    )
                    SwitchRow(
                        key = PreferKey.textSelectAble,
                        title = getString(R.string.selectText),
                        defaultValue = true
                    )
                    SwitchRow(
                        key = PreferKey.showBrightnessView,
                        title = getString(R.string.show_brightness_view),
                        defaultValue = true
                    )
                    SwitchRow(
                        key = PreferKey.noAnimScrollPage,
                        title = getString(R.string.no_anim_scroll_page),
                        defaultValue = false
                    )
                    ChoiceRow(
                        key = PreferKey.clickImgWay,
                        title = getString(R.string.click_image_way),
                        entriesRes = R.array.click_image_way_title,
                        valuesRes = R.array.click_image_way_value,
                        defaultValue = "0"
                    )
                    SwitchRow(
                        key = PreferKey.optimizeRender,
                        title = getString(R.string.enable_optimize_render),
                        defaultValue = false,
                        visible = CanvasRecorderFactory.isSupport
                    )
                    SettingActionRow(
                        title = getString(R.string.click_regional_config),
                        onClick = {
                            (activity as? ReadBookActivity)?.showClickRegionalConfig()
                        }
                    )
                    SwitchRow(
                        key = KEY_DISABLE_RETURN_KEY,
                        title = getString(R.string.disable_return_key),
                        defaultValue = false
                    )
                    SettingActionRow(
                        title = getString(R.string.custom_page_key),
                        onClick = {
                            PageKeyDialog(requireContext()).show()
                        }
                    )
                    SwitchRow(
                        key = PreferKey.expandTextMenu,
                        title = getString(R.string.expand_text_menu),
                        defaultValue = false
                    )
                    SettingActionRow(
                        title = getString(R.string.content_select_menu_config),
                        summary = getString(R.string.content_select_menu_config_summary),
                        onClick = {
                            ContentSelectMenuConfigDialog()
                                .show(childFragmentManager, "contentSelectMenuConfig")
                        }
                    )
                    SwitchRow(
                        key = PreferKey.showReadTitleAddition,
                        title = getString(R.string.show_read_title_addition),
                        defaultValue = true
                    )
                }
            },
            actions = {}
        )
    }

    @Composable
    private fun SwitchRow(
        key: String,
        title: String,
        defaultValue: Boolean,
        visible: Boolean = true
    ) {
        if (!visible) return
        var checked by remember { mutableStateOf(booleanSetting(key, defaultValue)) }
        AppDialogSwitchRow(
            text = title,
            checked = checked,
            onCheckedChange = {
                checked = it
                updateBooleanSetting(key, it)
                handleSettingChanged(key)
            }
        )
    }

    @Composable
    private fun ChoiceRow(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: String
    ) {
        var selectedValue by remember { mutableStateOf(stringSetting(key, defaultValue)) }
        val options = remember(entriesRes, valuesRes) {
            val entries = resources.getStringArray(entriesRes)
            val values = resources.getStringArray(valuesRes)
            values.mapIndexed { index, value ->
                value to entries.getOrElse(index) { value }
            }
        }
        val label = options.firstOrNull { it.first == selectedValue }?.second ?: selectedValue
        SettingActionRow(
            title = title,
            summary = label,
            onClick = {
                showComposeSingleChoiceDialog(
                    title = title,
                    labels = options.map { it.second },
                    selectedIndex = options.indexOfFirst { it.first == selectedValue }.coerceAtLeast(0),
                    positiveText = getString(R.string.ok),
                    negativeText = getString(R.string.cancel),
                    onPositive = { index ->
                        options.getOrNull(index)?.let { option ->
                            selectedValue = option.first
                            updateStringSetting(key, option.first)
                            handleSettingChanged(key)
                        }
                    }
                )
            }
        )
    }

    @Composable
    private fun SettingActionRow(
        title: String,
        summary: String? = null,
        onClick: () -> Unit
    ) {
        val style = rememberAppDialogStyle()
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            cornerRadius = style.actionRadius,
            insidePadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    color = style.primaryText,
                    fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                    fontWeight = FontWeight.Medium
                )
                summary?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = it,
                        color = style.secondaryText,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize
                    )
                }
            }
        }
    }

    companion object {
        private const val KEY_DISABLE_RETURN_KEY = "disableReturnKey"
    }
}
