package io.legado.app.help.storage

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.Cache
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppCloudStorage
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.model.BookCover
import io.legado.app.model.VideoPlay.VIDEO_PREF_NAME
import io.legado.app.ui.book.read.config.HighlightRuleStore
import io.legado.app.data.repository.CoverGalleryRepository
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.RealPathUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.defaultSharedPreferences
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.getSharedPreferences
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import androidx.core.content.edit

/**
 * F-P0-2 备份选择器（借鉴蛋蛋Max）
 * 章节缓存信息，记录单个章节的缓存文件信息
 */
data class ChapterCacheInfo(
    val index: Int,
    val title: String,
    val titleMD5: String,
    val fileName: String
)

/**
 * F-P0-2 备份选择器（借鉴蛋蛋Max）
 * 书籍缓存索引，恢复时用于匹配
 */
data class BookCacheIndex(
    val bookUrl: String,
    val bookName: String,
    val author: String,
    val folderName: String,
    val chapters: List<ChapterCacheInfo> = emptyList()
)

/**
 * 备份
 */
object Backup {

    private const val runtimeSourceCacheFileName = "runtimeSourceCache.json"
    private const val bookCacheFolderName = "book_cache"
    private const val bookCacheIndexFileName = "bookCacheIndex.json"
    private const val READ_BG_DIR = "bg"

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

    private val mutex = Mutex()

    private val backupFileNames by lazy {
        arrayOf(
            "bookshelf.json",
            "bookmark.json",
            "bookGroup.json",
            "bookSource.json",
            "rssSources.json",
            "rssStar.json",
            "replaceRule.json",
            HighlightRuleStore.backupFileName,
            "readRecord.json",
            "readRecordDetail.json",
            "searchHistory.json",
            "sourceSub.json",
            "txtTocRule.json",
            "httpTTS.json",
            "keyboardAssists.json",
            "dictRule.json",
            "servers.json",
            DirectLinkUpload.ruleFileName,
            ReadBookConfig.configFileName,
            ReadBookConfig.shareConfigFileName,
            ThemeConfig.configFileName,
            BookCover.configFileName,
            "config.xml",
            "videoConfig.xml",
            CoverGalleryRepository.backupDirName
        )
    }

    /**
     * F-P0-2 备份选择器：获取所有背景图片文件
     */
    fun getBackgroundImageFiles(): List<File> {
        val files = mutableListOf<File>()

        // 阅读界面背景图片
        getReadBackgroundImageFiles().let { files.addAll(it) }

        // 主题背景图片（白天/夜间）
        listOf(PreferKey.bgImage, PreferKey.bgImageN).forEach { prefKey ->
            appCtx.getPrefString(prefKey)?.let { path ->
                resolveThemeBackgroundFile(path, prefKey)?.let { files.add(it) }
            }
        }

        // 主题配置中的背景图片
        getThemeConfigBackgroundFiles().forEach { (_, file) -> files.add(file) }

        return files.distinctBy { it.absolutePath }
    }

    private fun getReadBackgroundImageFiles(): List<File> {
        return ReadBookConfig.getAllPicBgStr().mapNotNull { bg ->
            val file = if (bg.contains(File.separator)) {
                File(bg)
            } else {
                appCtx.externalFiles.getFile(READ_BG_DIR, bg)
            }
            file.takeIf { it.exists() && it.isFile }
        }.distinctBy { it.absolutePath }
    }

    private fun resolveThemeBackgroundFile(path: String, prefKey: String): File? {
        val file = when {
            path.startsWith("http") -> {
                val name = ThemeConfig.getUrlToFile(path)
                appCtx.externalFiles.getFile(prefKey, name)
            }
            path.contains(File.separator) -> File(path)
            else -> appCtx.externalFiles.getFile(prefKey, path)
        }
        return file.takeIf { it.exists() && it.isFile }
    }

    private fun getThemeConfigBackgroundFiles(): List<Pair<String, File>> {
        return ThemeConfig.configList.mapNotNull { config ->
            val bgPath = config.backgroundImgPath ?: return@mapNotNull null
            val prefKey = if (config.isNightTheme) PreferKey.bgImageN else PreferKey.bgImage
            resolveThemeBackgroundFile(bgPath, prefKey)?.let { prefKey to it }
        }.distinctBy { "${it.first}:${it.second.absolutePath}" }
    }

    private fun getRuntimeSourceCaches(): List<Cache> {
        return appDb.cacheDao.getRuntimeSourceCaches()
    }

    private fun stageRuntimeSourceCaches(rootPath: String) {
        val runtimeCaches = getRuntimeSourceCaches()
        FileUtils.createFileIfNotExist(rootPath + File.separator + runtimeSourceCacheFileName)
            .writeText(GSON.toJson(runtimeCaches))
    }

    /**
     * F-P0-2 备份选择器：备份阅读+主题背景图片到临时目录
     */
    fun stageBackgroundImageFiles(rootPath: String) {
        getReadBackgroundImageFiles().forEach { bgFile ->
            bgFile.copyTo(File(rootPath, bgFile.name), overwrite = true)
        }
        listOf(PreferKey.bgImage, PreferKey.bgImageN).forEach { prefKey ->
            appCtx.getPrefString(prefKey)?.let { path ->
                resolveThemeBackgroundFile(path, prefKey)
            }?.let { bgFile ->
                val targetDir = File(rootPath, prefKey).createFolderIfNotExist()
                bgFile.copyTo(File(targetDir, bgFile.name), overwrite = true)
            }
        }
        getThemeConfigBackgroundFiles().forEach { (prefKey, bgFile) ->
            val targetDir = File(rootPath, prefKey).createFolderIfNotExist()
            bgFile.copyTo(File(targetDir, bgFile.name), overwrite = true)
        }
    }

    /**
     * F-P0-2 备份选择器：备份高亮规则背景图片到临时目录
     */
    fun stageHighlightRuleBackgroundFiles(rootPath: String) {
        val targetDir = File(rootPath, HighlightRuleStore.backupBgDirName).createFolderIfNotExist()
        HighlightRuleStore.getUsedBgImageFiles(appCtx).forEach { bgFile ->
            bgFile.copyTo(File(targetDir, bgFile.name), overwrite = true)
        }
    }

    /**
     * F-P0-2 备份选择器：备份封面图集到临时目录
     */
    private fun stageCoverGallery(rootPath: String) {
        val groups = appDb.coverGalleryDao.allGroups
        if (groups.isEmpty()) return
        val imagesByGroup = appDb.coverGalleryDao.allImages.groupBy { it.groupId }
        val rootDir = File(rootPath, CoverGalleryRepository.backupDirName).createFolderIfNotExist()
        val usedFolderNames = hashSetOf<String>()
        groups.forEachIndexed { index, group ->
            val folderName = uniqueCoverGalleryFolderName(group.name, index, usedFolderNames)
            val groupDir = File(rootDir, folderName).createFolderIfNotExist()
            imagesByGroup[group.id].orEmpty()
                .sortedWith(compareBy({ it.order }, { it.id }))
                .map { File(it.path) }
                .filter { it.exists() && it.isFile }
                .distinctBy { it.absolutePath }
                .forEach { imageFile ->
                    imageFile.copyTo(File(groupDir, imageFile.name), overwrite = true)
                }
        }
    }

    private fun uniqueCoverGalleryFolderName(
        groupName: String,
        index: Int,
        usedFolderNames: MutableSet<String>
    ): String {
        val fallbackName = "group${index + 1}"
        val baseName = groupName.ifBlank { fallbackName }.normalizeFileName().ifBlank {
            fallbackName
        }
        var folderName = baseName
        var suffix = 2
        while (!usedFolderNames.add(folderName)) {
            folderName = "$baseName ($suffix)"
            suffix++
        }
        return folderName
    }

    /**
     * F-P0-2 备份选择器：备份选中书籍的缓存文件 + 章节索引
     */
    internal fun stageBookCache(rootPath: String) {
        val selectedBooks = BookCacheSelectorConfig.getSelectedBooks()
        if (selectedBooks.isEmpty()) {
            LogUtils.d(TAG, "没有选中要备份缓存的书籍")
            return
        }

        val cacheDir = File(BookHelp.cachePath)
        if (!cacheDir.exists() || !cacheDir.isDirectory) {
            LogUtils.d(TAG, "书籍缓存目录不存在")
            return
        }

        val bookCacheIndexList = mutableListOf<BookCacheIndex>()
        val targetCacheDir = File(rootPath, bookCacheFolderName).createFolderIfNotExist()

        selectedBooks.forEach { book ->
            val folderName = book.getFolderNameNoCache()
            val bookFolder = File(cacheDir, folderName)

            if (!bookFolder.exists() || !bookFolder.isDirectory) {
                LogUtils.d(TAG, "书籍缓存文件夹不存在: ${book.name}")
                return@forEach
            }

            val chapterList = appDb.bookChapterDao.getChapterList(book.bookUrl)
            val chapterMap = chapterList.associateBy { it.index }

            val chapterCacheInfos = mutableListOf<ChapterCacheInfo>()
            bookFolder.listFiles()?.forEach { file ->
                if (file.isFile && file.name.endsWith(".nb")) {
                    parseChapterFileName(file.name, chapterMap)?.let { chapterCacheInfos.add(it) }
                }
            }

            bookCacheIndexList.add(
                BookCacheIndex(
                    bookUrl = book.bookUrl,
                    bookName = book.name,
                    author = book.author ?: "",
                    folderName = folderName,
                    chapters = chapterCacheInfos.sortedBy { it.index }
                )
            )

            val targetBookDir = File(targetCacheDir, folderName).createFolderIfNotExist()
            bookFolder.copyRecursively(targetBookDir, overwrite = true)
            LogUtils.d(TAG, "备份书籍缓存: ${book.name} -> $folderName, 章节数: ${chapterCacheInfos.size}")
        }

        if (bookCacheIndexList.isNotEmpty()) {
            val indexFile = File(rootPath, bookCacheIndexFileName)
            indexFile.writeText(GSON.toJson(bookCacheIndexList))
            LogUtils.d(TAG, "书籍缓存索引已保存，共 ${bookCacheIndexList.size} 本书")
        }
    }

    private fun parseChapterFileName(
        fileName: String,
        chapterMap: Map<Int, BookChapter>
    ): ChapterCacheInfo? {
        if (!fileName.endsWith(".nb")) return null
        val nameWithoutExt = fileName.removeSuffix(".nb")
        val parts = nameWithoutExt.split("-")
        if (parts.size != 2) return null
        val index = parts[0].toIntOrNull() ?: return null
        val titleMD5 = parts[1]
        val chapter = chapterMap[index] ?: return null
        return ChapterCacheInfo(
            index = index,
            title = chapter.title,
            titleMD5 = titleMD5,
            fileName = fileName
        )
    }

    /**
     * F-P0-2 备份选择器：备份选中书籍的章节目录（与缓存一起，确保恢复后可读）
     */
    internal suspend fun stageBookChapterForCache(rootPath: String) {
        val selectedBooks = BookCacheSelectorConfig.getSelectedBooks()
        if (selectedBooks.isEmpty()) return

        val allChapters = mutableListOf<BookChapter>()
        selectedBooks.forEach { book ->
            val chapters = appDb.bookChapterDao.getChapterList(book.bookUrl)
            allChapters.addAll(chapters)
        }

        if (allChapters.isNotEmpty()) {
            writeListToJson(allChapters, "bookChapterCache.json", rootPath)
            LogUtils.d(TAG, "章节目录已备份，共 ${allChapters.size} 章")
        }
    }

    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val deviceName = AppConfig.webDavDeviceName
        return if (deviceName?.isNotBlank() == true) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    private fun shouldBackup(): Boolean {
        val lastBackup = LocalConfig.lastBackup
        return lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()
    }

    fun autoBack(context: Context) {
        if (shouldBackup()) {
            Coroutine.async {
                mutex.withLock {
                    if (shouldBackup()) {
                        val backupZipFileName = getNowZipFileName()
                        if (!AppCloudStorage.hasBackup(backupZipFileName)) {
                            backup(context, AppConfig.backupPath)
                        } else {
                            LocalConfig.lastBackup = System.currentTimeMillis()
                        }
                    }
                }
            }.onError {
                AppLog.put("自动备份失败\n${it.localizedMessage}")
            }
        }
    }

    suspend fun backupLocked(
        context: Context,
        path: String?,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false
    ) {
        mutex.withLock {
            withContext(IO) {
                backup(context, path, uploadCloud, uploadWebDavFallback)
            }
        }
    }

    /**
     * F-P0-2 备份选择器：核心备份逻辑
     * 根据用户在 BackupSelectorConfig 中的选择决定备份哪些内容
     */
    private suspend fun backup(
        context: Context,
        path: String?,
        uploadCloud: Boolean = true,
        uploadWebDavFallback: Boolean = false
    ) {
        LogUtils.d(TAG, "开始备份 path:$path")
        LocalConfig.lastBackup = System.currentTimeMillis()
        val aes = BackupAES()
        FileUtils.delete(backupPath)

        val selectedFiles = BackupSelectorConfig.getSelectedFileNames()

        // 数据库导出
        if (selectedFiles.contains("bookshelf.json")) {
            writeListToJson(appDb.bookDao.all, "bookshelf.json", backupPath)
        }
        if (selectedFiles.contains("bookmark.json")) {
            writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
        }
        if (selectedFiles.contains("bookGroup.json")) {
            writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        }
        if (selectedFiles.contains("bookSource.json")) {
            writeListToJson(appDb.bookSourceDao.all, "bookSource.json", backupPath)
        }
        if (selectedFiles.contains("rssSources.json")) {
            writeListToJson(appDb.rssSourceDao.all, "rssSources.json", backupPath)
        }
        if (selectedFiles.contains("rssStar.json")) {
            writeListToJson(appDb.rssStarDao.all, "rssStar.json", backupPath)
        }
        if (selectedFiles.contains("replaceRule.json")) {
            writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", backupPath)
        }
        if (selectedFiles.contains(HighlightRuleStore.backupFileName)) {
            FileUtils.createFileIfNotExist(backupPath + File.separator + HighlightRuleStore.backupFileName)
                .writeText(GSON.toJson(HighlightRuleStore.createBackupData(appCtx)))
        }
        if (selectedFiles.contains("readRecord.json")) {
            writeListToJson(appDb.readRecordDao.all, "readRecord.json", backupPath)
        }
        if (selectedFiles.contains("readRecordDetail.json")) {
            writeListToJson(appDb.readRecordDao.getAllDetailsList(), "readRecordDetail.json", backupPath)
        }
        if (selectedFiles.contains("searchHistory.json")) {
            writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", backupPath)
        }
        if (selectedFiles.contains("sourceSub.json")) {
            writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", backupPath)
        }
        if (selectedFiles.contains("txtTocRule.json")) {
            writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        }
        if (selectedFiles.contains("httpTTS.json")) {
            writeListToJson(appDb.httpTTSDao.all, "httpTTS.json", backupPath)
        }
        if (selectedFiles.contains("keyboardAssists.json")) {
            writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        }
        if (selectedFiles.contains("dictRule.json")) {
            writeListToJson(appDb.dictRuleDao.all, "dictRule.json", backupPath)
        }
        if (selectedFiles.contains(CoverGalleryRepository.backupDirName)) {
            stageCoverGallery(backupPath)
        }

        // 服务器配置加密存储
        if (selectedFiles.contains("servers.json")) {
            GSON.toJson(appDb.serverDao.all).let { json ->
                aes.runCatching {
                    encryptBase64(json)
                }.getOrDefault(json).let {
                    FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                        .writeText(it)
                }
            }
        }

        currentCoroutineContext().ensureActive()

        // 阅读配置
        if (selectedFiles.contains(ReadBookConfig.configFileName)) {
            GSON.toJson(ReadBookConfig.getBackupConfigList()).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.configFileName)
                    .writeText(it)
            }
        }
        if (selectedFiles.contains(ReadBookConfig.shareConfigFileName)) {
            GSON.toJson(ReadBookConfig.getBackupShareConfig()).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.shareConfigFileName)
                    .writeText(it)
            }
        }

        // 主题配置
        if (selectedFiles.contains(ThemeConfig.configFileName)) {
            GSON.toJson(ThemeConfig.configList).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfig.configFileName)
                    .writeText(it)
            }
        }

        // 直链上传配置
        if (selectedFiles.contains(DirectLinkUpload.ruleFileName)) {
            DirectLinkUpload.getConfig()?.let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + DirectLinkUpload.ruleFileName)
                    .writeText(GSON.toJson(it))
            }
        }

        // 封面规则配置
        if (selectedFiles.contains(BookCover.configFileName)) {
            BookCover.getConfig()?.let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                    .writeText(GSON.toJson(it))
            }
        }

        currentCoroutineContext().ensureActive()

        // 应用主配置
        if (selectedFiles.contains("config.xml")) {
            appCtx.getSharedPreferences(backupPath, "config")?.let { sp ->
                val edit = sp.edit()
                edit.clear()
                appCtx.defaultSharedPreferences.all.forEach { (key, value) ->
                    if (BackupConfig.keyIsNotIgnore(key)) {
                        when (key) {
                            in BackupAES.sensitivePrefKeys -> {
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
                }
                edit.commit()
            }
        }

        currentCoroutineContext().ensureActive()

        // 视频播放配置
        if (selectedFiles.contains("videoConfig.xml")) {
            appCtx.getSharedPreferences(backupPath, "videoConfig")?.let { sp ->
                sp.edit(commit = true) {
                    clear()
                    appCtx.getSharedPreferences(VIDEO_PREF_NAME, Context.MODE_PRIVATE).all.forEach { (key, value) ->
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
        }

        currentCoroutineContext().ensureActive()

        // 背景图片、高亮规则背景、书源运行数据、书籍缓存
        if (selectedFiles.contains(READ_BG_DIR)) {
            stageBackgroundImageFiles(backupPath)
        }
        if (selectedFiles.contains(HighlightRuleStore.backupFileName)) {
            stageHighlightRuleBackgroundFiles(backupPath)
        }
        if (selectedFiles.contains(runtimeSourceCacheFileName)) {
            stageRuntimeSourceCaches(backupPath)
        }
        if (selectedFiles.contains(bookCacheFolderName)) {
            stageBookCache(backupPath)
            stageBookChapterForCache(backupPath)
        }

        currentCoroutineContext().ensureActive()

        val zipFileName = getNowZipFileName()
        val paths = getBackupPaths()
        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))

        val backupFileName = if (AppConfig.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }

        if (ZipUtils.zipFiles(paths, zipFilePath)) {
            when {
                path.isNullOrBlank() -> {
                    // 未配置备份路径时，写入公共目录 Download/legado/backup，
                    // 确保用户可在系统文件管理器/文件选择器中直接看到备份文件（Android 11+ 公共目录需 MediaStore 扫描）
                    val defaultDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    ).getFile("legado", "backup")
                    if (!defaultDir.exists() && !defaultDir.mkdirs()) {
                        throw NoStackTraceException("无法创建默认备份目录\n路径: ${defaultDir.absolutePath}")
                    }
                    copyBackup(defaultDir, backupFileName)
                }

                path.isContentScheme() -> {
                    // SAF tree 若落在 Android/data 私有目录（系统选择器选到私有目录），文件管理器同样不可见
                    val treePath = RealPathUtil.getTreePath(path.toUri())
                    if (treePath != null && treePath.startsWith(appCtx.externalFiles.parent!!)) {
                        throw NoStackTraceException(
                            "备份路径是应用私有目录，系统文件管理器不可见，请重新选择公共目录（如 Download/Documents）"
                        )
                    }
                    copyBackup(context, path.toUri(), backupFileName)
                }

                else -> {
                    val targetFile = File(path)
                    // 已配置的备份路径若指向应用私有目录（Android/data 下），
                    // 文件管理器不可见，会导致"提示备份成功却找不到文件"，直接拒绝并引导重选公共目录
                    if (targetFile.absolutePath.startsWith(appCtx.externalFiles.parent!!)) {
                        throw NoStackTraceException(
                            "备份路径是应用私有目录，系统文件管理器不可见，请重新选择公共目录（如 Download/Documents）"
                        )
                    }
                    copyBackup(targetFile, backupFileName)
                }
            }
            // 打包成功后按云存储类型上传（S3/WebDAV，对齐 archive 多云端能力）
            if (uploadCloud) {
                val cloudType = if (uploadWebDavFallback) CloudStorageType.WEBDAV else AppCloudStorage.type
                AppLog.put("Upload cloud backup: ${cloudType.name} $zipFileName")
                if (uploadWebDavFallback) {
                    AppCloudStorage.backupToWebDav(zipFileName)
                } else {
                    AppCloudStorage.backup(zipFileName)
                }
                AppLog.put("Cloud backup finished: ${cloudType.name} $zipFileName")
            }
        } else {
            // 打包失败不再静默"成功"，向上抛出让调用方提示真实错误
            throw NoStackTraceException("备份文件打包失败，请重试")
        }
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)

        currentCoroutineContext().ensureActive()

        AppCloudStorage.upBgs(getBackgroundImageFiles().toTypedArray())
    }

    /**
     * F-P0-2 备份选择器：收集备份目录下所有文件路径用于打包
     */
    private fun getBackupPaths(): ArrayList<String> {
        return File(backupPath)
            .listFiles()
            ?.mapTo(arrayListOf()) { it.absolutePath }
            ?: arrayListOf()
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            if (list.isNotEmpty()) {
                LogUtils.d(TAG, "阅读备份 $fileName 列表大小 ${list.size}")
                val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
                file.outputStream().buffered().use {
                    GSON.writeToOutputStream(it, list)
                }
                LogUtils.d(TAG, "阅读备份 $fileName 写入大小 ${file.length()}")
            } else {
                LogUtils.d(TAG, "阅读备份 $fileName 列表为空")
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(context: Context, uri: Uri, fileName: String) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)!!
        treeDoc.findFile(fileName)?.delete()
        val fileDoc = treeDoc.createFile("", fileName)
            ?: throw NoStackTraceException("创建文件失败")
        val outputS = fileDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        outputS.use {
            FileInputStream(zipFilePath).use { inputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(rootFile: File, fileName: String) {
        FileInputStream(File(zipFilePath)).use { inputS ->
            val file = FileUtils.createFileIfNotExist(rootFile, fileName)
            FileOutputStream(file).use { outputS ->
                inputS.copyTo(outputS)
            }
            // 检查文件是否真的写入成功
            if (!file.exists() || file.length() == 0L) {
                throw NoStackTraceException("备份文件写入失败，请检查备份路径权限\n路径: ${file.absolutePath}")
            }
            // 通知MediaStore扫描，确保文件在系统文件管理器可见
            MediaScannerConnection.scanFile(appCtx, arrayOf(file.absolutePath), null, null)
        }
    }

    fun clearCache() {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
    }
}
