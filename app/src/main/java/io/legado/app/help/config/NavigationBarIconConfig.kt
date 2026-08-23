package io.legado.app.help.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.view.Menu
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.bottomBackground
import io.legado.app.lib.theme.getSecondaryTextColor
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.SvgUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.getPrefString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.min

object NavigationBarIconConfig {

    const val MODE_DAY = "day"
    const val MODE_NIGHT = "night"
    const val STATE_NORMAL = "normal"
    const val STATE_SELECTED = "selected"
    const val EXTRA_SEARCH = "search"
    const val DEFAULT_DIR_NAME = "default"
    private const val packageFileName = "navigation.json"
    private const val remoteListTimeoutMillis = 4_000L
    private const val activeDayKey = PreferKey.navigationBarPackageDay
    private const val activeNightKey = PreferKey.navigationBarPackageNight
    private const val legacyMigratedDayKey = "navigationBarLegacyMigratedDay"
    private const val legacyMigratedNightKey = "navigationBarLegacyMigratedNight"
    private const val maxIconBitmapCacheSize = 24
    private const val redImportIconSize = 192
    private val redSvgXmlDeclarationRegex = Regex("^\\s*<\\?xml[^>]*\\?>", RegexOption.IGNORE_CASE)
    private val redSvgDoctypeRegex = Regex(
        "<!DOCTYPE\\s+svg\\b[^>]*>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )

    private var currentDayEntryCache: CachedEntry? = null
    private var currentNightEntryCache: CachedEntry? = null
    private val iconBitmapCache = object : LinkedHashMap<String, Bitmap>(maxIconBitmapCacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>): Boolean {
            return size > maxIconBitmapCacheSize
        }
    }

    val rootDir: File
        get() = appCtx.externalFiles.getFile("navigationBarPackages")

    private val legacyRootDir: File
        get() = appCtx.externalFiles.getFile("navigationIcons")

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("navigationBarTemp").apply { mkdirs() }

    private val remoteCacheDir: File
        get() = rootDir.getFile("remote_cache").apply { mkdirs() }

    data class NavItem(
        val key: String,
        @StringRes val titleRes: Int,
        @IdRes val menuId: Int,
        @DrawableRes val defaultIconRes: Int
    )

    @Keep
    data class Config(
        var name: String,
        var isNightMode: Boolean,
        var layoutMode: String = "floating",
        var sidebarGravity: String = "start",
        var effectMode: String = "glass",
        var opacity: Int = 72,
        var updatedAt: Long = System.currentTimeMillis(),
        var sidebarBackgroundPath: String? = null,
        var wallpaperPath: String? = null,
        var borderColor: Int? = null,
        var borderAlpha: Int = 100,
        var hideSearchInFloatingStyle: Boolean = false,
        var icons: MutableMap<String, String> = linkedMapOf()
    )

    data class Entry(
        val config: Config,
        val source: Source,
        val dirName: String,
        val localDir: File? = null,
        val remoteUpdatedAt: Long = 0L
    )

    private data class CachedEntry(
        val dirName: String,
        val lastModified: Long,
        val entry: Entry
    )

    enum class Source { BUILTIN, LOCAL, REMOTE, BOTH }

    @Keep
    private data class RemoteCache(
        val name: String,
        val dirName: String,
        val isNightMode: Boolean,
        val updatedAt: Long
    )

    val items = listOf(
        NavItem("bookshelf", R.string.bookshelf, R.id.menu_bookshelf, R.drawable.ic_bottom_books),
        NavItem("discovery", R.string.discovery, R.id.menu_discovery, R.drawable.ic_bottom_explore),
        NavItem("rss", R.string.rss, R.id.menu_rss, R.drawable.ic_bottom_rss_feed),
        NavItem("readRecord", R.string.side_nav_stats, R.id.menu_read_record, R.drawable.ic_bottom_read_record),
        NavItem("my", R.string.my, R.id.menu_my_config, R.drawable.ic_bottom_person),
        NavItem("ai", R.string.side_nav_assistant, R.id.menu_ai, R.drawable.ic_bottom_ai_assistant)
    )

    val extraItems = listOf(
        NavItem(EXTRA_SEARCH, R.string.navigation_icon_search_ball, 0, R.drawable.ic_search)
    )

    fun activeDirName(isNight: Boolean): String {
        return appCtx.getPrefString(if (isNight) activeNightKey else activeDayKey, DEFAULT_DIR_NAME)
            ?.ifBlank { DEFAULT_DIR_NAME }
            ?: DEFAULT_DIR_NAME
    }

    suspend fun loadEntries(isNight: Boolean, includeRemote: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        migrateLegacyIconsIfNeeded(isNight)
        val local = loadLocal(isNight).associateBy { it.dirName }
        val remote = if (includeRemote) {
            loadRemoteOrCache(isNight, containerId, scope).associateBy { it.dirName }
        } else {
            emptyMap()
        }
        val entries = linkedMapOf<String, Entry>()
        entries[DEFAULT_DIR_NAME] = defaultEntry(isNight)
        (local.keys + remote.keys).forEach { key ->
            val localEntry = local[key]
            val remoteEntry = remote[key]
            entries[key] = when {
                localEntry != null && remoteEntry != null -> localEntry.copy(
                    source = Source.BOTH,
                    remoteUpdatedAt = remoteEntry.remoteUpdatedAt
                )
                localEntry != null -> localEntry
                remoteEntry != null -> remoteEntry
                else -> return@forEach
            }
        }
        return entries.values.sortedWith(
            compareBy<Entry> { it.dirName != DEFAULT_DIR_NAME }
                .thenBy { it.source == Source.REMOTE }
                .thenByDescending { if (it.source == Source.REMOTE) it.remoteUpdatedAt else it.config.updatedAt }
                .thenBy { it.config.name }
                .thenBy { it.dirName }
        )
    }

    fun currentEntry(isNight: Boolean): Entry {
        val dirName = activeDirName(isNight)
        if (dirName == DEFAULT_DIR_NAME) return defaultEntry(isNight)
        val dir = localDir(isNight, dirName)
        val configFile = File(dir, packageFileName)
        val lastModified = configFile.lastModified()
        val cached = if (isNight) currentNightEntryCache else currentDayEntryCache
        if (cached?.dirName == dirName && cached.lastModified == lastModified) {
            return cached.entry
        }
        val entry = readEntry(dir) ?: defaultEntry(isNight)
        val next = CachedEntry(dirName, lastModified, entry)
        if (isNight) {
            currentNightEntryCache = next
        } else {
            currentDayEntryCache = next
        }
        return entry
    }

    fun currentSignature(isNight: Boolean): String {
        val dirName = activeDirName(isNight)
        if (dirName == DEFAULT_DIR_NAME) {
            return "$isNight|$DEFAULT_DIR_NAME|${MainLayoutPresetConfig.defaultBottomLayoutMode()}|${MainLayoutPresetConfig.floatingBottomBarHideSearch()}"
        }
        val configFile = File(localDir(isNight, dirName), packageFileName)
        return "$isNight|$dirName|${configFile.lastModified()}"
    }

    fun apply(entry: Entry) {
        val config = normalizeConfig(entry.config)
        select(entry)
        AppConfig.bottomBarLayoutMode = config.layoutMode
        AppConfig.bottomBarSidebarGravity = config.sidebarGravity
        AppConfig.bottomBarEffectMode = config.effectMode
        AppConfig.floatingBottomBarHideSearch = config.layoutMode == "floating" && config.hideSearchInFloatingStyle
        AppConfig.liquidGlassLevel = config.opacity
        AppConfig.frostedGlassLevel = config.opacity
        if (config.layoutMode == "sidebar") {
            appCtx.putPrefBoolean(PreferKey.mergeDiscoveryRss, false)
        }
    }

    fun select(entry: Entry) {
        val key = if (entry.config.isNightMode) activeNightKey else activeDayKey
        appCtx.putPrefString(key, entry.dirName)
    }

    fun applyCurrentBottomConfig(isNight: Boolean) {
        val config = normalizeConfig(currentEntry(isNight).config)
        AppConfig.bottomBarLayoutMode = config.layoutMode
        AppConfig.bottomBarSidebarGravity = config.sidebarGravity
        AppConfig.bottomBarEffectMode = config.effectMode
        AppConfig.floatingBottomBarHideSearch = config.layoutMode == "floating" && config.hideSearchInFloatingStyle
        AppConfig.liquidGlassLevel = config.opacity
        AppConfig.frostedGlassLevel = config.opacity
        if (config.layoutMode == "sidebar") {
            appCtx.putPrefBoolean(PreferKey.mergeDiscoveryRss, false)
        }
    }

    suspend fun loadLocalOnlyForKit(isNight: Boolean): List<Entry> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        loadLocal(isNight)
    }

    fun defaultEntryForKit(isNight: Boolean): Entry {
        return defaultEntry(isNight)
    }

    fun standardEntryForKit(isNight: Boolean): Entry {
        return Entry(
            Config(
                name = defaultName(isNight),
                isNightMode = isNight,
                layoutMode = "standard",
                sidebarGravity = "start",
                effectMode = "solid",
                opacity = 76,
                updatedAt = 0L
            ),
            Source.BUILTIN,
            DEFAULT_DIR_NAME
        )
    }

    suspend fun restoreApplied(isNight: Boolean): Entry? {
        val dirName = activeDirName(isNight)
        if (dirName == DEFAULT_DIR_NAME) {
            applyCurrentBottomConfig(isNight)
            return defaultEntry(isNight)
        }
        readEntry(localDir(isNight, dirName))?.let {
            clearRuntimeCache()
            if (isNight == AppConfig.isNightTheme) apply(it)
            return it
        }
        val remoteEntries = loadRemoteOrCache(isNight)
        val remote = remoteEntries.firstOrNull { it.dirName == dirName }
            ?: remoteEntries.firstOrNull { it.config.name.normalizeFileName() == dirName }
            ?: run {
                io.legado.app.constant.AppLog.put("恢复底栏包未找到: $dirName")
                return null
            }
        return runCatching { download(remote) }.onSuccess {
            clearRuntimeCache()
            if (it.dirName != dirName || isNight == AppConfig.isNightTheme) apply(it)
        }.getOrElse {
            io.legado.app.constant.AppLog.put("恢复底栏包下载失败: $dirName\n${it.localizedMessage}", it)
            null
        }
    }

    fun addOrUpdate(config: Config, oldEntry: Entry? = null): Entry {
        val name = config.name.trim().ifBlank { defaultName(config.isNightMode) }
        val keepOldDir = oldEntry != null &&
            oldEntry.dirName.isNotBlank() &&
            oldEntry.dirName != DEFAULT_DIR_NAME &&
            oldEntry.source != Source.REMOTE
        val preferredDirName = name.normalizeFileName().ifBlank { "navigation_${System.currentTimeMillis()}" }
        val dirName = if (keepOldDir) {
            oldEntry.dirName
        } else {
            uniqueDirName(config.isNightMode, preferredDirName)
        }
        if (localNameExists(config.isNightMode, name, oldEntry?.dirName)) {
            throw IllegalArgumentException(appCtx.getString(R.string.navigation_bar_name_exists))
        }
        val dir = localDir(config.isNightMode, dirName).apply { mkdirs() }
        val source = normalizeConfig(config)
        val normalized = source.copy(
            name = name,
            layoutMode = source.layoutMode,
            sidebarGravity = source.sidebarGravity,
            effectMode = source.effectMode,
            opacity = source.opacity.coerceIn(0, 100),
            wallpaperPath = normalizeWallpaperPath(source.wallpaperPath, dir),
            borderAlpha = source.borderAlpha.coerceIn(0, 100),
            updatedAt = System.currentTimeMillis(),
            icons = source.icons.toMutableMap()
        )
        File(dir, packageFileName).writeText(GSON.toJson(normalized))
        clearRuntimeCache()
        return Entry(normalized, Source.LOCAL, dirName, localDir = dir)
    }

    private fun localNameExists(isNightMode: Boolean, name: String, excludeDirName: String? = null): Boolean {
        return loadLocal(isNightMode).any {
            it.dirName != excludeDirName && it.config.name == name
        }
    }

    private fun uniqueDirName(isNightMode: Boolean, preferred: String): String {
        val clean = preferred.normalizeFileName().ifBlank { "navigation_${System.currentTimeMillis()}" }
        if (readEntry(localDir(isNightMode, clean)) == null) return clean
        var index = 1
        while (true) {
            val candidate = "${clean}_$index"
            if (readEntry(localDir(isNightMode, candidate)) == null) return candidate
            index++
        }
    }

    fun deleteLocal(entry: Entry) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        FileUtils.delete(entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName), deleteRootDir = true)
        resetActiveIfNeeded(entry)
    }

    suspend fun delete(entry: Entry, containerId: String? = null, scope: String? = null) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        when (entry.source) {
            Source.REMOTE -> deleteRemote(entry, containerId, scope)
            Source.BOTH -> {
                val remoteResult = runCatching { deleteRemote(entry, containerId, scope) }
                deleteLocal(entry)
                remoteResult.getOrThrow()
            }
            Source.LOCAL -> deleteLocal(entry)
            Source.BUILTIN -> return
        }
        resetActiveIfNeeded(entry)
    }

    suspend fun exportZip(entry: Entry): File {
        val localEntry = if (entry.source == Source.REMOTE) download(entry) else entry
        val dir = localEntry.localDir ?: localDir(localEntry.config.isNightMode, localEntry.dirName)
        val zipFile = tempDir.getFile("${localEntry.dirName}.zip")
        if (zipFile.exists()) zipFile.delete()
        ZipUtils.zipFile(dir, zipFile)
        return zipFile
    }

    fun importZip(zipFile: File): Entry {
        return importZipInternal(zipFile)
    }

    fun importPackage(file: File): Entry {
        val zipFile = RedAssetPackage.zipPayload(file, tempDir) ?: return importZip(file)
        return try {
            when (RedAssetPackage.classifyZip(zipFile)) {
                RedAssetPackage.Kind.NavigationBar -> importRedNavigationPackage(zipFile, file.nameWithoutExtension)
                RedAssetPackage.Kind.ThemeZip,
                RedAssetPackage.Kind.CoverCollection -> throw IllegalArgumentException(appCtx.getString(R.string.wrong_format))
                RedAssetPackage.Kind.Unknown -> importZipInternal(zipFile)
            }
        } finally {
            zipFile.delete()
        }
    }

    fun importRedNavigationDirectory(
        sourceDir: File,
        fallbackName: String,
        isNightMode: Boolean,
        opacity: Int = 72
    ): Entry? {
        if (!sourceDir.isDirectory) return null
        val files = sourceDir.walkTopDown()
            .filter { it.isFile && !it.name.isRedNavigationMetaName() }
            .toList()
        if (files.isEmpty()) return null
        val packageDir = tempDir.getFile("red_nav_package_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val packageZip = tempDir.getFile("red_nav_package_${UUID.randomUUID()}.zip")
        return try {
            val icons = linkedMapOf<String, String>()
            redNavigationTargets.forEach { target ->
                val normal = findRedNavigationIcon(files, target.aliases, STATE_NORMAL)
                    ?: findRedNavigationIcon(files, target.aliases, null)
                val selected = findRedNavigationIcon(files, target.aliases, STATE_SELECTED)
                    ?: normal
                    ?: findRedNavigationIcon(files, target.aliases, null)
                normal?.let {
                    writeRedNavigationIcon(packageDir, icons, it, target.key, STATE_NORMAL)
                }
                selected?.let {
                    writeRedNavigationIcon(packageDir, icons, it, target.key, STATE_SELECTED)
                }
            }
            if (icons.isEmpty()) return null
            val name = readRedNavigationName(sourceDir)
                .ifBlank { fallbackName }
                .ifBlank { "RED Navigation" }
            val config = Config(
                name = name,
                isNightMode = isNightMode,
                layoutMode = "floating",
                effectMode = "glass",
                opacity = opacity.coerceIn(0, 100),
                icons = icons
            )
            File(packageDir, packageFileName).writeText(GSON.toJson(config))
            if (!ZipUtils.zipFile(packageDir, packageZip) || !packageZip.isFile || packageZip.length() <= 0L) {
                return null
            }
            importZipInternal(packageZip)
        } finally {
            packageZip.delete()
            FileUtils.delete(packageDir, deleteRootDir = true)
        }
    }

    fun copyEntryForMode(entry: Entry, isNightMode: Boolean): Entry? {
        if (entry.config.isNightMode == isNightMode) return entry
        if (entry.dirName == DEFAULT_DIR_NAME) return null
        val sourceDir = entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName)
        if (!sourceDir.isDirectory) return null
        val config = normalizeConfig(entry.config.copy(
            isNightMode = isNightMode,
            icons = entry.config.icons.toMutableMap()
        ))
        val dirName = config.name.normalizeFileName().ifBlank { "navigation_${System.currentTimeMillis()}" }
        val targetDir = localDir(isNightMode, dirName)
        if (targetDir.exists()) {
            FileUtils.delete(targetDir, deleteRootDir = true)
        }
        sourceDir.copyRecursively(targetDir, overwrite = true)
        File(targetDir, packageFileName).writeText(GSON.toJson(config))
        clearRuntimeCache()
        return Entry(config, Source.LOCAL, dirName, localDir = targetDir)
    }

    suspend fun upload(entry: Entry, containerId: String? = null, scope: String? = null) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        AppCloudStorage.uploadNavigationBarPackage(entry.config.isNightMode, entry.dirName, exportZip(entry), containerId, scope)
    }

    suspend fun download(entry: Entry, containerId: String? = null, scope: String? = null): Entry {
        val zipFile = tempDir.getFile("${entry.dirName}.zip")
        AppCloudStorage.downloadNavigationBarPackage(entry.config.isNightMode, entry.dirName, zipFile, containerId, scope)
        return importZipInternal(zipFile, entry.remoteUpdatedAt).copy(source = Source.BOTH, remoteUpdatedAt = entry.remoteUpdatedAt)
    }

    suspend fun deleteRemote(entry: Entry, containerId: String? = null, scope: String? = null) {
        if (entry.dirName == DEFAULT_DIR_NAME) return
        AppCloudStorage.deleteNavigationBarPackage(entry.config.isNightMode, entry.dirName, containerId, scope)
    }

    fun saveIconToPackage(
        context: Context,
        uri: Uri,
        entry: Entry,
        itemKey: String,
        selected: Boolean,
        targetSize: Int
    ): Entry {
        if (entry.dirName == DEFAULT_DIR_NAME) {
            throw IllegalArgumentException(context.getString(R.string.navigation_bar_default_readonly))
        }
        val bitmap = decodeIconBitmap(context, uri, targetSize) ?: throw IllegalArgumentException(context.getString(R.string.navigation_icon_decode_failed))
        val output = drawCenteredIcon(bitmap, targetSize)
        if (bitmap !== output && !bitmap.isRecycled) bitmap.recycle()
        val dirName = entry.dirName.ifBlank {
            entry.config.name.normalizeFileName().ifBlank { "navigation_${System.currentTimeMillis()}" }
        }
        val dir = entry.localDir ?: localDir(entry.config.isNightMode, dirName).apply { mkdirs() }
        val state = if (selected) STATE_SELECTED else STATE_NORMAL
        val fileName = "${itemKey}_$state.png"
        val file = File(dir, fileName)
        FileOutputStream(file).use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!output.isRecycled) output.recycle()
        val config = entry.config.copy(icons = entry.config.icons.toMutableMap())
        config.icons[iconKey(itemKey, state)] = fileName
        return addOrUpdate(config, entry)
    }

    fun saveSingleIconToPackage(
        context: Context,
        uri: Uri,
        entry: Entry,
        itemKey: String,
        targetSize: Int
    ): Entry {
        if (entry.dirName == DEFAULT_DIR_NAME) {
            throw IllegalArgumentException(context.getString(R.string.navigation_bar_default_readonly))
        }
        val bitmap = decodeIconBitmap(context, uri, targetSize)
            ?: throw IllegalArgumentException(context.getString(R.string.navigation_icon_decode_failed))
        val output = drawCenteredIcon(bitmap, targetSize)
        if (bitmap !== output && !bitmap.isRecycled) bitmap.recycle()
        val dirName = entry.dirName.ifBlank {
            entry.config.name.normalizeFileName().ifBlank { "navigation_${System.currentTimeMillis()}" }
        }
        val dir = entry.localDir ?: localDir(entry.config.isNightMode, dirName).apply { mkdirs() }
        val fileName = "${itemKey}_single.png"
        val file = File(dir, fileName)
        FileOutputStream(file).use { output.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (!output.isRecycled) output.recycle()
        val config = entry.config.copy(icons = entry.config.icons.toMutableMap())
        config.icons[singleIconKey(itemKey)] = fileName
        return addOrUpdate(config, entry)
    }

    fun clearIcon(entry: Entry, itemKey: String, selected: Boolean): Entry {
        if (entry.dirName == DEFAULT_DIR_NAME) return entry
        val state = if (selected) STATE_SELECTED else STATE_NORMAL
        val config = entry.config.copy(icons = entry.config.icons.toMutableMap())
        config.icons.remove(iconKey(itemKey, state))
        return addOrUpdate(config, entry)
    }

    fun clearSingleIcon(entry: Entry, itemKey: String): Entry {
        if (entry.dirName == DEFAULT_DIR_NAME) return entry
        val config = entry.config.copy(icons = entry.config.icons.toMutableMap())
        config.icons.remove(singleIconKey(itemKey))
        return addOrUpdate(config, entry)
    }

    fun saveSidebarBackgroundToPackage(
        context: Context,
        sourcePath: String,
        entry: Entry
    ): Entry {
        if (entry.dirName == DEFAULT_DIR_NAME) {
            throw IllegalArgumentException(context.getString(R.string.navigation_bar_default_readonly))
        }
        val source = File(sourcePath)
        if (!source.exists()) {
            throw IllegalArgumentException(
                context.getString(R.string.image_crop_failed, context.getString(R.string.unknown))
            )
        }
        val dirName = entry.dirName.ifBlank {
            entry.config.name.normalizeFileName().ifBlank { "navigation_${System.currentTimeMillis()}" }
        }
        val dir = entry.localDir ?: localDir(entry.config.isNightMode, dirName).apply { mkdirs() }
        val suffix = source.name.substringAfterLast('.', "")
            .takeIf { it.isNotBlank() }
            ?.let { ".${it.lowercase(Locale.ROOT)}" }
            ?: ".jpg"
        val fileName = "sidebar_background$suffix"
        source.copyTo(File(dir, fileName), overwrite = true)
        val config = entry.config.copy(icons = entry.config.icons.toMutableMap())
        config.sidebarBackgroundPath = fileName
        return addOrUpdate(config, entry)
    }

    fun clearSidebarBackground(entry: Entry): Entry {
        if (entry.dirName == DEFAULT_DIR_NAME) return entry
        entry.config.sidebarBackgroundPath?.let { name ->
            File(entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName), name)
                .takeIf { it.exists() }
                ?.delete()
        }
        val config = entry.config.copy(icons = entry.config.icons.toMutableMap())
        config.sidebarBackgroundPath = null
        return addOrUpdate(config, entry)
    }

    fun saveBottomWallpaperToPackage(
        context: Context,
        sourcePath: String,
        entry: Entry
    ): Entry {
        if (entry.dirName == DEFAULT_DIR_NAME) {
            throw IllegalArgumentException(context.getString(R.string.navigation_bar_default_readonly))
        }
        val source = File(sourcePath)
        if (!source.exists() || !source.isFile) {
            throw IllegalArgumentException(
                context.getString(R.string.image_crop_failed, context.getString(R.string.unknown))
            )
        }
        val dirName = entry.dirName.ifBlank {
            entry.config.name.normalizeFileName().ifBlank { "navigation_${System.currentTimeMillis()}" }
        }
        val dir = entry.localDir ?: localDir(entry.config.isNightMode, dirName).apply { mkdirs() }
        val wallpaperName = copyBottomWallpaperIntoPackage(source, dir)
        val config = entry.config.copy(icons = entry.config.icons.toMutableMap())
        config.wallpaperPath = wallpaperName
        return addOrUpdate(config, entry.copy(dirName = dirName, localDir = dir))
    }

    fun clearBottomWallpaper(entry: Entry): Entry {
        if (entry.dirName == DEFAULT_DIR_NAME) return entry
        val dir = entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName)
        entry.config.wallpaperPath?.let { value ->
            val file = File(value)
            val resolved = if (file.isAbsolute) file else File(dir, value)
            if (isPackagedBottomWallpaper(resolved, dir)) {
                resolved.takeIf { it.exists() }?.delete()
            }
        }
        val config = entry.config.copy(icons = entry.config.icons.toMutableMap())
        config.wallpaperPath = null
        return addOrUpdate(config, entry)
    }

    fun applyTo(menu: Menu, context: Context, isNight: Boolean): Boolean {
        val entry = currentEntry(isNight)
        val hasCustom = entry.dirName != DEFAULT_DIR_NAME && items.any { item ->
            iconPath(entry, item.key, STATE_NORMAL) != null ||
                iconPath(entry, item.key, STATE_SELECTED) != null
        }
        items.forEach { item ->
            menu.findItem(item.menuId)?.icon = createMenuDrawable(context, entry, item)
        }
        return hasCustom
    }

    fun previewDrawable(context: Context, entry: Entry, item: NavItem, selected: Boolean): Drawable? {
        val state = if (selected) STATE_SELECTED else STATE_NORMAL
        return loadDrawable(context, iconPath(entry, item.key, state))
            ?: loadDrawable(context, iconPath(entry, item.key, STATE_NORMAL))
            ?: ContextCompat.getDrawable(context, item.defaultIconRes)
    }

    fun currentDrawable(context: Context, itemKey: String, selected: Boolean): Drawable? {
        val item = items.firstOrNull { it.key == itemKey } ?: return null
        return previewDrawable(context, currentEntry(AppConfig.isNightTheme), item, selected)
    }

    fun previewSingleDrawable(context: Context, entry: Entry, item: NavItem): Drawable? {
        return loadDrawable(context, singleIconPath(entry, item.key))
            ?: ContextCompat.getDrawable(context, item.defaultIconRes)
    }

    fun currentSingleDrawable(context: Context, itemKey: String): Drawable? {
        val item = extraItems.firstOrNull { it.key == itemKey } ?: return null
        return previewSingleDrawable(context, currentEntry(AppConfig.isNightTheme), item)
    }

    fun hasSingleIcon(entry: Entry, itemKey: String): Boolean {
        return singleIconPath(entry, itemKey) != null
    }

    fun hasCurrentSingleIcon(itemKey: String): Boolean {
        return hasSingleIcon(currentEntry(AppConfig.isNightTheme), itemKey)
    }

    fun currentMenuDrawable(context: Context, itemKey: String): Drawable? {
        val item = items.firstOrNull { it.key == itemKey } ?: return null
        return createMenuDrawable(context, currentEntry(AppConfig.isNightTheme), item)
    }

    fun currentSidebarBackgroundPath(isNight: Boolean): String? {
        return resolveSidebarBackgroundPath(currentEntry(isNight))
    }

    fun currentBottomWallpaperPath(isNight: Boolean): String? {
        return resolveBottomWallpaperPath(currentEntry(isNight))
    }

    fun getIconFileName(entry: Entry, itemKey: String, selected: Boolean): String? {
        val state = if (selected) STATE_SELECTED else STATE_NORMAL
        return entry.config.icons[iconKey(itemKey, state)]?.let { File(it).name }
    }

    fun getSingleIconFileName(entry: Entry, itemKey: String): String? {
        return entry.config.icons[singleIconKey(itemKey)]?.let { File(it).name }
    }

    private fun defaultEntry(isNight: Boolean): Entry {
        val layoutMode = MainLayoutPresetConfig.defaultBottomLayoutMode()
        return Entry(
            Config(
                name = defaultName(isNight),
                isNightMode = isNight,
                layoutMode = layoutMode,
                sidebarGravity = "start",
                effectMode = if (layoutMode == "standard") "solid" else "glass",
                opacity = 76,
                hideSearchInFloatingStyle = layoutMode == "floating" && MainLayoutPresetConfig.floatingBottomBarHideSearch(),
                updatedAt = 0L
            ),
            Source.BUILTIN,
            DEFAULT_DIR_NAME
        )
    }

    private fun loadLocal(isNight: Boolean): List<Entry> {
        return typeDir(isNight).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readEntry(it) }
            .orEmpty()
    }

    private suspend fun loadRemote(isNight: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        return AppCloudStorage.listNavigationBarPackages(isNight, containerId, scope).mapNotNull { file ->
            val name = remoteZipBaseName(file.displayName)
            val dirName = name.normalizeFileName().ifBlank {
                AppLog.put("跳过异常远端底栏包: ${file.displayName}")
                return@mapNotNull null
            }
            Entry(
                Config(name = name.ifBlank { dirName }, isNightMode = isNight, updatedAt = file.lastModify),
                Source.REMOTE,
                dirName,
                remoteUpdatedAt = file.lastModify
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

    private suspend fun loadRemoteOrCache(isNight: Boolean, containerId: String? = null, scope: String? = null): List<Entry> {
        val cached = readRemoteCache(isNight, containerId)
        return try {
            val remote = withTimeout(remoteListTimeoutMillis) {
                loadRemote(isNight, containerId, scope)
            }
            writeRemoteCache(isNight, remote, containerId)
            remote
        } catch (e: TimeoutCancellationException) {
            AppLog.put(
                "加载远端底栏包列表超时: type=${AppCloudStorage.type}, container=${containerId.orEmpty()}, timeout=${remoteListTimeoutMillis}ms"
            )
            cached
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            AppLog.put(
                "加载远端底栏包列表失败: type=${AppCloudStorage.type}, container=${containerId.orEmpty()}\n${e.localizedMessage}",
                e
            )
            cached
        }
    }

    private fun remoteCacheFile(isNight: Boolean, containerId: String? = null): File {
        val mode = if (isNight) "night" else "day"
        val suffix = containerId?.takeIf { it.isNotBlank() }?.normalizeFileName()?.let { "_$it" }.orEmpty()
        return remoteCacheDir.getFile("$mode$suffix.json")
    }

    private fun readRemoteCache(isNight: Boolean, containerId: String? = null): List<Entry> {
        val file = remoteCacheFile(isNight, containerId)
        if (!file.exists()) return emptyList()
        return GSON.fromJsonArray<RemoteCache>(file.readText()).getOrDefault(emptyList())
            .filter { it.isNightMode == isNight }
            .mapNotNull { cache ->
                val dirName = cache.dirName.ifBlank { cache.name.normalizeFileName() }
                    .ifBlank { return@mapNotNull null }
                Entry(
                    Config(
                        name = cache.name.ifBlank { dirName },
                        isNightMode = cache.isNightMode,
                        updatedAt = cache.updatedAt
                    ),
                    Source.REMOTE,
                    dirName,
                    remoteUpdatedAt = cache.updatedAt
                )
            }
    }

    private fun writeRemoteCache(isNight: Boolean, entries: List<Entry>, containerId: String? = null) {
        val cache = entries.map {
            RemoteCache(
                name = it.config.name,
                dirName = it.dirName,
                isNightMode = it.config.isNightMode,
                updatedAt = it.remoteUpdatedAt.takeIf { time -> time > 0L } ?: it.config.updatedAt
            )
        }
        remoteCacheFile(isNight, containerId).writeTextIfChanged(GSON.toJson(cache))
    }

    private fun readEntry(dir: File): Entry? {
        val config = readConfig(dir) ?: return null
        return Entry(config, Source.LOCAL, dir.name, localDir = dir)
    }

    private fun readConfig(dir: File): Config? {
        val file = File(dir, packageFileName)
        if (!file.exists()) return null
        return GSON.fromJsonObject<Config>(file.readText()).getOrNull()?.let(::normalizeConfig)
    }

    private fun importZipInternal(zipFile: File, remoteUpdatedAt: Long = 0L): Entry {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            ZipUtils.unZipToPath(zipFile, unzipDir)
            val packageFile = unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }
                ?: throw IllegalArgumentException(appCtx.getString(R.string.navigation_bar_config_missing))
            val config = normalizeConfig(GSON.fromJsonObject<Config>(packageFile.readText()).getOrThrow())
            if (config.name.normalizeFileName() == DEFAULT_DIR_NAME) {
                config.name = "${config.name}_${appCtx.getString(R.string.navigation_bar_import_suffix)}"
            }
            config.opacity = config.opacity.coerceIn(0, 100)
            if (remoteUpdatedAt == 0L) {
                config.updatedAt = System.currentTimeMillis()
            }
            val dirName = config.name.normalizeFileName().ifBlank { "navigation_${System.currentTimeMillis()}" }
            val targetDir = localDir(config.isNightMode, dirName)
            if (targetDir.exists()) {
                FileUtils.delete(targetDir, deleteRootDir = true)
            }
            targetDir.mkdirs()
            packageFile.parentFile?.copyRecursively(targetDir, overwrite = true)
            File(targetDir, packageFileName).writeText(GSON.toJson(config))
            clearRuntimeCache()
            Entry(config, Source.LOCAL, dirName, localDir = targetDir, remoteUpdatedAt = remoteUpdatedAt)
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun importRedNavigationPackage(zipFile: File, fallbackName: String): Entry {
        val unzipDir = tempDir.getFile("red_nav_import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        return try {
            RedAssetPackage.unzipSecure(zipFile, unzipDir)
            unzipDir.walkTopDown().firstOrNull { it.isFile && it.name == packageFileName }?.let {
                return importZipInternal(zipFile)
            }
            importRedNavigationDirectory(
                sourceDir = unzipDir,
                fallbackName = fallbackName,
                isNightMode = AppConfig.isNightTheme
            ) ?: throw IllegalArgumentException(appCtx.getString(R.string.wrong_format))
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun writeRedNavigationIcon(
        targetDir: File,
        icons: MutableMap<String, String>,
        source: File,
        targetKey: String,
        state: String
    ) {
        val fileName = "${targetKey}_$state.png"
        val bitmap = decodeRedNavigationIcon(source, redImportIconSize) ?: run {
            AppLog.put("跳过无法识别的 RED 底栏图标: ${source.name}")
            return
        }
        val output = drawCenteredIcon(bitmap, redImportIconSize)
        if (bitmap !== output && !bitmap.isRecycled) bitmap.recycle()
        FileOutputStream(File(targetDir, fileName)).use {
            output.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        if (!output.isRecycled) output.recycle()
        icons[iconKey(targetKey, state)] = fileName
    }

    private fun readRedNavigationName(root: File): String {
        val metaFile = root.walkTopDown().firstOrNull {
            it.isFile && it.name.isRedNavigationMetaName()
        } ?: return ""
        val text = runCatching { metaFile.readText() }.getOrDefault("")
        if (text.isBlank()) return ""
        return GSON.fromJsonObject<RedNameMeta>(text).getOrNull()?.name.orEmpty()
    }

    private fun String.isRedNavigationMetaName(): Boolean {
        return equals("meta.json", ignoreCase = true) ||
            equals("package.json", ignoreCase = true) ||
            equals("manifest.json", ignoreCase = true) ||
            equals("reeden_bottom_navbar_pack.json", ignoreCase = true)
    }

    private fun findRedNavigationIcon(
        files: List<File>,
        aliases: List<String>,
        state: String?
    ): File? {
        return files
            .mapNotNull { file -> scoreRedNavigationIcon(file, aliases, state)?.let { it to file } }
            .maxWithOrNull(
                compareBy<Pair<Int, File>> { it.first }
                    .thenByDescending { it.second.name.length }
                    .thenBy { it.second.name }
            )
            ?.second
    }

    private fun scoreRedNavigationIcon(
        file: File,
        aliases: List<String>,
        state: String?
    ): Int? {
        val semantic = RedNavigationFileSemantic.from(file.nameWithoutExtension)
        val aliasScore = aliases.mapIndexedNotNull { index, alias ->
            semantic.scoreAlias(alias)?.minus(index)
        }.maxOrNull() ?: return null
        val fileState = semantic.state()
        val stateScore = when (state) {
            STATE_NORMAL -> when (fileState) {
                STATE_NORMAL -> 40
                null -> 8
                else -> return null
            }
            STATE_SELECTED -> when (fileState) {
                STATE_SELECTED -> 40
                null -> 8
                else -> return null
            }
            else -> when (fileState) {
                null -> 16
                STATE_NORMAL -> 8
                STATE_SELECTED -> 6
                else -> 0
            }
        }
        return aliasScore + stateScore
    }

    private fun decodeRedNavigationIcon(file: File, targetSize: Int): Bitmap? {
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrElse {
            AppLog.put("读取 RED 底栏图标失败: ${file.name}, ${it.localizedMessage}")
            return null
        }
        if (bytes.isIcoHeader()) {
            decodeIcoPng(bytes)?.let { return it }
        }
        if (file.extension.equals("svg", ignoreCase = true) || bytes.looksLikeSvg()) {
            decodeRedNavigationSvg(file, bytes, targetSize)?.let { return it }
        }
        BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        if (!file.extension.equals("svg", ignoreCase = true)) {
            SvgUtils.createBitmap(file.absolutePath, targetSize, targetSize)?.let { return it }
        }
        return null
    }

    private fun decodeRedNavigationSvg(file: File, bytes: ByteArray, targetSize: Int): Bitmap? {
        val text = runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")
        val sanitized = sanitizeRedNavigationSvg(text)
        if (sanitized.isNotBlank()) {
            renderRedNavigationSvg(file, sanitized.toByteArray(Charsets.UTF_8), targetSize, "sanitized")
                ?.let { return it }
        }
        if (sanitized != text) {
            renderRedNavigationSvg(file, bytes, targetSize, "raw")
                ?.let { return it }
        } else if (sanitized.isBlank()) {
            renderRedNavigationSvg(file, bytes, targetSize, "raw")
                ?.let { return it }
        }
        AppLog.put("跳过无法解析的 RED 底栏 SVG: ${file.name}")
        return null
    }

    private fun renderRedNavigationSvg(file: File, bytes: ByteArray, targetSize: Int, label: String): Bitmap? {
        return runCatching {
            SvgUtils.createBitmapOrThrow(ByteArrayInputStream(bytes), targetSize, targetSize)
        }.getOrElse {
            AppLog.put("解析 RED 底栏 SVG 失败($label): ${file.name}, ${it.localizedMessage}")
            null
        }
    }

    private fun sanitizeRedNavigationSvg(text: String): String {
        val withoutBom = text.trimStart('\uFEFF')
        return redSvgDoctypeRegex
            .replace(redSvgXmlDeclarationRegex.replace(withoutBom, ""), "")
            .trimStart()
    }

    private fun ByteArray.isIcoHeader(): Boolean {
        return size >= 4 && this[0] == 0.toByte() && this[1] == 0.toByte() &&
            this[2] == 1.toByte() && this[3] == 0.toByte()
    }

    private fun ByteArray.looksLikeSvg(): Boolean {
        val limit = minOf(size, 4096)
        if (limit <= 0) return false
        val head = String(this, 0, limit, Charsets.UTF_8).lowercase(Locale.ROOT)
        return "<svg" in head || "<!doctype svg" in head
    }

    private data class RedNavigationTarget(
        val key: String,
        val aliases: List<String>
    )

    private data class RedNavigationFileSemantic(
        val tokens: Set<String>,
        val compact: String
    ) {
        fun scoreAlias(alias: String): Int? {
            val normalized = normalize(alias)
            if (normalized.isBlank()) return null
            if (normalized in tokens) return 120
            if (compact == normalized) return 110
            if (normalized.length <= 2) return null
            return when {
                compact.startsWith(normalized) -> 80
                compact.endsWith(normalized) -> 76
                compact.contains(normalized) -> 64
                else -> null
            }
        }

        fun state(): String? {
            val normalHit = normalStateAliases.any { normalize(it) in tokens }
            val selectedHit = selectedStateAliases.any { normalize(it) in tokens }
            return when {
                normalHit -> "normal"
                selectedHit -> "selected"
                normalStateAliases.any { compact.endsWith(normalize(it)) } -> "normal"
                selectedStateAliases.any { compact.endsWith(normalize(it)) } -> "selected"
                else -> null
            }
        }

        companion object {
            private val normalStateAliases = setOf(
                "normal",
                "default",
                "off",
                "inactive",
                "unselected",
                "outline",
                "regular",
                "idle"
            )

            private val selectedStateAliases = setOf(
                "selected",
                "active",
                "on",
                "current",
                "checked",
                "fill",
                "filled",
                "focus",
                "focused",
                "select"
            )

            fun from(name: String): RedNavigationFileSemantic {
                val separated = name.replace(Regex("([a-z])([A-Z])"), "$1_$2")
                    .lowercase(Locale.ROOT)
                val tokens = separated
                    .split(Regex("[^a-z0-9]+"))
                    .filter { it.isNotBlank() }
                    .map { normalize(it) }
                    .toSet()
                return RedNavigationFileSemantic(
                    tokens = tokens,
                    compact = normalize(separated)
                )
            }

            private fun normalize(value: String): String {
                return value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "")
            }
        }
    }

    private val redNavigationTargets = listOf(
        RedNavigationTarget("bookshelf", listOf("bookshelf", "bookcase", "shelf", "books", "book")),
        RedNavigationTarget("discovery", listOf("feature", "discovery", "explore", "find", "home")),
        RedNavigationTarget("rss", listOf("notes", "rss", "subscribe", "subscription", "feed")),
        RedNavigationTarget("readRecord", listOf("statistics", "stats", "readRecord", "record", "history")),
        RedNavigationTarget("my", listOf("settings", "my", "mine", "profile", "user", "config")),
        RedNavigationTarget("ai", listOf("ai", "assistant", "gpt")),
        RedNavigationTarget(EXTRA_SEARCH, listOf("search", "findSearch"))
    )

    private fun createMenuDrawable(context: Context, entry: Entry, item: NavItem): Drawable {
        val defaultColor = defaultIconColor(context)
        val selectedColor = ThemeStore.accentColor(context)
        val normal = loadDrawable(context, iconPath(entry, item.key, STATE_NORMAL))
            ?: defaultDrawable(context, item.defaultIconRes, defaultColor)
        val selected = loadDrawable(context, iconPath(entry, item.key, STATE_SELECTED))
            ?: loadDrawable(context, iconPath(entry, item.key, STATE_NORMAL))
            ?: defaultDrawable(context, item.defaultIconRes, selectedColor)
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), selected)
            addState(intArrayOf(android.R.attr.state_selected), selected)
            addState(intArrayOf(), normal)
        }
    }

    private fun iconPath(entry: Entry, itemKey: String, state: String): String? {
        if (entry.dirName == DEFAULT_DIR_NAME) return null
        val value = entry.config.icons[iconKey(itemKey, state)] ?: return null
        val file = File(value)
        return if (file.isAbsolute) value else File(entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName), value).absolutePath
    }

    private fun singleIconPath(entry: Entry, itemKey: String): String? {
        if (entry.dirName == DEFAULT_DIR_NAME) return null
        val value = entry.config.icons[singleIconKey(itemKey)] ?: return null
        val file = File(value)
        return if (file.isAbsolute) value else File(entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName), value).absolutePath
    }

    private fun resolveSidebarBackgroundPath(entry: Entry): String? {
        if (entry.dirName == DEFAULT_DIR_NAME) return null
        val value = entry.config.sidebarBackgroundPath ?: return null
        val file = File(value)
        return if (file.isAbsolute) value else File(
            entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName),
            value
        ).absolutePath
    }

    private fun resolveBottomWallpaperPath(entry: Entry): String? {
        if (entry.dirName == DEFAULT_DIR_NAME) return null
        val value = entry.config.wallpaperPath ?: return null
        val file = File(value)
        return if (file.isAbsolute) value else File(
            entry.localDir ?: localDir(entry.config.isNightMode, entry.dirName),
            value
        ).absolutePath
    }

    private fun iconKey(itemKey: String, state: String): String = "${itemKey}_$state"

    private fun singleIconKey(itemKey: String): String = "${itemKey}_single"

    private fun defaultDrawable(context: Context, @DrawableRes resId: Int, color: Int): Drawable {
        val drawable = ContextCompat.getDrawable(context, resId)!!.mutate()
        DrawableCompat.setTint(drawable, color)
        return drawable
    }

    private fun defaultIconColor(context: Context): Int {
        val bgColor = context.bottomBackground
        val textIsDark = ColorUtils.isColorLight(bgColor)
        return context.getSecondaryTextColor(textIsDark)
    }

    private fun loadDrawable(context: Context, path: String?): Drawable? {
        if (path.isNullOrBlank()) return null
        val file = File(path)
        val cacheKey = "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        val bitmap = synchronized(iconBitmapCache) {
            iconBitmapCache[cacheKey]?.takeIf { !it.isRecycled } ?: BitmapFactory.decodeFile(path)?.also {
                iconBitmapCache[cacheKey] = it
            }
        } ?: return null
        return BitmapDrawable(context.resources, bitmap)
    }

    private fun clearRuntimeCache() {
        currentDayEntryCache = null
        currentNightEntryCache = null
        synchronized(iconBitmapCache) {
            iconBitmapCache.clear()
        }
    }

    private fun decodeIconBitmap(context: Context, uri: Uri, targetSize: Int): Bitmap? {
        val name = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
        return if (name.endsWith(".svg")) {
            context.contentResolver.openInputStream(uri)?.use {
                SvgUtils.createBitmap(it, targetSize, targetSize)
            }
        } else if (name.endsWith(".ico")) {
            context.contentResolver.openInputStream(uri)?.use {
                decodeIcoPng(it.readBytes())
            }
        } else {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }
        }
    }

    private fun decodeIcoPng(bytes: ByteArray): Bitmap? {
        if (bytes.size < 22) return null
        val type = leShort(bytes, 2)
        val count = leShort(bytes, 4)
        if (type != 1 || count <= 0) return null
        var bestOffset = 0
        var bestSize = 0
        var bestPixels = -1
        repeat(count) { index ->
            val entry = 6 + index * 16
            if (entry + 16 > bytes.size) return@repeat
            val width = bytes[entry].toInt().and(0xff).let { if (it == 0) 256 else it }
            val height = bytes[entry + 1].toInt().and(0xff).let { if (it == 0) 256 else it }
            val size = leInt(bytes, entry + 8)
            val offset = leInt(bytes, entry + 12)
            if (size <= 0 || offset <= 0 || offset + size > bytes.size) return@repeat
            val pixels = width * height
            if (pixels > bestPixels) {
                bestPixels = pixels
                bestOffset = offset
                bestSize = size
            }
        }
        if (bestSize <= 0) return null
        return BitmapFactory.decodeByteArray(bytes, bestOffset, bestSize)
    }

    private fun leShort(bytes: ByteArray, offset: Int): Int {
        return bytes[offset].toInt().and(0xff) or
            (bytes[offset + 1].toInt().and(0xff) shl 8)
    }

    private fun leInt(bytes: ByteArray, offset: Int): Int {
        return bytes[offset].toInt().and(0xff) or
            (bytes[offset + 1].toInt().and(0xff) shl 8) or
            (bytes[offset + 2].toInt().and(0xff) shl 16) or
            (bytes[offset + 3].toInt().and(0xff) shl 24)
    }

    private fun drawCenteredIcon(source: Bitmap, targetSize: Int): Bitmap {
        val output = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.TRANSPARENT)
        val scale = min(targetSize.toFloat() / source.width, targetSize.toFloat() / source.height)
        val width = source.width * scale
        val height = source.height * scale
        val left = (targetSize - width) / 2f
        val top = (targetSize - height) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
        canvas.drawBitmap(source, null, RectF(left, top, left + width, top + height), paint)
        return output
    }

    private fun localDir(isNight: Boolean, dirName: String): File = typeDir(isNight).getFile(dirName)

    private fun typeDir(isNight: Boolean): File {
        return rootDir.getFile(if (isNight) MODE_NIGHT else MODE_DAY).apply { mkdirs() }
    }

    private fun defaultName(isNight: Boolean): String {
        return appCtx.getString(if (isNight) R.string.navigation_bar_night_default_name else R.string.navigation_bar_day_default_name)
    }

    private fun normalizeConfig(config: Config): Config {
        val layoutMode = runCatching { config.layoutMode }.getOrNull()
            ?.takeIf { it in setOf("floating", "sidebar", "standard") }
            ?: "floating"
        val sidebarGravity = runCatching { config.sidebarGravity }.getOrNull()
            ?.takeIf { it in setOf("start", "end") }
            ?: "start"
        val effectMode = runCatching { config.effectMode }.getOrNull()
            ?.takeIf { it in setOf("solid", "glass", "frosted") }
            ?: "glass"
        val resolvedEffectMode = if (layoutMode == "standard") "solid" else effectMode
        val icons = runCatching { config.icons }.getOrNull() ?: linkedMapOf()
        val sidebarBackgroundPath = runCatching { config.sidebarBackgroundPath }.getOrNull()
        val wallpaperPath = runCatching { config.wallpaperPath }.getOrNull()
        val borderAlpha = runCatching { config.borderAlpha }.getOrDefault(100).coerceIn(0, 100)
        val hideSearchInFloatingStyle = runCatching { config.hideSearchInFloatingStyle }.getOrDefault(false)
        config.layoutMode = layoutMode
        config.sidebarGravity = sidebarGravity
        config.effectMode = resolvedEffectMode
        config.opacity = config.opacity.coerceIn(0, 100)
        config.sidebarBackgroundPath = sidebarBackgroundPath
        config.wallpaperPath = wallpaperPath?.takeIf { it.isNotBlank() }
        config.borderAlpha = borderAlpha
        config.hideSearchInFloatingStyle = layoutMode == "floating" && hideSearchInFloatingStyle
        config.icons = icons.toMutableMap()
        return config
    }

    private fun normalizeWallpaperPath(path: String?, dir: File): String? {
        val value = path?.takeIf { it.isNotBlank() } ?: return null
        val source = File(value)
        if (!source.isAbsolute) {
            return value
        }
        if (!source.exists() || !source.isFile) {
            return null
        }
        return copyBottomWallpaperIntoPackage(source, dir)
    }

    private fun copyBottomWallpaperIntoPackage(source: File, dir: File): String {
        dir.mkdirs()
        val suffix = source.extension.takeIf { it.isNotBlank() } ?: "jpg"
        val target = File(dir, "bottom_bar_wallpaper.$suffix")
        if (source.canonicalFile != target.canonicalFile) {
            source.copyTo(target, overwrite = true)
        }
        deletePackagedBottomWallpapers(dir, target)
        return target.name
    }

    private fun deletePackagedBottomWallpapers(dir: File, keepFile: File? = null) {
        val keepCanonical = keepFile?.takeIf { it.exists() }?.canonicalFile
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("bottom_bar_wallpaper.") }
            ?.filter { keepCanonical == null || it.canonicalFile != keepCanonical }
            ?.forEach { it.delete() }
    }

    private fun isPackagedBottomWallpaper(file: File, dir: File): Boolean {
        if (!file.name.startsWith("bottom_bar_wallpaper.")) return false
        return runCatching { file.canonicalFile.parentFile == dir.canonicalFile }
            .getOrDefault(false)
    }

    private fun resetActiveIfNeeded(entry: Entry) {
        if (activeDirName(entry.config.isNightMode) == entry.dirName) {
            appCtx.putPrefString(if (entry.config.isNightMode) activeNightKey else activeDayKey, DEFAULT_DIR_NAME)
        }
    }

    private fun legacyMigratedKey(isNight: Boolean): String {
        return if (isNight) legacyMigratedNightKey else legacyMigratedDayKey
    }

    private fun migrateLegacyIconsIfNeeded(isNight: Boolean) {
        val migratedKey = legacyMigratedKey(isNight)
        if (appCtx.getPrefBoolean(migratedKey, false)) return
        if (!legacyRootDir.exists()) {
            appCtx.putPrefBoolean(migratedKey, true)
            return
        }
        val mode = if (isNight) MODE_NIGHT else MODE_DAY
        val hasLegacy = items.any { item ->
            File(legacyRootDir, "${mode}_${item.key}_$STATE_NORMAL.png").exists() ||
                File(legacyRootDir, "${mode}_${item.key}_$STATE_SELECTED.png").exists()
        }
        if (!hasLegacy) {
            appCtx.putPrefBoolean(migratedKey, true)
            return
        }
        val customName = appCtx.getString(R.string.navigation_bar_custom_name)
        if (readEntry(localDir(isNight, customName.normalizeFileName())) != null) {
            appCtx.putPrefBoolean(migratedKey, true)
            return
        }
        val dir = localDir(isNight, customName.normalizeFileName()).apply { mkdirs() }
        val icons = linkedMapOf<String, String>()
        items.forEach { item ->
            arrayOf(STATE_NORMAL, STATE_SELECTED).forEach { state ->
                val source = File(legacyRootDir, "${mode}_${item.key}_$state.png")
                if (source.exists()) {
                    val name = "${item.key}_$state.png"
                    source.copyTo(File(dir, name), overwrite = true)
                    icons[iconKey(item.key, state)] = name
                }
            }
        }
        addOrUpdate(
            Config(
                name = customName,
                isNightMode = isNight,
                effectMode = AppConfig.bottomBarEffectMode,
                opacity = if (AppConfig.bottomBarEffectMode == "frosted") AppConfig.frostedGlassLevel else AppConfig.liquidGlassLevel,
                icons = icons
            )
        )
        appCtx.putPrefBoolean(migratedKey, true)
    }

    @Keep
    private data class RedNameMeta(
        val name: String = ""
    )
}