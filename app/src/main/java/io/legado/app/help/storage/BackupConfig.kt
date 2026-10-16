package io.legado.app.help.storage

import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx

/**
 * 备份配置
 */
@Suppress("ConstPropertyName")
object BackupConfig {

    private val ignoreConfigPath = FileUtils.getPath(appCtx.filesDir, "restoreIgnore.json")
    val ignoreConfig: HashMap<String, Boolean> by lazy {
        val file = FileUtils.createFileIfNotExist(ignoreConfigPath)
        val json = file.readText()
        GSON.fromJsonObject<HashMap<String, Boolean>>(json).getOrNull() ?: hashMapOf()
    }

    private const val readConfigKey = "readConfig"
    private const val themeConfigKey = "themeConfig"
    private const val coverConfigKey = "coverConfig"
    private const val localBookKey = "localBook"

    //配置忽略key
    val ignoreKeys = arrayOf(
        readConfigKey,
        PreferKey.themeMode,
        themeConfigKey,
        coverConfigKey,
        PreferKey.bookshelfLayout,
        PreferKey.showRss,
        PreferKey.threadCount,
        PreferKey.searchThreadCount,
        PreferKey.updateCacheThreadCount,
        localBookKey
    )

    //配置忽略标题
    val ignoreTitle = arrayOf(
        appCtx.getString(R.string.read_config),
        appCtx.getString(R.string.theme_mode),
        appCtx.getString(R.string.theme_config),
        appCtx.getString(R.string.cover_config),
        appCtx.getString(R.string.bookshelf_layout),
        appCtx.getString(R.string.show_rss),
        appCtx.getString(R.string.thread_count),
        appCtx.getString(R.string.search_thread_count),
        appCtx.getString(R.string.update_cache_thread_count),
        appCtx.getString(R.string.local_book)
    )

    //自动忽略keys
    private val ignorePrefKeys = arrayOf(
        PreferKey.defaultCover,
        PreferKey.defaultCoverDark,
        PreferKey.backupPath,
        PreferKey.defaultBookTreeUri,
        PreferKey.webDavDeviceName,
        PreferKey.bitmapCacheSize,
        PreferKey.webServiceWakeLock,
        PreferKey.readAloudWakeLock,
        PreferKey.audioPlayWakeLock,
        // 已删除的旧键（2026-09-27 底栏悬浮搜索按钮裁决）：旧备份里的该键不得写回
        "floatingBottomBarHideSearch",
        // 已删除的旧键（2026-10-15 死件普查：两键全仓零读取，连字面量通道亦无）
        // ① 下载管理已隐藏任务 id（该能力已由 DB 承载）② 覆盖安装一次性内置规则补齐标志（已完成使命）
        "downloadDismissedIds",
        "highlightRuleBuiltinMergedV2",
        // NEW-52（2026-10-16）：「设置项形同虚设」审计（audit_prefer_key_consumption）命中 ——
        // 两项功能已迁移（清除缓存并入缓存管理 / WebView 清理为缓存管理第 4 分项），
        // 旧处理逻辑与 XML 条目均已删 ⇒ 键声明退役，此处防旧备份写回（「删键三处」约定）。
        "cleanCache",
        "clearWebViewData",
        // NEW-87（2026-10-16）：「回复栏跟随圆角」幽灵设置 —— 键有存储有引用但开关无可感效果
        //（`UiCorner.replyRadius` 零调用、仓内无「回复栏」视图，上游 Archive 亦零消费）⇒ 存储链退役，
        // 此处防旧备份写回（「删键三处」约定）。
        "uiCornerReplyFollow",
        "uiCornerReplyFollowNight"
    )

    //阅读配置
    private val readPrefKeys = arrayOf(
        PreferKey.readStyleSelect,
        PreferKey.comicStyleSelect,
        PreferKey.shareLayout,
        PreferKey.hideStatusBar,
        PreferKey.hideNavigationBar,
        PreferKey.autoReadSpeed,
        PreferKey.clickActionTL,
        PreferKey.clickActionTC,
        PreferKey.clickActionTR,
        PreferKey.clickActionML,
        PreferKey.clickActionMC,
        PreferKey.clickActionMR,
        PreferKey.clickActionBL,
        PreferKey.clickActionBC,
        PreferKey.clickActionBR
    )

    private val themePrefKeys = arrayOf(
        PreferKey.cPrimary,
        PreferKey.cAccent,
        PreferKey.cBackground,
        PreferKey.cBBackground,
        PreferKey.bgImage,
        PreferKey.bgImageBlurring,
        PreferKey.tNavBar,
        PreferKey.cNPrimary,
        PreferKey.cNAccent,
        PreferKey.cNBackground,
        PreferKey.cNBBackground,
        PreferKey.bgImageN,
        PreferKey.bgImageNBlurring,
        PreferKey.tNavBarN
    )

    private val coverPrefKeys = arrayOf(
        PreferKey.useDefaultCover,
        PreferKey.loadCoverOnlyWifi,
        PreferKey.coverShowName,
        PreferKey.coverShowAuthor,
        PreferKey.coverShowNameN,
        PreferKey.coverShowAuthorN
    )

    fun keyIsNotIgnore(key: String): Boolean {
        return when {
            ignorePrefKeys.contains(key) -> false
            ignoreReadConfig && readPrefKeys.contains(key) -> false
            ignoreThemeConfig && themePrefKeys.contains(key) -> false
            ignoreCoverConfig && coverPrefKeys.contains(key) -> false
            PreferKey.themeMode == key && ignoreThemeMode -> false
            PreferKey.bookshelfLayout == key && ignoreBookshelfLayout -> false
            PreferKey.showRss == key && ignoreShowRss -> false
            PreferKey.threadCount == key && ignoreThreadCount -> false
            PreferKey.searchThreadCount == key && ignoreSearchThreadCount -> false
            PreferKey.updateCacheThreadCount == key && ignoreUpdateCacheThreadCount -> false
            else -> true
        }
    }

    val ignoreReadConfig: Boolean
        get() = ignoreConfig[readConfigKey] == true
    private val ignoreThemeMode: Boolean
        get() = ignoreConfig[PreferKey.themeMode] == true
    private val ignoreThemeConfig: Boolean
        get() = ignoreConfig[themeConfigKey] == true
    private val ignoreCoverConfig: Boolean
        get() = ignoreConfig[coverConfigKey] == true
    private val ignoreBookshelfLayout: Boolean
        get() = ignoreConfig[PreferKey.bookshelfLayout] == true
    private val ignoreShowRss: Boolean
        get() = ignoreConfig[PreferKey.showRss] == true
    private val ignoreThreadCount: Boolean
        get() = ignoreConfig[PreferKey.threadCount] == true
    private val ignoreSearchThreadCount: Boolean
        get() = ignoreConfig[PreferKey.searchThreadCount] == true
    private val ignoreUpdateCacheThreadCount: Boolean
        get() = ignoreConfig[PreferKey.updateCacheThreadCount] == true
    val ignoreLocalBook: Boolean
        get() = ignoreConfig[localBookKey] == true

    fun saveIgnoreConfig() {
        val json = GSON.toJson(ignoreConfig)
        FileUtils.createFileIfNotExist(ignoreConfigPath).writeText(json)
    }

}