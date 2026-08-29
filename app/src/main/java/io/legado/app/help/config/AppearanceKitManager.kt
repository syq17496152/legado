package io.legado.app.help.config

import android.content.Context
import androidx.annotation.Keep
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.compress.SafeZipExtractor
import io.legado.app.utils.compress.SafeZipLimits
import io.legado.app.utils.compress.readTextLimited
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefString
import io.legado.app.utils.isSameOrSubFileOf
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefString
import io.legado.app.utils.normalizeFileName
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

object AppearanceKitManager {

    const val KIT_FLOATING = "builtin_floating"
    const val KIT_FLOATING_NO_SEARCH = "builtin_floating_no_search"
    const val KIT_REGULAR = "builtin_regular"
    const val KIT_SIDEBAR = "builtin_sidebar"
    const val DARK_PURPLE_KIT_ID = "kit_dark_purple"
    const val DARK_PURPLE_THEME_NAME = "暗夜紫"
    const val DARK_PURPLE_TOP_BAR_NAME = "暗夜紫顶栏"
    private const val kitManifestName = "appearance_kit.json"
    private const val kitVersion = 1
    private const val maxKitManifestBytes = 1024L * 1024L
    private val kitZipLimits = SafeZipLimits(
        maxEntries = 2_048,
        maxEntryBytes = 128L * 1024L * 1024L,
        maxTotalBytes = 512L * 1024L * 1024L,
        maxCompressionRatio = 500L
    )
    private val currentModeThemeMutex = Mutex()
    private val currentModeThemeGeneration = AtomicLong()

    private val rootDir: File
        get() = appCtx.externalFiles.getFile("appThemeKits").apply { mkdirs() }

    private val indexFile: File
        get() = rootDir.getFile("kits.json")

    private val tempDir: File
        get() = appCtx.externalFiles.getFile("appearanceKitTemp").apply { mkdirs() }

    fun builtinKits(): List<AppearanceKit> {
        return listOf(
            AppearanceKit(
                id = KIT_FLOATING,
                name = "悬浮底栏",
                summary = "悬浮底栏 + 默认顶栏",
                type = AppearanceKitType.BUILTIN,
                binding = KitBinding(
                    preset = MainLayoutPresetConfig.PRESET_DEFAULT,
                    floatingBottomBarHideSearch = false
                )
            ),
            AppearanceKit(
                id = KIT_FLOATING_NO_SEARCH,
                name = "无搜索悬浮底栏",
                summary = "悬浮底栏(隐藏搜索) + 顶栏显示搜索",
                type = AppearanceKitType.BUILTIN,
                binding = KitBinding(
                    preset = MainLayoutPresetConfig.PRESET_DEFAULT,
                    floatingBottomBarHideSearch = true
                )
            ),
            AppearanceKit(
                id = KIT_REGULAR,
                name = "常规底栏",
                summary = "常规底栏 + 常规顶栏",
                type = AppearanceKitType.BUILTIN,
                binding = KitBinding(preset = MainLayoutPresetConfig.PRESET_REGULAR)
            ),
            AppearanceKit(
                id = KIT_SIDEBAR,
                name = "侧边栏",
                summary = "侧边栏 + 默认顶栏",
                type = AppearanceKitType.BUILTIN,
                binding = KitBinding(preset = MainLayoutPresetConfig.PRESET_SIDEBAR)
            )
        )
    }

    suspend fun importedThemeKits(): List<AppearanceKit> {
        migrateLegacyThemeKits()
        val indexed = loadIndex().map { it.toAppearanceKit() }
        if (indexed.isNotEmpty()) return indexed.sortedBy { it.name }
        val day = ThemePackageManager.loadLocalOnly(false)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        val night = ThemePackageManager.loadLocalOnly(true)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        return (day + night)
            .groupBy { it.packageInfo.name }
            .map { (name, entries) ->
                val hasDay = entries.any { !it.packageInfo.isNightTheme }
                val hasNight = entries.any { it.packageInfo.isNightTheme }
                AppearanceKit(
                    id = "theme:${name}",
                    name = name,
                    summary = when {
                        hasDay && hasNight -> "已导入日间 / 夜间界面主题"
                        hasNight -> "已导入夜间界面主题"
                        else -> "已导入日间界面主题"
                    },
                    type = AppearanceKitType.IMPORTED_THEME,
                    binding = KitBinding().apply {
                        entries.forEach {
                            setTheme(it.packageInfo.isNightTheme, ComponentRef(it.dirName, it.packageInfo.name))
                        }
                    }
                )
            }
            .sortedBy { it.name }
    }

    /**
     * F-暗夜紫外观套件：确保「暗夜紫主题包 + 专属紫调顶栏包 + 外观套件索引」整套就绪（幂等）。
     * - 老用户升级：仅补齐缺失组件（主题包/顶栏包/套件索引），不改变当前选择，完全无感；
     * - 首次安装：调用方把返回的 kit 交给 [apply] 即自动套用整套（暗夜紫主题 + 紫调顶栏 + regular 布局）。
     */
    suspend fun ensureDarkPurpleKit(): StoredAppearanceKit? = withContext(IO) {
        val darkPurple = ThemeConfig.configList.firstOrNull {
            it.themeName == DARK_PURPLE_THEME_NAME && it.isNightTheme
        } ?: return@withContext null
        // 1) 暗夜紫主题包（进主题列表，切走后可回选）
        val themeDir = if (ThemePackageManager.localThemeExists(true, DARK_PURPLE_THEME_NAME)) {
            ThemePackageManager.loadLocalOnly(true)
                .firstOrNull { it.packageInfo.name == DARK_PURPLE_THEME_NAME }?.dirName
        } else {
            ThemePackageManager.addFromConfig(darkPurple).dirName
        } ?: DARK_PURPLE_THEME_NAME
        // 2) 专属紫调顶栏包（幂等：已存在则按目标配置更新，保留原目录名）
        val targetTopBar = TopBarConfig.Config(
            name = DARK_PURPLE_TOP_BAR_NAME,
            isNightMode = true,
            style = TopBarConfig.STYLE_REGULAR,          // 胶囊搜索框+标签条
            tagBarColor = 0xFF2A2138.toInt(),            // 深紫底（贴合暗夜紫卡片色）
            tagBarAlpha = 100,
            tagSelectedColor = 0xFFCE93D8.toInt(),       // 亮紫选中色（贴合主题 accent）
            tagSelectedAlpha = 100,
            cornerScale = 1f,                            // 贴卡片圆角
            updatedAt = System.currentTimeMillis()
        )
        val existingTopBar = TopBarConfig.loadLocalOnlyForKit(true)
            .firstOrNull { it.config.name == DARK_PURPLE_TOP_BAR_NAME }
        val topBarDir = if (existingTopBar != null) {
            TopBarConfig.addOrUpdate(targetTopBar, existingTopBar).dirName
        } else {
            TopBarConfig.addOrUpdate(targetTopBar).dirName
        }
        // 3) 外观套件索引（幂等：同 id 覆盖）
        val kit = StoredAppearanceKit(
            id = DARK_PURPLE_KIT_ID,
            name = DARK_PURPLE_THEME_NAME,
            binding = KitBinding(
                preset = MainLayoutPresetConfig.PRESET_REGULAR,
                nightTheme = ComponentRef(dirName = themeDir, name = DARK_PURPLE_THEME_NAME),
                nightTopBar = ComponentRef(dirName = topBarDir, name = DARK_PURPLE_TOP_BAR_NAME)
            ),
            importedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        saveOrReplaceKit(kit)
        kit
    }

    fun currentKitId(): String {
        return appCtx.getPrefString(PreferKey.currentAppearanceKitId, "")
            ?.takeIf { it.isNotBlank() }
            ?: when (MainLayoutPresetConfig.currentPreset()) {
                MainLayoutPresetConfig.PRESET_REGULAR -> KIT_REGULAR
                MainLayoutPresetConfig.PRESET_SIDEBAR -> KIT_SIDEBAR
                else -> if (AppConfig.floatingBottomBarHideSearch) KIT_FLOATING_NO_SEARCH else KIT_FLOATING
            }
    }

    suspend fun apply(context: Context, kit: AppearanceKit) {
        currentModeThemeMutex.lock()
        try {
            currentModeThemeGeneration.incrementAndGet()
            val binding = kit.binding ?: loadIndex().firstOrNull { it.id == kit.id }?.binding
            applyBinding(context, binding ?: KitBinding(preset = MainLayoutPresetConfig.PRESET_DEFAULT))
            context.putPrefString(PreferKey.currentAppearanceKitId, kit.id)
            postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
            postEvent(EventBus.RECREATE, "")
        } finally {
            currentModeThemeMutex.unlock()
        }
    }

    suspend fun applyCurrentModeTheme(
        context: Context,
        isNightTheme: Boolean = AppConfig.isNightTheme
    ): Boolean {
        val generation = currentModeThemeGeneration.incrementAndGet()
        currentModeThemeMutex.lock()
        try {
            currentCoroutineContext().ensureActive()
            if (generation != currentModeThemeGeneration.get()) return false
            val binding = loadIndex().firstOrNull { it.id == currentKitId() }?.binding
            currentCoroutineContext().ensureActive()
            if (generation != currentModeThemeGeneration.get()) return false
            if (binding != null) {
                applyCurrentThemeRef(context, binding, isNightTheme)
            }
            currentCoroutineContext().ensureActive()
            if (generation != currentModeThemeGeneration.get()) return false
            ThemeConfig.applyTheme(context, isNightTheme)
            BookCover.upDefaultCover()
            return true
        } finally {
            currentModeThemeMutex.unlock()
        }
    }

    suspend fun deleteImportedTheme(context: Context, kit: AppearanceKit): Boolean = withContext(IO) {
        if (kit.type != AppearanceKitType.IMPORTED_THEME) return@withContext false
        if (appCtx.getPrefString(PreferKey.currentAppearanceKitId, "") == kit.id) {
            throw IllegalArgumentException(context.getString(io.legado.app.R.string.theme_delete_applied_forbidden))
        }
        val kits = loadIndex()
        val target = kits.firstOrNull { it.id == kit.id } ?: return@withContext false
        deleteExclusiveComponents(target, kits.filterNot { it.id == target.id })
        saveIndex(kits.filterNot { it.id == target.id })
        true
    }

    suspend fun renameKit(kit: AppearanceKit, name: String): Boolean = withContext(IO) {
        if (kit.type != AppearanceKitType.IMPORTED_THEME) return@withContext false
        val nextName = name.trim().ifBlank { return@withContext false }
        val kits = loadIndex()
        val index = kits.indexOfFirst { it.id == kit.id }
        if (index < 0) return@withContext false
        if (kits.any { it.id != kit.id && it.name == nextName }) {
            throw IllegalArgumentException("应用主题名称已存在")
        }
        val next = kits.toMutableList()
        next[index] = next[index].copy(name = nextName, updatedAt = System.currentTimeMillis())
        saveIndex(next)
        if (appCtx.getPrefString(PreferKey.currentAppearanceKitId, "") == kit.id) {
            postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
            postEvent(EventBus.RECREATE, "")
        }
        true
    }

    suspend fun importedKit(id: String): StoredAppearanceKit? = withContext(IO) {
        loadIndex().firstOrNull { it.id == id }
    }

    suspend fun createFromCurrent(context: Context, name: String): StoredAppearanceKit = withContext(IO) {
        val nextName = name.trim().ifBlank { appCtx.getString(io.legado.app.R.string.appearance_kit_manage) }
        val kits = loadIndex()
        if (kits.any { it.name == nextName }) {
            throw IllegalArgumentException(appCtx.getString(io.legado.app.R.string.theme_name_exists))
        }
        ThemePackageManager.ensureLocalAppliedTheme(context, false)
        ThemePackageManager.ensureLocalAppliedTheme(context, true)
        val now = System.currentTimeMillis()
        val kit = StoredAppearanceKit(
            id = "kit_${UUID.randomUUID()}",
            name = nextName,
            binding = currentBinding(context),
            importedAt = now,
            updatedAt = now
        )
        saveIndex(kits + kit)
        context.putPrefString(PreferKey.currentAppearanceKitId, kit.id)
        kit
    }

    suspend fun saveImportedKit(kit: StoredAppearanceKit, context: Context = appCtx): Boolean = withContext(IO) {
        val nextName = kit.name.trim().ifBlank { return@withContext false }
        val kits = loadIndex()
        if (kits.any { it.id != kit.id && it.name == nextName }) {
            throw IllegalArgumentException("应用主题名称已存在")
        }
        val target = kits.firstOrNull { it.id == kit.id } ?: return@withContext false
        val next = kit.copy(
            id = target.id,
            name = nextName,
            importedAt = target.importedAt,
            updatedAt = System.currentTimeMillis()
        )
        saveIndex(kits.map { if (it.id == target.id) next else it })
        if (appCtx.getPrefString(PreferKey.currentAppearanceKitId, "") == target.id) {
            applyBinding(context, next.binding)
            postEvent(EventBus.MAIN_APPEARANCE_KIT_CHANGED, true)
            postEvent(EventBus.RECREATE, "")
        }
        true
    }

    suspend fun syncCurrentThemeRef(isNight: Boolean, entry: ThemePackageManager.Entry?): Boolean {
        val ref = entry
            ?.takeIf { it.source != ThemePackageManager.Source.BUILTIN }
            ?.let { ComponentRef(it.dirName, it.packageInfo.name) }
        return updateCurrentBinding { binding ->
            binding.setTheme(isNight, ref)
        }
    }

    suspend fun syncCurrentTopBarRef(isNight: Boolean, entry: TopBarConfig.Entry?): Boolean {
        val ref = entry
            ?.takeIf { it.dirName != TopBarConfig.DEFAULT_DIR_NAME }
            ?.let { ComponentRef(it.dirName, it.config.name) }
        return updateCurrentBinding { binding ->
            binding.setTopBar(isNight, ref)
        }
    }

    suspend fun syncCurrentNavigationBarRef(isNight: Boolean, entry: NavigationBarIconConfig.Entry?): Boolean {
        val ref = entry
            ?.takeIf { it.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME }
            ?.let { ComponentRef(it.dirName, it.config.name) }
        return updateCurrentBinding { binding ->
            binding.setNavigationBar(isNight, ref)
        }
    }

    suspend fun syncCurrentCoverCollectionRef(isNight: Boolean, collection: CoverCollectionManager.Collection?): Boolean {
        val ref = collection?.let { ComponentRef(it.id, it.name) }
        return updateCurrentBinding { binding ->
            binding.setCoverCollection(isNight, ref)
        }
    }

    private suspend fun updateCurrentBinding(update: (KitBinding) -> Unit): Boolean = withContext(IO) {
        val currentId = appCtx.getPrefString(PreferKey.currentAppearanceKitId, "")
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext false
        val kits = loadIndex()
        val index = kits.indexOfFirst { it.id == currentId }
        if (index < 0) return@withContext false
        val nextBinding = kits[index].binding.copy()
        update(nextBinding)
        val nextKits = kits.toMutableList()
        nextKits[index] = kits[index].copy(
            binding = nextBinding,
            updatedAt = System.currentTimeMillis()
        )
        saveIndex(nextKits)
        true
    }

    suspend fun editableOptions(isNight: Boolean): AppearanceKitEditOptions = withContext(IO) {
        AppearanceKitEditOptions(
            themes = ThemePackageManager.loadLocalOnly(isNight)
                .filter { it.source != ThemePackageManager.Source.BUILTIN }
                .map { ComponentRef(it.dirName, it.packageInfo.name) },
            topBars = listOf(ComponentRef(TopBarConfig.DEFAULT_DIR_NAME, "默认顶栏")) +
                TopBarConfig.loadLocalOnlyForKit(isNight)
                    .filter { it.dirName != TopBarConfig.DEFAULT_DIR_NAME }
                    .map { ComponentRef(it.dirName, it.config.name) },
            navigationBars = NavigationBarIconConfig.loadLocalOnlyForKit(isNight)
                .filter { it.dirName != NavigationBarIconConfig.DEFAULT_DIR_NAME }
                .map { ComponentRef(it.dirName, it.config.name) },
            coverCollections = CoverCollectionManager.loadEntries(isNight)
                .filter { it.source != CoverCollectionManager.Source.REMOTE }
                .map { ComponentRef(it.collection.id, it.collection.name) }
        )
    }

    suspend fun importPackage(file: File): ImportResult = withContext(IO) {
        val kitZip = appearanceKitZipPayload(file)
        if (kitZip != null) {
            try {
                importAppearanceKit(kitZip)
            } finally {
                if (kitZip != file) kitZip.delete()
            }
        } else {
            val result = ThemePackageManager.importPackageDetailed(file)
            val kit = createKitFromImportResult(result)
            ImportResult(
                themeCount = result.themes.size,
                navigationBarCount = result.navigationBars.size,
                coverCollectionCount = result.coverCollections.size,
                kitCount = if (kit != null) 1 else 0
            )
        }
    }

    suspend fun exportCurrent(context: Context): File = withContext(IO) {
        val packageDir = tempDir.getFile("export_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        val components = mutableListOf<KitComponent>()
        try {
            exportThemes(context, packageDir, components)
            exportTopBars(context, packageDir, components)
            exportNavigationBars(packageDir, components)
            exportCoverCollections(packageDir, components)
            val manifest = AppearanceKitPackage(
                id = UUID.randomUUID().toString(),
                name = "应用主题",
                version = kitVersion,
                exportedAt = System.currentTimeMillis(),
                binding = currentBinding(context),
                components = components
            )
            File(packageDir, kitManifestName).writeText(GSON.toJson(manifest))
            val zipFile = tempDir.getFile("appearance_kit_${System.currentTimeMillis()}.zip")
            if (zipFile.exists()) zipFile.delete()
            val packageFiles = packageDir.listFiles()?.toList().orEmpty()
            if (!ZipUtils.zipFiles(packageFiles, zipFile) || !zipFile.isFile || zipFile.length() <= 0L) {
                throw IllegalStateException("Export failed")
            }
            zipFile
        } finally {
            FileUtils.delete(packageDir, deleteRootDir = true)
        }
    }

    fun isAppearanceKitPackage(file: File): Boolean {
        val kitZip = appearanceKitZipPayload(file) ?: return false
        if (kitZip != file) kitZip.delete()
        return true
    }

    private suspend fun importAppearanceKit(file: File): ImportResult {
        val unzipDir = tempDir.getFile("import_${System.currentTimeMillis()}").apply {
            if (exists()) FileUtils.delete(this, deleteRootDir = true)
            mkdirs()
        }
        var result = ImportResult()
        try {
            unzipSecure(file, unzipDir)
            val manifestFile = findKitManifestFile(unzipDir)
                ?: throw IllegalArgumentException(appCtx.getString(io.legado.app.R.string.appearance_kit_manifest_missing))
            val packageRoot = manifestFile.parentFile ?: unzipDir
            val manifest = GSON.fromJsonObject<AppearanceKitPackage>(
                manifestFile.readTextLimited(maxKitManifestBytes)
            ).getOrThrow()
            val importedBinding = KitBinding()
            manifest.components.forEach { component ->
                val componentFile = resolveKitComponentFile(packageRoot, unzipDir, component.path) ?: return@forEach
                when (component.type) {
                    KitComponentType.THEME.name -> {
                        val entry = ThemePackageManager.importZip(componentFile)
                        importedBinding.setTheme(component.isNight, ComponentRef(entry.dirName, entry.packageInfo.name))
                        result = result.copy(themeCount = result.themeCount + 1)
                    }
                    KitComponentType.TOP_BAR.name -> {
                        val entry = TopBarConfig.importZip(componentFile)
                        importedBinding.setTopBar(component.isNight, ComponentRef(entry.dirName, entry.config.name))
                        result = result.copy(topBarCount = result.topBarCount + 1)
                    }
                    KitComponentType.NAVIGATION_BAR.name -> {
                        val entry = NavigationBarIconConfig.importZip(componentFile)
                        importedBinding.setNavigationBar(component.isNight, ComponentRef(entry.dirName, entry.config.name))
                        result = result.copy(navigationBarCount = result.navigationBarCount + 1)
                    }
                    KitComponentType.COVER_COLLECTION.name -> {
                        val collection = CoverCollectionManager.importZip(appCtx, componentFile, component.isNight)
                        importedBinding.setCoverCollection(component.isNight, ComponentRef(collection.id, collection.name))
                        result = result.copy(coverCollectionCount = result.coverCollectionCount + 1)
                    }
                }
            }
            val finalBinding = manifest.binding.mergeImported(importedBinding)
            saveOrReplaceKit(
                StoredAppearanceKit(
                    id = manifest.id.ifBlank { "kit_${manifest.name.normalizeFileName()}_${System.currentTimeMillis()}" },
                    name = manifest.name.ifBlank { "应用主题" },
                    previewPath = manifest.previewPath,
                    binding = finalBinding,
                    importedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            result = result.copy(kitCount = result.kitCount + 1)
            return result
        } finally {
            FileUtils.delete(unzipDir, deleteRootDir = true)
        }
    }

    private fun appearanceKitZipPayload(file: File): File? {
        if (hasKitManifest(file)) return file
        val payload = RedAssetPackage.zipPayload(file, tempDir) ?: return null
        if (hasKitManifest(payload)) return payload
        payload.delete()
        return null
    }

    private fun hasKitManifest(file: File): Boolean {
        return runCatching {
            ZipFile(file).use { zip -> zip.findKitManifestEntry() != null }
        }.getOrDefault(false)
    }

    private fun ZipFile.findKitManifestEntry(): ZipEntry? {
        return entries().asSequence().firstOrNull { entry ->
            !entry.isDirectory && entry.name.isKitManifestPath()
        }
    }

    private fun String.isKitManifestPath(): Boolean {
        val normalized = replace('\\', '/').trim('/')
        return normalized == kitManifestName || normalized.endsWith("/$kitManifestName")
    }

    private fun findKitManifestFile(root: File): File? {
        return root.walkTopDown().firstOrNull { it.isFile && it.name == kitManifestName }
    }

    private fun resolveKitComponentFile(packageRoot: File, unzipRoot: File, path: String): File? {
        val normalized = path.replace('\\', '/').trim('/').takeIf { it.isNotBlank() } ?: return null
        if (File(normalized).isAbsolute) return null
        return listOf(
            File(packageRoot, normalized),
            File(unzipRoot, normalized)
        ).firstOrNull { it.isFile && it.isSameOrSubFileOf(unzipRoot) }
    }

    private suspend fun applyBinding(context: Context, binding: KitBinding) {
        val currentNight = AppConfig.isNightTheme
        val resolvedPreset = binding.preset?.takeIf { it.isNotBlank() } ?: MainLayoutPresetConfig.PRESET_REGULAR
        MainLayoutPresetConfig.apply(context, resolvedPreset, notify = false)
        // Apply only the active mode. The other mode remains an independent
        // theme reference and is projected when day/night mode switches.
        applyCurrentThemeRef(context, binding, currentNight)
        applyTopBarRef(false, binding.dayTopBar)
        applyTopBarRef(true, binding.nightTopBar)
        applyNavigationRef(false, binding.dayNavigationBar)
        applyNavigationRef(true, binding.nightNavigationBar)
        CoverCollectionManager.setSelected(false, binding.dayCoverCollection?.dirName)
        CoverCollectionManager.setSelected(true, binding.nightCoverCollection?.dirName)
        if (AppConfig.isNightTheme != currentNight) {
            AppConfig.isNightTheme = currentNight
        }
        NavigationBarIconConfig.applyCurrentBottomConfig(currentNight)
        // 主题包显式携带的"底栏隐藏悬浮搜索"覆盖在底栏配置之后生效(隐藏时顶栏会自动显示搜索)。
        binding.floatingBottomBarHideSearch?.let { AppConfig.floatingBottomBarHideSearch = it }
        ThemeConfig.applyTheme(context)
        BookCover.upDefaultCover()
    }

    private suspend fun applyThemeRef(context: Context, isNight: Boolean, ref: ComponentRef?) {
        val entry = ref?.let { findThemeEntry(isNight, it) } ?: ThemePackageManager.builtinEntryForKit(isNight)
        ThemePackageManager.apply(context, entry, switchNightMode = false, notify = false)
    }

    private suspend fun applyCurrentThemeRef(context: Context, binding: KitBinding, isNight: Boolean) {
        applyThemeRef(context, isNight, if (isNight) binding.nightTheme else binding.dayTheme)
    }

    private suspend fun applyTopBarRef(isNight: Boolean, ref: ComponentRef?) {
        val entry = when (ref?.dirName) {
            TopBarConfig.DEFAULT_DIR_NAME -> TopBarConfig.defaultEntryForKit(appCtx, isNight)
            else -> ref?.let { findTopBarEntry(isNight, it) }
        }
        // 缺省顶栏样式由 MainLayoutPresetConfig.apply 写入的 defaultTopBarStyle 决定，
        // apply() 仅持久化 dirName(=DEFAULT)，故无需按 preset 区分 fallback entry。
        TopBarConfig.apply(entry ?: TopBarConfig.defaultEntryForKit(appCtx, isNight))
    }

    private suspend fun applyNavigationRef(isNight: Boolean, ref: ComponentRef?) {
        val entry = ref?.let { findNavigationEntry(isNight, it) }
        if (entry != null) {
            NavigationBarIconConfig.select(entry)
        } else {
            NavigationBarIconConfig.select(NavigationBarIconConfig.standardEntryForKit(isNight))
        }
    }

    private suspend fun findThemeEntry(isNight: Boolean, ref: ComponentRef): ThemePackageManager.Entry? {
        val entries = ThemePackageManager.loadLocalOnly(isNight)
        if (ref.dirName.isNotBlank()) {
            entries.firstOrNull { it.dirName == ref.dirName }?.let { return it }
        }
        return entries.firstOrNull { it.packageInfo.name == ref.name }
    }

    private suspend fun findTopBarEntry(isNight: Boolean, ref: ComponentRef): TopBarConfig.Entry? {
        return TopBarConfig.loadLocalOnlyForKit(isNight).firstOrNull {
            it.dirName == ref.dirName || it.config.name == ref.name
        }
    }

    private suspend fun findNavigationEntry(isNight: Boolean, ref: ComponentRef): NavigationBarIconConfig.Entry? {
        return NavigationBarIconConfig.loadLocalOnlyForKit(isNight).firstOrNull {
            it.dirName == ref.dirName || it.config.name == ref.name
        }
    }

    private fun createKitFromImportResult(result: ThemePackageManager.ThemeImportResult): StoredAppearanceKit? {
        if (result.themes.isEmpty()) return null
        val name = result.sourceName.ifBlank {
            result.themes.first().packageInfo.name.ifBlank { "应用主题" }
        }
        val binding = KitBinding()
        result.themes.forEach {
            binding.setTheme(it.packageInfo.isNightTheme, ComponentRef(it.dirName, it.packageInfo.name))
        }
        result.navigationBars.forEach {
            binding.setNavigationBar(it.config.isNightMode, ComponentRef(it.dirName, it.config.name))
        }
        if (binding.dayNavigationBar == null) {
            result.navigationBars.firstOrNull { it.config.isNightMode }?.let { source ->
                NavigationBarIconConfig.copyEntryForMode(source, false)?.let {
                    binding.setNavigationBar(false, ComponentRef(it.dirName, it.config.name))
                }
            }
        }
        if (binding.nightNavigationBar == null) {
            result.navigationBars.firstOrNull { !it.config.isNightMode }?.let { source ->
                NavigationBarIconConfig.copyEntryForMode(source, true)?.let {
                    binding.setNavigationBar(true, ComponentRef(it.dirName, it.config.name))
                }
            }
        }
        result.coverCollections.forEach {
            binding.setCoverCollection(it.isNight, ComponentRef(it.id, it.name))
        }
        return saveOrReplaceKit(
            StoredAppearanceKit(
                id = "theme_${name.normalizeFileName()}",
                name = name,
                binding = binding,
                importedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun migrateLegacyThemeKits() {
        if (loadIndex().isNotEmpty()) return
        val day = ThemePackageManager.loadLocalOnly(false)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        val night = ThemePackageManager.loadLocalOnly(true)
            .filter { it.source != ThemePackageManager.Source.BUILTIN }
        val kits = (day + night).groupBy { it.packageInfo.name }.map { (name, entries) ->
            val binding = KitBinding()
            entries.forEach {
                binding.setTheme(it.packageInfo.isNightTheme, ComponentRef(it.dirName, it.packageInfo.name))
            }
            StoredAppearanceKit(
                id = "theme_${name.normalizeFileName()}",
                name = name,
                binding = binding,
                importedAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        }
        if (kits.isNotEmpty()) saveIndex(kits)
    }

    private suspend fun currentBinding(context: Context): KitBinding {
        return KitBinding(
            preset = MainLayoutPresetConfig.currentPreset(),
            dayTheme = currentThemeRef(context, false),
            nightTheme = currentThemeRef(context, true),
            dayTopBar = currentTopBarRef(context, false),
            nightTopBar = currentTopBarRef(context, true),
            dayNavigationBar = currentNavigationRef(false),
            nightNavigationBar = currentNavigationRef(true),
            dayCoverCollection = currentCoverRef(false),
            nightCoverCollection = currentCoverRef(true),
            floatingBottomBarHideSearch = AppConfig.floatingBottomBarHideSearch
        )
    }

    private fun currentThemeRef(context: Context, isNight: Boolean): ComponentRef? {
        val name = context.getPrefString(if (isNight) PreferKey.dNThemeName else PreferKey.dThemeName).orEmpty()
        if (name.isBlank() || ThemePackageManager.isBuiltinThemeForKit(isNight, name)) return null
        val dirName = context.getPrefString(
            if (isNight) PreferKey.dNThemeDirName else PreferKey.dThemeDirName
        ).orEmpty().ifBlank { name.normalizeFileName() }
        return ComponentRef(dirName, name)
    }

    private fun currentTopBarRef(context: Context, isNight: Boolean): ComponentRef? {
        val entry = TopBarConfig.currentEntry(context, isNight)
        if (entry.dirName == TopBarConfig.DEFAULT_DIR_NAME) return null
        return ComponentRef(entry.dirName, entry.config.name)
    }

    private fun currentNavigationRef(isNight: Boolean): ComponentRef? {
        val entry = NavigationBarIconConfig.currentEntry(isNight)
        if (entry.dirName == NavigationBarIconConfig.DEFAULT_DIR_NAME) return null
        return ComponentRef(entry.dirName, entry.config.name)
    }

    private suspend fun currentCoverRef(isNight: Boolean): ComponentRef? {
        val entry = CoverCollectionManager.selectedEntry(isNight) ?: return null
        return ComponentRef(entry.collection.id, entry.collection.name)
    }

    private suspend fun deleteExclusiveComponents(target: StoredAppearanceKit, others: List<StoredAppearanceKit>) {
        val usedThemeRefs = others.flatMap { listOfNotNull(it.binding.dayTheme, it.binding.nightTheme) }.map { it.key() }.toSet()
        val usedTopRefs = others.flatMap { listOfNotNull(it.binding.dayTopBar, it.binding.nightTopBar) }.map { it.key() }.toSet()
        val usedNavRefs = others.flatMap { listOfNotNull(it.binding.dayNavigationBar, it.binding.nightNavigationBar) }.map { it.key() }.toSet()
        val usedCoverRefs = others.flatMap { listOfNotNull(it.binding.dayCoverCollection, it.binding.nightCoverCollection) }.map { it.key() }.toSet()

        listOf(false to target.binding.dayTheme, true to target.binding.nightTheme).forEach { (isNight, ref) ->
            if (ref != null && ref.key() !in usedThemeRefs) {
                findThemeEntry(isNight, ref)?.let { ThemePackageManager.deleteLocal(it) }
            }
        }
        listOf(false to target.binding.dayTopBar, true to target.binding.nightTopBar).forEach { (isNight, ref) ->
            if (ref != null && ref.key() !in usedTopRefs) {
                findTopBarEntry(isNight, ref)?.let { TopBarConfig.deleteLocal(it) }
            }
        }
        listOf(false to target.binding.dayNavigationBar, true to target.binding.nightNavigationBar).forEach { (isNight, ref) ->
            if (ref != null && ref.key() !in usedNavRefs) {
                findNavigationEntry(isNight, ref)?.let { NavigationBarIconConfig.deleteLocal(it) }
            }
        }
        listOf(false to target.binding.dayCoverCollection, true to target.binding.nightCoverCollection).forEach { (isNight, ref) ->
            if (ref != null && ref.key() !in usedCoverRefs) {
                CoverCollectionManager.localEntryForKit(isNight, ref.dirName, ref.name)
                    ?.let { CoverCollectionManager.deleteLocal(it) }
            }
        }
    }

    private fun loadIndex(): List<StoredAppearanceKit> {
        if (!indexFile.isFile) return emptyList()
        return GSON.fromJsonArray<StoredAppearanceKit>(indexFile.readText()).getOrDefault(emptyList())
    }

    private fun saveIndex(kits: List<StoredAppearanceKit>) {
        indexFile.parentFile?.mkdirs()
        indexFile.writeText(GSON.toJson(kits.sortedBy { it.name }))
    }

    private fun saveOrReplaceKit(kit: StoredAppearanceKit): StoredAppearanceKit {
        val list = loadIndex().filterNot { it.id == kit.id || it.name == kit.name } + kit
        saveIndex(list)
        return kit
    }

    private fun unzipSecure(zipFile: File, targetDir: File) {
        SafeZipExtractor.extract(zipFile, targetDir, kitZipLimits)
    }

    private suspend fun exportThemes(
        context: Context,
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = ThemePackageManager.ensureLocalAppliedTheme(context, isNight)
            if (entry.source == ThemePackageManager.Source.BUILTIN) return@forEach
            val zip = ThemePackageManager.exportZip(entry)
            val targetName = "theme_${if (isNight) "night" else "day"}.zip"
            zip.copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.THEME.name, isNight, targetName)
        }
    }

    private suspend fun exportTopBars(
        context: Context,
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = TopBarConfig.currentEntry(context, isNight)
            if (entry.dirName == TopBarConfig.DEFAULT_DIR_NAME) return@forEach
            val targetName = "top_bar_${if (isNight) "night" else "day"}.zip"
            TopBarConfig.exportZip(entry).copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.TOP_BAR.name, isNight, targetName)
        }
    }

    private suspend fun exportNavigationBars(
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = NavigationBarIconConfig.currentEntry(isNight)
            if (entry.dirName == NavigationBarIconConfig.DEFAULT_DIR_NAME) return@forEach
            val targetName = "navigation_bar_${if (isNight) "night" else "day"}.zip"
            NavigationBarIconConfig.exportZip(entry).copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.NAVIGATION_BAR.name, isNight, targetName)
        }
    }

    private suspend fun exportCoverCollections(
        packageDir: File,
        components: MutableList<KitComponent>
    ) {
        listOf(false, true).forEach { isNight ->
            val entry = CoverCollectionManager.selectedEntry(isNight) ?: return@forEach
            val targetName = "cover_collection_${if (isNight) "night" else "day"}.zip"
            CoverCollectionManager.exportZip(entry).copyTo(File(packageDir, targetName), overwrite = true)
            components += KitComponent(KitComponentType.COVER_COLLECTION.name, isNight, targetName)
        }
    }
}

data class ImportResult(
    val themeCount: Int = 0,
    val topBarCount: Int = 0,
    val navigationBarCount: Int = 0,
    val coverCollectionCount: Int = 0,
    val kitCount: Int = 0
) {
    val total: Int
        get() = themeCount + topBarCount + navigationBarCount + coverCollectionCount + kitCount
}

@Keep
private data class AppearanceKitPackage(
    val id: String = "",
    val name: String = "",
    val version: Int = 0,
    val exportedAt: Long = 0L,
    val previewPath: String? = null,
    val binding: KitBinding = KitBinding(),
    val components: List<KitComponent> = emptyList()
)

@Keep
private data class KitComponent(
    val type: String = "",
    val isNight: Boolean = false,
    val path: String = ""
)

private enum class KitComponentType {
    THEME,
    TOP_BAR,
    NAVIGATION_BAR,
    COVER_COLLECTION
}

data class AppearanceKit(
    val id: String,
    val name: String,
    val summary: String,
    val type: AppearanceKitType,
    val binding: KitBinding? = null,
    val previewPath: String? = null
)

enum class AppearanceKitType {
    BUILTIN,
    IMPORTED_THEME
}

@Keep
data class StoredAppearanceKit(
    val id: String = "",
    val name: String = "",
    val previewPath: String? = null,
    val binding: KitBinding = KitBinding(),
    val importedAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    fun toAppearanceKit(): AppearanceKit {
        val parts = listOfNotNull(
            binding.dayTheme?.let { "日间主题" },
            binding.nightTheme?.let { "夜间主题" },
            binding.dayNavigationBar?.let { "底栏" } ?: binding.nightNavigationBar?.let { "底栏" },
            binding.dayTopBar?.let { "顶栏" } ?: binding.nightTopBar?.let { "顶栏" },
            binding.dayCoverCollection?.let { "封面" } ?: binding.nightCoverCollection?.let { "封面" }
        ).distinct()
        return AppearanceKit(
            id = id,
            name = name,
            summary = if (parts.isEmpty()) "应用后缺失组件将恢复默认" else parts.joinToString(" / "),
            type = AppearanceKitType.IMPORTED_THEME,
            binding = binding,
            previewPath = previewPath
        )
    }
}

@Keep
data class KitBinding(
    var preset: String? = null,
    var dayTheme: ComponentRef? = null,
    var nightTheme: ComponentRef? = null,
    var dayTopBar: ComponentRef? = null,
    var nightTopBar: ComponentRef? = null,
    var dayNavigationBar: ComponentRef? = null,
    var nightNavigationBar: ComponentRef? = null,
    var dayCoverCollection: ComponentRef? = null,
    var nightCoverCollection: ComponentRef? = null,
    // 悬浮底栏是否隐藏搜索按钮(隐藏时顶栏自动显示搜索)。可空：旧主题包无此字段时不改变现状。
    var floatingBottomBarHideSearch: Boolean? = null
) {
    fun mergeImported(imported: KitBinding): KitBinding {
        return copy(
            dayTheme = imported.dayTheme ?: dayTheme,
            nightTheme = imported.nightTheme ?: nightTheme,
            dayTopBar = imported.dayTopBar ?: dayTopBar,
            nightTopBar = imported.nightTopBar ?: nightTopBar,
            dayNavigationBar = imported.dayNavigationBar ?: dayNavigationBar,
            nightNavigationBar = imported.nightNavigationBar ?: nightNavigationBar,
            dayCoverCollection = imported.dayCoverCollection ?: dayCoverCollection,
            nightCoverCollection = imported.nightCoverCollection ?: nightCoverCollection,
            floatingBottomBarHideSearch = imported.floatingBottomBarHideSearch ?: floatingBottomBarHideSearch
        )
    }

    fun setTheme(isNight: Boolean, ref: ComponentRef?) {
        if (isNight) {
            nightTheme = ref
        } else {
            dayTheme = ref
        }
    }

    fun setTopBar(isNight: Boolean, ref: ComponentRef?) {
        if (isNight) {
            nightTopBar = ref
        } else {
            dayTopBar = ref
        }
    }

    fun setNavigationBar(isNight: Boolean, ref: ComponentRef?) {
        if (isNight) {
            nightNavigationBar = ref
        } else {
            dayNavigationBar = ref
        }
    }

    fun setCoverCollection(isNight: Boolean, ref: ComponentRef?) {
        if (isNight) {
            nightCoverCollection = ref
        } else {
            dayCoverCollection = ref
        }
    }
}

@Keep
data class AppearanceKitEditOptions(
    val themes: List<ComponentRef> = emptyList(),
    val topBars: List<ComponentRef> = emptyList(),
    val navigationBars: List<ComponentRef> = emptyList(),
    val coverCollections: List<ComponentRef> = emptyList()
)

@Keep
data class ComponentRef(
    val dirName: String = "",
    val name: String = ""
) {
    fun key(): String = dirName.ifBlank { name }
}