package io.legado.app.ui.config.theme.compose

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.ThemeConfig
import io.legado.app.help.config.ThemePaletteExtractor
import io.legado.app.lib.theme.ThemeRuntimeKeys
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getFile
import io.legado.app.utils.getPrefInt
import io.legado.app.utils.getPrefString
import io.legado.app.utils.externalFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** 颜色槽位（4 固定 + 5 可选） */
enum class ThemeColorSlot {
    PRIMARY,
    ACCENT,
    BACKGROUND,
    BOTTOM_BACKGROUND,
    CARD,
    MUTED,
    SEARCH_FIELD,
    TAB_BACKGROUND,
    SHELF
}

/** 质感滑杆类别 */
enum class ThemeSliderKind {
    CORNER_SCALE,
    LAYOUT_ALPHA,
    CARD_SHADOW,
    FONT_SCALE
}

/**
 * AD-05 主题编辑器状态：对齐 ThemeConfig.Config 可编辑字段子集。
 * 可选色与 cardShadow/fontScale 的 null = 跟随默认（Config 可空语义保持）。
 */
data class ThemeEditorState(
    val themeName: String = "",
    val isNight: Boolean = false,
    val primaryColor: String = "#FF000000",
    val accentColor: String = "#FF000000",
    val backgroundColor: String = "#FFFFFFFF",
    val bottomBackground: String = "#FFFFFFFF",
    val cardColor: String? = null,
    val mutedColor: String? = null,
    val searchFieldBackgroundColor: String? = null,
    val tabBackgroundColor: String? = null,
    val shelfColor: String? = null,
    val wallpaperPath: String? = null,
    val wallpaperBitmap: ImageBitmap? = null,
    val wallpaperBlur: Int = 0,
    val uiCornerScale: Float = 1f,
    val uiLayoutAlpha: Int = 100,
    val cardShadow: Int? = null,
    val fontScale: Int? = null,
    val suggestions: ThemePaletteExtractor.ExtractedPalette? = null,
    val extracting: Boolean = false,
    val applySuccess: Boolean = false
)

/**
 * 编辑字段投影（ui-theme-governance-polish AD-03）：仅可编辑字段参与 dirty 对比，
 * 剔除 isNight/applySuccess/suggestions/extracting/wallpaperBitmap 等非编辑字段。
 * 新增编辑字段时必须同步更新本投影。
 */
data class ThemeEditorDirtySnapshot(
    val themeName: String,
    val primaryColor: String,
    val accentColor: String,
    val backgroundColor: String,
    val bottomBackground: String,
    val cardColor: String?,
    val mutedColor: String?,
    val searchFieldBackgroundColor: String?,
    val tabBackgroundColor: String?,
    val shelfColor: String?,
    val wallpaperPath: String?,
    val wallpaperBlur: Int,
    val uiCornerScale: Float,
    val uiLayoutAlpha: Int,
    val cardShadow: Int?,
    val fontScale: Int?
)

private fun ThemeEditorState.toDirtyComparable(): ThemeEditorDirtySnapshot {
    return ThemeEditorDirtySnapshot(
        themeName = themeName,
        primaryColor = primaryColor,
        accentColor = accentColor,
        backgroundColor = backgroundColor,
        bottomBackground = bottomBackground,
        cardColor = cardColor,
        mutedColor = mutedColor,
        searchFieldBackgroundColor = searchFieldBackgroundColor,
        tabBackgroundColor = tabBackgroundColor,
        shelfColor = shelfColor,
        wallpaperPath = wallpaperPath,
        wallpaperBlur = wallpaperBlur,
        uiCornerScale = uiCornerScale,
        uiLayoutAlpha = uiLayoutAlpha,
        cardShadow = cardShadow,
        fontScale = fontScale
    )
}

/**
 * AD-05 Compose 主题编辑器 ViewModel。
 * 全部编辑态收敛于此（消除旧 View 弹窗 20+ pending 成员变量），预览子树只消费本 State，
 * 禁止 context.backgroundColor 等 pref 直读穿透（评审门禁）。
 */
class ThemeEditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(ThemeEditorState())
    val state: StateFlow<ThemeEditorState> = _state

    /** 日/夜两模式草稿缓存：切 Tab 不丢未保存修改 */
    private val drafts = mutableMapOf<Boolean, ThemeEditorState>()

    /** 按编辑模式的初始快照（ui-theme-governance-polish AD-03）：与 drafts 同构，首次进入模式时记录 */
    private val initialSnapshots = mutableMapOf<Boolean, ThemeEditorDirtySnapshot>()

    private var initialized = false

    /** 幂等初始化：config 为 null 时从当前应用主题加载（新建） */
    fun init(config: ThemeConfig.Config?, initialNight: Boolean = false) {
        if (initialized) return
        initialized = true
        val draft = config?.let { fromConfig(it) } ?: loadCurrentConfig(initialNight)
        _state.value = draft.copy(themeName = config?.themeName ?: draft.themeName)
        recordInitialSnapshot(_state.value)
    }

    /** 当前模式是否有未保存修改（快照对比，剔除非编辑字段） */
    fun isDirty(current: ThemeEditorState): Boolean {
        val initial = initialSnapshots[current.isNight] ?: return false
        return current.toDirtyComparable() != initial
    }

    private fun recordInitialSnapshot(state: ThemeEditorState) {
        initialSnapshots[state.isNight] = state.toDirtyComparable()
    }

    fun updateName(name: String) {
        _state.update { it.copy(themeName = name) }
    }

    fun updateColor(slot: ThemeColorSlot, hex: String) {
        val argb = ThemePaletteExtractor.parseHexOrNull(hex) ?: return
        val normalized = ThemePaletteExtractor.toHex6(argb)
        _state.update { current ->
            when (slot) {
                ThemeColorSlot.PRIMARY -> current.copy(primaryColor = normalized)
                ThemeColorSlot.ACCENT -> current.copy(accentColor = normalized)
                ThemeColorSlot.BACKGROUND -> current.copy(backgroundColor = normalized)
                ThemeColorSlot.BOTTOM_BACKGROUND -> current.copy(bottomBackground = normalized)
                ThemeColorSlot.CARD -> current.copy(cardColor = normalized)
                ThemeColorSlot.MUTED -> current.copy(mutedColor = normalized)
                ThemeColorSlot.SEARCH_FIELD -> current.copy(searchFieldBackgroundColor = normalized)
                ThemeColorSlot.TAB_BACKGROUND -> current.copy(tabBackgroundColor = normalized)
                ThemeColorSlot.SHELF -> current.copy(shelfColor = normalized)
            }
        }
    }

    /** 可选色回退"跟随默认"（null 语义） */
    fun clearOptionalColor(slot: ThemeColorSlot) {
        _state.update { current ->
            when (slot) {
                ThemeColorSlot.CARD -> current.copy(cardColor = null)
                ThemeColorSlot.MUTED -> current.copy(mutedColor = null)
                ThemeColorSlot.SEARCH_FIELD -> current.copy(searchFieldBackgroundColor = null)
                ThemeColorSlot.TAB_BACKGROUND -> current.copy(tabBackgroundColor = null)
                ThemeColorSlot.SHELF -> current.copy(shelfColor = null)
                else -> current
            }
        }
    }

    fun updateSlider(kind: ThemeSliderKind, value: Float) {
        _state.update { current ->
            when (kind) {
                ThemeSliderKind.CORNER_SCALE -> current.copy(uiCornerScale = value.coerceIn(0f, 3f))
                ThemeSliderKind.LAYOUT_ALPHA -> current.copy(uiLayoutAlpha = value.toInt().coerceIn(0, 100))
                ThemeSliderKind.CARD_SHADOW -> current.copy(cardShadow = value.toInt().coerceIn(0, 24))
                ThemeSliderKind.FONT_SCALE -> current.copy(fontScale = value.toInt().coerceIn(8, 16))
            }
        }
    }

    /** 滑杆回退"跟随默认"（null 语义，apply 时不写该字段） */
    fun resetSliderToDefault(kind: ThemeSliderKind) {
        _state.update { current ->
            when (kind) {
                ThemeSliderKind.CARD_SHADOW -> current.copy(cardShadow = null)
                ThemeSliderKind.FONT_SCALE -> current.copy(fontScale = null)
                else -> current
            }
        }
    }

    fun updateWallpaperBlur(blur: Int) {
        _state.update { it.copy(wallpaperBlur = blur.coerceIn(0, 25)) }
    }

    /** 切换日/夜编辑模式：当前草稿入缓存，目标模式取缓存或当前应用主题 */
    fun switchMode(night: Boolean) {
        val current = _state.value
        if (current.isNight == night) return
        drafts[current.isNight] = current
        val target = drafts.getOrPut(night) { loadCurrentConfig(night) }
        _state.value = target.copy(isNight = night)
        // 首次进入该模式才记录初始快照（回访模式保留原快照以正确判 dirty）
        if (night !in initialSnapshots) {
            recordInitialSnapshot(_state.value)
        }
    }

    /**
     * 从图加载壁纸：拷贝入应用私有目录（防临时 uri 失效）→ 降采样预览位图 → 后台取色填建议色。
     * 取色失败静默降级（仅不显示建议区）。
     */
    fun loadFromImage(uri: Uri) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val saved = withContext(Dispatchers.IO) {
                kotlin.runCatching {
                    val dir = context.externalFiles.getFile("themePackageTemp").apply { mkdirs() }
                    val file = File(dir, "editor_${System.currentTimeMillis()}.jpg")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    } ?: return@runCatching null
                    file.takeIf { it.length() > 0L }?.absolutePath
                }.getOrNull()
            } ?: return@launch
            val bitmap = withContext(Dispatchers.IO) {
                decodePreviewBitmap(saved)
            }
            _state.update {
                it.copy(
                    wallpaperPath = saved,
                    wallpaperBitmap = bitmap?.asImageBitmap(),
                    suggestions = null,
                    extracting = true
                )
            }
            val palette = ThemePaletteExtractor.extract(context, uri)
            _state.update {
                it.copy(suggestions = palette, extracting = false)
            }
        }
    }

    fun clearWallpaper() {
        _state.update {
            it.copy(
                wallpaperPath = null,
                wallpaperBitmap = null,
                wallpaperBlur = 0,
                suggestions = null,
                extracting = false
            )
        }
    }

    /** 把壁纸提取的建议色应用到当前编辑模式 */
    fun applySuggestedPalette() {
        val palette = _state.value.suggestions ?: return
        val candidate = if (_state.value.isNight) palette.night else palette.day
        val primary = ThemePaletteExtractor.parseHexOrNull(candidate.primary) ?: return
        val accent = ThemePaletteExtractor.parseHexOrNull(candidate.accent) ?: return
        val background = ThemePaletteExtractor.parseHexOrNull(candidate.background) ?: return
        val bottom = ThemePaletteExtractor.parseHexOrNull(candidate.bottomBackground) ?: return
        _state.update {
            it.copy(
                primaryColor = ThemePaletteExtractor.toHex6(primary),
                accentColor = ThemePaletteExtractor.toHex6(accent),
                backgroundColor = ThemePaletteExtractor.toHex6(background),
                bottomBackground = ThemePaletteExtractor.toHex6(bottom),
                cardColor = candidate.card,
                mutedColor = candidate.muted,
                searchFieldBackgroundColor = candidate.searchFieldBackground,
                tabBackgroundColor = candidate.tabBackground,
                shelfColor = candidate.shelf
            )
        }
    }

    /** 应用：构造 Config → ThemeConfig.applyConfig 落盘（switchNightMode 保证所见即所得） */
    fun apply() {
        val current = _state.value
        val app = getApplication<Application>()
        val name = current.themeName.trim().ifBlank {
            app.getString(if (current.isNight) R.string.theme_night else R.string.theme_day)
        }
        val config = ThemeConfig.Config(
            themeName = name,
            isNightTheme = current.isNight,
            primaryColor = current.primaryColor,
            accentColor = current.accentColor,
            backgroundColor = current.backgroundColor,
            bottomBackground = current.bottomBackground,
            transparentNavBar = true,
            backgroundImgPath = current.wallpaperPath,
            backgroundImgBlur = current.wallpaperBlur,
            backgroundImgCrop = null,
            uiCornerScale = current.uiCornerScale,
            uiLayoutAlpha = current.uiLayoutAlpha,
            cardShadow = current.cardShadow,
            cardColor = current.cardColor,
            mutedColor = current.mutedColor,
            searchFieldBackgroundColor = current.searchFieldBackgroundColor,
            tabBackgroundColor = current.tabBackgroundColor,
            shelfColor = current.shelfColor,
            fontScale = current.fontScale
        )
        viewModelScope.launch(Dispatchers.Main) {
            kotlin.runCatching {
                ThemeConfig.applyConfig(app, config, switchNightMode = true)
            }.onSuccess {
                _state.update { it.copy(applySuccess = true) }
                // 保存成功：重建当前模式初始快照 + 同步草稿缓存剔 applySuccess
                // （防 drafts 携带 true 导致重复触发自动关闭，红队 N2-P1-1 场景 C）
                val applied = _state.value
                recordInitialSnapshot(applied)
                drafts[applied.isNight] = applied.copy(applySuccess = false)
            }
        }
    }

    /** UI 自动关闭消费掉 applySuccess 后复位（防二次进入误触发，红队 R2-P1-1 场景 B） */
    fun onApplyConsumed() {
        _state.update { it.copy(applySuccess = false) }
    }

    private fun fromConfig(config: ThemeConfig.Config): ThemeEditorState {
        return ThemeEditorState(
            themeName = config.themeName,
            isNight = config.isNightTheme,
            primaryColor = config.primaryColor,
            accentColor = config.accentColor,
            backgroundColor = config.backgroundColor,
            bottomBackground = config.bottomBackground,
            cardColor = config.cardColor,
            mutedColor = config.mutedColor,
            searchFieldBackgroundColor = config.searchFieldBackgroundColor,
            tabBackgroundColor = config.tabBackgroundColor,
            shelfColor = config.shelfColor,
            wallpaperPath = config.backgroundImgPath?.takeIf { it.isNotBlank() },
            wallpaperBlur = config.backgroundImgBlur,
            uiCornerScale = config.uiCornerScale ?: 1f,
            uiLayoutAlpha = config.uiLayoutAlpha ?: 100,
            cardShadow = config.cardShadow,
            fontScale = config.fontScale?.takeIf { it in 8..16 }
        )
    }

    /** 从当前应用主题 pref 加载草稿（同源 ThemeManageActivity.currentConfig 精简版） */
    private fun loadCurrentConfig(isNight: Boolean): ThemeEditorState {
        val app = getApplication<Application>()
        val primary = app.getPrefInt(
            if (isNight) PreferKey.cNPrimary else PreferKey.cPrimary,
            app.getCompatColor(
                if (isNight) io.legado.app.R.color.md_blue_grey_600 else io.legado.app.R.color.md_brown_500
            )
        )
        val accent = app.getPrefInt(
            if (isNight) PreferKey.cNAccent else PreferKey.cAccent,
            app.getCompatColor(
                if (isNight) io.legado.app.R.color.md_deep_orange_800 else io.legado.app.R.color.md_red_600
            )
        )
        val background = app.getPrefInt(
            if (isNight) PreferKey.cNBackground else PreferKey.cBackground,
            app.getCompatColor(
                if (isNight) io.legado.app.R.color.md_grey_900 else io.legado.app.R.color.md_grey_100
            )
        )
        val bottom = app.getPrefInt(
            if (isNight) PreferKey.cNBBackground else PreferKey.cBBackground,
            app.getCompatColor(
                if (isNight) io.legado.app.R.color.md_grey_850 else io.legado.app.R.color.md_grey_200
            )
        )
        val wallpaperPath = app.getPrefString(if (isNight) PreferKey.bgImageN else PreferKey.bgImage)
            ?.takeIf { it.isNotBlank() }
        return ThemeEditorState(
            themeName = "",
            isNight = isNight,
            primaryColor = ThemePaletteExtractor.toHex6(primary),
            accentColor = ThemePaletteExtractor.toHex6(accent),
            backgroundColor = ThemePaletteExtractor.toHex6(background),
            bottomBackground = ThemePaletteExtractor.toHex6(bottom),
            cardColor = app.getPrefString(ThemeRuntimeKeys.themeCardColor(isNight))?.takeIf { it.isNotBlank() },
            mutedColor = app.getPrefString(ThemeRuntimeKeys.themeMutedColor(isNight))?.takeIf { it.isNotBlank() },
            searchFieldBackgroundColor = app.getPrefString(
                ThemeRuntimeKeys.themeSearchFieldBackgroundColor(isNight)
            )?.takeIf { it.isNotBlank() },
            tabBackgroundColor = app.getPrefString(ThemeRuntimeKeys.themeTabBackgroundColor(isNight))?.takeIf { it.isNotBlank() },
            shelfColor = app.getPrefString(ThemeRuntimeKeys.themeShelfColor(isNight))?.takeIf { it.isNotBlank() },
            wallpaperPath = wallpaperPath,
            wallpaperBitmap = wallpaperPath?.let { decodePreviewBitmap(it)?.asImageBitmap() },
            wallpaperBlur = app.getPrefInt(if (isNight) PreferKey.bgImageNBlurring else PreferKey.bgImageBlurring, 0),
            uiCornerScale = app.getPrefString(ThemeRuntimeKeys.uiCornerScale(isNight), "1")
                ?.toFloatOrNull()?.coerceIn(0f, 3f) ?: 1f,
            uiLayoutAlpha = app.getPrefInt(
                ThemeRuntimeKeys.uiLayoutAlpha(isNight),
                app.getPrefInt(PreferKey.uiCornerEffectLevel, 100)
            ).coerceIn(0, 100),
            cardShadow = app.getPrefInt(ThemeRuntimeKeys.themeCardShadow(isNight), -1)
                .takeIf { it >= 0 },
            fontScale = app.getPrefInt(ThemeRuntimeKeys.fontScale(isNight), 0)
                .takeIf { it in 8..16 }
        )
    }

    /** 预览位图降采样 ≤1024px（取色降采样在 ThemePaletteExtractor 内 ≤512px） */
    private fun decodePreviewBitmap(path: String): Bitmap? {
        return kotlin.runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > PREVIEW_MAX_SIDE) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(path, options)
        }.getOrNull()
    }

    companion object {
        private const val PREVIEW_MAX_SIDE = 1024
    }
}
