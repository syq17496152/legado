package io.legado.app.ui.config

import android.content.ComponentName
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.view.postDelayed
import androidx.fragment.app.activityViewModels
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DebugFloatBallManager
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.model.CheckSource
import io.legado.app.model.ImageProvider
import io.legado.app.receiver.SharedReceiverActivity
import io.legado.app.service.WebService
import io.legado.app.ui.book.read.config.ContentSelectMenuConfigDialog
import io.legado.app.ui.config.compose.ComposeSettingFragment
import io.legado.app.ui.config.compose.SettingActionSpec
import io.legado.app.ui.config.compose.SettingChoiceOption
import io.legado.app.ui.config.compose.SettingChoiceSpec
import io.legado.app.ui.config.compose.SettingItemSpec
import io.legado.app.ui.config.compose.SettingPageSpec
import io.legado.app.ui.config.compose.SettingSectionSpec
import io.legado.app.ui.config.compose.SettingSwitchSpec
import io.legado.app.ui.debug.DebugToolsActivity
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeNumberPickerDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.isJsonObject
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.restart
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import splitties.init.appCtx

/**
 * 其它设置
 * L-E3 S2 → E3 7.11al 升级：内容区 Compose 化（ComposeSettingFragment，对齐 Archive）。
 * 保留主项目增强：showDiscovery/showRss、updateToVariant、searchThreadCount/updateCacheThreadCount/
 * rssParseConcurrency/imageLoadConcurrency、debugLogFloatingBall、sourceRecycleBinEnabled、debug_tools 调试工具入口、
 * 老用户线程数迁移一次性提示（migratedThreadCountJustDone）。
 * 注意：legacy 单线程数 threadCount 已迁移为搜索/更新缓存分离配置，不再展示。
 */
class OtherConfigFragment : ComposeSettingFragment() {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val packageManager = appCtx.packageManager
    private val componentName = ComponentName(
        appCtx,
        SharedReceiverActivity::class.java.name
    )
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }

    override val titleRes: Int = R.string.other_setting

    override fun onResume() {
        super.onResume()
        val enabled = isProcessTextEnabled()
        if (booleanSetting(PreferKey.processText, true) != enabled) {
            updateBooleanSetting(PreferKey.processText, enabled)
        }
        // 老用户线程数配置迁移后首次进入提示
        if (AppConfig.migratedThreadCountJustDone) {
            AppConfig.migratedThreadCountJustDone = false
            context?.let {
                Toast.makeText(it, R.string.migrated_thread_count_toast, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun buildPageSpec(): SettingPageSpec {
        return SettingPageSpec(
            titleRes = titleRes,
            sections = listOf(
                SettingSectionSpec(
                    items = listOf(
                        choice(
                            key = KEY_LANGUAGE,
                            title = getString(R.string.language),
                            entriesRes = R.array.language,
                            valuesRes = R.array.language_value,
                            defaultValue = "auto"
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.main_activity),
                    items = listOf(
                        switch(
                            key = PreferKey.autoRefresh,
                            title = getString(R.string.pt_auto_refresh),
                            summary = getString(R.string.ps_auto_refresh),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.onlyUpdateRead,
                            title = getString(R.string.only_update_read),
                            summary = getString(R.string.ps_only_update_read),
                            defaultValue = false,
                            visible = booleanSetting(PreferKey.autoRefresh, false)
                        ),
                        switch(
                            key = PreferKey.defaultToRead,
                            title = getString(R.string.pt_default_read),
                            summary = getString(R.string.ps_default_read),
                            defaultValue = false
                        ),
                        switch(
                            key = PreferKey.showDiscovery,
                            title = getString(R.string.show_discovery),
                            defaultValue = true
                        ),
                        switch(
                            key = PreferKey.showRss,
                            title = getString(R.string.show_rss),
                            defaultValue = true
                        ),
                        choice(
                            key = PreferKey.defaultHomePage,
                            title = getString(R.string.default_home_page),
                            entriesRes = R.array.default_home_page,
                            valuesRes = R.array.default_home_page_value,
                            defaultValue = "bookshelf"
                        )
                    )
                ),
                SettingSectionSpec(
                    title = getString(R.string.other_setting),
                    items = otherSettingItems()
                )
            )
        )
    }

    override fun onSettingPreferenceChanged(key: String) {
        when (key) {
            PreferKey.threadCount -> {
                postEvent(PreferKey.threadCount, "")
            }

            PreferKey.searchThreadCount -> {
                postEvent(PreferKey.searchThreadCount, "")
                context?.let {
                    Toast.makeText(it, R.string.search_thread_count_toast, Toast.LENGTH_SHORT).show()
                }
            }

            PreferKey.updateCacheThreadCount -> {
                postEvent(PreferKey.updateCacheThreadCount, "")
                context?.let {
                    Toast.makeText(it, R.string.update_cache_thread_count_toast, Toast.LENGTH_SHORT).show()
                }
            }

            PreferKey.webPort -> {
                if (WebService.isRun) {
                    WebService.stop(requireContext())
                    WebService.start(requireContext())
                }
            }

            PreferKey.epubReadEngine -> {
                postEvent(EventBus.UP_CONFIG, arrayListOf(13))
            }

            PreferKey.recordLog -> {
                AppConfig.recordLog = booleanSetting(PreferKey.recordLog, false)
                LogUtils.upLevel()
                LogUtils.logDeviceInfo()
                LiveEventBus.config().enableLogger(AppConfig.recordLog)
                AppFreezeMonitor.init(appCtx)
                DispatchersMonitor.init()
            }

            PreferKey.processText -> {
                setProcessTextEnable(booleanSetting(PreferKey.processText, true))
            }

            PreferKey.debugLogFloatingBall -> {
                val enabled = booleanSetting(PreferKey.debugLogFloatingBall, false)
                if (enabled) {
                    DebugFloatBallManager.onActivityResumed(requireActivity())
                } else {
                    DebugFloatBallManager.updateState(false)
                }
            }

            PreferKey.showDiscovery, PreferKey.showRss -> postEvent(EventBus.NOTIFY_MAIN, true)

            KEY_LANGUAGE -> view?.postDelayed(1000) {
                appCtx.restart()
            }

            PreferKey.highBrush -> {
                // AppConfig 全局监听已同步 useHighRefreshRate
            }
        }
    }

    private fun otherSettingItems(): List<SettingItemSpec> {
        return listOf(
            SettingActionSpec(
                key = PreferKey.contentSelectMenuConfig,
                title = getString(R.string.content_select_menu_config),
                summary = getString(R.string.content_select_menu_config_summary),
                onClick = {
                    ContentSelectMenuConfigDialog()
                        .show(parentFragmentManager, "contentSelectMenuConfig")
                }
            ),
            intChoice(
                key = PreferKey.fastScrollerTouchTargetDp,
                title = getString(R.string.fast_scroller_touch_target),
                entriesRes = R.array.fast_scroller_touch_target_entries,
                valuesRes = R.array.fast_scroller_touch_target_values,
                defaultValue = AppConfig.DEFAULT_FAST_SCROLLER_TOUCH_TARGET_DP
            ),
            choice(
                key = PreferKey.epubReadEngine,
                title = getString(R.string.epub_read_engine),
                summary = epubReadEngineSummary(),
                entriesRes = R.array.epub_read_engine_entries,
                valuesRes = R.array.epub_read_engine_values,
                defaultValue = "text"
            ),
            SettingActionSpec(
                key = KEY_LOCAL_PASSWORD,
                title = getString(R.string.set_local_password),
                summary = getString(R.string.set_local_password_summary),
                onClick = ::alertLocalPassword
            ),
            SettingActionSpec(
                key = PreferKey.userAgent,
                title = getString(R.string.user_agent),
                summary = userAgentValue(),
                onClick = ::showUserAgentDialog
            ),
            SettingActionSpec(
                key = PreferKey.customHosts,
                title = getString(R.string.custom_hosts),
                summary = getString(R.string.custom_hosts_summary),
                onClick = ::showCustomHostsDialog
            ),
            switch(
                key = PreferKey.webServiceWakeLock,
                title = getString(R.string.web_service_wake_lock),
                summary = getString(R.string.web_service_wake_lock_summary),
                defaultValue = false
            ),
            SettingActionSpec(
                key = PreferKey.defaultBookTreeUri,
                title = getString(R.string.book_tree_uri_t),
                summary = AppConfig.defaultBookTreeUri ?: getString(R.string.book_tree_uri_s),
                onClick = {
                    localBookTreeSelect.launch {
                        title = getString(R.string.select_book_folder)
                        mode = HandleFileContract.DIR_SYS
                    }
                }
            ),
            numberAction(
                key = PreferKey.sourceEditMaxLine,
                title = getString(R.string.source_edit_text_max_line),
                summary = getString(
                    R.string.source_edit_max_line_summary,
                    AppConfig.sourceEditMaxLine.toString()
                ),
                min = 10,
                max = Int.MAX_VALUE,
                value = AppConfig.sourceEditMaxLine,
                onSelected = { AppConfig.sourceEditMaxLine = it }
            ),
            SettingActionSpec(
                key = PreferKey.checkSource,
                title = getString(R.string.check_source_config),
                summary = CheckSource.summary,
                onClick = { showDialogFragment<CheckSourceConfig>() }
            ),
            SettingActionSpec(
                key = PreferKey.uploadRule,
                title = getString(R.string.direct_link_upload_rule),
                summary = getString(R.string.direct_link_upload_rule_summary),
                onClick = { showDialogFragment<DirectLinkUploadConfig>() }
            ),
            switch(
                key = PreferKey.cronet,
                title = "Cronet",
                summary = getString(R.string.pref_cronet_summary),
                defaultValue = false
            ),
            switch(
                key = PreferKey.antiAlias,
                title = getString(R.string.anti_alias),
                summary = getString(R.string.pref_anti_alias_summary),
                defaultValue = false
            ),
            switch(
                key = PreferKey.highBrush,
                title = getString(R.string.high_brush_title),
                summary = getString(R.string.high_brush_summary),
                defaultValue = true
            ),
            numberAction(
                key = PreferKey.bitmapCacheSize,
                title = getString(R.string.bitmap_cache_size),
                summary = getString(R.string.bitmap_cache_size_summary, AppConfig.bitmapCacheSize.toString()),
                min = 1,
                max = 1024,
                value = AppConfig.bitmapCacheSize,
                onSelected = {
                    AppConfig.bitmapCacheSize = it
                    ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize)
                }
            ),
            numberAction(
                key = PreferKey.imageRetainNum,
                title = getString(R.string.image_retain_number),
                summary = getString(R.string.image_retain_number_summary, AppConfig.imageRetainNum.toString()),
                min = 0,
                max = 999,
                value = AppConfig.imageRetainNum,
                onSelected = { AppConfig.imageRetainNum = it }
            ),
            numberAction(
                key = PreferKey.preDownloadNum,
                title = getString(R.string.pre_download),
                summary = getString(R.string.pre_download_s, AppConfig.preDownloadNum.toString()),
                min = 0,
                max = 9999,
                value = AppConfig.preDownloadNum,
                onSelected = { AppConfig.preDownloadNum = it }
            ),
            switch(
                key = PreferKey.replaceEnableDefault,
                title = getString(R.string.replace_enable_default_t),
                summary = getString(R.string.replace_enable_default_s),
                defaultValue = true
            ),
            switch(
                key = KEY_MEDIA_BUTTON_ON_EXIT,
                title = getString(R.string.media_button_on_exit_title),
                summary = getString(R.string.media_button_on_exit_summary),
                defaultValue = true
            ),
            switch(
                key = PreferKey.readAloudByMediaButton,
                title = getString(R.string.read_aloud_by_media_button_title),
                summary = getString(R.string.read_aloud_by_media_button_summary),
                defaultValue = false
            ),
            switch(
                key = PreferKey.ignoreAudioFocus,
                title = getString(R.string.ignore_audio_focus_title),
                summary = getString(R.string.ignore_audio_focus_summary),
                defaultValue = false
            ),
            switch(
                key = PreferKey.autoClearExpired,
                title = getString(R.string.auto_clear_expired),
                summary = getString(R.string.auto_clear_expired_summary),
                defaultValue = true
            ),
            switch(
                key = PreferKey.showAddToShelfAlert,
                title = getString(R.string.show_add_to_shelf_alert_title),
                summary = getString(R.string.show_add_to_shelf_alert_summary),
                defaultValue = true
            ),
            choice(
                key = PreferKey.updateToVariant,
                title = getString(R.string.update_to_variant_title),
                summary = getString(R.string.update_to_variant_summary),
                entriesRes = R.array.default_app_variant,
                valuesRes = R.array.default_app_variant_value,
                defaultValue = "default_version"
            ),
            switch(
                key = KEY_AUTO_UPDATE_VARIANT,
                title = getString(R.string.auto_update),
                summary = getString(R.string.auto_update_summary),
                defaultValue = true
            ),
            switch(
                key = PreferKey.showMangaUi,
                title = getString(R.string.show_manga_ui),
                defaultValue = true
            ),
            SettingActionSpec(
                key = PreferKey.videoSetting,
                title = getString(R.string.video_setting),
                summary = getString(R.string.video_setting_summary),
                // video-player-dual-layout R11：入口归一——改跳转全局设置页（原 SettingsDialog 弹框收编为播放页宿主）
                onClick = {
                    startActivity<ConfigActivity> {
                        putExtra("configTag", ConfigTag.VIDEO_PLAYER)
                    }
                }
            ),
            switch(
                key = PreferKey.autoRefreshMediaToc,
                title = getString(R.string.auto_refresh_media_toc),
                summary = getString(R.string.auto_refresh_media_toc_summary),
                defaultValue = true
            ),
            numberAction(
                key = PreferKey.webPort,
                title = getString(R.string.web_port_title),
                summary = getString(R.string.web_port_summary, AppConfig.webPort.toString()),
                min = 1024,
                max = 60000,
                value = AppConfig.webPort,
                onSelected = { AppConfig.webPort = it }
            ),
            SettingActionSpec(
                key = PreferKey.cleanCache,
                title = getString(R.string.clear_cache),
                summary = getString(R.string.clear_cache_summary),
                onClick = ::clearCache
            ),
            SettingActionSpec(
                key = PreferKey.clearWebViewData,
                title = getString(R.string.clear_webview_data),
                summary = getString(R.string.clear_webview_data_summary),
                onClick = ::clearWebViewData
            ),
            SettingActionSpec(
                key = PreferKey.shrinkDatabase,
                title = getString(R.string.shrink_database),
                summary = getString(R.string.shrink_database_summary),
                onClick = ::shrinkDatabase
            ),
            // 主项目增强：搜索 / 更新缓存分离线程数（legacy 单线程数已迁移并隐藏）
            numberAction(
                key = PreferKey.searchThreadCount,
                title = getString(R.string.search_thread_count_title),
                summary = getString(R.string.search_thread_count_summary, AppConfig.searchThreadCount.toString()),
                min = 1,
                max = 128,
                value = AppConfig.searchThreadCount,
                onSelected = { AppConfig.searchThreadCount = it }
            ),
            numberAction(
                key = PreferKey.updateCacheThreadCount,
                title = getString(R.string.update_cache_thread_count_title),
                summary = getString(
                    R.string.update_cache_thread_count_summary,
                    AppConfig.updateCacheThreadCount.toString()
                ),
                min = 1,
                max = 256,
                value = AppConfig.updateCacheThreadCount,
                onSelected = { AppConfig.updateCacheThreadCount = it }
            ),
            numberAction(
                key = PreferKey.rssParseConcurrency,
                title = getString(R.string.rss_parse_concurrency),
                summary = getString(R.string.rss_parse_concurrency_summary, AppConfig.rssParseConcurrency.toString()),
                min = 1,
                max = 20,
                value = AppConfig.rssParseConcurrency,
                onSelected = { AppConfig.rssParseConcurrency = it }
            ),
            numberAction(
                key = PreferKey.imageLoadConcurrency,
                title = getString(R.string.image_load_concurrency),
                summary = getString(
                    R.string.image_load_concurrency_summary,
                    AppConfig.imageLoadConcurrency.toString()
                ),
                min = 1,
                max = 20,
                value = AppConfig.imageLoadConcurrency,
                onSelected = { AppConfig.imageLoadConcurrency = it }
            ),
            switch(
                key = PreferKey.processText,
                title = getString(R.string.add_to_text_context_menu_t),
                summary = getString(R.string.add_to_text_context_menu_s),
                defaultValue = true
            ),
            switch(
                key = PreferKey.recordLog,
                title = getString(R.string.record_log),
                summary = getString(R.string.record_debug_log),
                defaultValue = false
            ),
            // 主项目增强：调试日志悬浮球
            switch(
                key = PreferKey.debugLogFloatingBall,
                title = getString(R.string.debug_log_floating_ball),
                summary = getString(R.string.debug_log_floating_ball_s),
                defaultValue = false
            ),
            // 主项目增强：B7 规则回收站开关
            switch(
                key = PreferKey.sourceRecycleBinEnabled,
                title = getString(R.string.source_recycle_bin_enabled),
                summary = getString(R.string.source_recycle_bin_enabled_s),
                defaultValue = false
            ),
            // 主项目增强：F-P9-3 调试工具迁移入口
            SettingActionSpec(
                key = KEY_DEBUG_TOOLS,
                title = getString(R.string.debug_tools),
                summary = getString(R.string.debug_tools_desc),
                onClick = { startActivity<DebugToolsActivity>() }
            ),
            switch(
                key = PreferKey.recordHeapDump,
                title = getString(R.string.record_heap_dump_t),
                summary = getString(R.string.record_heap_dump_s),
                defaultValue = false
            )
        )
    }

    private fun switch(
        key: String,
        title: String,
        summary: String? = null,
        defaultValue: Boolean,
        visible: Boolean = true
    ): SettingSwitchSpec {
        return SettingSwitchSpec(
            key = key,
            title = title,
            summary = summary,
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
        defaultValue: String,
        summary: String? = null
    ): SettingChoiceSpec {
        val options = choiceOptions(entriesRes, valuesRes)
        val selectedValue = stringSetting(key, defaultValue)
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = summary ?: choiceLabel(options, selectedValue),
            options = options,
            selectedValue = selectedValue,
            onSelected = { updateStringSetting(key, it) }
        )
    }

    private fun intChoice(
        key: String,
        title: String,
        entriesRes: Int,
        valuesRes: Int,
        defaultValue: Int,
        summary: String? = null
    ): SettingChoiceSpec {
        val options = choiceOptions(entriesRes, valuesRes)
        val selectedValue = intSetting(key, defaultValue).toString()
        return SettingChoiceSpec(
            key = key,
            title = title,
            summary = summary ?: choiceLabel(options, selectedValue),
            options = options,
            selectedValue = selectedValue,
            onSelected = { updateIntSetting(key, it.toIntOrNull() ?: defaultValue) }
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

    private fun numberAction(
        key: String,
        title: String,
        summary: String,
        min: Int,
        max: Int,
        value: Int,
        onSelected: (Int) -> Unit
    ): SettingActionSpec {
        return SettingActionSpec(
            key = key,
            title = title,
            summary = summary,
            onClick = {
                showComposeNumberPickerDialog(
                    title = title,
                    value = value,
                    minValue = min,
                    maxValue = max,
                    onValue = onSelected
                )
            }
        )
    }

    private fun epubReadEngineSummary(): String {
        val current = when (AppConfig.epubReadEngine) {
            "core" -> getString(R.string.epub_read_engine_core)
            else -> getString(R.string.epub_read_engine_text)
        }
        return "${getString(R.string.epub_read_engine_summary)}\n$current"
    }

    private fun showUserAgentDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.user_agent),
            hint = getString(R.string.user_agent),
            initialValue = userAgentValue(),
            onPositive = { userAgent ->
                if (userAgent.isNullOrBlank()) {
                    removePref(PreferKey.userAgent)
                } else {
                    putPrefString(PreferKey.userAgent, userAgent)
                }
            }
        )
    }

    private fun userAgentValue(): String {
        return stringSetting(PreferKey.userAgent, "")
            .ifBlank { DEFAULT_USER_AGENT }
    }

    private fun showCustomHostsDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.custom_hosts),
            hint = getString(R.string.json_format),
            initialValue = AppConfig.customHosts.orEmpty(),
            minLines = 8,
            maxLines = 14,
            onPositive = { customHosts ->
                if (customHosts.isJsonObject()) {
                    putPrefString(PreferKey.customHosts, customHosts)
                } else {
                    removePref(PreferKey.customHosts)
                }
            }
        )
    }

    private fun clearCache() {
        showComposeConfirmDialog(
            title = getString(R.string.clear_cache),
            message = getString(R.string.sure_del),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.no),
            onPositive = { viewModel.clearCache() }
        )
    }

    private fun shrinkDatabase() {
        showComposeConfirmDialog(
            title = getString(R.string.sure),
            message = getString(R.string.shrink_database),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.no),
            onPositive = { viewModel.shrinkDatabase() }
        )
    }

    private fun clearWebViewData() {
        showComposeConfirmDialog(
            title = getString(R.string.clear_webview_data),
            message = getString(R.string.sure_del),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.no),
            onPositive = { viewModel.clearWebViewData() }
        )
    }

    private fun isProcessTextEnabled(): Boolean {
        return packageManager.getComponentEnabledSetting(componentName) !=
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun setProcessTextEnable(enable: Boolean) {
        if (enable) {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
        } else {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun alertLocalPassword() {
        showComposeTextInputDialog(
            title = getString(R.string.set_local_password),
            hint = "password",
            message = getString(R.string.set_local_password_summary),
            onPositive = { LocalConfig.password = it }
        )
    }

    companion object {
        private const val KEY_LANGUAGE = "language"
        private const val KEY_LOCAL_PASSWORD = "localPassword"
        private const val KEY_MEDIA_BUTTON_ON_EXIT = "mediaButtonOnExit"
        private const val KEY_AUTO_UPDATE_VARIANT = "autoUpdateVariant"
        private const val KEY_DEBUG_TOOLS = "debug_tools"
        private val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" +
                BuildConfig.Cronet_Main_Version +
                " Safari/537.36"
    }
}
