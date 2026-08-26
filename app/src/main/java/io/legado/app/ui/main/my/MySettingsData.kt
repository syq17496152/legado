package io.legado.app.ui.main.my

import android.app.Activity
import android.content.Context
import android.content.res.XmlResourceParser
import androidx.appcompat.app.AppCompatActivity
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ThemeConfig
import io.legado.app.service.WebService
import io.legado.app.ui.about.AboutActivity
import io.legado.app.ui.about.ReadRecordActivity
import io.legado.app.ui.autoTask.AutoTaskActivity
import io.legado.app.ui.book.bookmark.AllBookmarkActivity
import io.legado.app.ui.book.cache.CacheManageActivity
import io.legado.app.ui.book.source.manage.BookSourceActivity
import io.legado.app.ui.book.toc.rule.TxtTocRuleActivity
import io.legado.app.ui.config.AppearanceKitActivity
import io.legado.app.ui.config.ConfigActivity
import io.legado.app.ui.config.ConfigTag
import io.legado.app.ui.config.RelaySettingsActivity
import io.legado.app.ui.dict.rule.DictRuleActivity
import io.legado.app.ui.file.FileManageActivity
import io.legado.app.ui.highlight.HighlightRuleActivity
import io.legado.app.ui.replace.ReplaceRuleActivity
import io.legado.app.ui.rss.search.RssSearchActivity
import io.legado.app.ui.rss.source.manage.RssSourceActivity
import io.legado.app.ui.urlrecord.UrlRecordActivity
import io.legado.app.ui.widget.compose.ComposeActionListDialog
import io.legado.app.utils.openUrl
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.sendToClip
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import org.xmlpull.v1.XmlPullParser

/**
 * 我的页设置数据构建 + 行点击路由 + 主题模式/Web 服务交互。
 *
 * 供 MyFragment 与 SettingsSearchActivity 两宿主共用，防止行为漂移（header-search-unify AD-03/AD-05）。
 * 逻辑平迁自 MyFragment 原私有方法，仅将接收者由 Fragment 替换为 Context/Activity：
 * - getString() → context.getString()
 * - view?.post  → window?.decorView?.post
 * - showDialogFragment 走 AppCompatActivity 扩展（io.legado.app.utils）
 * - "exit" 行由 activity?.finish()（退出宿主 Activity）改为 finish()（退出搜索页自身）
 */

// ===================== 数据构建（纯函数） =====================

internal fun buildSettingsSections(context: Context): List<MySettingsSectionModel> {
    fun actionRow(
        key: String,
        titleRes: Int,
        summaryRes: Int?,
        danger: Boolean = false
    ): MySettingsRowModel {
        return MySettingsRowModel(
            key = key,
            title = context.getString(titleRes),
            summary = summaryRes?.let(context::getString),
            danger = danger
        )
    }

    return listOf(
        MySettingsSectionModel(
            title = context.getString(R.string.config_category_content),
            rows = listOf(
                actionRow("bookSourceManage", R.string.book_source_manage, R.string.book_source_manage_desc),
                actionRow("rssSourceManage", R.string.rss_source_manage, R.string.rss_source_manage_summary),
                actionRow("txtTocRuleManage", R.string.txt_toc_rule, R.string.config_txt_toc_rule),
                actionRow("replaceManage", R.string.replace_purify, R.string.replace_purify_desc),
                actionRow("dictRuleManage", R.string.dict_rule, R.string.config_dict_rule)
            )
        ),
        MySettingsSectionModel(
            title = context.getString(R.string.config_category_appearance),
            rows = listOf(
                MySettingsRowModel(
                    key = PreferKey.themeMode,
                    title = context.getString(R.string.theme_mode),
                    summary = context.getString(R.string.theme_mode_desc),
                    kind = MySettingsRowKind.ThemeMode
                ),
                MySettingsRowModel(
                    key = "appearanceKit",
                    title = context.getString(R.string.appearance_kit_manage),
                    summary = context.getString(R.string.appearance_kit_summary)
                ),
                actionRow("theme_setting", R.string.theme_setting, R.string.theme_setting_s),
                actionRow("ai_setting", R.string.ai_setting, R.string.ai_setting_summary)
            )
        ),
        MySettingsSectionModel(
            title = context.getString(R.string.config_category_sync),
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
                    title = context.getString(R.string.web_service),
                    summary = context.getString(R.string.web_service_desc),
                    kind = MySettingsRowKind.WebService
                )
            )
        ),
        MySettingsSectionModel(
            title = context.getString(R.string.config_category_tools),
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
                // bugfix ⑥: 移除重复"文件管理"入口（精准管理 aggregated 文件管理，避免两个入口）
                actionRow("about", R.string.about, null),
                actionRow("exit", R.string.exit, null)
            )
        )
    )
}

internal fun buildSettingsThemeOptions(context: Context): List<MySettingsThemeOption> {
    val labels = context.resources.getStringArray(R.array.theme_mode)
    val values = context.resources.getStringArray(R.array.theme_mode_v)
    return labels.mapIndexed { index, label ->
        MySettingsThemeOption(
            value = values.getOrElse(index) { index.toString() },
            label = label
        )
    }.ifEmpty {
        listOf(MySettingsThemeOption("0", context.getString(R.string.theme_mode)))
    }
}

internal fun buildSettingsSubSearchItems(context: Context): List<MySettingsSubSearchItem> {
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
        buildPreferenceXmlSearchItems(context, ownerKey, xmlRes, ownerConfigTag)
    } + listOf(
        MySettingsSubSearchItem(
            ownerKey = "theme_setting",
            title = context.getString(R.string.welcome_style),
            summary = context.getString(R.string.welcome_style_summary),
            key = PreferKey.welcomeShowTime,
            ownerConfigTag = ConfigTag.WELCOME_CONFIG
        )
    )
}

private fun buildPreferenceXmlSearchItems(
    context: Context,
    ownerKey: String,
    xmlRes: Int,
    ownerConfigTag: String
): List<MySettingsSubSearchItem> {
    val items = ArrayList<MySettingsSubSearchItem>()
    val parser: XmlResourceParser = context.resources.getXml(xmlRes)
    try {
        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val title = collectPreferenceAttr(context, parser, "title").orEmpty()
                val key = collectPreferenceAttr(context, parser, "key").orEmpty()
                if (title.isNotBlank() && key.isNotBlank()) {
                    items.add(
                        MySettingsSubSearchItem(
                            ownerKey = ownerKey,
                            title = title,
                            summary = collectPreferenceAttr(context, parser, "summary").orEmpty(),
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

private fun collectPreferenceAttr(
    context: Context,
    parser: XmlResourceParser,
    attrName: String
): String? {
    val namespace = "http://schemas.android.com/apk/res/android"
    val attrValue = parser.getAttributeValue(namespace, attrName)?.trim().orEmpty()
    if (attrValue.isBlank()) return null
    val attrRes = parser.getAttributeResourceValue(namespace, attrName, 0)
    return if (attrRes != 0) {
        runCatching {
            context.getString(attrRes)
        }.getOrNull()?.trim().orEmpty().takeIf { it.isNotBlank() }
    } else {
        attrValue.removePrefix("@").takeIf { it.isNotBlank() }
    }
}

/** Web 服务当前 UI 状态（WebService.isRun + 地址/描述） */
internal fun Context.webServiceUiState(): MyWebServiceUiState {
    return MyWebServiceUiState(
        checked = WebService.isRun,
        summary = if (WebService.isRun) {
            WebService.hostAddress
        } else {
            getString(R.string.web_service_desc)
        }
    )
}

// ===================== 行点击路由（Activity 扩展） =====================

internal fun Activity.handleSettingsRowClick(key: String, searchTarget: MySettingsSubSearchItem?) {
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
        "rssSearch" -> RssSearchActivity.start(this, null)
        "about" -> startActivity<AboutActivity>()
        "exit" -> finish()
    }
}

// ===================== 主题模式（AppCompatActivity 扩展） =====================

internal fun currentThemeModeLabel(
    context: Context,
    themeOptions: List<MySettingsThemeOption>,
    currentValue: String
): String {
    return themeOptions.firstOrNull { it.value == currentValue }?.label
        ?: themeOptions.firstOrNull()?.label
        ?: context.getString(R.string.theme_mode)
}

internal fun AppCompatActivity.showThemeModeActions(
    themeOptions: List<MySettingsThemeOption>,
    currentValue: String,
    onModeSelected: (String) -> Unit
) {
    showDialogFragment(
        ComposeActionListDialog.create(
            title = getString(R.string.theme_mode),
            labels = themeOptions.map { option ->
                if (option.value == currentValue) {
                    "${option.label}  ✓"
                } else {
                    option.label
                }
            },
            negativeText = getString(R.string.cancel),
            onSelected = { index ->
                themeOptions.getOrNull(index)?.value?.let(onModeSelected)
            }
        )
    )
}

fun AppCompatActivity.applyThemeMode(value: String, onStateChanged: (String) -> Unit) {
    putPrefString(PreferKey.themeMode, value)
    onStateChanged(value)
    window?.decorView?.post {
        ThemeConfig.applyDayNight(this)
    }
}

// ===================== Web 服务（AppCompatActivity 扩展） =====================

internal fun AppCompatActivity.setWebServiceEnabled(
    enabled: Boolean,
    onStateChanged: (MyWebServiceUiState) -> Unit
) {
    putPrefBoolean(PreferKey.webService, enabled)
    if (enabled) {
        WebService.start(this)
    } else {
        WebService.stop(this)
    }
    onStateChanged(webServiceUiState())
}

internal fun AppCompatActivity.handleWebServiceClick(onStateChanged: (MyWebServiceUiState) -> Unit) {
    if (WebService.isRun) {
        showWebServiceOptions()
    } else {
        setWebServiceEnabled(true, onStateChanged)
    }
}

fun AppCompatActivity.showWebServiceOptions() {
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
                    0 -> sendToClip(url)
                    1 -> openUrl(url)
                }
            }
        )
    )
}