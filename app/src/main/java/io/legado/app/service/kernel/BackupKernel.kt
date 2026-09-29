package io.legado.app.service.kernel

import androidx.annotation.Keep
import androidx.core.content.edit
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupAES
import io.legado.app.help.storage.BackupConfig
import io.legado.app.help.storage.BackupRestoreLock
import io.legado.app.help.storage.Restore
import io.legado.app.help.storage.BookCacheSelectorConfig
import io.legado.app.model.BookCover
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.io.File

/**
 * 备份域内核（web-mcp-productization 一期 · §2.3.3）。
 *
 * 从 `api/controller/BackupController.kt`（529 行）搬出**全部业务**，Controller 只留「把产物包成
 * HTTP 下载响应」。解耦要点：
 * 1. **返回 `File` 而非 `NanoHTTPD.Response`** —— 让将来的 MCP 通道也能触发备份而不依赖 HTTP 响应；
 * 2. 本层**零** `ReturnData` / 零 `web.*` 依赖（门禁 G-22）；
 * 3. 超时由 `withTimeout` 表达（原实现是「120s 门闩 + 后台协程」：超时后**响应已回错但备份仍在后台跑**，
 *    且 `BackupRestoreLock` 仍被那个孤儿协程持有 ⇒ 后续备份/恢复被阻塞。改为 `withTimeout` 后超时会
 *    **取消**该次备份并随 `withLock` 正常释放共享锁）；
 * 4. 临时 zip **每次唯一命名**（`web_backup_{时间戳}.zip`）⇒ 即使两次导出并发，后一次也不会覆盖
 *    正在被 HTTP 层流式读取的前一个文件；调用方读完必须删除（见 `BackupController` 的流包装）。
 *
 * 说明：原实现另有 `cachedBackupZip` 字段，写入后**只在同一个函数里读一次**（无跨调用消费者）⇒
 * 属冗余状态，搬迁时不再保留（行为无差异）。概览缓存 `cachedBackupOverview` 有真实消费者
 * （`/backupPreview` 免重算），保留。
 */
object BackupKernel {

    /** 单次备份墙钟上限（沿用原 120s 门闩数值，语义由「放弃等待」变为「取消备份」）。 */
    const val BACKUP_TIMEOUT_MS = 120_000L

    /** 概览条目（Gson 序列化给网页端 ⇒ 必须 `@Keep`，否则 R8 改名让网页端字段全读不到）。 */
    @Keep
    data class BackupItemInfo(
        val fileName: String,
        val displayName: String,
        val description: String,
        val count: Int,
        val size: Long
    )

    /** 备份概览（Gson 序列化；含 `List<BackupItemInfo>` 集合字段 ⇒ 必须 `@Keep`）。 */
    @Keep
    data class BackupOverview(
        val fileName: String,
        val totalSize: Long,
        val createTime: Long,
        val items: List<BackupItemInfo>
    )

    private data class BackupItemDef(
        val fileName: String,
        val displayName: String,
        val description: String,
        val counter: () -> Int
    )

    private data class ConfigItemDef(
        val fileName: String,
        val displayName: String,
        val description: String
    )

    /** Web 备份专用临时目录 */
    private val webBackupPath: String by lazy {
        appCtx.filesDir.getFile("web_backup").createFolderIfNotExist().absolutePath
    }

    /** 缓存最近一次备份的概览信息（`/backupPreview` 用） */
    @Volatile
    private var cachedBackupOverview: BackupOverview? = null

    /**
     * 执行备份并返回打包好的 ZIP 文件；调用方负责在用完后删除该文件。
     *
     * @throws kotlinx.coroutines.TimeoutCancellationException 超过 [BACKUP_TIMEOUT_MS]
     *   （由 `withTimeout` 抛出，见 `BackupController` 对超时的错误话术映射）。
     */
    suspend fun backup(): File = withTimeout(BACKUP_TIMEOUT_MS) {
        val file = BackupRestoreLock.withStorageLock { buildBackupZip() }
        cachedBackupOverview = generateBackupOverview()
        file
    }

    /** 读取概览：优先用最近一次备份的缓存，未备份过则现算。 */
    suspend fun preview(): BackupOverview =
        cachedBackupOverview ?: generateBackupOverview().also { cachedBackupOverview = it }

    // ============================================================ 二期 §2.16：恢复与备份配置

    /**
     * `backup_restore`：恢复备份（**默认 dryRun**）。
     *
     * 恢复是**破坏性**操作（覆盖当前数据），且 spec §7.3 要求它走**端侧确认闸门** ⇒ 本方法
     * 默认只做**预检**（返回"将恢复的概览"），不落库。真正恢复需 `dryRun=false` 且提供
     * `cloudName`（云备份名）或 `path`（本地备份文件路径），二者按优先级取云。
     *
     * 与既有实现的关系：恢复体分别委派 `AppCloudStorage.restore(name)` 与
     * `Restore.restoreLocked(path)`（后者是 `Restore` 唯一公开的按路径恢复入口；
     * 私有的 `restore(path)` 不可见）。全程持 `BackupRestoreLock` 共享锁 + 120s 墙钟上限。
     */
    suspend fun restore(
        dryRun: Boolean = true,
        cloudName: String? = null,
        path: String? = null,
    ): Map<String, Any?> {
        val overview = preview()
        if (dryRun || (cloudName.isNullOrBlank() && path.isNullOrBlank())) {
            return mapOf(
                "dryRun" to true,
                "fileName" to overview.fileName,
                "totalSize" to overview.totalSize,
                "itemCount" to overview.items.size,
                "items" to overview.items,
                "note" to "干跑：未执行恢复。真正恢复需 dryRun=false 且给出 cloudName 或 path，并经端侧确认。",
            )
        }
        val source = if (!cloudName.isNullOrBlank()) "cloud:$cloudName" else "file:$path"
        withTimeout(BACKUP_TIMEOUT_MS) {
            BackupRestoreLock.withStorageLock {
                if (!cloudName.isNullOrBlank()) {
                    AppCloudStorage.restore(cloudName)
                } else {
                    Restore.restoreLocked(path!!)
                }
            }
        }
        return mapOf("dryRun" to false, "restored" to true, "source" to source)
    }

    /** `backup_config_get`：备份忽略配置（键 + 标题 + 当前是否忽略 + 原始映射）。 */
    suspend fun configGet(): Map<String, Any?> = withContext(Dispatchers.IO) {
        val keys = BackupConfig.ignoreKeys
        val titles = BackupConfig.ignoreTitle
        val current = BackupConfig.ignoreConfig
        mapOf(
            "items" to keys.mapIndexed { index, key ->
                mapOf(
                    "key" to key,
                    "title" to titles.getOrNull(index),
                    "ignored" to (current[key] ?: false),
                )
            },
            "raw" to HashMap(current),
        )
    }

    /**
     * `backup_config_save`：保存备份忽略配置。
     *
     * 只接受 `BackupConfig.ignoreKeys` 白名单内的键（防写入无效键污染 `restoreIgnore.json`）；
     * 命中 0 个已知键时抛错（避免"静默什么都没存"）。
     */
    suspend fun configSave(ignore: Map<String, Boolean>): Map<String, Any?> = withContext(Dispatchers.IO) {
        val allowed = BackupConfig.ignoreKeys.toSet()
        val accepted = ignore.filterKeys { it in allowed }
        require(accepted.isNotEmpty()) {
            "ignore 未命中任何已知配置键（可用键：${allowed.joinToString()}）"
        }
        val config = BackupConfig.ignoreConfig
        accepted.forEach { (key, value) -> config[key] = value }
        BackupConfig.saveIgnoreConfig()
        mapOf("saved" to accepted.size, "applied" to accepted)
    }

    /**
     * 在共享锁临界区内产出 zip（**私有**：防绕过 [BackupRestoreLock] 直调，REQ-07 / R9）。
     *
     * 与定时/手动备份并发会争用同一批数据库与工作目录，故对外入口必须加锁。
     */
    private suspend fun buildBackupZip(): File {
        val aes = BackupAES()
        FileUtils.delete(webBackupPath)

        withContext(Dispatchers.IO) {
            // 数据库导出
            writeListToJson(appDb.bookDao.all, "bookshelf.json", webBackupPath)
            writeListToJson(appDb.bookmarkDao.all, "bookmark.json", webBackupPath)
            writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", webBackupPath)
            writeListToJson(appDb.bookSourceDao.all, "bookSource.json", webBackupPath)
            writeListToJson(appDb.rssSourceDao.all, "rssSources.json", webBackupPath)
            writeListToJson(appDb.rssStarDao.all, "rssStar.json", webBackupPath)
            writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", webBackupPath)
            FileUtils.createFileIfNotExist(webBackupPath + File.separator + HighlightRuleStore.backupFileName)
                .writeText(GSON.toJson(HighlightRuleStore.createBackupData(appCtx)))
            writeListToJson(appDb.readRecordDao.all, "readRecord.json", webBackupPath)
            writeListToJson(appDb.readRecordDao.getAllDetailsList(), "readRecordDetail.json", webBackupPath)
            writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", webBackupPath)
            writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", webBackupPath)
            writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", webBackupPath)
            writeListToJson(appDb.httpTTSDao.all, "httpTTS.json", webBackupPath)
            writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", webBackupPath)
            writeListToJson(appDb.dictRuleDao.all, "dictRule.json", webBackupPath)

            // ===== REQ-05 / 1.2.7 对等性补全（Web 备份此前漏写的类别） =====
            // Web 备份**硬编码全集、不走 BackupSelectorConfig 选择器** ⇒ 选择器加了新条目它不会自动跟上，
            // 必须逐条登记。实测此前漏写 5 类：手动划线 / 自动任务 / 选角模板 / 封面图集 / 书源运行数据。
            writeListToJson(appDb.bookHighlightDao.all, "highlights.json", webBackupPath)
            writeListToJson(appDb.sceneBookmarkDao.all, "sceneBookmarks.json", webBackupPath)
            writeListToJson(appDb.autoTaskRuleDao.all(), "autoTask.json", webBackupPath)
            writeListToJson(appDb.ttsCastingTemplateDao.all(), "ttsCastingTemplates.json", webBackupPath)
            Backup.stageCoverGallery(webBackupPath)
            Backup.stageRuntimeSourceCaches(webBackupPath)
            // W4 / REQ-17（AD-08）：订阅已读记录（四处同名同文件铁律的第 ④ 处）
            // Web 备份硬编码全集、不走选择器 ⇒ 选择器加了新条目它不会自动跟上，必须在此显式登记
            writeListToJson(appDb.rssReadRecordDao.getRecords(), "rssReadRecord.json", webBackupPath)

            // 服务器配置加密存储
            GSON.toJson(appDb.serverDao.all).let { json ->
                aes.runCatching {
                    encryptBase64(json)
                }.getOrDefault(json).let {
                    FileUtils.createFileIfNotExist(webBackupPath + File.separator + "servers.json")
                        .writeText(it)
                }
            }

            // 阅读配置
            GSON.toJson(ReadBookConfig.getBackupConfigList()).let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + ReadBookConfig.configFileName)
                    .writeText(it)
            }
            GSON.toJson(ReadBookConfig.getBackupShareConfig()).let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + ReadBookConfig.shareConfigFileName)
                    .writeText(it)
            }

            // 主题配置
            GSON.toJson(ThemeConfig.configList).let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + ThemeConfig.configFileName)
                    .writeText(it)
            }

            // 直链上传配置
            DirectLinkUpload.getConfig()?.let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + DirectLinkUpload.ruleFileName)
                    .writeText(GSON.toJson(it))
            }

            // 封面规则配置
            BookCover.getConfig()?.let {
                FileUtils.createFileIfNotExist(webBackupPath + File.separator + BookCover.configFileName)
                    .writeText(GSON.toJson(it))
            }

            // 应用主配置
            appCtx.getSharedPreferences(webBackupPath, "config")?.let { sp ->
                val edit = sp.edit()
                appCtx.defaultSharedPreferences.all.forEach { (key, value) ->
                    when (key) {
                        PreferKey.webDavPassword -> {
                            edit.putString(key, aes.runCatching {
                                encryptBase64(value.toString())
                            }.getOrDefault(value.toString()))
                        }
                        else -> when (value) {
                            is Int -> edit.putInt(key, value)
                            is Boolean -> edit.putBoolean(key, value)
                            is Long -> edit.putLong(key, value)
                            is Float -> edit.putFloat(key, value)
                            is String -> edit.putString(key, value)
                        }
                    }
                }
                edit.commit()
            }

            // 视频播放配置
            appCtx.getSharedPreferences(webBackupPath, "videoConfig")?.let { sp ->
                sp.edit(commit = true) {
                    appCtx.getSharedPreferences(VIDEO_PREF_NAME, android.content.Context.MODE_PRIVATE).all.forEach { (key, value) ->
                        when (value) {
                            is Int -> putInt(key, value)
                            is Boolean -> putBoolean(key, value)
                            is Long -> putLong(key, value)
                            is Float -> putFloat(key, value)
                            is String -> putString(key, value)
                        }
                    }
                }
            }

            // 背景图片、高亮规则背景、书籍缓存
            Backup.stageBackgroundImageFiles(webBackupPath)
            Backup.stageHighlightRuleBackgroundFiles(webBackupPath)
            Backup.stageBookCache(webBackupPath)
            Backup.stageBookChapterForCache(webBackupPath)
        }

        // 打包 ZIP
        val backupDir = File(webBackupPath)
        val files = backupDir.listFiles()?.toList().orEmpty()
        if (files.isEmpty()) throw RuntimeException("ZIP打包失败")

        val paths = files.map { it.absolutePath }

        // 清理历史残留（超时取消 / 响应中途断开都会留下未删除的临时包）；
        // 放在创建本次 tempZip **之前**，故绝不会扫到本次产物。删除对「正在被 HTTP 层读取」的
        // 旧残留也是安全的：Linux 下 unlink 不影响已打开的 fd。
        appCtx.externalFiles.listFiles { _, name -> name.startsWith("web_backup") && name.endsWith(".zip") }
            ?.forEach { FileUtils.delete(it) }

        val tempZip = File(appCtx.externalFiles.absolutePath, "web_backup_${System.currentTimeMillis()}.zip")
        if (!ZipUtils.zipFiles(paths, tempZip.absolutePath)) {
            throw RuntimeException("ZIP打包失败")
        }
        if (!tempZip.exists() || tempZip.length() == 0L) {
            FileUtils.delete(tempZip)
            throw RuntimeException("备份文件生成失败")
        }
        return tempZip
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        withContext(Dispatchers.IO) {
            val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, list)
            }
        }
    }

    /**
     * 生成备份概览信息
     */
    private suspend fun generateBackupOverview(): BackupOverview = withContext(Dispatchers.IO) {
        val items = mutableListOf<BackupItemInfo>()
        var totalSize = 0L

        val backupItems = listOf(
            BackupItemDef(HighlightRuleStore.backupFileName, "高亮规则", "阅读高亮规则和分组配置") {
                HighlightRuleStore.load(appCtx).size
            },
            BackupItemDef("bookshelf.json", "书架书籍", "书架上的所有书籍信息") {
                appDb.bookDao.all.size
            },
            BackupItemDef("bookmark.json", "书签", "书籍阅读书签") {
                appDb.bookmarkDao.all.size
            },
            BackupItemDef("bookGroup.json", "书籍分组", "书架分组信息") {
                appDb.bookGroupDao.all.size
            },
            BackupItemDef("bookSource.json", "书源", "网络小说书源") {
                appDb.bookSourceDao.all.size
            },
            BackupItemDef("rssSources.json", "订阅源", "订阅源") {
                appDb.rssSourceDao.all.size
            },
            BackupItemDef("rssStar.json", "订阅收藏", "订阅收藏内容") {
                appDb.rssStarDao.all.size
            },
            // W4 / REQ-17：概览与备份内容保持一致（否则用户在概览里看不到该类别的体量）
            BackupItemDef("rssReadRecord.json", "订阅已读记录", "订阅文章的已读状态") {
                appDb.rssReadRecordDao.countRecords
            },
            BackupItemDef("replaceRule.json", "替换规则", "正文替换净化规则") {
                appDb.replaceRuleDao.all.size
            },
            // W8 9.5 / REQ-33：名场面书签（概览与备份内容一致，否则用户在概览里看不到该类别体量）
            BackupItemDef("sceneBookmarks.json", "名场面书签", "一键收藏的名场面与 AI 描述") {
                appDb.sceneBookmarkDao.all.size
            },
            BackupItemDef("readRecord.json", "阅读记录", "阅读时长统计记录") {
                appDb.readRecordDao.all.size
            },
            BackupItemDef("readRecordDetail.json", "阅读详情", "每本书每天的阅读统计") {
                appDb.readRecordDao.getDetailsCount()
            },
            BackupItemDef("searchHistory.json", "搜索历史", "搜索关键词历史") {
                appDb.searchKeywordDao.all.size
            },
            BackupItemDef("sourceSub.json", "订阅源订阅", "订阅源订阅信息") {
                appDb.ruleSubDao.all.size
            },
            BackupItemDef("txtTocRule.json", "TXT目录规则", "本地TXT目录解析规则") {
                appDb.txtTocRuleDao.all.size
            },
            BackupItemDef("httpTTS.json", "TTS配置", "在线朗读引擎配置") {
                appDb.httpTTSDao.all.size
            },
            BackupItemDef("keyboardAssists.json", "键盘辅助", "键盘快捷输入配置") {
                appDb.keyboardAssistsDao.all.size
            },
            BackupItemDef("dictRule.json", "词典规则", "长按查词规则") {
                appDb.dictRuleDao.all.size
            },
            BackupItemDef("servers.json", "服务器配置", "远程服务器配置（加密）") {
                appDb.serverDao.all.size
            },
            BackupItemDef("runtimeSourceCache.json", "书源运行数据", "书源登录信息和运行变量") {
                appDb.cacheDao.getRuntimeSourceCaches().size
            }
        )

        backupItems.forEach { item ->
            val count = item.counter()
            val file = File(webBackupPath, item.fileName)
            val size = if (file.exists()) file.length() else 0L
            totalSize += size

            items.add(
                BackupItemInfo(
                    fileName = item.fileName,
                    displayName = item.displayName,
                    description = item.description,
                    count = count,
                    size = size
                )
            )
        }

        // 书籍缓存
        val selectedBooks = BookCacheSelectorConfig.getSelectedBooks()
        if (selectedBooks.isNotEmpty()) {
            val cacheDir = File(BookHelp.cachePath)
            var bookCacheSize = 0L
            var chapterCount = 0
            if (cacheDir.exists()) {
                selectedBooks.forEach { book ->
                    val folderName = book.getFolderNameNoCache()
                    val bookFolder = File(cacheDir, folderName)
                    if (bookFolder.exists()) {
                        bookCacheSize += bookFolder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        chapterCount += appDb.bookChapterDao.getChapterList(book.bookUrl).size
                    }
                }
            }
            val indexEstimatedSize = selectedBooks.size * 300L
            val chapterEstimatedSize = chapterCount * 200L
            totalSize += bookCacheSize + indexEstimatedSize + chapterEstimatedSize
            items.add(
                BackupItemInfo(
                    fileName = "book_cache",
                    displayName = "书籍缓存",
                    description = "已缓存的章节内容文件",
                    count = selectedBooks.size,
                    size = bookCacheSize
                )
            )
            items.add(
                BackupItemInfo(
                    fileName = "bookCacheIndex.json",
                    displayName = "书籍缓存索引",
                    description = "缓存文件的索引信息",
                    count = selectedBooks.size,
                    size = indexEstimatedSize
                )
            )
            items.add(
                BackupItemInfo(
                    fileName = "bookChapterCache.json",
                    displayName = "书籍章节目录",
                    description = "缓存书籍的章节目录数据",
                    count = chapterCount,
                    size = chapterEstimatedSize
                )
            )
        }

        val configItems = listOf(
            ConfigItemDef(ReadBookConfig.configFileName, "阅读样式配置", "阅读界面样式配置"),
            ConfigItemDef(ReadBookConfig.shareConfigFileName, "共享阅读配置", "跨设备共享的阅读配置"),
            ConfigItemDef(ThemeConfig.configFileName, "主题配置", "界面主题样式配置"),
            ConfigItemDef(BookCover.configFileName, "封面规则", "自定义封面生成规则"),
            ConfigItemDef(DirectLinkUpload.ruleFileName, "直链上传配置", "直链上传规则配置"),
            ConfigItemDef("config.xml", "应用设置", "应用程序偏好设置"),
            ConfigItemDef("videoConfig.xml", "视频配置", "视频播放器设置")
        )

        configItems.forEach { item ->
            val file = File(webBackupPath, item.fileName)
            if (file.exists()) {
                totalSize += file.length()
                items.add(
                    BackupItemInfo(
                        fileName = item.fileName,
                        displayName = item.displayName,
                        description = item.description,
                        count = 1,
                        size = file.length()
                    )
                )
            }
        }

        val bgFiles = Backup.getBackgroundImageFiles()
        val bgSize = bgFiles.sumOf { it.length() }
        if (bgFiles.isNotEmpty()) {
            totalSize += bgSize
            items.add(
                BackupItemInfo(
                    fileName = "readConfigBgImages",
                    displayName = "背景图片",
                    description = "阅读背景使用的自定义图片文件",
                    count = bgFiles.size,
                    size = bgSize
                )
            )
        }

        val highlightRuleBgFiles = HighlightRuleStore.getUsedBgImageFiles(appCtx)
        val highlightRuleBgSize = highlightRuleBgFiles.sumOf { it.length() }
        if (highlightRuleBgFiles.isNotEmpty()) {
            totalSize += highlightRuleBgSize
            items.add(
                BackupItemInfo(
                    fileName = HighlightRuleStore.backupBgDirName,
                    displayName = "高亮背景图片",
                    description = "高亮规则使用的自定义背景图片",
                    count = highlightRuleBgFiles.size,
                    size = highlightRuleBgSize
                )
            )
        }

        BackupOverview(
            fileName = "backup.zip",
            totalSize = totalSize,
            createTime = System.currentTimeMillis(),
            items = items.filter { it.count > 0 || it.size > 0 }
        )
    }
}
