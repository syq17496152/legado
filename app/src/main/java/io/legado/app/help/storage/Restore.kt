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
import io.legado.app.data.entities.RssStar
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
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.fromJsonArray
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
        } ?: run {
            val bookSourceFile = File(path, "bookSource.json")
            if (bookSourceFile.exists()) {
                val json = bookSourceFile.readText()
                ImportOldData.importOldSource(json)
            }
        }
        fileToListT<RssSource>(path, "rssSources.json")?.let {
            withContext(IO) { appDb.rssSourceDao.insert(*it.toTypedArray()) }
        }
        fileToListT<RssStar>(path, "rssStar.json")?.let {
            withContext(IO) { appDb.rssStarDao.insert(*it.toTypedArray()) }
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