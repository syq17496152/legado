package io.legado.app.help.config

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.annotation.Keep
import androidx.documentfile.provider.DocumentFile
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.ImageTypeUtils
import io.legado.app.utils.MAX_DATA_URL_BYTES
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isSameOrSubFileOf
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefString
import io.legado.app.utils.readBytesLimited
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.compress.SafeZipExtractor
import io.legado.app.utils.compress.SafeZipLimits
import io.legado.app.utils.compress.readTextLimited
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile

object ThemePackageManager {

    private const val packageFileName = "theme.json"
    private const val maxPackageManifestBytes = 512L * 1024L
    private const val maxRemoteResourceBytes = 32L * 1024L * 1024L
    private val packageZipLimits = SafeZipLimits(
        maxEntries = 512,
        maxEntryBytes = 32L * 1024L * 1024L,
        maxTotalBytes = 128L * 1024L * 1024L,
        maxCompressionRatio = 250L
    )
    private const val remoteListTimeoutMillis = 4_000L
    private const val mainBackgroundPrefix = "background"
    private const val bookInfoBackgroundPrefix = "book_info_background"
    private const val panelBackgroundPrefix = "panel_background"
    private const val uiFontPrefix = "ui_font"
    private const val titleFontPrefix = "title_font"
    private const val redReaderSchemaDirName = "reader_schema"
    private const val builtinDayDirName = "builtin_day"
    private const val builtinNightDirName = "builtin_night"
    private const val builtinDayName = "\u5185\u7f6e\u65e5\u95f4\u4e3b\u9898"
    private const val builtinNightName = "\u5185\u7f6e\u591c\u95f4\u4e3b\u9898"
    private val importMutex = Mutex()
    private val imageMagic = listOf(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) to ".png",
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) to ".jpg",
        byteArrayOf(0x52, 0x49, 0x46, 0x46) to ".webp",
        byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x37, 0x61) to ".gif",
        byteArrayOf(0x47, 0x49, 0x46, 0x38, 0x39, 0x61) to ".gif"
    )

    val rootDir: File
        get() = appCtx.externalFiles.getFile("themePackages")

    suspend fun load(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<Entry> = withContext(IO) {
        val local = importMutex.withLock {
            loadLocal(isNightTheme).filterNot(::isBuiltinDuplicate).associateBy { it.dirName }
        }
        val remote = loadRemoteOrCache(isNightTheme, containerId, scope).associateBy { it.dirName }
        val keys = local.keys + remote.keys
        val mergedEntries = keys.mapNotNull { key ->
            val localEntry = local[key]
            val remoteEntry = remote[key]
            when {
                localEntry != null && remoteEntry != null -> localEntry.copy(
                    source = Source.BOTH,
                    remoteUpdatedAt = remoteEntry.remoteUpdatedAt
                )

                localEntry != null -> localEntry
                remoteEntry != null -> remoteEntry
                else -> null
            }
        }
        sortEntries(listOf(builtinEntry(isNightTheme)) + mergedEntries)
    }

    suspend fun loadLocalOnly(isNightTheme: Boolean): List<Entry> = withContext(IO) {
        importMutex.withLock {
            sortEntries(listOf(builtinEntry(isNightTheme)) + loadLocal(isNightTheme).filterNot(::isBuiltinDuplicate))
        }
    }

    suspend fun localThemeExists(
        isNightTheme: Boolean,
        themeName: String,
        excludeDirName: String? = null
    ): Boolean = withContext(IO) {
        val normalizedName = themeName.trim()
        importMutex.withLock {
            loadLocal(isNightTheme).any {
                it.dirName != excludeDirName && it.packageInfo.name == normalizedName
            }
        }
    }

    suspend fun addFromCurrent(context: Context, name: String, isNightTheme: Boolean): Entry =
        withContext(IO) {
            val normalizedName = name.trim().ifBlank { builtinName(isNightTheme) }
            val config = ThemeConfig.getThemeConfig(context, isNightTheme).copy(
                themeName = normalizedName,
                isNightTheme = isNightTheme
            )
            importMutex.withLock { saveConfig(config) }
        }

    suspend fun addFromConfig(config: ThemeConfig.Config): Entry = withContext(IO) {
        importMutex.withLock { saveConfig(config) }
    }

    suspend fun themeExists(
        isNightTheme: Boolean,
        themeName: String,
        excludeDirName: String? = null
    ): Boolean = withContext(IO) {
        val normalizedName = themeName.trim()
        val localExists = importMutex.withLock {
            loadLocal(isNightTheme).any {
                it.dirName != excludeDirName && it.packageInfo.name == normalizedName
            }
        }
        if (localExists) {
            return@withContext true
        }
        loadRemoteOrCache(isNightTheme).any {
            it.dirName != excludeDirName && it.packageInfo.name == normalizedName
        }
    }

    suspend fun upload(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        AppCloudStorage.uploadThemePackage(
            entry.packageInfo.isNightTheme,
            entry.dirName,
            exportZip(entry),
            containerId,
            scope
        )
    }

    suspend fun download(entry: Entry, containerId: String? = null, scope: String? = null): Entry = withContext(IO) {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppCloudStorage.downloadThemePackage(entry.packageInfo.isNightTheme, entry.dirName, zipFile, containerId, scope)
        importZipInternal(zipFile, entry.remoteUpdatedAt).copy(source = Source.BOTH, remoteUpdatedAt = entry.remoteUpdatedAt)
    }

    suspend fun importZip(zipFile: File): Entry = withContext(IO) {
        val pkg = peekPackage(zipFile)
        importZipInternal(zipFile, 0L)
    }

    suspend fun importPackage(file: File): List<Entry> = withContext(IO) {
        when (detectRedPackageFormat(file)) {
            RedPackageFormat.RED04_ZIP,
            RedPackageFormat.RED_GZIP_JSON,
            RedPackageFormat.RAW_GZIP_JSON -> importPackageDetailed(file).themes
            RedPackageFormat.RED_ASSET_ZIP -> importPackageDetailed(file).themes
            RedPackageFormat.RED10_PRIVATE -> throw IllegalArgumentException(appCtx.getString(R.string.theme_red_private_encrypted_unsupported))
            null -> listOf(importZip(file))
        }
    }

    suspend fun importPackageDetailed(file: File): ThemeImportResult = withContext(IO) {
        when (detectRedPackageFormat(file)) {
            RedPackageFormat.RED04_ZIP -> importRedZipDetailed(file)
            RedPackageFormat.RED_ASSET_ZIP -> importRedAssetZipDetailed(file)
            RedPackageFormat.RED_GZIP_JSON,
            RedPackageFormat.RAW_GZIP_JSON -> {
                val themes = importRedGzip(file)
                ThemeImportResult(sourceName = themes.firstOrNull()?.packageInfo?.name.orEmpty(), themes = themes)
            }
            RedPackageFormat.RED10_PRIVATE -> throw IllegalArgumentException(appCtx.getString(R.string.theme_red_private_encrypted_unsupported))
            null -> {
                val theme = importZip(file)
                ThemeImportResult(sourceName = theme.packageInfo.name, themes = listOf(theme))
            }
        }
    }

    private suspend fun importRedAssetZipDetailed(file: File): ThemeImportResult {
        val zipFile = RedAssetPackage.zipPayload(file, tempDir)
            ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        return try {
            when (RedAssetPackage.classifyZip(zipFile)) {
                RedAssetPackage.Kind.NavigationBar -> {
                    val entry = NavigationBarIconConfig.importPackage(file)
                    ThemeImportResult(
                        sourceName = entry.config.name,
                        navigationBars = listOf(entry)
                    )
                }
                RedAssetPackage.Kind.CoverCollection -> {
                    val collection = CoverCollectionManager.importPackage(appCtx, file, AppConfig.isNightTheme)
                    ThemeImportResult(
                        sourceName = collection.name,
                        coverCollections = listOf(collection)
                    )
                }
                RedAssetPackage.Kind.ThemeZip -> importRedZipDetailed(file)
                RedAssetPackage.Kind.Unknown -> throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
            }
        } finally {
            zipFile.delete()
        }
    }

    suspend fun exportZip(entry: Entry): File = withContext(IO) {
        if (entry.source == Source.BUILTIN) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_builtin_export_forbidden))
        }
        val localEntry = if (entry.source == Source.REMOTE) download(entry) else entry
        importMutex.withLock {
            val dir = localEntry.localDir ?: localDir(localEntry.packageInfo.isNightTheme, localEntry.dirName)
            val zipFile = tempDir.getFile("${localEntry.dirName}.zip")
            if (zipFile.exists()) zipFile.delete()
            ZipUtils.zipFile(dir, zipFile)
            zipFile
        }
    }

    suspend fun deleteLocal(entry: Entry) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        importMutex.withLock {
            entry.localDir?.let { FileUtils.delete(it, deleteRootDir = true) }
            removeThemeConfig(entry.packageInfo.isNightTheme, entry.packageInfo.name, entry.dirName)
        }
    }

    suspend fun deleteRemote(entry: Entry, containerId: String? = null, scope: String? = null) = withContext(IO) {
        if (entry.source == Source.BUILTIN) return@withContext
        AppCloudStorage.deleteThemePackage(entry.packageInfo.isNightTheme, entry.dirName, containerId, scope)
    }

    suspend fun apply(
        context: Context,
        entry: Entry,
        switchNightMode: Boolean = true,
        notify: Boolean = true
    ) {
        val config = withContext(IO) {
            importMutex.withLock { validatedConfig(entry) }
        }
        ThemeConfig.applyConfig(context, config, switchNightMode, notify)
        recordAppliedThemeRef(context, entry)
    }

    private fun recordAppliedThemeRef(context: Context, entry: Entry) {
        context.putPrefString(
            if (entry.packageInfo.isNightTheme) PreferKey.dNThemeDirName else PreferKey.dThemeDirName,
            entry.dirName
        )
    }

    fun builtinEntryForKit(isNightTheme: Boolean): Entry {
        return builtinEntry(isNightTheme)
    }

    fun isBuiltinThemeForKit(isNightTheme: Boolean, name: String): Boolean {
        return isBuiltinTheme(isNightTheme, name)
    }

    suspend fun reapplyRestoredAppliedThemes(context: Context) = withContext(IO) {
        val currentNight = AppConfig.isNightTheme
        reapplyRestoredAppliedTheme(context, !currentNight)
        reapplyRestoredAppliedTheme(context, currentNight)
    }

    suspend fun restoreAppliedThemes(context: Context) = withContext(IO) {
        restoreAppliedTheme(context, false)
        restoreAppliedTheme(context, true)
    }

    fun getConfig(entry: Entry): ThemeConfig.Config {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        return resolveConfigPaths(entry.packageInfo, dir)
    }

    suspend fun ensureLocalAppliedTheme(context: Context, isNightTheme: Boolean): Entry =
        withContext(IO) {
            importMutex.withLock {
                val currentConfig = ThemeConfig.getThemeConfig(context, isNightTheme)
                val themeName = currentConfig.themeName.trim()
                if (isBuiltinTheme(isNightTheme, themeName)) {
                    return@withLock builtinEntry(isNightTheme)
                }
                val config = currentConfig.copy(
                    isNightTheme = isNightTheme,
                    themeName = themeName.ifBlank { builtinName(isNightTheme) }
                )
                if (isBuiltinTheme(isNightTheme, config.themeName)) {
                    return@withLock builtinEntry(isNightTheme)
                }
                val dirName = config.themeName.normalizeFileName()
                val dir = localDir(isNightTheme, dirName)
                readPackage(dir)?.let { pkg ->
                    if (!isBuiltinPackage(pkg)) {
                        return@withLock Entry(pkg, Source.LOCAL, localDir = dir)
                    }
                }
                saveConfig(config.copy(isNightTheme = isNightTheme))
            }
        }

    // dirName 精确匹配优先（同名日夜主题消歧）；跨设备目录名可能不同，失配时回退按名称匹配
    private fun List<Entry>.matchStoredEntry(
        storedDirName: String,
        normalizedDirName: String,
        themeName: String
    ): Entry? {
        if (storedDirName.isNotBlank()) {
            firstOrNull { it.dirName == storedDirName }?.let { return it }
        }
        return firstOrNull { it.dirName == normalizedDirName || it.packageInfo.name == themeName }
    }

    private suspend fun reapplyRestoredAppliedTheme(context: Context, isNightTheme: Boolean) {
        val themeName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        )?.trim().orEmpty()
        if (themeName.isBlank()) return
        if (isBuiltinTheme(isNightTheme, themeName)) {
            val config = validatedConfig(builtinEntry(isNightTheme))
            ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
            return
        }
        val storedDirName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeDirName else PreferKey.dThemeDirName
        )?.trim().orEmpty()
        val normalizedDirName = storedDirName.ifBlank { themeName.normalizeFileName() }
        val directDir = localDir(isNightTheme, normalizedDirName)
        val entry = readPackage(directDir)?.let { pkg ->
            Entry(pkg, Source.LOCAL, localDir = directDir)
        } ?: loadLocal(isNightTheme).matchStoredEntry(storedDirName, normalizedDirName, themeName)
        ?: return
        val config = validatedConfig(entry)
        ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
    }

    private suspend fun restoreAppliedTheme(context: Context, isNightTheme: Boolean) {
        val themeName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeName else PreferKey.dThemeName
        )?.trim().orEmpty()
        if (themeName.isBlank()) return
        if (isBuiltinTheme(isNightTheme, themeName)) {
            val config = validatedConfig(builtinEntry(isNightTheme))
            ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
            return
        }
        val storedDirName = context.getPrefString(
            if (isNightTheme) PreferKey.dNThemeDirName else PreferKey.dThemeDirName
        )?.trim().orEmpty()
        val normalizedDirName = storedDirName.ifBlank { themeName.normalizeFileName() }
        val localEntry = loadLocal(isNightTheme)
            .matchStoredEntry(storedDirName, normalizedDirName, themeName)
        val localConfig = localEntry?.let { entry ->
            runCatching { validatedConfig(entry) }.getOrElse {
                AppLog.put("restore theme local package invalid: $themeName\n${it.localizedMessage}", it)
                null
            }
        }
        if (localConfig != null) {
            ThemeConfig.applyConfig(context, localConfig, switchNightMode = false, notify = false)
            return
        }
        val remoteEntry = loadRemoteOrCache(isNightTheme)
            .matchStoredEntry(storedDirName, normalizedDirName, themeName)
            ?: run {
            AppLog.put("restore theme package not found: $themeName")
            applyBuiltinTheme(context, isNightTheme)
            return
        }
        val downloadedEntry = runCatching { download(remoteEntry) }.getOrElse {
            AppLog.put("restore theme package download failed: $themeName\n${it.localizedMessage}", it)
            applyBuiltinTheme(context, isNightTheme)
            return
        }
        val remoteConfig = runCatching { validatedConfig(downloadedEntry) }.getOrElse {
            AppLog.put("restore theme package resource invalid: $themeName\n${it.localizedMessage}", it)
            applyBuiltinTheme(context, isNightTheme)
            return
        }
        ThemeConfig.applyConfig(context, remoteConfig, switchNightMode = false, notify = false)
    }

    private suspend fun applyBuiltinTheme(context: Context, isNightTheme: Boolean) {
        val config = validatedConfig(builtinEntry(isNightTheme))
        ThemeConfig.applyConfig(context, config, switchNightMode = false, notify = false)
    }

    private fun builtinEntry(isNightTheme: Boolean): Entry {
        val name = builtinName(isNightTheme)
        val config = ThemeConfig.Config(
            themeName = name,
            isNightTheme = isNightTheme,
            primaryColor = if (isNightTheme) "#455A64" else "#795548",
            accentColor = if (isNightTheme) "#FF8A65" else "#E53935",
            backgroundColor = if (isNightTheme) "#212121" else "#F5F5F5",
            bottomBackground = if (isNightTheme) "#303030" else "#EEEEEE",
            transparentNavBar = true,
            backgroundImgPath = null,
            backgroundImgBlur = 0,
            bookInfoBackgroundImgPath = null,
            panelBackgroundImgPath = null
        )
        return Entry(
            Package(
                name = name,
                dirName = builtinDirName(isNightTheme),
                isNightTheme = isNightTheme,
                updatedAt = 0L,
                config = config
            ),
            Source.BUILTIN
        )
    }

    private fun saveConfig(config: ThemeConfig.Config): Entry {
        val normalizedName = config.themeName.trim()
            .ifBlank { builtinName(config.isNightTheme) }
        val dirName = uniqueDirName(config.isNightTheme, normalizedName.normalizeFileName(), normalizedName)
        val dir = localDir(config.isNightTheme, dirName).apply {
            if (!exists()) mkdirs()
        }
        val namedConfig = config.copy(themeName = normalizedName)
        val packagedConfig = copyAssetsIntoPackage(namedConfig, dir, config.isNightTheme)
        val pkg = Package(
            name = normalizedName,
            dirName = dirName,
            isNightTheme = config.isNightTheme,
            updatedAt = System.currentTimeMillis(),
            config = packagedConfig
        )
        File(dir, packageFileName).writeText(GSON.toJson(pkg))
        ThemeConfig.addConfig(resolveConfigPaths(pkg, dir))
        return Entry(pkg, Source.LOCAL, localDir = dir)
    }

    private fun uniqueDirName(isNightTheme: Boolean, preferred: String, themeName: String): String {
        val clean = preferred.ifBlank { builtinName(isNightTheme).normalizeFileName() }
        val existing = readPackage(localDir(isNightTheme, clean))
        if (existing == null || existing.name == themeName) return clean
        var index = 1
        while (true) {
            val candidate = "${clean}_$index"
            if (readPackage(localDir(isNightTheme, candidate)) == null) return candidate
            index++
        }
    }

    private fun saveConfigReplacingLocal(config: ThemeConfig.Config): Entry {
        val normalizedName = config.themeName.trim()
            .ifBlank { builtinName(config.isNightTheme) }
        val dirName = normalizedName.normalizeFileName()
        val dir = localDir(config.isNightTheme, dirName)
        if (dir.exists()) {
            FileUtils.delete(dir, deleteRootDir = true)
        }
        removeThemeConfig(config.isNightTheme, normalizedName, dirName)
        return saveConfig(config.copy(themeName = normalizedName))
    }

    private fun removeThemeConfig(isNightTheme: Boolean, themeName: String, dirName: String) {
        ThemeConfig.removePersistedConfig(isNightTheme, themeName, dirName)
    }

    private fun loadLocal(isNightTheme: Boolean): List<Entry> {
        val typeDir = typeDir(isNightTheme)
        return typeDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                readPackage(dir)?.let { pkg ->
                    Entry(pkg, Source.LOCAL, localDir = dir)
                }
            }.orEmpty()
    }

    private fun isBuiltinDuplicate(entry: Entry): Boolean {
        return isBuiltinPackage(entry.packageInfo)
    }

    private fun isBuiltinPackage(pkg: Package): Boolean {
        return isBuiltinTheme(pkg.isNightTheme, pkg.name) || pkg.dirName == builtinDirName(pkg.isNightTheme)
    }

    private fun isBuiltinTheme(isNightTheme: Boolean, name: String): Boolean {
        return name == builtinName(isNightTheme) ||
            name == if (isNightTheme) "Built-in night" else "Built-in day"
    }

    private fun builtinName(isNightTheme: Boolean): String {
        return if (isNightTheme) builtinNightName else builtinDayName
    }

    private fun builtinDirName(isNightTheme: Boolean): String {
        return if (isNightTheme) builtinNightDirName else builtinDayDirName
    }

    private fun sortEntries(entries: List<Entry>): List<Entry> {
        return entries.sortedWith(
            compareBy<Entry> { it.source != Source.BUILTIN }
                .thenBy { it.source == Source.REMOTE }
                .thenByDescending { if (it.source == Source.REMOTE) it.remoteUpdatedAt else it.packageInfo.updatedAt }
                .thenBy { it.packageInfo.name }
                .thenBy { it.dirName }
        )
    }

    private suspend fun loadRemote(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        return AppCloudStorage.listThemePackages(isNightTheme, containerId, scope).mapNotNull { remoteDir ->
            val rawName = remoteZipBaseName(remoteDir.displayName)
            val dirName = rawName.normalizeFileName().ifBlank {
                AppLog.put("跳过异常远端主题包: ${remoteDir.displayName}")
                return@mapNotNull null
            }
            Entry(
                packageInfo = Package(
                    name = rawName.ifBlank { dirName },
                    dirName = dirName,
                    isNightTheme = isNightTheme,
                    updatedAt = remoteDir.lastModify,
                    config = null
                ),
                source = Source.REMOTE,
                remoteUpdatedAt = remoteDir.lastModify
            )
        }.dedupeRemoteEntries()
    }

    private fun remoteZipBaseName(displayName: String): String {
        val name = displayName.trim().trimEnd('/').trim()
        return if (name.endsWith(".zip", ignoreCase = true)) {
            name.dropLast(4).trim()
        } else {
            name
        }
    }

    private fun List<Entry>.dedupeRemoteEntries(): List<Entry> {
        return groupBy { it.dirName }.values.mapNotNull { entries ->
            entries.maxByOrNull { it.remoteUpdatedAt }
        }
    }

    private suspend fun loadRemoteOrCache(isNightTheme: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        val cached = readRemoteCache(isNightTheme, containerId)
        return try {
            val remote = withTimeout(remoteListTimeoutMillis) {
                loadRemote(isNightTheme, containerId, scope)
            }
            if (remote.isNotEmpty() || cached.isEmpty()) {
                writeRemoteCache(isNightTheme, remote, containerId)
            }
            remote
        } catch (e: TimeoutCancellationException) {
            AppLog.put(
                "加载远端主题包列表超时: type=${AppCloudStorage.type}, container=${containerId.orEmpty()}, timeout=${remoteListTimeoutMillis}ms"
            )
            cached
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put(
                "加载远端主题包列表失败: type=${AppCloudStorage.type}, container=${containerId.orEmpty()}\n${e.localizedMessage}",
                e
            )
            cached
        }
    }

    private fun remoteCacheFile(isNightTheme: Boolean, containerId: String? = null): File {
        val mode = if (isNightTheme) "night" else "day"
        val suffix = containerId?.takeIf { it.isNotBlank() }?.normalizeFileName()?.let { "_$it" }.orEmpty()
        return remoteCacheDir.getFile("$mode$suffix.json")
    }

    private fun readRemoteCache(isNightTheme: Boolean, containerId: String? = null): List<Entry> {
        val file = remoteCacheFile(isNightTheme, containerId)
        if (!file.exists()) return emptyList()
        return GSON.fromJsonArray<Package>(file.readText()).getOrDefault(emptyList())
            .filter { it.isNightTheme == isNightTheme }
            .map { pkg ->
                Entry(pkg.copy(config = null), Source.REMOTE, remoteUpdatedAt = pkg.updatedAt)
            }
    }

    private fun writeRemoteCache(isNightTheme: Boolean, entries: List<Entry>, containerId: String? = null) {
        val packages = entries.map {
            it.packageInfo.copy(
                config = null,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.packageInfo.updatedAt
            )
        }
        remoteCacheFile(isNightTheme, containerId).writeTextIfChanged(GSON.toJson(packages))
    }

    private fun readPackage(dir: File): Package? {
        val file = File(dir, packageFileName)
        if (!file.exists()) return null
        return runCatching {
            GSON.fromJsonObject<Package>(file.readTextLimited(maxPackageManifestBytes)).getOrNull()
        }.getOrNull()
    }

    private fun peekPackage(zipFile: File): Package {
        val unzipDir = tempDir.getFile("peek_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            SafeZipExtractor.extract(zipFile, unzipDir, packageZipLimits) {
                it == packageFileName || it.endsWith("/$packageFileName")
            }
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
                ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
            GSON.fromJsonObject<Package>(packageFile.readTextLimited(maxPackageManifestBytes)).getOrThrow()
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private suspend fun importRedGzip(file: File): List<Entry> {
        val redPackage = readRedThemePackage(file)
        if (redPackage.type != "theme" || redPackage.data.isEmpty()) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        }
        val configs = redPackage.data.flatMap { item ->
            listOfNotNull(
                item.light
                    ?.takeIf { it.hasMeaningfulThemeContent(item.lightBackgroundImage) }
                    ?.toThemeConfig(item.name, false, item.lightBackgroundImage),
                item.dark
                    ?.takeIf { it.hasMeaningfulThemeContent(item.darkBackgroundImage) }
                    ?.toThemeConfig(item.name, true, item.darkBackgroundImage)
            )
        }
        if (configs.isEmpty()) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
        }
        return importMutex.withLock { configs.map(::saveConfigReplacingLocal) }
    }

    private fun detectRedPackageFormat(file: File): RedPackageFormat? {
        if (!file.isFile || file.length() < 2) return null
        return file.inputStream().use { input ->
            val header = ByteArray(8)
            val size = input.read(header)
            when {
                size >= 6 &&
                    header[0] == 'R'.code.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'D'.code.toByte() &&
                    header[3] == 4.toByte() &&
                    header[4] == 'P'.code.toByte() &&
                    header[5] == 'K'.code.toByte() -> RedPackageFormat.RED04_ZIP

                size >= 6 &&
                    header[0] == 'R'.code.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'D'.code.toByte() &&
                    header[4] == 'P'.code.toByte() &&
                    header[5] == 'K'.code.toByte() -> RedPackageFormat.RED_ASSET_ZIP

                size >= 6 &&
                    header[0] == 'R'.code.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'D'.code.toByte() &&
                    header[3] == 0x10.toByte() -> RedPackageFormat.RED10_PRIVATE

                size >= 6 &&
                    header[0] == 'R'.code.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'D'.code.toByte() &&
                    header[4] == 0x1F.toByte() &&
                    header[5] == 0x8B.toByte() -> RedPackageFormat.RED_GZIP_JSON

                size >= 2 &&
                    header[0] == 0x1F.toByte() &&
                    header[1] == 0x8B.toByte() -> RedPackageFormat.RAW_GZIP_JSON

                else -> null
            }
        }
    }

    private suspend fun importRedZipDetailed(file: File): ThemeImportResult {
        val zipFile = tempDir.getFile("red_${System.currentTimeMillis()}.zip")
        val unzipDir = tempDir.getFile("red_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(4)
                if (input.read(header) != header.size) {
                    throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
                }
                FileOutputStream(zipFile).use { output -> input.copyTo(output) }
            }
            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (entry.isDirectory) return@forEach
                    val target = File(unzipDir, entry.name)
                    if (!target.isSameOrSubFileOf(unzipDir)) {
                        throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    }
                }
            }
            val themeFile = File(unzipDir, packageFileName)
            val redTheme = GSON.fromJsonObject<RedThemeV4>(themeFile.readText()).getOrThrow()
            val configs = listOfNotNull(
                redTheme.light
                    ?.takeIf { it.hasMeaningfulThemeContent(File(unzipDir, "light/theme_bg.img").takeIf(File::isFile)?.absolutePath) }
                    ?.toThemeConfig(redTheme.name, false, unzipDir),
                redTheme.dark
                    ?.takeIf { it.hasMeaningfulThemeContent(File(unzipDir, "dark/theme_bg.img").takeIf(File::isFile)?.absolutePath) }
                    ?.toThemeConfig(redTheme.name, true, unzipDir)
            )
            val entries = importMutex.withLock { configs.map(::saveConfigReplacingLocal) }
            if (entries.isEmpty()) {
                throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
            }
            val dayNavigationBar = importRedNavigationPack(unzipDir, redTheme.name, redTheme.light, redTheme.dark, false)
            val nightNavigationBar = importRedNavigationPack(unzipDir, redTheme.name, redTheme.dark, redTheme.light, true)
            val navigationBars = listOfNotNull(
                dayNavigationBar ?: nightNavigationBar?.let {
                    NavigationBarIconConfig.copyEntryForMode(it, false)
                },
                nightNavigationBar ?: dayNavigationBar?.let {
                    NavigationBarIconConfig.copyEntryForMode(it, true)
                }
            )
            val coverCollections = listOfNotNull(
                importRedCoverGallery(unzipDir, redTheme.light, false),
                importRedCoverGallery(unzipDir, redTheme.dark, true)
            )
            copyRedReaderSchema(unzipDir, redTheme.light)
            copyRedReaderSchema(unzipDir, redTheme.dark)
            ThemeImportResult(
                sourceName = redTheme.name,
                themes = entries,
                navigationBars = navigationBars,
                coverCollections = coverCollections
            )
        } catch (e: Throwable) {
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid), e)
        } finally {
            zipFile.delete()
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun RedThemeColors.toThemeConfig(name: String, isNightTheme: Boolean, root: File): ThemeConfig.Config {
        val modeDir = if (isNightTheme) "dark" else "light"
        val background = File(root, "$modeDir/theme_bg.img")
            .takeIf { it.isFile }
            ?.absolutePath
        val panelBackground = resolveRedCardBackground(root)
        return toThemeConfig(name, isNightTheme).copy(
            backgroundImgPath = background,
            panelBackgroundImgPath = panelBackground,
            panelBackgroundScaleType = ThemeConfig.PANEL_BG_CROP,
            panelBorderColor = borderColor.normalizeArgbColorOrNull(),
            panelBorderAlpha = if (borderColor.isBlank()) null else 100,
            uiLayoutAlpha = cardColor.alphaPercentOrNull() ?: 100,
            dialogAlpha = 100
        )
    }

    private fun importRedNavigationPack(
        root: File,
        fallbackName: String,
        colors: RedThemeColors?,
        fallbackColors: RedThemeColors?,
        isNightTheme: Boolean
    ): NavigationBarIconConfig.Entry? {
        val navigationColors = colors?.takeIf { it.navbarPackId.isNotBlank() }
            ?: fallbackColors?.takeIf { it.navbarPackId.isNotBlank() }
            ?: return null
        val navbarRoot = File(root, "navbar_pack").takeIf { it.isDirectory } ?: return null
        val packId = navigationColors.navbarPackId
        val sourceDir = File(navbarRoot, packId).takeIf { it.isDirectory }
            ?: navbarRoot.takeIf { dir -> dir.listFiles()?.any { it.isFile } == true }
            ?: navbarRoot.listFiles()?.firstOrNull { it.isDirectory }
            ?: return null
        return NavigationBarIconConfig.importRedNavigationDirectory(
            sourceDir = sourceDir,
            fallbackName = fallbackName,
            isNightMode = isNightTheme,
            opacity = navigationColors.navigationBarOpacityPercent()
        )
    }

    private suspend fun importRedCoverGallery(
        root: File,
        colors: RedThemeColors?,
        isNightTheme: Boolean
    ): CoverCollectionManager.Collection? {
        val galleryId = colors?.coverGalleryId?.takeIf { it.isNotBlank() } ?: return null
        val sourceDir = File(root, "cover_gallery/$galleryId").takeIf { it.isDirectory } ?: return null
        val meta = File(sourceDir, "meta.json")
            .takeIf { it.isFile }
            ?.let { GSON.fromJsonObject<RedNameMeta>(it.readText()).getOrNull() }
        val packageDir = tempDir.getFile("red_cover_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val imagesDir = packageDir.getFile("images").apply { mkdirs() }
        val zipFile = tempDir.getFile("red_cover_${System.currentTimeMillis()}.zip")
        try {
            val images = sourceDir.listFiles()
                ?.filter { it.isFile && it.name != "meta.json" }
                ?.sortedBy { it.name }
                ?.mapIndexedNotNull { index, source ->
                    val target = File(imagesDir, "cover_${index + 1}.${source.extension.ifBlank { "png" }}")
                    source.copyTo(target, overwrite = true)
                    "images/${target.name}"
                }
                .orEmpty()
            if (images.isEmpty()) return null
            val collection = CoverCollectionManager.Collection(
                id = UUID.randomUUID().toString(),
                name = meta?.name?.ifBlank { null } ?: "RED Cover Gallery",
                dirName = (meta?.name?.ifBlank { null } ?: "RED Cover Gallery").normalizeFileName(),
                isNight = isNightTheme,
                images = images,
                updatedAt = System.currentTimeMillis()
            )
            File(packageDir, "collection.json").writeText(GSON.toJson(collection))
            ZipUtils.zipFile(packageDir, zipFile)
            return CoverCollectionManager.importZip(appCtx, zipFile, isNightTheme)
        } finally {
            zipFile.delete()
            FileUtils.delete(packageDir, deleteRootDir = true)
        }
    }

    private fun copyRedReaderSchema(root: File, light: RedThemeColors?) {
        val schemaId = light?.readerColorSchemaId?.takeIf { it.isNotBlank() } ?: return
        val sourceDir = File(root, "reader_schema/$schemaId").takeIf { it.isDirectory } ?: return
        val targetDir = rootDir.getFile(redReaderSchemaDirName).getFile(schemaId).apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        sourceDir.copyRecursively(targetDir, overwrite = true)
    }

    private fun String.alphaPercentOrNull(): Int? {
        val value = trim().removePrefix("#")
        if (value.length != 8) return null
        return value.take(2).toIntOrNull(16)
            ?.let { (it * 100f / 255f).toInt().coerceIn(0, 100) }
    }

    private fun RedThemeColors.navigationBarOpacityPercent(): Int {
        return cardColor.alphaPercentOrNull()
            ?.takeIf { it >= 16 }
            ?: 72
    }

    private fun RedThemeColors.hasMeaningfulThemeContent(backgroundPayload: String? = null): Boolean {
        return listOf(
            primaryColor,
            backgroundColor,
            foregroundColor,
            mutedForegroundColor,
            cardColor,
            cardForegroundColor,
            mutedColor,
            borderColor,
            accentColor,
            backgroundImage,
            backgroundPayload.orEmpty(),
            cardBackgroundImage,
            coverGalleryId,
            navbarPackId,
            readerColorSchemaId,
            searchFieldBackgroundColor,
            tabBackgroundColor,
            shelfColor
        ).any { it.isNotBlank() } ||
            cardShadow != null ||
            cardBackgroundBlur != null ||
            cardBorder ||
            switchBorder ||
            tabBorder ||
            searchFieldBorder
    }

    private fun RedThemeColors.resolveRedCardBackground(root: File): String? {
        val relativePath = cardBackgroundImage.takeIf { it.isNotBlank() } ?: return null
        val direct = File(root, relativePath)
        if (direct.isFile) return direct.absolutePath
        val fileName = relativePath.substringAfterLast('/')
        return root.walkTopDown().firstOrNull { it.isFile && it.name == fileName }?.absolutePath
    }

    private fun writeInlineRedImage(encoded: String, prefix: String, dir: File): String? {
        if (encoded.isBlank()) return null
        return runCatching {
            if (encoded.length.toLong() * 3L / 4L > MAX_DATA_URL_BYTES) return@runCatching null
            val decoded = Base64.decode(encoded, Base64.DEFAULT)
            if (decoded.size > MAX_DATA_URL_BYTES) return@runCatching null
            val bytes = if (decoded.size >= 2 && decoded[0] == 0x1F.toByte() && decoded[1] == 0x8B.toByte()) {
                GZIPInputStream(ByteArrayInputStream(decoded)).use {
                    it.readBytesLimited(MAX_DATA_URL_BYTES.toLong())
                }
            } else {
                decoded
            }
            val suffix = detectImageSuffix(bytes)
            val target = dir.getFile("${prefix.normalizeFileName()}$suffix")
            FileOutputStream(target).use { it.write(bytes) }
            target.absolutePath
        }.getOrNull()
    }

    private fun detectImageSuffix(bytes: ByteArray): String {
        imageMagic.forEach { (magic, suffix) ->
            if (bytes.size >= magic.size && magic.indices.all { bytes[it] == magic[it] }) {
                return suffix
            }
        }
        return ".img"
    }

    private fun readRedThemePackage(file: File): RedThemePackage {
        val format = detectRedPackageFormat(file)
        return runCatching {
            file.inputStream().use { input ->
                when (format) {
                    RedPackageFormat.RED_GZIP_JSON -> {
                        val header = ByteArray(4)
                        if (input.read(header) != header.size) {
                            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
                        }
                    }

                    RedPackageFormat.RAW_GZIP_JSON -> Unit
                    else -> throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid))
                }
                GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { reader ->
                    GSON.fromJsonObject<RedThemePackage>(reader.readText()).getOrThrow()
                }
            }
        }.getOrElse {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_red_invalid), it)
        }
    }

    private fun RedThemeColors.toThemeConfig(
        name: String,
        isNightTheme: Boolean,
        inlineBackground: String? = null
    ): ThemeConfig.Config {
        val backgroundPath = inlineBackground?.let {
            writeInlineRedImage(
                encoded = it,
                prefix = "${name}_${if (isNightTheme) "night" else "day"}_background",
                dir = tempDir
            )
        }
        val panelPath = cardBackgroundImage.takeIf { it.isNotBlank() }?.let {
            resolveRedCardBackground(tempDir)
        }
        return ThemeConfig.Config(
            themeName = name.trim().ifBlank { "RED Theme" },
            isNightTheme = isNightTheme,
            primaryColor = primaryColor.normalizeArgbColor(default = if (isNightTheme) "#FFD97757" else "#FFD97757"),
            accentColor = accentColor.normalizeArgbColor(default = primaryColor.ifBlank { "#FFD97757" }),
            backgroundColor = backgroundColor.normalizeArgbColor(default = if (isNightTheme) "#FF1C1917" else "#FFFAF9F6"),
            bottomBackground = cardColor
                .ifBlank { mutedColor }
                .normalizeArgbColor(default = if (isNightTheme) "#FF312E2B" else "#FFEEEBE3"),
            transparentNavBar = false,
            backgroundImgPath = backgroundPath,
            backgroundImgBlur = 0,
            bookInfoBackgroundImgPath = null,
            panelBackgroundImgPath = panelPath,
            panelBackgroundScaleType = ThemeConfig.PANEL_BG_CROP,
            panelBorderColor = borderColor.normalizeArgbColorOrNull(),
            panelBorderAlpha = if (cardBorder || switchBorder) 100 else 0,
            uiCornerScale = cardShadow?.let { (1f + it.coerceIn(0, 4) * 0.08f).coerceIn(0f, 3f) } ?: 1f,
            uiLayoutAlpha = cardColor.alphaPercentOrNull() ?: 100,
            dialogAlpha = 100,
            cardColor = cardColor.normalizeArgbColorOrNull(),
            mutedColor = mutedColor.normalizeArgbColorOrNull(),
            searchFieldBackgroundColor = searchFieldBackgroundColor.normalizeArgbColorOrNull(),
            tabBackgroundColor = tabBackgroundColor.normalizeArgbColorOrNull(),
            shelfColor = shelfColor.normalizeArgbColorOrNull(),
            cardShadow = cardShadow?.coerceIn(0, 24),
            cardBackgroundBlur = cardBackgroundBlur?.coerceIn(0f, 25f),
            uiCornerSearchFollow = true,
            uiCornerReplyFollow = true,
            fontScale = null,
            uiFontPath = null,
            titleFontPath = null
        )
    }

    private fun String.normalizeArgbColor(default: String): String {
        return normalizeArgbColorOrNull() ?: default
    }

    private fun String.normalizeArgbColorOrNull(): String? {
        val value = trim()
        if (!value.startsWith("#")) return null
        val hex = value.drop(1)
        return when (hex.length) {
            6 -> "#FF${hex.uppercase()}"
            8 -> "#${hex.uppercase()}"
            else -> null
        }
    }

    private suspend fun importZipInternal(zipFile: File, remoteUpdatedAt: Long): Entry = importMutex.withLock {
        val unzipDir = tempDir.getFile("import_${UUID.randomUUID()}").apply {
            check(mkdirs()) { "failed to create ${absolutePath}" }
        }
        try {
            SafeZipExtractor.extract(zipFile, unzipDir, packageZipLimits)
            val packageFiles = unzipDir.walkTopDown()
                .filter { it.isFile && it.name == packageFileName }
                .toList()
            if (packageFiles.isEmpty()) {
                throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
            }
            require(packageFiles.size == 1) { "theme package contains multiple $packageFileName files" }
            val packageFile = packageFiles.single()
            val pkg = GSON.fromJsonObject<Package>(packageFile.readTextLimited(maxPackageManifestBytes)).getOrThrow()
            val dirName = safeImportedDirName(pkg)
            val parentDir = typeDir(pkg.isNightTheme).canonicalFile
            val targetDir = File(parentDir, dirName).canonicalFile
            require(targetDir.parentFile == parentDir) { "theme directory escapes package root" }
            val stagingDir = File(parentDir, ".$dirName.staging-${UUID.randomUUID()}")
            val backupDir = File(parentDir, ".$dirName.backup-${UUID.randomUUID()}")
            val sourceDir = requireNotNull(packageFile.parentFile)
            val oldPackage = readPackage(targetDir)

            try {
                check(sourceDir.copyRecursively(stagingDir, overwrite = false)) {
                    "failed to stage imported theme"
                }
                val stagedPackage = readPackage(stagingDir)
                    ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
                val targetPackage = stagedPackage.copy(
                    dirName = dirName,
                    updatedAt = if (remoteUpdatedAt == 0L) System.currentTimeMillis() else stagedPackage.updatedAt
                )
                File(stagingDir, packageFileName).writeText(GSON.toJson(targetPackage))
                val verifiedPackage = readPackage(stagingDir)
                    ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
                require(verifiedPackage.dirName == dirName && verifiedPackage.isNightTheme == pkg.isNightTheme) {
                    "staged theme identity mismatch"
                }

                installStagedTheme(targetDir, stagingDir, backupDir) { installedDir ->
                    val installedPackage = readPackage(installedDir)
                        ?: throw IllegalStateException("installed theme package is unreadable")
                    ThemeConfig.replaceImportedConfig(
                        newConfig = resolveConfigPaths(installedPackage, installedDir),
                        replacedThemeName = oldPackage?.name,
                        replacedDirName = dirName
                    )
                    Entry(
                        installedPackage,
                        Source.LOCAL,
                        localDir = installedDir,
                        remoteUpdatedAt = remoteUpdatedAt
                    )
                }
            } finally {
                deletePathBestEffort(stagingDir)
            }
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun safeImportedDirName(pkg: Package): String {
        val dirName = pkg.dirName.ifBlank { pkg.name.normalizeFileName() }.trim()
        require(dirName.isNotEmpty() && dirName != "." && dirName != "..") { "invalid theme directory" }
        require(!File(dirName).isAbsolute && '/' !in dirName && '\\' !in dirName) {
            "theme directory must be a single path segment"
        }
        return dirName
    }

    private fun <T> installStagedTheme(
        targetDir: File,
        stagingDir: File,
        backupDir: File,
        afterInstall: (File) -> T
    ): T {
        var backupCreated = false
        try {
            if (targetDir.exists()) {
                NioFileExchange.move(targetDir, backupDir)
                backupCreated = true
            }
            try {
                NioFileExchange.move(stagingDir, targetDir)
            } catch (installError: Exception) {
                if (backupCreated) {
                    restoreThemeBackup(backupDir, targetDir, installError)
                }
                throw installError
            }

            val result = try {
                afterInstall(targetDir)
            } catch (commitError: Exception) {
                rollbackInstalledTheme(targetDir, backupDir.takeIf { backupCreated }, commitError)
                throw commitError
            }
            if (backupCreated) {
                deletePathBestEffort(backupDir)
            }
            return result
        } finally {
            deletePathBestEffort(stagingDir)
        }
    }

    private fun restoreThemeBackup(backupDir: File, targetDir: File, originalError: Exception) {
        try {
            NioFileExchange.move(backupDir, targetDir)
        } catch (restoreError: Exception) {
            originalError.addSuppressed(restoreError)
            throw ThemeDirectoryRestoreException(backupDir, originalError)
        }
    }

    private fun rollbackInstalledTheme(targetDir: File, backupDir: File?, originalError: Exception) {
        if (backupDir == null) {
            if (!targetDir.deleteRecursively() && targetDir.exists()) {
                throw IOException("failed to remove newly installed theme ${targetDir.absolutePath}", originalError)
            }
            return
        }
        val failedDir = File(targetDir.parentFile, ".${targetDir.name}.failed-${UUID.randomUUID()}")
        try {
            if (targetDir.exists()) {
                NioFileExchange.move(targetDir, failedDir)
            }
            NioFileExchange.move(backupDir, targetDir)
            deletePathBestEffort(failedDir)
        } catch (restoreError: Exception) {
            originalError.addSuppressed(restoreError)
            throw ThemeDirectoryRestoreException(backupDir, originalError)
        }
    }

    private fun deletePathBestEffort(file: File) {
        runCatching {
            if (file.exists() && !file.deleteRecursively()) {
                throw IOException("failed to delete ${file.absolutePath}")
            }
        }.onFailure {
            AppLog.put("theme transaction cleanup failed: ${file.absolutePath}", it)
        }
    }

    private class ThemeDirectoryRestoreException(
        val backupDir: File,
        cause: Throwable
    ) : IOException("failed to restore theme; backup kept at ${backupDir.absolutePath}", cause)

    private fun copyAssetsIntoPackage(
        config: ThemeConfig.Config,
        dir: File,
        isNightTheme: Boolean
    ): ThemeConfig.Config {
        val background = copyAsset(config.backgroundImgPath, dir, mainBackgroundPrefix)
        val bookInfo = copyAsset(
            config.bookInfoBackgroundImgPath
                ?: appCtx.getPrefString(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage),
            dir,
            bookInfoBackgroundPrefix
        )
        val panelBackground = copyAsset(
            config.panelBackgroundImgPath
                ?: appCtx.getPrefString(if (isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage),
            dir,
            panelBackgroundPrefix
        )
        val uiFont = copyAsset(config.uiFontPath, dir, uiFontPrefix, keepOriginalName = true)
        val titleFont = copyAsset(config.titleFontPath, dir, titleFontPrefix, keepOriginalName = true)
        return config.copy(
            backgroundImgPath = background,
            bookInfoBackgroundImgPath = bookInfo,
            panelBackgroundImgPath = panelBackground,
            uiFontPath = uiFont,
            titleFontPath = titleFont
        )
    }

    private fun copyAsset(
        path: String?,
        dir: File,
        prefix: String,
        keepOriginalName: Boolean = false
    ): String? {
        if (path.isNullOrBlank()) {
            deletePackagedAssets(dir, prefix)
            return path
        }
        if (path.startsWith("http", ignoreCase = true)) {
            deletePackagedAssets(dir, prefix)
            return path
        }
        if (path.startsWith("content://", ignoreCase = true)) {
            return runCatching {
                val uri = Uri.parse(path)
                val name = DocumentFile.fromSingleUri(appCtx, uri)?.name.orEmpty()
                val target = File(dir, packageAssetName(prefix, name, keepOriginalName))
                appCtx.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                } ?: return path
                deletePackagedAssets(dir, prefix, target)
                target.name
            }.getOrDefault(path)
        }
        val source = File(path)
        if (!source.exists()) {
            return findPackagedAssetByPrefix(dir, prefix)?.name ?: path
        }
        if (source.parentFile?.canonicalFile == dir.canonicalFile && source.name.startsWith(prefix)) {
            deletePackagedAssets(dir, prefix, source)
            return source.name
        }
        val target = File(dir, packageAssetName(prefix, source.name, keepOriginalName))
        if (source.canonicalFile == target.canonicalFile) {
            deletePackagedAssets(dir, prefix, target)
            return target.name
        }
        source.copyTo(target, overwrite = true)
        deletePackagedAssets(dir, prefix, target)
        return target.name
    }

    private fun deletePackagedAssets(dir: File, prefix: String, keepFile: File? = null) {
        val keepCanonical = keepFile?.takeIf { it.exists() }?.canonicalFile
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(prefix) }
            ?.filter { keepCanonical == null || it.canonicalFile != keepCanonical }
            ?.forEach { it.delete() }
    }

    private fun findPackagedAssetByPrefix(dir: File, prefix: String): File? {
        return dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith(prefix) }
    }

    private fun packageAssetName(prefix: String, sourceName: String, keepOriginalName: Boolean): String {
        val sourceFile = File(sourceName)
        val suffix = ImageTypeUtils.preferredRasterExtension(sourceFile.takeIf { it.isFile }, "")
            .ifBlank { sourceName.substringAfterLast('.', "") }
            .takeIf { it.isNotBlank() }
            ?.let { ".$it" }
            .orEmpty()
        if (!keepOriginalName) {
            return "$prefix$suffix"
        }
        val normalizedName = sourceName.normalizeFileName()
        if (normalizedName.startsWith("$prefix.")) {
            return "$prefix$suffix"
        }
        val cleanName = normalizedName.removePrefix("${prefix}_")
        return if (cleanName.isBlank()) {
            "$prefix$suffix"
        } else {
            "${prefix}_${cleanName}"
        }
    }

    private suspend fun validatedConfig(entry: Entry): ThemeConfig.Config {
        val dir = entry.localDir ?: localDir(entry.packageInfo.isNightTheme, entry.dirName)
        if (entry.source != Source.BUILTIN && !File(dir, packageFileName).isFile) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_config_file_missing))
        }
        val config = resolveConfigPaths(entry.packageInfo, dir)
        return config.copy(
            backgroundImgPath = localResourcePath(config.backgroundImgPath, backgroundDir(config.isNightTheme)),
            bookInfoBackgroundImgPath = localResourcePath(
                config.bookInfoBackgroundImgPath,
                bookInfoBackgroundDir(config.isNightTheme)
            ),
            panelBackgroundImgPath = localResourcePath(config.panelBackgroundImgPath, panelBackgroundDir(config.isNightTheme)),
            uiFontPath = localResourcePath(config.uiFontPath),
            titleFontPath = localResourcePath(config.titleFontPath)
        )
    }

    private suspend fun localResourcePath(path: String?, remoteCacheDir: File? = null): String? {
        if (path.isNullOrBlank()) return path
        if (path.startsWith("http", ignoreCase = true)) {
            val cacheDir = remoteCacheDir ?: throw IllegalArgumentException(appCtx.getString(R.string.theme_resource_not_local))
            return downloadRemoteResource(path, cacheDir).absolutePath
        }
        val file = File(path)
        if (!isReadableOwnFile(file)) {
            throw IllegalArgumentException(appCtx.getString(R.string.theme_resource_not_readable, file.name.ifBlank { path }))
        }
        return path
    }

    private suspend fun downloadRemoteResource(path: String, dir: File): File {
        dir.mkdirs()
        val target = File(dir, remoteResourceName(path))
        if (isReadableOwnFile(target)) return target
        val staging = File(dir, ".${target.name}.${UUID.randomUUID()}.part")
        try {
            withTimeout(60_000L) {
                okHttpClient.newCallResponse(0) {
                    url(path)
                }.use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalArgumentException(appCtx.getString(R.string.theme_resource_download_failed, response.code))
                    }
                    val declaredLength = response.body.contentLength()
                    if (declaredLength > maxRemoteResourceBytes) {
                        throw IOException("theme resource is too large")
                    }
                    response.body.byteStream().use { input ->
                        FileOutputStream(staging).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                total += count.toLong()
                                if (total > maxRemoteResourceBytes) {
                                    throw IOException("theme resource is too large")
                                }
                                output.write(buffer, 0, count)
                            }
                            check(total > 0L) { "theme resource is empty" }
                        }
                    }
                }
            }
            if (isReadableOwnFile(target)) return target
            check(staging.renameTo(target)) { "failed to store theme resource" }
            return target
        } finally {
            staging.delete()
        }
    }

    private fun remoteResourceName(path: String): String {
        val cleanPath = path.substringBefore('?').substringBefore('#')
        val suffix = cleanPath.substringAfterLast('/', "")
            .substringAfterLast('.', "")
            .takeIf { it.isNotBlank() && it.length <= 8 }
            ?.let { ".$it" }
            .orEmpty()
        return Integer.toHexString(path.hashCode()) + suffix
    }

    private fun resolveConfigPaths(pkg: Package, dir: File): ThemeConfig.Config {
        val config = pkg.config ?: ThemeConfig.Config(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            primaryColor = "#795548",
            accentColor = "#E53935",
            backgroundColor = if (pkg.isNightTheme) "#212121" else "#F5F5F5",
            bottomBackground = if (pkg.isNightTheme) "#303030" else "#EEEEEE",
            transparentNavBar = true,
            backgroundImgPath = null,
            backgroundImgBlur = 0
        )
        return config.copy(
            themeName = pkg.name,
            isNightTheme = pkg.isNightTheme,
            backgroundImgPath = resolvePath(config.backgroundImgPath, dir),
            bookInfoBackgroundImgPath = resolvePath(config.bookInfoBackgroundImgPath, dir),
            panelBackgroundImgPath = resolvePath(config.panelBackgroundImgPath, dir),
            uiFontPath = resolvePath(config.uiFontPath, dir),
            titleFontPath = resolvePath(config.titleFontPath, dir)
        )
    }

    private fun resolvePath(path: String?, dir: File): String? {
        if (path.isNullOrBlank() || path.startsWith("http", ignoreCase = true)) return path
        val file = File(path)
        if (file.isAbsolute) {
            if (isReadableOwnFile(file)) return path
            findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
            findPackagedAssetByPrefix(dir, file.name.substringBeforeLast('.', file.name))?.let {
                return it.absolutePath
            }
            return null
        }
        val packagedFile = File(dir, path).canonicalFile
        if (!packagedFile.isSameOrSubFileOf(dir)) return null
        if (isReadableOwnFile(packagedFile)) return packagedFile.absolutePath
        findPackagedAsset(dir, file.name)?.let { return it.absolutePath }
        return packagedFile.absolutePath
    }

    private fun isReadableOwnFile(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (isOtherAppExternalDataPath(file.absolutePath)) return false
        return runCatching {
            FileInputStream(file).use { true }
        }.getOrDefault(false)
    }

    private fun isOtherAppExternalDataPath(path: String): Boolean {
        val marker = "/Android/data/"
        val normalized = path.replace('\\', '/')
        val start = normalized.indexOf(marker, ignoreCase = true)
        if (start < 0) return false
        val packageStart = start + marker.length
        val packageEnd = normalized.indexOf('/', packageStart).takeIf { it >= 0 } ?: normalized.length
        val ownerPackage = normalized.substring(packageStart, packageEnd)
        return ownerPackage.isNotBlank() && ownerPackage != appCtx.packageName
    }
    private fun backgroundDir(isNightTheme: Boolean): File {
        return appCtx.externalFiles.getFile(if (isNightTheme) PreferKey.bgImageN else PreferKey.bgImage)
    }

    private fun bookInfoBackgroundDir(isNightTheme: Boolean): File {
        return appCtx.externalFiles.getFile(if (isNightTheme) PreferKey.bookInfoBgImageN else PreferKey.bookInfoBgImage)
    }

    private fun panelBackgroundDir(isNightTheme: Boolean): File {
        return appCtx.externalFiles.getFile(if (isNightTheme) PreferKey.panelBgImageN else PreferKey.panelBgImage)
    }

    private fun findPackagedAsset(dir: File, fileName: String): File? {
        if (fileName.isBlank()) return null
        val lowerName = fileName.lowercase()
        return dir.walkTopDown().firstOrNull { file ->
            file.isFile && file.name.lowercase() == lowerName
        }
    }

    fun localDir(isNightTheme: Boolean, dirName: String): File {
        return typeDir(isNightTheme).getFile(dirName)
    }

    private val tempDir: File
        get() = rootDir.getFile("temp").apply {
            if (!exists()) mkdirs()
        }

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply {
            if (!exists()) mkdirs()
        }

    private fun typeDir(isNightTheme: Boolean): File {
        return rootDir.getFile(if (isNightTheme) "night" else "day").apply {
            if (!exists()) mkdirs()
        }
    }

    data class Entry(
        val packageInfo: Package,
        val source: Source,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    ) {
        val dirName: String get() = packageInfo.dirName
    }

    data class ThemeImportResult(
        val sourceName: String = "",
        val themes: List<Entry> = emptyList(),
        val navigationBars: List<NavigationBarIconConfig.Entry> = emptyList(),
        val coverCollections: List<CoverCollectionManager.Collection> = emptyList()
    )

    @Keep
    data class Package(
        val name: String,
        val dirName: String,
        val isNightTheme: Boolean,
        val updatedAt: Long,
        val config: ThemeConfig.Config?
    )

    @Keep
    private data class RedThemeV4(
        val name: String = "",
        val light: RedThemeColors? = null,
        val dark: RedThemeColors? = null
    )

    @Keep
    private data class RedNameMeta(
        val name: String = ""
    )

    @Keep
    private data class RedThemePackage(
        val version: Int = 1,
        val type: String = "",
        val data: List<RedThemeItem> = emptyList()
    )

    @Keep
    private data class RedThemeItem(
        val name: String = "",
        val light: RedThemeColors? = null,
        val dark: RedThemeColors? = null,
        val lightBackgroundImage: String = "",
        val darkBackgroundImage: String = ""
    )

    @Keep
    private data class RedThemeColors(
        val primaryColor: String = "",
        val backgroundColor: String = "",
        val foregroundColor: String = "",
        val mutedForegroundColor: String = "",
        val cardColor: String = "",
        val cardForegroundColor: String = "",
        val cardBackgroundImage: String = "",
        val mutedColor: String = "",
        val borderColor: String = "",
        val accentColor: String = "",
        val backgroundImage: String = "",
        val backgroundImageFit: String = "",
        val backgroundImageOpacity: Float? = null,
        val coverGalleryId: String = "",
        val navbarPackId: String = "",
        val readerColorSchemaId: String = "",
        val cardShadow: Int? = null,
        val cardBackgroundBlur: Float? = null,
        val cardBorder: Boolean = false,
        val searchFieldBorder: Boolean = false,
        val searchFieldBackgroundColor: String = "",
        val switchBorder: Boolean = false,
        val tabBorder: Boolean = false,
        val tabBackgroundColor: String = "",
        val shelfColor: String = ""
    )

    private enum class RedPackageFormat {
        RED04_ZIP,
        RED_ASSET_ZIP,
        RED10_PRIVATE,
        RED_GZIP_JSON,
        RAW_GZIP_JSON
    }

    enum class Source {
        BUILTIN,
        LOCAL,
        REMOTE,
        BOTH
    }
}