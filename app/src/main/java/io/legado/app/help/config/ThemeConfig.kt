package io.legado.app.help.config

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.graphics.toColorInt
import androidx.core.graphics.ColorUtils as AndroidColorUtils
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.constant.Theme
import io.legado.app.help.DefaultData
import io.legado.app.lib.theme.ThemeStore
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.defaultThemeTextColor
import io.legado.app.lib.theme.defaultThemeTextColorHex
import io.legado.app.model.BookCover
import io.legado.app.ui.theme.ThemeSync
import io.legado.app.utils.BitmapUtils
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.externalFiles
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.hexString
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.postEvent
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putPrefInt
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.stackBlur
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import androidx.core.graphics.drawable.toDrawable
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.CenterCropBitmapDrawable
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Keep
object ThemeConfig {

    private const val MAX_REMOTE_BACKGROUND_BYTES = 32L * 1024L * 1024L
    private const val MAX_REMOTE_BACKGROUND_PIXELS = 100_000_000L
    private const val MAX_NINE_PATCH_BACKGROUND_PIXELS = 4_000_000L
    private const val MAX_NINE_PATCH_DIMENSION = 4096
    private const val VALIDATION_DECODE_PIXELS = 1_000_000L
    private val backgroundDownloadLocks = ConcurrentHashMap<String, Mutex>()
    private var usableBgImageCacheKey: String? = null
    private var usableBgImageCacheValue: Boolean = false
    const val configFileName = "themeConfig.json"
    const val PANEL_BG_CROP = "crop"
    const val PANEL_BG_FIT = "fit"
    val configFilePath = FileUtils.getPath(appCtx.filesDir, configFileName)
    private val configMutationLock = Any()
    private val configStore by lazy { AtomicTextFileStore(File(configFilePath)) }

    @Volatile
    private var configSnapshot: List<Config>? = null
    val configList: List<Config>
        get() = configSnapshot ?: synchronized(configMutationLock) {
            configSnapshot ?: normalizeConfigList(getConfigs() ?: DefaultData.themeConfigs).also {
                configSnapshot = it
            }
        }

    private var needClearImg = true

    private fun clearUsableBgImageCache() {
        usableBgImageCacheKey = null
        usableBgImageCacheValue = false
    }

    fun getTheme() = when {
        AppConfig.isEInkMode -> Theme.EInk
        AppConfig.isNightTheme -> Theme.Dark
        else -> Theme.Light
    }

    fun isDarkTheme(): Boolean {
        return getTheme() == Theme.Dark
    }

    fun applyDayNight(context: Context, isNightTheme: Boolean = AppConfig.isNightTheme) {
        clearUsableBgImageCache()
        applyTheme(context, isNightTheme)
        initNightMode(isNightTheme)
        BookCover.upDefaultCover()
        // T6（theme-arch-gap）：MAIN_THEME_BACKGROUND_CHANGED 死事件已删（4 发 0 订阅），背景刷新统一走 RECREATE+ThemeSync
        postEvent(EventBus.RECREATE, "")
    }

    fun applyDayNightInit(context: Context) {
        val isNightTheme = AppConfig.isNightTheme
        applyTheme(context, isNightTheme)
        initNightMode(isNightTheme)
    }

    private fun initNightMode(isNightTheme: Boolean = AppConfig.isNightTheme) {
        val targetMode =
            if (isNightTheme) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        AppCompatDelegate.setDefaultNightMode(targetMode)
    }

    /**
     * 获取链接获取图片文件名
     * F-P0-2 备份选择器：Backup.kt 需要调用此方法解析主题背景图路径，故改为 public
     */
    fun getUrlToFile(url: String): String {
        val suffix = when {
            url.contains(".9.png", ignoreCase = true) -> ".9.png"
            url.contains(".png", ignoreCase = true) -> ".png"
            url.contains(".gif", ignoreCase = true) -> ".gif"
            url.contains("webp", ignoreCase = true) -> ".webp"
            else -> ".jpg"
        }
        return MD5Utils.md5Encode16(url) + suffix
    }

    fun getBgImage(context: Context, metrics: DisplayMetrics): Drawable? {
        val themeMode = getTheme()
        val preferenceKey = when (themeMode) {
            Theme.Light -> PreferKey.bgImage
            Theme.Dark -> PreferKey.bgImageN
            else -> return  null
        }
        var path = context.getPrefString(preferenceKey)
        if (path.isNullOrBlank()) return null
        if (path.startsWith("http")) {
            val name = getUrlToFile(path)
            val fileRoot = context.externalFiles
            val filePath = FileUtils.getPath(fileRoot, preferenceKey, name)
            if (!isUsableThemeImage(File(filePath))) return null
            path = filePath
        }
        if (path.endsWith(".9.png")) {
            val bgDrawable = BitmapUtils.decodeNinePatchDrawable(path)
            return bgDrawable
        }
        val bgImgBlu = when (themeMode) {
            Theme.Light -> context.getPrefInt(PreferKey.bgImageBlurring, 0)
            Theme.Dark -> context.getPrefInt(PreferKey.bgImageNBlurring, 0)
            else -> 0
        }
        val bgImage = BitmapUtils
            .decodeBitmap(path, metrics.widthPixels, metrics.heightPixels)
        if (bgImgBlu == 0) {
            return bgImage?.let { CenterCropBitmapDrawable(context.resources, it) }
        }
        val source = bgImage ?: return null
        val blurred = try {
            source.stackBlur(bgImgBlu)
        } catch (error: Throwable) {
            if (!source.isRecycled) source.recycle()
            throw error
        }
        if (blurred !== source && !source.isRecycled) source.recycle()
        return CenterCropBitmapDrawable(context.resources, blurred)
    }

    fun hasUsableBgImage(context: Context): Boolean {
        val preferenceKey = when (getTheme()) {
            Theme.Light -> PreferKey.bgImage
            Theme.Dark -> PreferKey.bgImageN
            else -> return false
        }
        val path = context.getPrefString(preferenceKey)?.takeIf { it.isNotBlank() } ?: return false
        if (path.startsWith("http", ignoreCase = true)) {
            val file = File(FileUtils.getPath(context.externalFiles, preferenceKey, getUrlToFile(path)))
            val cacheKey = "$preferenceKey|$path|${file.length()}|${file.lastModified()}"
            if (usableBgImageCacheKey == cacheKey) return usableBgImageCacheValue
            return isUsableThemeImage(file).also {
                usableBgImageCacheKey = cacheKey
                usableBgImageCacheValue = it
            }
        }
        val cacheKey = "$preferenceKey|$path"
        if (usableBgImageCacheKey == cacheKey) return usableBgImageCacheValue
        return isReadableThemeFile(path).also {
            usableBgImageCacheKey = cacheKey
            usableBgImageCacheValue = it
        }
    }

    fun getFallbackBackgroundColor(context: Context): Int {
        return when {
            AppConfig.isEInkMode -> Color.WHITE
            AppConfig.isNightTheme -> context.getPrefInt(
                PreferKey.cNBackground,
                context.getCompatColor(R.color.md_grey_900)
            )
            else -> context.getPrefInt(
                PreferKey.cBackground,
                context.getCompatColor(R.color.md_grey_100)
            )
        }
    }

    fun upConfig() {
        synchronized(configMutationLock) {
            val refreshed = normalizeConfigList(getConfigs() ?: DefaultData.themeConfigs)
            configSnapshot = refreshed
        }
    }

    fun save() {
        synchronized(configMutationLock) {
            commitConfigList(configList.toList())
        }
    }

    fun delConfig(index: Int) {
        synchronized(configMutationLock) {
            val candidate = configList.toMutableList()
            candidate.removeAt(index)
            commitConfigList(candidate)
        }
    }

    fun addConfig(json: String): Boolean {
        GSON.fromJsonObject<Config>(json.trim { it < ' ' }).getOrNull()
            ?.let {
                if (validateConfig(it)) {
                    addConfig(it)
                    return true
                }
            }
        return false
    }

    fun addConfig(newConfig: Config) {
        if (!validateConfig(newConfig)) {
            return
        }
        synchronized(configMutationLock) {
            val candidate = configList.toMutableList()
            val existingIndex = candidate.indexOfFirst {
                newConfig.themeName == it.themeName &&
                    newConfig.isNightTheme == it.isNightTheme
            }
            if (existingIndex >= 0) {
                candidate[existingIndex] = newConfig
            } else {
                candidate.add(newConfig)
            }
            commitConfigList(candidate)
        }
    }

    fun addConfigs(newConfigs: List<Config>?) {
        val newConfigs = newConfigs?.filter{
            validateConfig(it)
        }
        if (newConfigs.isNullOrEmpty()) {
            return
        }
        synchronized(configMutationLock) {
            val candidate = configList.toMutableList()
            newConfigs.forEach { newConfig ->
                val existingIndex = candidate.indexOfFirst {
                    it.themeName == newConfig.themeName &&
                        it.isNightTheme == newConfig.isNightTheme
                }
                if (existingIndex != -1) {
                    candidate[existingIndex] = newConfig
                } else {
                    candidate.add(newConfig)
                }
            }
            commitConfigList(candidate)
        }
    }

    /**
     * 仅添加新主题，不覆盖用户已有的同名主题
     * 用于版本升级时合并新增的默认主题（DefaultData.importDefaultThemeConfigs 调用）
     */
    fun addNewConfigs(newConfigs: List<Config>?) {
        val newConfigs = newConfigs?.filter { validateConfig(it) }
        if (newConfigs.isNullOrEmpty()) {
            return
        }
        synchronized(configMutationLock) {
            val candidate = configList.toMutableList()
            var changed = false
            newConfigs.forEach { newConfig ->
                // 同名同日夜视为同一主题，仅当缺失时新增，不覆盖用户已有同名主题
                if (candidate.none { it.themeName == newConfig.themeName && it.isNightTheme == newConfig.isNightTheme }) {
                    candidate.add(newConfig)
                    changed = true
                }
            }
            if (changed) {
                commitConfigList(candidate)
            }
        }
    }

    internal fun replaceImportedConfig(
        newConfig: Config,
        replacedThemeName: String?,
        replacedDirName: String?
    ) {
        require(validateConfig(newConfig)) { "invalid imported theme config" }
        synchronized(configMutationLock) {
            val oldName = replacedThemeName?.trim().orEmpty()
            val oldDirName = replacedDirName?.trim().orEmpty()
            val candidate = configList.filterNot { config ->
                config.isNightTheme == newConfig.isNightTheme &&
                    (config.themeName == newConfig.themeName ||
                        (oldName.isNotEmpty() && config.themeName == oldName) ||
                        (oldDirName.isNotEmpty() && config.themeName.normalizeFileName() == oldDirName))
            } + newConfig
            commitConfigList(candidate)
        }
    }

    internal fun removePersistedConfig(
        isNightTheme: Boolean,
        themeName: String,
        dirName: String
    ): Boolean = synchronized(configMutationLock) {
        val normalizedName = themeName.trim()
        val normalizedDirName = dirName.trim().ifBlank { normalizedName.normalizeFileName() }
        val candidate = configList.filterNot { config ->
            config.isNightTheme == isNightTheme &&
                (config.themeName == normalizedName ||
                    config.themeName.normalizeFileName() == normalizedDirName)
        }
        if (candidate.size == configList.size) {
            false
        } else {
            commitConfigList(candidate)
            true
        }
    }

    private fun commitConfigList(candidate: List<Config>) {
        val normalized = normalizeConfigList(candidate)
        val json = GSON.toJson(normalized)
        configStore.writeVerified(json) { persistedJson ->
            val persisted = GSON.fromJsonArray<Config>(persistedJson).getOrNull()
            persisted != null && normalizeConfigList(persisted) == normalized
        }
        configSnapshot = normalized
    }

    private fun validateConfig(config: Config): Boolean {
        try {
            config.primaryColor.toColorInt()
            config.accentColor.toColorInt()
            config.backgroundColor.toColorInt()
            config.bottomBackground.toColorInt()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    private fun normalizeConfigList(configs: List<Config>): List<Config> {
        val normalized = linkedMapOf<Pair<String, Boolean>, Config>()
        configs.forEach { config ->
            if (validateConfig(config)) {
                normalized[config.themeName to config.isNightTheme] = config
            }
        }
        return normalized.values.toList()
    }

    private fun getConfigs(): List<Config>? {
        val configFile = File(configFilePath)
        configStore.recoverInterruptedCommit()
        if (configFile.exists()) {
            kotlin.runCatching {
                val json = configFile.readText()
                return GSON.fromJsonArray<Config>(json).getOrThrow()
            }.onFailure {
                it.printOnDebug()
            }
        }
        return null
    }

    fun applyConfig(
        context: Context,
        config: Config,
        switchNightMode: Boolean = true,
        notify: Boolean = true
    ) {
        try {
            clearUsableBgImageCache()
            if (needClearImg) {
                needClearImg = false
                clearBg(context)
            }
            val primary = config.primaryColor.toColorInt()
            val accent = config.accentColor.toColorInt()
            val background = config.backgroundColor.toColorInt()
            val bBackground = config.bottomBackground.toColorInt()
            val isNightTheme = config.isNightTheme
            val backgroundPath = config.backgroundImgPath
            val backgroundCrop = normalizeBackgroundCrop(config.backgroundImgCrop)
            val bookInfoBackgroundPath = config.bookInfoBackgroundImgPath
            val panelBackgroundPath = config.panelBackgroundImgPath
            val panelBackgroundScaleType = config.panelBackgroundScaleType?.takeIf {
                it == PANEL_BG_CROP || it == PANEL_BG_FIT
            } ?: PANEL_BG_CROP
            val panelBorderColor = config.panelBorderColor?.takeIf { it.isNotBlank() }
            val panelBorderAlpha = config.panelBorderAlpha?.coerceIn(0, 100) ?: 100
            config.uiCornerScale?.let {
                context.putPrefString(ThemeRuntimeKeys.uiCornerScale(isNightTheme), it.coerceIn(0f, 3f).toPlainScale())
            }
            config.uiLayoutAlpha?.let {
                context.putPrefInt(ThemeRuntimeKeys.uiLayoutAlpha(isNightTheme), it.coerceIn(0, 100))
            }
            config.dialogAlpha?.let {
                context.putPrefInt(ThemeRuntimeKeys.dialogAlpha(isNightTheme), it.coerceIn(0, 100))
            }
            applyExtendedInterfaceColors(context, config)
            config.uiCornerSearchFollow?.let {
                context.putPrefBoolean(ThemeRuntimeKeys.uiCornerSearchFollow(isNightTheme), it)
            }
            config.uiCornerReplyFollow?.let {
                context.putPrefBoolean(ThemeRuntimeKeys.uiCornerReplyFollow(isNightTheme), it)
            }
            config.fontScale?.let {
                context.putPrefInt(ThemeRuntimeKeys.fontScale(isNightTheme), it.coerceIn(0, 16))
            }
            context.putPrefString(ThemeRuntimeKeys.uiFontPath(isNightTheme), config.uiFontPath.orEmpty())
            context.putPrefString(ThemeRuntimeKeys.titleFontPath(isNightTheme), config.titleFontPath.orEmpty())
            applyFontColorPrefs(context, config)
            if (backgroundPath != null && backgroundPath.startsWith("http")) {
                val fileRoot = context.externalFiles
                val preferenceKey = if (isNightTheme) {
                    PreferKey.bgImageN
                } else {
                    PreferKey.bgImage
                }
                val name = getUrlToFile(backgroundPath)
                val fileFold = File(fileRoot, preferenceKey)
                if (!fileFold.exists()) {
                    fileFold.mkdirs()
                }
                val fileImg = File(fileFold, name)
                if (!isUsableThemeImage(fileImg)) {
                    if (fileImg.exists()) {
                        fileImg.delete()
                        clearUsableBgImageCache()
                    }
                    appCtx.toastOnUi(R.string.theme_background_downloading)
                    Coroutine.async {
                        downloadThemeBackground(backgroundPath, fileImg)
                    }.onSuccess { downloaded ->
                        if (downloaded) {
                            appCtx.toastOnUi(R.string.theme_background_downloaded)
                            if (notify) {
                                // T13（theme-arch-gap）：补 ThemeSync.bump 与 applyTheme 路径对称，
                                // 豁免页 Compose 侧背景相关读取即时失效重组；T6：死事件 MAIN 已删
                                ThemeSync.bump()
                                postEvent(EventBus.RECREATE, "")
                            }
                        }
                    }.onError {
                        appCtx.toastOnUi(it.localizedMessage)
                    }
                }
            }
            val backgroundBlur = config.backgroundImgBlur
            if (isNightTheme) {
                context.putPrefString(PreferKey.dNThemeName, config.themeName)
                context.putPrefInt(PreferKey.cNPrimary, primary)
                context.putPrefInt(PreferKey.cNAccent, accent)
                context.putPrefInt(PreferKey.cNBackground, background)
                context.putPrefInt(PreferKey.cNBBackground, bBackground)
                context.putPrefBoolean(PreferKey.tNavBarN, true)
                context.putPrefString(PreferKey.bgImageN, backgroundPath)
                context.putPrefInt(PreferKey.bgImageNBlurring, backgroundBlur)
                context.putPrefString(PreferKey.bgImageNCrop, backgroundCrop.orEmpty())
                context.putPrefString(PreferKey.bookInfoBgImageN, bookInfoBackgroundPath)
                context.putPrefString(PreferKey.panelBgImageN, panelBackgroundPath)
                context.putPrefString(PreferKey.panelBgScaleTypeN, panelBackgroundScaleType)
                context.putPrefString(PreferKey.panelBorderColorN, panelBorderColor.orEmpty())
                context.putPrefInt(PreferKey.panelBorderAlphaN, panelBorderAlpha)
            } else {
                context.putPrefString(PreferKey.dThemeName, config.themeName)
                context.putPrefInt(PreferKey.cPrimary, primary)
                context.putPrefInt(PreferKey.cAccent, accent)
                context.putPrefInt(PreferKey.cBackground, background)
                context.putPrefInt(PreferKey.cBBackground, bBackground)
                context.putPrefBoolean(PreferKey.tNavBar, true)
                context.putPrefString(PreferKey.bgImage, backgroundPath)
                context.putPrefInt(PreferKey.bgImageBlurring, backgroundBlur)
                context.putPrefString(PreferKey.bgImageCrop, backgroundCrop.orEmpty())
                context.putPrefString(PreferKey.bookInfoBgImage, bookInfoBackgroundPath)
                context.putPrefString(PreferKey.panelBgImage, panelBackgroundPath)
                context.putPrefString(PreferKey.panelBgScaleType, panelBackgroundScaleType)
                context.putPrefString(PreferKey.panelBorderColor, panelBorderColor.orEmpty())
                context.putPrefInt(PreferKey.panelBorderAlpha, panelBorderAlpha)
            }
            if (switchNightMode) {
                AppConfig.isNightTheme = isNightTheme
            }
            if (!notify) {
                return
            }
            if (switchNightMode) {
                applyDayNight(context)
            } else {
                applyTheme(context)
                BookCover.upDefaultCover()
                // T6（theme-arch-gap）：死事件 MAIN 已删，RECREATE 驱动刷新（applyTheme 末尾已 bump）
                postEvent(EventBus.RECREATE, "")
            }
        } catch (e: Exception) {
            AppLog.put("设置主题出错\n$e", e, true)
        }
    }

    fun getDurConfig(context: Context): Config {
        val isNight = AppConfig.isNightTheme
        val name = if (isNight) {
            context.getPrefString(PreferKey.dNThemeName) ?: ""
        } else {
            context.getPrefString(PreferKey.dThemeName) ?: ""
        }
        return if (isNight) {
            getNightTheme(context, name)
        } else {
            getDayTheme(context, name)
        }
    }

    fun getThemeConfig(context: Context, isNightTheme: Boolean): Config {
        val name = if (isNightTheme) {
            context.getPrefString(PreferKey.dNThemeName) ?: ""
        } else {
            context.getPrefString(PreferKey.dThemeName) ?: ""
        }
        return if (isNightTheme) {
            getNightTheme(context, name)
        } else {
            getDayTheme(context, name)
        }
    }

    private fun Context.themeUiCornerScale(isNightTheme: Boolean): Float {
        return getPrefString(ThemeRuntimeKeys.uiCornerScale(isNightTheme), "1")
            ?.toFloatOrNull()
            ?.coerceIn(0f, 3f)
            ?: 1f
    }

    private fun Context.themeUiLayoutAlpha(isNightTheme: Boolean): Int {
        return getPrefInt(
            ThemeRuntimeKeys.uiLayoutAlpha(isNightTheme),
            getPrefInt(PreferKey.uiCornerEffectLevel, 100)
        ).coerceIn(0, 100)
    }

    private fun Context.themeDialogAlpha(isNightTheme: Boolean): Int {
        return getPrefInt(ThemeRuntimeKeys.dialogAlpha(isNightTheme), 100).coerceIn(0, 100)
    }

    private fun Context.themeUiCornerSearchFollow(isNightTheme: Boolean): Boolean {
        return getPrefBoolean(ThemeRuntimeKeys.uiCornerSearchFollow(isNightTheme), false)
    }

    private fun Context.themeUiCornerReplyFollow(isNightTheme: Boolean): Boolean {
        return getPrefBoolean(ThemeRuntimeKeys.uiCornerReplyFollow(isNightTheme), false)
    }

    private fun getDayTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(PreferKey.cPrimary, context.getCompatColor(R.color.md_brown_500))
        val accent =
            context.getPrefInt(PreferKey.cAccent, context.getCompatColor(R.color.md_red_600))
        val background =
            context.getPrefInt(PreferKey.cBackground, context.getCompatColor(R.color.md_grey_100))
        val bBackground =
            context.getPrefInt(PreferKey.cBBackground, context.getCompatColor(R.color.md_grey_200))
        val bgImgPath =
            context.getPrefString(PreferKey.bgImage)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageBlurring, 0)
        val bgImgCrop =
            context.getPrefString(PreferKey.bgImageCrop)
        val bookInfoBgImgPath =
            context.getPrefString(PreferKey.bookInfoBgImage)
        val panelBgImgPath =
            context.getPrefString(PreferKey.panelBgImage)
        val panelBgScaleType =
            context.getPrefString(PreferKey.panelBgScaleType) ?: PANEL_BG_CROP
        val panelBorderColor =
            context.getPrefString(PreferKey.panelBorderColor)
        val panelBorderAlpha =
            context.getPrefInt(PreferKey.panelBorderAlpha, 100)
        val stored = configList.firstOrNull {
            it.themeName == name && !it.isNightTheme
        }

        return mergeStoredThemeAssets(
            Config(
                themeName = name,
                isNightTheme = false,
                primaryColor = "#${primary.hexString}",
                accentColor = "#${accent.hexString}",
                backgroundColor = "#${background.hexString}",
                bottomBackground = "#${bBackground.hexString}",
                transparentNavBar = true,
                backgroundImgPath = bgImgPath,
                backgroundImgBlur = bgImgBlur,
                backgroundImgCrop = bgImgCrop,
                bookInfoBackgroundImgPath = bookInfoBgImgPath,
                panelBackgroundImgPath = panelBgImgPath,
                panelBackgroundScaleType = panelBgScaleType,
                panelBorderColor = panelBorderColor,
                panelBorderAlpha = panelBorderAlpha,
                uiCornerScale = stored?.uiCornerScale ?: context.themeUiCornerScale(false),
                uiLayoutAlpha = stored?.uiLayoutAlpha ?: context.themeUiLayoutAlpha(false),
                dialogAlpha = stored?.dialogAlpha ?: context.themeDialogAlpha(false),
                uiCornerSearchFollow = stored?.uiCornerSearchFollow ?: context.themeUiCornerSearchFollow(false),
                uiCornerReplyFollow = stored?.uiCornerReplyFollow ?: context.themeUiCornerReplyFollow(false),
                fontScale = stored?.fontScale ?: context.getPrefInt(ThemeRuntimeKeys.fontScale(false), 0),
                uiFontPath = stored?.uiFontPath ?: context.getPrefString(ThemeRuntimeKeys.uiFontPath(false)).orEmpty(),
                titleFontPath = stored?.titleFontPath ?: context.getPrefString(ThemeRuntimeKeys.titleFontPath(false)).orEmpty(),
                uiFontColor = stored?.uiFontColor ?: context.getPrefString(ThemeRuntimeKeys.uiFontColor(false)).orEmpty()
                    .takeIf { it.isNotBlank() } ?: defaultThemeTextColorHex(false),
                titleFontColor = stored?.titleFontColor ?: context.getPrefString(ThemeRuntimeKeys.titleFontColor(false)).orEmpty()
                    .takeIf { it.isNotBlank() } ?: defaultThemeTextColorHex(false)
            )
        )
    }

    fun saveDayTheme(context: Context, name: String) {
        val config = getDayTheme(context, name)
        addConfig(config)
    }

    private fun getNightTheme(context: Context, name: String): Config {
        val primary =
            context.getPrefInt(
                PreferKey.cNPrimary,
                context.getCompatColor(R.color.md_blue_grey_600)
            )
        val accent =
            context.getPrefInt(
                PreferKey.cNAccent,
                context.getCompatColor(R.color.md_deep_orange_800)
            )
        val background =
            context.getPrefInt(PreferKey.cNBackground, context.getCompatColor(R.color.md_grey_900))
        val bBackground =
            context.getPrefInt(PreferKey.cNBBackground, context.getCompatColor(R.color.md_grey_850))
        val bgImgPath =
            context.getPrefString(PreferKey.bgImageN)
        val bgImgBlur =
            context.getPrefInt(PreferKey.bgImageNBlurring, 0)
        val bgImgCrop =
            context.getPrefString(PreferKey.bgImageNCrop)
        val bookInfoBgImgPath =
            context.getPrefString(PreferKey.bookInfoBgImageN)
        val panelBgImgPath =
            context.getPrefString(PreferKey.panelBgImageN)
        val panelBgScaleType =
            context.getPrefString(PreferKey.panelBgScaleTypeN) ?: PANEL_BG_CROP
        val panelBorderColor =
            context.getPrefString(PreferKey.panelBorderColorN)
        val panelBorderAlpha =
            context.getPrefInt(PreferKey.panelBorderAlphaN, 100)
        val stored = configList.firstOrNull {
            it.themeName == name && it.isNightTheme
        }
        return mergeStoredThemeAssets(
            Config(
                themeName = name,
                isNightTheme = true,
                primaryColor = "#${primary.hexString}",
                accentColor = "#${accent.hexString}",
                backgroundColor = "#${background.hexString}",
                bottomBackground = "#${bBackground.hexString}",
                transparentNavBar = true,
                backgroundImgPath = bgImgPath,
                backgroundImgBlur = bgImgBlur,
                backgroundImgCrop = bgImgCrop,
                bookInfoBackgroundImgPath = bookInfoBgImgPath,
                panelBackgroundImgPath = panelBgImgPath,
                panelBackgroundScaleType = panelBgScaleType,
                panelBorderColor = panelBorderColor,
                panelBorderAlpha = panelBorderAlpha,
                uiCornerScale = stored?.uiCornerScale ?: context.themeUiCornerScale(true),
                uiLayoutAlpha = stored?.uiLayoutAlpha ?: context.themeUiLayoutAlpha(true),
                dialogAlpha = stored?.dialogAlpha ?: context.themeDialogAlpha(true),
                uiCornerSearchFollow = stored?.uiCornerSearchFollow ?: context.themeUiCornerSearchFollow(true),
                uiCornerReplyFollow = stored?.uiCornerReplyFollow ?: context.themeUiCornerReplyFollow(true),
                fontScale = stored?.fontScale ?: context.getPrefInt(ThemeRuntimeKeys.fontScale(true), 0),
                uiFontPath = stored?.uiFontPath ?: context.getPrefString(ThemeRuntimeKeys.uiFontPath(true)).orEmpty(),
                titleFontPath = stored?.titleFontPath ?: context.getPrefString(ThemeRuntimeKeys.titleFontPath(true)).orEmpty(),
                uiFontColor = stored?.uiFontColor ?: context.getPrefString(ThemeRuntimeKeys.uiFontColor(true)).orEmpty()
                    .takeIf { it.isNotBlank() } ?: defaultThemeTextColorHex(true),
                titleFontColor = stored?.titleFontColor ?: context.getPrefString(ThemeRuntimeKeys.titleFontColor(true)).orEmpty()
                    .takeIf { it.isNotBlank() } ?: defaultThemeTextColorHex(true)
            )
        )
    }

    private fun mergeStoredThemeAssets(config: Config): Config {
        if (config.themeName.isBlank()) return config
        val stored = configList.firstOrNull {
            it.themeName == config.themeName && it.isNightTheme == config.isNightTheme
        } ?: return config
        return config.copy(
            backgroundImgPath = preferThemeAsset(config.backgroundImgPath, stored.backgroundImgPath),
            bookInfoBackgroundImgPath = preferThemeAsset(
                config.bookInfoBackgroundImgPath,
                stored.bookInfoBackgroundImgPath
            ),
            panelBackgroundImgPath = preferThemeAsset(
                config.panelBackgroundImgPath,
                stored.panelBackgroundImgPath
            ),
            panelBackgroundScaleType = config.panelBackgroundScaleType ?: stored.panelBackgroundScaleType,
            panelBorderColor = config.panelBorderColor ?: stored.panelBorderColor,
            panelBorderAlpha = config.panelBorderAlpha ?: stored.panelBorderAlpha,
            backgroundImgBlur = if (config.backgroundImgPath.isNullOrBlank() && !stored.backgroundImgPath.isNullOrBlank()) {
                stored.backgroundImgBlur
            } else {
                config.backgroundImgBlur
            },
            backgroundImgCrop = if (config.backgroundImgPath.isNullOrBlank() && !stored.backgroundImgPath.isNullOrBlank()) {
                stored.backgroundImgCrop
            } else {
                normalizeBackgroundCrop(config.backgroundImgCrop) ?: stored.backgroundImgCrop
            },
            uiCornerScale = config.uiCornerScale ?: stored.uiCornerScale,
            uiLayoutAlpha = config.uiLayoutAlpha ?: stored.uiLayoutAlpha,
            dialogAlpha = config.dialogAlpha ?: stored.dialogAlpha,
            cardColor = config.cardColor,
            mutedColor = config.mutedColor,
            searchFieldBackgroundColor = config.searchFieldBackgroundColor,
            tabBackgroundColor = config.tabBackgroundColor,
            shelfColor = config.shelfColor,
            cardShadow = config.cardShadow,
            cardBackgroundBlur = config.cardBackgroundBlur,
            uiCornerSearchFollow = config.uiCornerSearchFollow ?: stored.uiCornerSearchFollow,
            uiCornerReplyFollow = config.uiCornerReplyFollow ?: stored.uiCornerReplyFollow,
            fontScale = config.fontScale ?: stored.fontScale,
            uiFontPath = config.uiFontPath ?: stored.uiFontPath,
            titleFontPath = config.titleFontPath ?: stored.titleFontPath,
            uiFontColor = config.uiFontColor ?: stored.uiFontColor,
            titleFontColor = config.titleFontColor ?: stored.titleFontColor
        )
    }

    private fun preferThemeAsset(current: String?, fallback: String?): String? {
        if (current != null) {
            if (current.isBlank()) return current
            if (current.startsWith("http", ignoreCase = true)) return current
            if (isReadableThemeFile(current)) return current
        }
        return fallback?.takeIf {
            it.startsWith("http", ignoreCase = true) || isReadableThemeFile(it)
        }
    }

    fun normalizeBackgroundCrop(value: String?): String? {
        val parts = value
            ?.split(',', '|', ';')
            ?.mapNotNull { it.trim().toFloatOrNull()?.coerceIn(0f, 1f) }
            ?: return null
        if (parts.size != 4) return null
        val (left, top, right, bottom) = parts
        if (right <= left || bottom <= top) return null
        return parts.joinToString(",") { crop ->
            String.format(Locale.US, "%.6f", crop).trimEnd('0').trimEnd('.')
        }
    }

    private fun applyExtendedInterfaceColors(context: Context, config: Config) {
        val isNightTheme = config.isNightTheme
        context.putOrClearThemeColor(ThemeRuntimeKeys.themeCardColor(isNightTheme), config.cardColor)
        context.putOrClearThemeColor(ThemeRuntimeKeys.themeMutedColor(isNightTheme), config.mutedColor)
        context.putOrClearThemeColor(
            ThemeRuntimeKeys.themeSearchFieldBackgroundColor(isNightTheme),
            config.searchFieldBackgroundColor
        )
        context.putOrClearThemeColor(ThemeRuntimeKeys.themeTabBackgroundColor(isNightTheme), config.tabBackgroundColor)
        context.putOrClearThemeColor(ThemeRuntimeKeys.themeShelfColor(isNightTheme), config.shelfColor)
        config.cardShadow?.let {
            context.putPrefInt(ThemeRuntimeKeys.themeCardShadow(isNightTheme), it.coerceIn(0, 24))
        } ?: context.removePref(ThemeRuntimeKeys.themeCardShadow(isNightTheme))
        config.cardBackgroundBlur?.let {
            context.putPrefInt(ThemeRuntimeKeys.themeCardBackgroundBlur(isNightTheme), (it * 10f).toInt().coerceIn(0, 250))
        } ?: context.removePref(ThemeRuntimeKeys.themeCardBackgroundBlur(isNightTheme))
    }

    private fun Context.putOrClearThemeColor(key: String, value: String?) {
        val normalized = value?.takeIf { it.isNotBlank() }
        if (normalized == null) {
            removePref(key)
        } else {
            putPrefString(key, normalized)
        }
    }

    private fun isReadableThemeFile(path: String): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        if (isOtherAppExternalDataPath(path)) return false
        return runCatching {
            FileInputStream(file).use { true }
        }.getOrDefault(false)
    }

    private fun isUsableThemeImage(file: File, isNinePatchOverride: Boolean? = null): Boolean {
        if (!file.isFile || file.length() <= 0L || file.length() > MAX_REMOTE_BACKGROUND_BYTES) {
            return false
        }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
            val pixels = bounds.outWidth.toLong() * bounds.outHeight.toLong()
            val isNinePatch = isNinePatchOverride
                ?: file.name.endsWith(".9.png", ignoreCase = true)
            if (isNinePatch) {
                if (bounds.outWidth > MAX_NINE_PATCH_DIMENSION ||
                    bounds.outHeight > MAX_NINE_PATCH_DIMENSION ||
                    pixels > MAX_NINE_PATCH_BACKGROUND_PIXELS
                ) {
                    return@runCatching false
                }
            } else if (pixels > MAX_REMOTE_BACKGROUND_PIXELS) {
                return@runCatching false
            }

            var sampleSize = 1
            while ((bounds.outWidth / sampleSize).toLong() *
                (bounds.outHeight / sampleSize).toLong() > VALIDATION_DECODE_PIXELS
            ) {
                sampleSize *= 2
            }
            val decoded = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: return@runCatching false
            decoded.recycle()
            true
        }.getOrDefault(false)
    }

    private suspend fun downloadThemeBackground(url: String, target: File): Boolean {
        val mutex = backgroundDownloadLocks.getOrPut(target.absolutePath) { Mutex() }
        mutex.lock()
        try {
            if (isUsableThemeImage(target)) return false
            check(!target.exists() || target.delete()) { "failed to remove invalid theme background" }
            target.parentFile?.mkdirs()
            target.parentFile?.listFiles { file ->
                file.name.startsWith(".${target.name}.") && file.name.endsWith(".part")
            }?.forEach(File::delete)
            val temp = File(target.parentFile, ".${target.name}.${System.nanoTime()}.part")
            val isNinePatch = target.name.endsWith(".9.png", ignoreCase = true)
            try {
                okHttpClient.newCallResponse(0) { url(url) }.use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val contentLength = response.body.contentLength()
                    check(contentLength < 0L || contentLength <= MAX_REMOTE_BACKGROUND_BYTES) {
                        "theme background is too large"
                    }
                    response.body.byteStream().use { input ->
                        temp.outputStream().buffered().use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                total += read
                                check(total <= MAX_REMOTE_BACKGROUND_BYTES) {
                                    "theme background is too large"
                                }
                                output.write(buffer, 0, read)
                            }
                            check(contentLength < 0L || total == contentLength) {
                                "incomplete theme background download"
                            }
                        }
                    }
                }
                check(isUsableThemeImage(temp, isNinePatch)) { "invalid theme background image" }
                check(temp.renameTo(target)) { "failed to install theme background" }
                if (!isUsableThemeImage(target, isNinePatch)) {
                    target.delete()
                    error("invalid installed theme background")
                }
                clearUsableBgImageCache()
                return true
            } finally {
                if (temp.exists()) temp.delete()
            }
        } finally {
            mutex.unlock()
        }
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

    private fun Float.toPlainScale(): String {
        return if (this % 1f == 0f) {
            this.toInt().toString()
        } else {
            String.format(Locale.US, "%.2f", this).trimEnd('0').trimEnd('.')
        }
    }

    private fun applyFontColorPrefs(context: Context, config: Config) {
        val isNightTheme = config.isNightTheme
        // 文字主要落在主背景、卡片、底部背景上，字体色与这些表面撞色时文字会不可读；
        // 设置了背景图时主背景色被图片遮盖，不参与判断以免误杀
        val surfaces = listOfNotNull(
            config.backgroundColor.toSurfaceColorOrNull()
                .takeIf { config.backgroundImgPath.isNullOrBlank() },
            config.cardColor?.toSurfaceColorOrNull(),
            config.bottomBackground.toSurfaceColorOrNull()
        )
        val defaultColor = defaultThemeTextColorHex(isNightTheme)
        val uiColor = sanitizeFontColorAgainstSurfaces(
            normalizeThemeColor(config.uiFontColor) ?: defaultColor, isNightTheme, surfaces
        )
        val titleColor = sanitizeFontColorAgainstSurfaces(
            normalizeThemeColor(config.titleFontColor) ?: defaultColor, isNightTheme, surfaces
        )
        context.putPrefString(ThemeRuntimeKeys.uiFontColor(isNightTheme), uiColor)
        context.putPrefString(ThemeRuntimeKeys.titleFontColor(isNightTheme), titleColor)
    }

    private const val MIN_FONT_SURFACE_CONTRAST = 1.3

    private fun String.toSurfaceColorOrNull(): Int? {
        val normalized = normalizeThemeColor(this) ?: return null
        return runCatching { normalized.toColorInt() }.getOrNull()
    }

    private fun sanitizeFontColorAgainstSurfaces(
        colorHex: String,
        isNightTheme: Boolean,
        surfaces: List<Int>
    ): String {
        if (surfaces.isEmpty()) return colorHex
        val color = runCatching { colorHex.toColorInt() }.getOrNull() ?: return colorHex
        if (surfaces.none { fontSurfaceContrast(color, it) < MIN_FONT_SURFACE_CONTRAST }) {
            return colorHex
        }
        // 撞色：在日夜两个默认文字色里选与所有表面最小对比度更高的那个
        val fallbacks = listOf(
            defaultThemeTextColorHex(isNightTheme),
            defaultThemeTextColorHex(!isNightTheme)
        )
        return fallbacks.maxByOrNull { hex ->
            val c = hex.toColorInt()
            surfaces.minOf { fontSurfaceContrast(c, it) }
        } ?: defaultThemeTextColorHex(isNightTheme)
    }

    private fun fontSurfaceContrast(foreground: Int, surface: Int): Double {
        val opaqueSurface = AndroidColorUtils.setAlphaComponent(surface, 255)
        val opaqueForeground = if (Color.alpha(foreground) == 255) {
            foreground
        } else {
            AndroidColorUtils.compositeColors(foreground, opaqueSurface)
        }
        return AndroidColorUtils.calculateContrast(opaqueForeground, opaqueSurface)
    }

    private fun normalizeThemeColor(value: String?): String? {
        val raw = value?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val withoutPrefix = raw
            .removePrefix("#")
            .removePrefix("0x")
            .removePrefix("0X")
        val candidate = if (
            withoutPrefix.length in setOf(6, 8) &&
            withoutPrefix.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        ) {
            "#$withoutPrefix"
        } else {
            raw
        }
        return kotlin.runCatching {
            "#${candidate.toColorInt().hexString}"
        }.getOrNull()
    }

    private fun ThemeStore.applyUiFontColor(context: Context, isNightTheme: Boolean): ThemeStore {
        val color = normalizeThemeColor(context.getPrefString(ThemeRuntimeKeys.uiFontColor(isNightTheme)))
            ?.toColorInt()
            ?: defaultThemeTextColor(isNightTheme)
        textColorPrimary(color)
        // 次要文字与主文字同源派生（与 View 层 secondaryTextColor 的 0.72 alpha 派生一致），
        // 消除 textColorSecondary fallback View attr 的不可控（本项目新增层 ThemeSpec 消费该值）
        textColorSecondary(ColorUtils.withAlpha(color, 0.72f))
        return this
    }

    fun saveNightTheme(context: Context, name: String) {
        val config = getNightTheme(context, name)
        addConfig(config)
    }

    /**
     * 更新主题
     */
    fun applyTheme(context: Context, isNightTheme: Boolean = AppConfig.isNightTheme) = with(context) {
        when {
            AppConfig.isEInkMode -> {
                ThemeStore.editTheme(this)
                    .primaryColor(Color.WHITE)
                    .accentColor(Color.BLACK)
                    .backgroundColor(Color.WHITE)
                    .bottomBackground(Color.WHITE)
                    .transparentNavBar(true)
                    .applyUiFontColor(this, isNightTheme)
                    .apply()
            }

            isNightTheme -> {
                val primary =
                    getPrefInt(PreferKey.cNPrimary, getCompatColor(R.color.md_blue_grey_600))
                val accent =
                    getPrefInt(PreferKey.cNAccent, getCompatColor(R.color.md_deep_orange_800))
                val background =
                    getPrefInt(PreferKey.cNBackground, getCompatColor(R.color.md_grey_900))
                val bBackground =
                    getPrefInt(PreferKey.cNBBackground, getCompatColor(R.color.md_grey_850))
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(true)
                    .applyUiFontColor(this, isNightTheme)
                    .apply()
            }

            else -> {
                val primary =
                    getPrefInt(PreferKey.cPrimary, getCompatColor(R.color.md_brown_500))
                val accent =
                    getPrefInt(PreferKey.cAccent, getCompatColor(R.color.md_red_600))
                val background =
                    getPrefInt(PreferKey.cBackground, getCompatColor(R.color.md_grey_100))
                val bBackground =
                    getPrefInt(PreferKey.cBBackground, getCompatColor(R.color.md_grey_200))
                ThemeStore.editTheme(this)
                    .primaryColor(ColorUtils.withAlpha(primary, 1f))
                    .accentColor(ColorUtils.withAlpha(accent, 1f))
                    .backgroundColor(ColorUtils.withAlpha(background, 1f))
                    .bottomBackground(ColorUtils.withAlpha(bBackground, 1f))
                    .transparentNavBar(true)
                    .applyUiFontColor(this, isNightTheme)
                    .apply()
            }
        }
        Coroutine.async {
            UiCorner.warmPanelBitmap(this@with)
        }
        // 主题架构 v2：bump 全局同步信号，所有已组合的 Compose 页面立即重组刷新
        ThemeSync.bump()
    }

    fun clearBg(context: Context) {
        val (nightConfigs, dayConfigs) = configList.partition { it.isNightTheme }
        val fileRoot = context.externalFiles
        val nightBackgroundImgPaths = nightConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                val name = getUrlToFile(path)
                FileUtils.getPath(fileRoot, PreferKey.bgImageN, name)
            } else {
                path
            }
        }
        val dayBackgroundImgPaths = dayConfigs.mapNotNull {
            val path = it.backgroundImgPath ?: return@mapNotNull null
            if (path.startsWith("http")) {
                val name = getUrlToFile(path)
                FileUtils.getPath(fileRoot, PreferKey.bgImage, name)
            } else {
                path
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImage).listFiles()?.forEach {
            if (!dayBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
        appCtx.externalFiles.getFile(PreferKey.bgImageN).listFiles()?.forEach {
            if (!nightBackgroundImgPaths.contains(it.absolutePath)) {
                it.delete()
            }
        }
    }

    @Keep
    data class Config(
        var themeName: String,
        var isNightTheme: Boolean,
        var primaryColor: String,
        var accentColor: String,
        var backgroundColor: String,
        var bottomBackground: String,
        var transparentNavBar: Boolean,
        var backgroundImgPath: String?,
        var backgroundImgBlur: Int,
        var backgroundImgCrop: String? = null,
        var bookInfoBackgroundImgPath: String? = null,
        var panelBackgroundImgPath: String? = null,
        var panelBackgroundScaleType: String? = PANEL_BG_CROP,
        var panelBorderColor: String? = null,
        var panelBorderAlpha: Int? = null,
        var uiCornerScale: Float? = null,
        var uiLayoutAlpha: Int? = null,
        var dialogAlpha: Int? = null,
        var cardColor: String? = null,
        var mutedColor: String? = null,
        var searchFieldBackgroundColor: String? = null,
        var tabBackgroundColor: String? = null,
        var shelfColor: String? = null,
        var cardShadow: Int? = null,
        var cardBackgroundBlur: Float? = null,
        var uiCornerSearchFollow: Boolean? = null,
        var uiCornerReplyFollow: Boolean? = null,
        var fontScale: Int? = null,
        var uiFontPath: String? = null,
        var titleFontPath: String? = null,
        var uiFontColor: String? = null,
        var titleFontColor: String? = null
    ) {

        override fun hashCode(): Int {
            return GSON.toJson(this).hashCode()
        }

        override fun equals(other: Any?): Boolean {
            other ?: return false
            if (other is Config) {
                return other.themeName == themeName
                        && other.isNightTheme == isNightTheme
                        && other.primaryColor == primaryColor
                        && other.accentColor == accentColor
                        && other.backgroundColor == backgroundColor
                        && other.bottomBackground == bottomBackground
                        && other.transparentNavBar == transparentNavBar
                        && other.backgroundImgPath == backgroundImgPath
                        && other.backgroundImgBlur == backgroundImgBlur
                        && other.backgroundImgCrop == backgroundImgCrop
                        && other.bookInfoBackgroundImgPath == bookInfoBackgroundImgPath
                        && other.panelBackgroundImgPath == panelBackgroundImgPath
                        && other.panelBackgroundScaleType == panelBackgroundScaleType
                        && other.panelBorderColor == panelBorderColor
                        && other.panelBorderAlpha == panelBorderAlpha
                        && other.uiCornerScale == uiCornerScale
                        && other.uiLayoutAlpha == uiLayoutAlpha
                        && other.dialogAlpha == dialogAlpha
                        && other.cardColor == cardColor
                        && other.mutedColor == mutedColor
                        && other.searchFieldBackgroundColor == searchFieldBackgroundColor
                        && other.tabBackgroundColor == tabBackgroundColor
                        && other.shelfColor == shelfColor
                        && other.cardShadow == cardShadow
                        && other.cardBackgroundBlur == cardBackgroundBlur
                        && other.uiCornerSearchFollow == uiCornerSearchFollow
                        && other.uiCornerReplyFollow == uiCornerReplyFollow
                        && other.fontScale == fontScale
                        && other.uiFontPath == uiFontPath
                        && other.titleFontPath == titleFontPath
                        && other.uiFontColor == uiFontColor
                        && other.titleFontColor == titleFontColor
            }
            return false
        }

        fun toMap() = mapOf(
            "themeName" to themeName,
            "isNightTheme" to isNightTheme,
            "primaryColor" to primaryColor,
            "accentColor" to accentColor,
            "backgroundColor" to backgroundColor,
            "bottomBackground" to bottomBackground,
            "transparentNavBar" to transparentNavBar,
            "backgroundImgPath" to backgroundImgPath,
            "backgroundImgBlur" to backgroundImgBlur,
            "backgroundImgCrop" to backgroundImgCrop,
            "bookInfoBackgroundImgPath" to bookInfoBackgroundImgPath,
            "panelBackgroundImgPath" to panelBackgroundImgPath,
            "panelBackgroundScaleType" to panelBackgroundScaleType,
            "panelBorderColor" to panelBorderColor,
            "panelBorderAlpha" to panelBorderAlpha,
            "uiCornerScale" to uiCornerScale,
            "uiLayoutAlpha" to uiLayoutAlpha,
            "dialogAlpha" to dialogAlpha,
            "cardColor" to cardColor,
            "mutedColor" to mutedColor,
            "searchFieldBackgroundColor" to searchFieldBackgroundColor,
            "tabBackgroundColor" to tabBackgroundColor,
            "shelfColor" to shelfColor,
            "cardShadow" to cardShadow,
            "cardBackgroundBlur" to cardBackgroundBlur,
            "uiCornerSearchFollow" to uiCornerSearchFollow,
            "uiCornerReplyFollow" to uiCornerReplyFollow,
            "fontScale" to fontScale,
            "uiFontPath" to uiFontPath,
            "titleFontPath" to titleFontPath,
            "uiFontColor" to uiFontColor,
            "titleFontColor" to titleFontColor
        )

    }

}