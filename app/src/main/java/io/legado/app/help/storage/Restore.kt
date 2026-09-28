package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppConst.androidId
import io.legado.app.constant.AppLog
import kotlinx.coroutines.CancellationException
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.CoverGalleryGroup
import io.legado.app.data.entities.CoverGalleryImage
import io.legado.app.data.repository.CoverGalleryRepository
import io.legado.app.help.book.BookHelp
import io.legado.app.help.source.SourceQueryCache
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookHighlight
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReadRecord
import io.legado.app.data.entities.ReadRecordDetail
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssReadRecord
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.SceneBookmark
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.delay

import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream

/**
 * 恢复
 */
object Restore {

    private const val TAG = "Restore"

    /**
     * 恢复（本地文件）。
     * R9：把「清空工作目录 + 解压」与「落库」整体纳入跨流程共享锁，
     * 避免恢复过程中被并发备份（或另一条恢复）清空工作目录。
     */
    suspend fun restore(context: Context, uri: Uri) {
        LogUtils.d(TAG, "开始恢复备份 uri:$uri")
        restoreAll {
            kotlin.runCatching {
                FileUtils.delete(Backup.backupPath)
                if (uri.isContentScheme()) {
                    DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                        ZipUtils.unZipToPath(it, Backup.backupPath)
                    }
                } else {
                    ZipUtils.unZipToPath(File(uri.path!!), Backup.backupPath)
                }
            }.onFailure {
                AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
            }.isSuccess
        }
    }

    /**
     * 恢复全流程入口（R9）：准备阶段与落库同处一个临界区。
     *
     * 云存储 / WebDAV / 本地文件三条恢复入口统一走此入口，保证「清空+解压+落库」全过程互斥。
     *
     * @param prepare 准备阶段（清理工作目录 + 解压/下载落盘）；返回 false 表示准备失败，不再落库
     */
    suspend fun restoreAll(prepare: suspend () -> Boolean) {
        BackupRestoreLock.withStorageLock {
            if (!prepare()) return@withStorageLock
            kotlin.runCatching {
                restoreUnlocked(Backup.backupPath)
                LocalConfig.lastBackup = System.currentTimeMillis()
            }.onFailure {
                appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
                AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
            }
        }
    }

    /**
     * 工作目录已就绪时的落库入口（对外唯一加锁入口）。
     * 注意：临界区内禁止再调用本方法（`Mutex` 不可重入）。
     */
    suspend fun restoreLocked(path: String) {
        BackupRestoreLock.withStorageLock {
            restoreUnlocked(path)
        }
    }

    /**
     * 未加锁的落库实现：仅供 [restoreAll] / [restoreLocked] 在持有共享锁时调用。
     */
    private suspend fun restoreUnlocked(path: String) = restore(path)

    private suspend fun restore(path: String) {
        val aes = BackupAES()
        fileToListT<Book>(path, "bookshelf.json")?.let {
            it.forEach { book ->
                book.upType()
            }
            it.filter { book -> book.isLocal }
                .forEach { book ->
                    book.coverUrl = LocalBook.getCoverPath(book)
                }
            val newBooks = arrayListOf<Book>()
            val ignoreLocalBook = BackupConfig.ignoreLocalBook
            it.forEach { book ->
                if (ignoreLocalBook && book.isLocal) {
                    return@forEach
                }
                if (withContext(IO) { appDb.bookDao.has(book.bookUrl) }) {
                    try {
                        withContext(IO) { appDb.bookDao.update(book) }
                    } catch (_: SQLiteConstraintException) {
                        withContext(IO) { appDb.bookDao.insert(book) }
                    }
                } else {
                    newBooks.add(book)
                }
            }
            withContext(IO) { appDb.bookDao.insert(*newBooks.toTypedArray()) }
        }
        fileToListT<Bookmark>(path, "bookmark.json")?.let {
            withContext(IO) { appDb.bookmarkDao.insert(*it.toTypedArray()) }
        }
        // B2.5：手动划线导入（insert 为 REPLACE → 幂等，重复恢复不会产生重复记录）
        fileToListT<BookHighlight>(path, "highlights.json")?.let {
            withContext(IO) { appDb.bookHighlightDao.insert(*it.toTypedArray()) }
        }
        // W8 9.5 / REQ-33：名场面书签还原（`@Insert(REPLACE)` ⇒ 幂等；旧备份无该文件时静默跳过）。
        // 描述与标签是库内字段，随行一起回填 ⇒ 恢复后 AI 描述保留。
        fileToListT<SceneBookmark>(path, "sceneBookmarks.json")?.let { list ->
            withContext(IO) { list.forEach { appDb.sceneBookmarkDao.insert(it) } }
        }
        // R8（B2，2026-09-23）：自动任务规则导入（autoTaskRuleDao 亦为 REPLACE ⇒ 幂等）。
        // 恢复后**必须立即重排**：否则规则已在库里但系统闹钟未建立，用户会以为「恢复了却不跑」。
        // 旧备份不含该文件时 fileToListT 返回 null ⇒ 静默跳过（不报错）。
        fileToListT<io.legado.app.model.AutoTaskRule>(path, "autoTask.json")?.let {
            withContext(IO) { appDb.autoTaskRuleDao.insert(*it.toTypedArray()) }
            io.legado.app.model.AutoTask.refreshSchedule()
        }
        fileToListT<BookGroup>(path, "bookGroup.json")?.let {
            withContext(IO) { appDb.bookGroupDao.insert(*it.toTypedArray()) }
        }
        fileToListT<BookSource>(path, "bookSource.json")?.let {
            withContext(IO) { appDb.bookSourceDao.insert(*it.toTypedArray()) }
            // R18（B4）：写源入口 ④备份恢复（书源）—— 使查询缓存整体失效
            SourceQueryCache.invalidate()
        } ?: run {
            val bookSourceFile = File(path, "bookSource.json")
            if (bookSourceFile.exists()) {
                val json = bookSourceFile.readText()
                ImportOldData.importOldSource(json)
            }
        }
        fileToListT<RssSource>(path, "rssSources.json")?.let {
            withContext(IO) { appDb.rssSourceDao.insert(*it.toTypedArray()) }
            // R18（B4）：写源入口 ④备份恢复（订阅源）
            SourceQueryCache.invalidate()
        }
        fileToListT<RssStar>(path, "rssStar.json")?.let {
            withContext(IO) { appDb.rssStarDao.insert(*it.toTypedArray()) }
        }
        // W4 / REQ-17（AD-08）：订阅已读记录还原（四处同名同文件铁律的第 ② 处）
        // 说明：Dao 的 insertRecord 为 `@Insert(onConflict = IGNORE)` ⇒ 已存在的记录不会被覆盖
        //（恢复语义：只补回缺失的已读状态，不推翻本机更新的阅读进度）。
        fileToListT<RssReadRecord>(path, "rssReadRecord.json")?.let {
            withContext(IO) { appDb.rssReadRecordDao.insertRecord(*it.toTypedArray()) }
        }
        fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
            withContext(IO) { appDb.replaceRuleDao.insert(*it.toTypedArray()) }
        }
        fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
            withContext(IO) { appDb.searchKeywordDao.insert(*it.toTypedArray()) }
        }
        fileToListT<RuleSub>(path, "sourceSub.json")?.let {
            withContext(IO) { appDb.ruleSubDao.insert(*it.toTypedArray()) }
        }
        fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
            withContext(IO) { appDb.txtTocRuleDao.insert(*it.toTypedArray()) }
        }
        fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
            withContext(IO) { appDb.httpTTSDao.insert(*it.toTypedArray()) }
        }
        // E3/F-1：选角模板恢复（Dao insert=REPLACE 追加式幂等）；异常隔离防单文件损坏中断 restoreLocked
        fileToListT<io.legado.app.data.entities.TtsCastingTemplate>(path, "ttsCastingTemplates.json")?.let {
            try {
                withContext(IO) { appDb.ttsCastingTemplateDao.insert(*it.toTypedArray()) }
                // 恢复后失效激活快照（热生效，无需重启）
                io.legado.app.help.readaloud.casting.TtsCastingStore.invalidateSnapshot()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("选角模板恢复失败，已跳过：${e.message}")
            }
        }
        fileToListT<DictRule>(path, "dictRule.json")?.let {
            withContext(IO) { appDb.dictRuleDao.insert(*it.toTypedArray()) }
        }
        fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
            withContext(IO) { appDb.keyboardAssistsDao.deleteAll() } //先删除所有,保证和备份数据一样
            withContext(IO) { appDb.keyboardAssistsDao.insert(*it.toTypedArray()) }
        }
        fileToListT<ReadRecord>(path, "readRecord.json")?.let {
            it.forEach { readRecord ->
                //判断是不是本机记录
                if (readRecord.deviceId != androidId) {
                    withContext(IO) { appDb.readRecordDao.insert(readRecord) }
                } else {
                    val time = withContext(IO) {
                        appDb.readRecordDao.getReadTime(readRecord.deviceId, readRecord.bookName)
                    }
                    if (time == null || time < readRecord.readTime) {
                        withContext(IO) { appDb.readRecordDao.insert(readRecord) }
                    }
                }
            }
        }
        // F-P0-2 备份选择器（借鉴蛋蛋Max）恢复阅读记录详情
        fileToListT<ReadRecordDetail>(path, "readRecordDetail.json")?.let {
            withContext(IO) { appDb.readRecordDao.insertDetails(*it.toTypedArray()) }
        }
        File(path, "servers.json").takeIf {
            it.exists()
        }?.runCatching {
            var json = readText()
            if (!json.isJsonArray()) {
                json = aes.decryptStr(json)
            }
            GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                withContext(IO) { appDb.serverDao.insert(*it.toTypedArray()) }
            }
        }?.onFailure {
            AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
        }
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            ACache.get(cacheDir = false).put(DirectLinkUpload.ruleFileName, json)
        }?.onFailure {
            AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it)
        }
        //恢复主题配置
        File(path, ThemeConfig.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            FileUtils.delete(ThemeConfig.configFilePath)
            copyTo(File(ThemeConfig.configFilePath))
            ThemeConfig.upConfig()
            // theme-fontscale-daynight AD-03：历史内置主题资产已移除，恢复不再合并补齐；
            // 旧备份中的主题包体系数据由主题列表按本地包正常恢复
        }?.onFailure {
            AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
        }
        File(path, BookCover.configFileName).takeIf {
            it.exists()
        }?.runCatching {
            val json = readText()
            BookCover.saveCoverRule(json)
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        if (!BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.configFilePath)
                copyTo(File(ReadBookConfig.configFilePath))
                ReadBookConfig.initConfigs()
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                FileUtils.delete(ReadBookConfig.shareConfigFilePath)
                copyTo(File(ReadBookConfig.shareConfigFilePath))
                ReadBookConfig.initShareConfig()
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
        }
        // ===== REQ-05 / 1.2.7 备份↔恢复对等性补全（此前「只备份不还原」的 5 类） =====
        // 缺口实情：这些类别在 `BackupSelectorConfig.allItems` 里可勾选、备份侧也会写出，
        // 但 Restore 侧从未读回 ⇒ 换设备/重装后静默丢失（用户报障 #1 根因之一）。
        // 顺序：背景图先落盘，再由下方 config 段恢复的配置去引用它们。
        restoreHighlightRule(path)
        restoreRuntimeSourceCache(path)
        restoreBackgroundImages(path)
        restoreBookCache(path)
        restoreCoverGallery(path)

        //AppWebDav.downBgs()
        appCtx.getSharedPreferences(path, "config")?.all?.let { map ->
            val edit = appCtx.defaultSharedPreferences.edit()

            map.forEach { (key, value) ->
                if (BackupConfig.keyIsNotIgnore(key)) {
                    when (key) {
                        PreferKey.webDavPassword -> {
                            kotlin.runCatching {
                                aes.decryptStr(value.toString())
                            }.getOrNull()?.let {
                                edit.putString(key, it)
                            } ?: let {
                                if (appCtx.getPrefString(PreferKey.webDavPassword)
                                        .isNullOrBlank()
                                ) {
                                    edit.putString(key, value.toString())
                                }
                            }
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
            }
            edit.apply()
            // 线程池拆分：恢复后清除迁移标志位，触发下次启动时重新迁移
            // 这样旧版本备份（只有 threadCount）恢复后能正确迁移，新版本备份（已有新配置）恢复后不会覆盖
            appCtx.defaultSharedPreferences.edit().remove(PreferKey.migratedThreadCount).apply()
        }
        appCtx.getSharedPreferences(path, "videoConfig")?.all?.let { map ->
            appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).edit().apply {
                map.forEach { (key, value) ->
                    when (value) {
                        is Int -> putInt(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is String -> putString(key, value)
                    }
                }
                apply()
            }
        }
        ReadBookConfig.apply {
            comicStyleSelect = appCtx.getPrefInt(PreferKey.comicStyleSelect)
            readStyleSelect = appCtx.getPrefInt(PreferKey.readStyleSelect)
            shareLayout = appCtx.getPrefBoolean(PreferKey.shareLayout)
            hideStatusBar = appCtx.getPrefBoolean(PreferKey.hideStatusBar)
            hideNavigationBar = appCtx.getPrefBoolean(PreferKey.hideNavigationBar)
            autoReadSpeed = appCtx.getPrefInt(PreferKey.autoReadSpeed, 46)
        }
        appCtx.toastOnUi(R.string.restore_success)
        withContext(Main) {
            delay(100)
            ThemeConfig.applyDayNight(appCtx)
        }
    }

    // ===================== REQ-05 / 1.2.7 补全的 5 类还原实现 =====================

    /** 高亮规则（`highlightRule.json` + `highlightRuleBg/` 背景图）：原只备份不还原。 */
    private fun restoreHighlightRule(path: String) {
        File(path, HighlightRuleStore.backupFileName).takeIf { it.exists() }?.runCatching {
            GSON.fromJsonObject<HighlightRuleStore.BackupData>(readText()).getOrThrow().let {
                // backupRootPath 供 HighlightRuleStore 从 `highlightRuleBg/` 找回规则背景图
                HighlightRuleStore.restoreBackupData(appCtx, it, path)
            }
        }?.onFailure {
            AppLog.put("恢复高亮规则出错\n${it.localizedMessage}", it)
        }
    }

    /** 书源运行数据（`runtimeSourceCache.json`）：原只备份不还原。 */
    private suspend fun restoreRuntimeSourceCache(path: String) {
        fileToListT<Cache>(path, "runtimeSourceCache.json")?.let {
            withContext(IO) { appDb.cacheDao.insert(*it.toTypedArray()) }
        }
    }

    /**
     * 背景图片（`bg` 条目）：原只备份不还原。
     *
     * 反向映射对齐 `Backup.stageBackgroundImageFiles` 的写出布局：
     * ① **阅读背景**平铺在备份根目录下（按文件名）⇒ 还原到 `externalFiles/bg/`
     *   （`Backup.getReadBackgroundImageFiles()` 对相对名正是从该目录取值）；
     * ② **主题背景 / 主题配置背景**放在备份根目录的 `<prefKey>/` 子目录下
     *   ⇒ 还原到 `externalFiles/<prefKey>/`。
     * 仅处理图片扩展名，避免把根目录下的 JSON/XML 配置一并搬到图片目录。
     */
    private fun restoreBackgroundImages(path: String) {
        val root = File(path)
        val readBgDir = appCtx.externalFiles.getFile(Backup.READ_BG_DIR).createFolderIfNotExist()
        root.listFiles()?.filter { it.isFile && it.isImageFile() }?.forEach { file ->
            file.copyTo(File(readBgDir, file.name), overwrite = true)
        }
        listOf(PreferKey.bgImage, PreferKey.bgImageN).forEach { prefKey ->
            val sourceDir = File(root, prefKey)
            if (!sourceDir.isDirectory) return@forEach
            val targetDir = appCtx.externalFiles.getFile(prefKey).createFolderIfNotExist()
            sourceDir.listFiles()?.filter { it.isFile && it.isImageFile() }?.forEach { file ->
                file.copyTo(File(targetDir, file.name), overwrite = true)
            }
        }
    }

    /**
     * 书籍缓存（`book_cache` 条目）：原只备份不还原。
     *
     * 对齐 `Backup.stageBookCache` / `stageBookChapterForCache`：
     * ① `bookChapterCache.json`（章节表）⇒ `bookChapterDao.insert`（REPLACE 幂等）；
     * ② `book_cache/<folderName>/`（章节正文 `.nb` 缓存）⇒ 复制回 `BookHelp.cachePath/<folderName>/`。
     * 说明：备份侧的 `bookCacheIndex.json` 是**信息性索引**（书名/作者/章节清单），
     * 还原不依赖它（目录名即 `getFolderNameNoCache()`，与读取侧一致），故不再读入。
     */
    private suspend fun restoreBookCache(path: String) {
        fileToListT<BookChapter>(path, "bookChapterCache.json")?.let {
            withContext(IO) { appDb.bookChapterDao.insert(*it.toTypedArray()) }
        }
        val sourceDir = File(path, Backup.bookCacheFolderName)
        if (!sourceDir.isDirectory) return
        withContext(IO) {
            val cacheDir = File(BookHelp.cachePath).createFolderIfNotExist()
            sourceDir.listFiles()?.filter { it.isDirectory }?.forEach { bookDir ->
                bookDir.copyRecursively(File(cacheDir, bookDir.name), overwrite = true)
            }
        }
    }

    /**
     * 封面图集（`封面图集` 条目）：原只备份不还原。
     *
     * 对齐 `Backup.stageCoverGallery`（布局 `封面图集/<分组目录名>/<图片文件>`）：
     * 逐分组目录重建分组 + 图片落回 `externalFiles/covers/`，再插入 `cover_gallery_images`。
     * **幂等**：同名分组已存在则复用其 id（重复恢复不会产生同名分组副本）。
     */
    private suspend fun restoreCoverGallery(path: String) = withContext(IO) {
        val sourceRoot = File(path, CoverGalleryRepository.backupDirName)
        if (!sourceRoot.isDirectory) return@withContext
        val coversDir = appCtx.externalFiles.getFile("covers").createFolderIfNotExist()
        val existingGroups = appDb.coverGalleryDao.allGroups.associateBy { it.name }
        var maxGroupOrder = appDb.coverGalleryDao.getMaxGroupOrder() ?: -1
        sourceRoot.listFiles()?.filter { it.isDirectory }?.sortedBy { it.name }?.forEach { groupDir ->
            val imageFiles = groupDir.listFiles()
                ?.filter { it.isFile && it.isImageFile() }
                ?.sortedBy { it.name }
            if (imageFiles.isNullOrEmpty()) return@forEach
            val groupId = existingGroups[groupDir.name]?.id ?: run {
                maxGroupOrder += 1
                appDb.coverGalleryDao.insertGroup(
                    CoverGalleryGroup(name = groupDir.name, order = maxGroupOrder)
                )
            }
            var order = appDb.coverGalleryDao.getMaxImageOrder(groupId)?.plus(1) ?: 0
            imageFiles.forEach { imageFile ->
                val target = File(coversDir, imageFile.name)
                imageFile.copyTo(target, overwrite = true)
                appDb.coverGalleryDao.insertImage(
                    CoverGalleryImage(
                        groupId = groupId,
                        path = target.absolutePath,
                        order = order
                    )
                )
                order += 1
            }
        }
        // 默认封面可能指向刚恢复的图集 ⇒ 刷新默认封面缓存
        BookCover.upDefaultCover()
    }

    private fun File.isImageFile(): Boolean =
        extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif")

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

}