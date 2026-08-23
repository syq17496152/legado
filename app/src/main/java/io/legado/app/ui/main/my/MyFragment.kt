package io.legado.app.ui.main.my

import android.content.SharedPreferences
import android.content.res.XmlResourceParser
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.FragmentMyConfigBinding
import io.legado.app.help.config.ThemeConfig
import io.legado.app.lib.theme.TopBarSearchStyle
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.autoTask.AutoTaskActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.AppearanceKitActivity
import io.legado.app.ui.config.RelaySettingsActivity
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.highlight.HighlightRuleActivity
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.rss.search.RssSearchActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.urlrecord.UrlRecordActivity
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.Schedule
import androidx.lifecycle.lifecycleScope
import io.legado.app.data.appDb
import io.legado.app.ui.widget.components.MetricItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.observeEventSticky
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.startActivity
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.LogUtils
import org.xmlpull.v1.XmlPullParser

class MyFragment() : BaseFragment(R.layout.fragment_my_config),
    MainFragmentInterface,
    SharedPreferences.OnSharedPreferenceChangeListener {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(FragmentMyConfigBinding::bind)
    private val settingsSearchView by lazy(LazyThreadSafetyMode.NONE) {
        binding.root.findViewById<SearchView>(R.id.search_view)
    }
    private val searchQueryState = mutableStateOf("")
    private val themeModeState = mutableStateOf("0")
    private val webServiceState = mutableStateOf(
        MyWebServiceUiState(checked = false, summary = "")
    )
    private val metricItemsState = mutableStateOf(emptyList<MetricItem>())
    private val sections by lazy(LazyThreadSafetyMode.NONE) { buildSections() }
    private val themeOptions by lazy(LazyThreadSafetyMode.NONE) { buildThemeOptions() }
    private val subSearchItems by lazy(LazyThreadSafetyMode.NONE) { buildSubSearchItems() }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        requireContext().putPrefBoolean(PreferKey.webService, WebService.isRun)
        initSearchView()
        applySearchBarStyle()
        installComposeContent()
        updateSettingsState()
    }

    override fun observeLiveBus() {
        observeEventSticky<String>(EventBus.WEB_SERVICE) {
            updateWebServiceState()
        }
    }

    override fun onResume() {
        super.onResume()
        requireContext().defaultSharedPreferences.registerOnSharedPreferenceChangeListener(this)
        updateSettingsState()
        // 用户反馈（2026-08-22）："我的"页头部四框统计信息（书架/书源/订阅源/累计阅读）隐藏
        // 代码保留（loadMetrics/buildMetricItems/MetricItem 完整存在），后期优化时恢复调用即可
        // loadMetrics()
    }

    override fun onPause() {
        requireContext().defaultSharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        menuInflater.inflate(R.menu.main_my, menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        when (item.itemId) {
            R.id.menu_help -> showHelp("appHelp")
        }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.webService -> {
                if (requireContext().getPrefBoolean(PreferKey.webService)) {
                    WebService.start(requireContext())
                } else {
                    WebService.stop(requireContext())
                }
                updateWebServiceState()
            }

            PreferKey.themeMode -> {
                themeModeState.value = requireContext().getPrefString(PreferKey.themeMode, "0") ?: "0"
            }

            "recordLog" -> LogUtils.upLevel()
        }
    }

    private fun installComposeContent() {
        binding.preFragment.removeAllViews()
        val composeView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                MySettingsScreen(
                    sections = sections,
                    subSearchItems = subSearchItems,
                    searchQuery = searchQueryState.value,
                    metrics = metricItemsState.value,
                    themeModeLabel = currentThemeModeLabel(),
                    webServiceState = webServiceState.value,
                    onThemeModeClick = ::showThemeModeActions,
                    onWebServiceCheckedChange = ::setWebServiceEnabled,
                    onWebServiceClick = ::handleWebServiceClick,
                    onRowClick = ::handleRowClick
                )
            }
        }
        binding.preFragment.addView(composeView)
    }

    private fun initSearchView() {
        settingsSearchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                applySearchQuery(query)
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                applySearchQuery(newText)
                return true
            }
        })
        settingsSearchView.setOnCloseListener {
            applySearchQuery("")
            false
        }
    }

    private fun applySearchQuery(query: String?) {
        searchQueryState.value = query?.trim().orEmpty()
    }

    private fun applySearchBarStyle() {
        settingsSearchView.applyUiBodyTypefaceDeep(requireContext().uiTypeface())
        TopBarSearchStyle.apply(settingsSearchView)
    }

    private fun updateSettingsState() {
        themeModeState.value = requireContext().getPrefString(PreferKey.themeMode, "0") ?: "0"
        updateWebServiceState()
    }

    private fun updateWebServiceState() {
        webServiceState.value = MyWebServiceUiState(
            checked = WebService.isRun,
            summary = if (WebService.isRun) {
                WebService.hostAddress
            } else {
                getString(R.string.web_service_desc)
            }
        )
    }

    private fun loadMetrics() {
        viewLifecycleOwner.lifecycleScope.launch {
            val context = requireContext()
            val metrics = withContext(Dispatchers.IO) {
                runCatching {
                    val shelfCount = appDb.bookDao.flowShelfAll().first().size
                    val sourceCount = appDb.bookSourceDao.allCount()
                    val rssCount = appDb.rssSourceDao.size
                    val totalReadMs = appDb.readRecordDao.allTime
                    buildMetricItems(context, shelfCount, sourceCount, rssCount, totalReadMs)
                }.getOrDefault(emptyList())
            }
            metricItemsState.value = metrics
        }
    }

    private fun buildMetricItems(
        context: Context,
        shelfCount: Int,
        sourceCount: Int,
        rssCount: Int,
        totalReadMs: Long
    ): List<MetricItem> {
        val readHours = if (totalReadMs > 0) {
            "%.1f".format(totalReadMs / 3600_000f).trimEnd('0').trimEnd('.')
        } else {
            "0"
        }
        return listOf(
            MetricItem(
                label = context.getString(R.string.my_metric_shelf),
                value = shelfCount.toString(),
                icon = Icons.Filled.MenuBook
            ),
            MetricItem(
                label = context.getString(R.string.my_metric_book_source),
                value = sourceCount.toString(),
                icon = Icons.Filled.Collections
            ),
            MetricItem(
                label = context.getString(R.string.my_metric_rss_source),
                value = rssCount.toString(),
                icon = Icons.Filled.Subscriptions
            ),
            MetricItem(
                label = context.getString(R.string.my_metric_read_time),
                value = "$readHours${context.getString(R.string.unit_hour)}",
                icon = Icons.Filled.Schedule
            )
        )
    }

    private fun setThemeMode(value: String) {
        requireContext().putPrefString(PreferKey.themeMode, value)
        themeModeState.value = value
        view?.post {
            ThemeConfig.applyDayNight(requireContext())
        }
    }

    private fun currentThemeModeLabel(): String {
        return themeOptions.firstOrNull { it.value == themeModeState.value }?.label
            ?: themeOptions.firstOrNull()?.label
            ?: getString(R.string.theme_mode)
    }

    private fun showThemeModeActions() {
        showDialogFragment(
            ComposeActionListDialog.create(
                title = getString(R.string.theme_mode),
                labels = themeOptions.map { option ->
                    if (option.value == themeModeState.value) {
                        "${option.label}  ✓"
                    } else {
                        option.label
                    }
                },
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    themeOptions.getOrNull(index)?.value?.let(::setThemeMode)
                }
            )
        )
    }

    private fun setWebServiceEnabled(enabled: Boolean) {
        requireContext().putPrefBoolean(PreferKey.webService, enabled)
        if (enabled) {
            WebService.start(requireContext())
        } else {
            WebService.stop(requireContext())
        }
        updateWebServiceState()
    }

    private fun handleWebServiceClick() {
        if (WebService.isRun) {
            showWebServiceActions()
        } else {
            setWebServiceEnabled(true)
        }
    }

    private fun showWebServiceActions() {
        val url = WebService.hostAddress.takeIf { WebService.isRun } ?: return
        showDialogFragment(
            ComposeActionListDialog.create(
                title = getString(R.string.web_service),
                labels = listOf(
                    getString(R.string.copy_text),
                    getString(R.string.open_in_browser)
                ),
                descriptions = listOf(url, url),
                negativeText = getString(R.string.cancel),
                onSelected = { index ->
                    when (index) {
                        0 -> requireContext().sendToClip(url)
                        1 -> requireContext().openUrl(url)
                    }
                }
            )
        )
    }

    private fun handleRowClick(key: String, searchTarget: MySettingsSubSearchItem?) {
        if (searchTarget != null) {
            startActivity<ConfigActivity> {
                putExtra("configTag", searchTarget.ownerConfigTag)
                putExtra("targetKey", searchTarget.key)
            }
            return
        }
        when (key) {
            "bookSourceManage" -> startActivity<BookSourceActivity>()
            "rssSourceManage" -> startActivity<RssSourceActivity>()
            "replaceManage" -> startActivity<ReplaceRuleActivity>()
            "dictRuleManage" -> startActivity<DictRuleActivity>()
            "txtTocRuleManage" -> startActivity<TxtTocRuleActivity>()
            "bookmark" -> startActivity<AllBookmarkActivity>()
            "autoTask" -> startActivity<AutoTaskActivity>()
            "preciseManage" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.PRECISE_MANAGE)
            }
            "setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.OTHER_CONFIG)
            }

            "web_dav_setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.BACKUP_CONFIG)
            }

            "publicWebRelay" -> startActivity<RelaySettingsActivity>()

            "cacheManage" -> startActivity<CacheManageActivity>()
            "theme_setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.THEME_CONFIG)
            }

            "appearanceKit" -> startActivity<AppearanceKitActivity>()

            "ai_setting" -> startActivity<ConfigActivity> {
                putExtra("configTag", ConfigTag.AI_CONFIG)
            }

            "fileManage" -> startActivity<FileManageActivity>()
            "readRecord" -> startActivity<ReadRecordActivity>()
            "featureBooks" -> startActivity<MyFeatureBooksActivity>()
            "highlightRule" -> startActivity<HighlightRuleActivity>()
            "urlRecord" -> startActivity<UrlRecordActivity>()
            "rssSearch" -> RssSearchActivity.start(requireContext(), null)
            "about" -> startActivity<AboutActivity>()
            "exit" -> activity?.finish()
        }
    }

    private fun buildSections(): List<MySettingsSectionModel> {
        return listOf(
            MySettingsSectionModel(
                title = getString(R.string.config_category_content),
                rows = listOf(
                    actionRow("bookSourceManage", R.string.book_source_manage, R.string.book_source_manage_desc),
                    actionRow("rssSourceManage", R.string.rss_source_manage, R.string.rss_source_manage_summary),
                    actionRow("txtTocRuleManage", R.string.txt_toc_rule, R.string.config_txt_toc_rule),
                    actionRow("replaceManage", R.string.replace_purify, R.string.replace_purify_desc),
                    actionRow("dictRuleManage", R.string.dict_rule, R.string.config_dict_rule)
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_appearance),
                rows = listOf(
                    MySettingsRowModel(
                        key = PreferKey.themeMode,
                        title = getString(R.string.theme_mode),
                        summary = getString(R.string.theme_mode_desc),
                        kind = MySettingsRowKind.ThemeMode
                    ),
                    MySettingsRowModel(
                        key = "appearanceKit",
                        title = getString(R.string.appearance_kit_manage),
                        summary = getString(R.string.appearance_kit_summary)
                    ),
                    actionRow("theme_setting", R.string.theme_setting, R.string.theme_setting_s),
                    actionRow("ai_setting", R.string.ai_setting, R.string.ai_setting_summary)
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_sync),
                rows = listOf(
                    actionRow("web_dav_setting", R.string.backup_restore, R.string.web_dav_set_import_old),
                    actionRow("cacheManage", R.string.cache_manage_title, R.string.cache_manage_summary),
                    actionRow(
                        "publicWebRelay",
                        R.string.public_web_relay,
                        R.string.public_web_relay_summary
                    ),
                    MySettingsRowModel(
                        key = PreferKey.webService,
                        title = getString(R.string.web_service),
                        summary = getString(R.string.web_service_desc),
                        kind = MySettingsRowKind.WebService
                    )
                )
            ),
            MySettingsSectionModel(
                title = getString(R.string.config_category_tools),
                rows = listOf(
                    actionRow("setting", R.string.other_setting, R.string.other_setting_s),
                    actionRow("featureBooks", R.string.my_feature_books, R.string.my_feature_books_desc),
                    actionRow("autoTask", R.string.auto_task_manage, R.string.auto_task_manage_desc),
                    actionRow("highlightRule", R.string.highlight_rule_manage, R.string.highlight_rule_manage_desc),
                    actionRow("urlRecord", R.string.url_record, R.string.url_record_summary),
                    actionRow("rssSearch", R.string.rss_search, R.string.rss_search_summary),
                    actionRow("preciseManage", R.string.precise_manage, R.string.precise_manage_summary),
                    actionRow("bookmark", R.string.bookmark, R.string.all_bookmark),
                    actionRow("readRecord", R.string.read_record, R.string.read_record_summary),
                    actionRow("fileManage", R.string.file_manage, R.string.file_manage_summary),
                    actionRow("about", R.string.about, null),
                    actionRow("exit", R.string.exit, null)
                )
            )
        )
    }

    private fun actionRow(
        key: String,
        titleRes: Int,
        summaryRes: Int?,
        danger: Boolean = false
    ): MySettingsRowModel {
        return MySettingsRowModel(
            key = key,
            title = getString(titleRes),
            summary = summaryRes?.let(::getString),
            danger = danger
        )
    }

    private fun buildThemeOptions(): List<MySettingsThemeOption> {
        val labels = resources.getStringArray(R.array.theme_mode)
        val values = resources.getStringArray(R.array.theme_mode_v)
        return labels.mapIndexed { index, label ->
            MySettingsThemeOption(
                value = values.getOrElse(index) { index.toString() },
                label = label
            )
        }.ifEmpty {
            listOf(MySettingsThemeOption("0", getString(R.string.theme_mode)))
        }
    }

    private fun buildSubSearchItems(): List<MySettingsSubSearchItem> {
        return listOf(
            Triple("theme_setting", R.xml.pref_config_theme, ConfigTag.THEME_CONFIG),
            Triple("theme_setting", R.xml.pref_config_cover, ConfigTag.COVER_CONFIG),
            Triple("theme_setting", R.xml.pref_config_welcome, ConfigTag.WELCOME_CONFIG),
            Triple("web_dav_setting", R.xml.pref_config_backup, ConfigTag.BACKUP_CONFIG),
            Triple("ai_setting", R.xml.pref_config_ai, ConfigTag.AI_CONFIG),
            Triple("setting", R.xml.pref_config_other, ConfigTag.OTHER_CONFIG),
            Triple(
                "theme_setting",
                R.xml.pref_config_discovery_subscription,
                ConfigTag.DISCOVERY_SUBSCRIPTION_CONFIG
            )
        ).flatMap { (ownerKey, xmlRes, ownerConfigTag) ->
            buildPreferenceXmlSearchItems(ownerKey, xmlRes, ownerConfigTag)
        } + listOf(
            MySettingsSubSearchItem(
                ownerKey = "theme_setting",
                title = getString(R.string.welcome_style),
                summary = getString(R.string.welcome_style_summary),
                key = PreferKey.welcomeShowTime,
                ownerConfigTag = ConfigTag.WELCOME_CONFIG
            )
        )
    }

    private fun buildPreferenceXmlSearchItems(
        ownerKey: String,
        xmlRes: Int,
        ownerConfigTag: String
    ): List<MySettingsSubSearchItem> {
        val items = ArrayList<MySettingsSubSearchItem>()
        val parser: XmlResourceParser = resources.getXml(xmlRes)
        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG) {
                    val title = collectPreferenceAttr(parser, "title").orEmpty()
                    val key = collectPreferenceAttr(parser, "key").orEmpty()
                    if (title.isNotBlank() && key.isNotBlank()) {
                        items.add(
                            MySettingsSubSearchItem(
                                ownerKey = ownerKey,
                                title = title,
                                summary = collectPreferenceAttr(parser, "summary").orEmpty(),
                                key = key.removePrefix("search_jump_"),
                                ownerConfigTag = ownerConfigTag
                            )
                        )
                    }
                }
                eventType = parser.next()
            }
        } finally {
            parser.close()
        }
        return items
    }

    private fun collectPreferenceAttr(parser: XmlResourceParser, attrName: String): String? {
        val namespace = "http://schemas.android.com/apk/res/android"
        val attrValue = parser.getAttributeValue(namespace, attrName)?.trim().orEmpty()
        if (attrValue.isBlank()) return null
        val attrRes = parser.getAttributeResourceValue(namespace, attrName, 0)
        return if (attrRes != 0) {
            runCatching {
                getString(attrRes)
            }.getOrNull()?.trim().orEmpty().takeIf { it.isNotBlank() }
        } else {
            attrValue.removePrefix("@").takeIf { it.isNotBlank() }
        }
    }
}