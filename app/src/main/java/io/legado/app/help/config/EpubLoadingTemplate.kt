package io.legado.app.help.config

import androidx.annotation.RawRes
import io.legado.app.R
import kotlin.math.pow

/**
 * EPUB 首屏加载模板（loading template）—— **模型 + 白名单 + 内置套 + 纯函数单源**。
 *
 * 归属为什么在 `help/config`（而不是 `model/localBook/epubcore/template/`）：
 * 本机制与「阅读页页面模板（readerTemplate）」是**两套独立机制**（ADR-4：不同数据模型、
 * 不同持久化、不同渲染面、不同入口）——前者只画「无正文可看的那一小段首屏」，后者接管正文版式。
 * 放同层（与 [ReaderTemplateManager] 并列）便于一眼看出"互不合并"，也避免 `ui` 包反向依赖内容域包。
 *
 * 与参考实现（另一个 fork）的关系：**只对齐契约形态，素材与文案一律自研**
 * ——①插画不复用对方 raw 资源（AGENTS.md §11：入库图片必须能由随仓脚本/手写 SVG 重放）；
 * ②`Scene` 用自研 key（`ink`/`orbit`/`bloom`），不沿用对方枚举名，避免语义搬迁与死场景；
 * ③`name/description/eyebrow/caption` 全为自研中文，**不含任何第三方作品/角色名**。
 *
 * 尺寸/色值上限都在本文件（唯一处），JSON 校验在 [EpubLoadingTemplateManager]，prefs 键名在
 * `PreferKey`，默认值哨兵在 [ReaderFeatureDefaults]。
 */
data class EpubLoadingTemplate(
    val id: String,
    val name: String,
    val description: String = "",
    val scene: Scene,
    val eyebrow: String = "",
    val caption: String = "",
    val titleFont: String = DEFAULT_TITLE_FONT,
    val titleSize: Float = DEFAULT_TITLE_SIZE,
    val artScale: Float = DEFAULT_ART_SCALE,
    val day: Palette,
    val night: Palette
) {

    /** 内置/自定义判别：**只看 id 前缀**（与 [EpubLoadingTemplateManager] 的 id 分配口径同源）。 */
    val builtIn: Boolean get() = id.startsWith(BUILT_IN_PREFIX)

    /**
     * 按昼夜取色套（日=浅色主题 / 夜=深色主题）。
     * ⚠️ 形参名必须与属性 `night` **区分开**：同名会让 `if (night) night else day` 里的第二个 `night`
     * 解析成布尔形参（返回类型退化成 Any），编译器报 "expected 'Palette', actual 'Any'"。
     */
    fun palette(nightMode: Boolean): Palette = if (nightMode) night else day

    companion object {
        /** 内置 id 前缀（`builtin.loading.<scene>`）。 */
        const val BUILT_IN_PREFIX = "builtin.loading."
        /** 自定义 id 前缀（`local.<uuid>`，由 Manager 分配，**不信任导入方 id**）。 */
        const val LOCAL_PREFIX = "local."

        /** JSON 文档类型与版本（导入校验的**严格等值**判据）。 */
        const val DOC_TYPE = "legado.epub.loading"
        const val DOC_VERSION = 1

        const val DEFAULT_TITLE_FONT = "serif"
        const val DEFAULT_TITLE_SIZE = 28f
        const val DEFAULT_ART_SCALE = 1f

        /** 标题字体白名单（须能被 `Typeface.create(font, NORMAL)` 接受）。 */
        val TITLE_FONTS = listOf("serif", "sans-serif", "sans-serif-light", "sans-serif-medium", "monospace")

        /** 标题字号与插画缩放的可接受区间（**闭区间**，边界值合法）。 */
        val TITLE_SIZE_RANGE = 18f..36f
        val ART_SCALE_RANGE = 0.7f..1.2f

        /** 文案字段长度上限（与参考实现同口径；防超长文案把首屏排版撑坏）。 */
        const val MAX_NAME_LENGTH = 60
        const val MAX_DESCRIPTION_LENGTH = 120
        const val MAX_EYEBROW_LENGTH = 60
        const val MAX_CAPTION_LENGTH = 120

        /**
         * 内置三套（**Scene 数与内置套数一一对应** ⇒ 不会出现"有场景但没套用"的死资源）。
         *
         * 三套的视觉语汇（自研）：
         * - 墨晕：宣纸底 + 淡墨远山弧 + 朱印 —— 对应古籍/宣纸语汇；
         * - 星轨：深空底 + 环状光带 + 星点坐标 —— 对应星图/航迹语汇；
         * - 花笺：奶油底 + 枝叶剪影 + 花瓣 —— 对应花笺/信纸语汇。
         * 日/夜各一套 Palette（六色）⇒ 同一份 SVG（`{{token}}` 参数化）出 **6 种观感**。
         */
        val BUILT_IN: List<EpubLoadingTemplate> = listOf(
            EpubLoadingTemplate(
                id = BUILT_IN_PREFIX + Scene.INK.key,
                name = "墨晕",
                description = "宣纸底色与淡墨远山，翻章时一帧静场",
                scene = Scene.INK,
                eyebrow = "翻 页 之 前",
                caption = "正在准备这一章",
                day = Palette(0xFFF6F1E6.toInt(), 0xFFEDE4D3.toInt(), 0xFF2B2724.toInt(),
                    0xFF8A8175.toInt(), 0xFF9C3A2E.toInt(), 0xFFE7D8BE.toInt()),
                night = Palette(0xFF14161A.toInt(), 0xFF1F232A.toInt(), 0xFFEDE7DA.toInt(),
                    0xFF8E887C.toInt(), 0xFFD9A05B.toInt(), 0xFF3A3F4A.toInt())
            ),
            EpubLoadingTemplate(
                id = BUILT_IN_PREFIX + Scene.ORBIT.key,
                name = "星轨",
                description = "深空与环状光带，适合夜间长读",
                scene = Scene.ORBIT,
                eyebrow = "即 将 展 开",
                caption = "正在装载这一章",
                day = Palette(0xFFDCEBF3.toInt(), 0xFFC3DAE8.toInt(), 0xFF1F2B33.toInt(),
                    0xFF6F8391.toInt(), 0xFF2E6E8E.toInt(), 0xFFEFF7FB.toInt()),
                night = Palette(0xFF06090F.toInt(), 0xFF0D1522.toInt(), 0xFFE6EEF6.toInt(),
                    0xFF7D8B9A.toInt(), 0xFF6FB4D8.toInt(), 0xFF1B2A3C.toInt())
            ),
            EpubLoadingTemplate(
                id = BUILT_IN_PREFIX + Scene.BLOOM.key,
                name = "花笺",
                description = "奶油纸面与枝叶剪影，温和不抢眼",
                scene = Scene.BLOOM,
                eyebrow = "稍 候 片 刻",
                caption = "正在铺开这一章",
                day = Palette(0xFFFFF4E9.toInt(), 0xFFF3DCC6.toInt(), 0xFF33291F.toInt(),
                    0xFF8B7A66.toInt(), 0xFFB5643F.toInt(), 0xFFFCEBDA.toInt()),
                night = Palette(0xFF17121C.toInt(), 0xFF241B2B.toInt(), 0xFFF1E7F2.toInt(),
                    0xFF9A8CA6.toInt(), 0xFFD089A8.toInt(), 0xFF2E2438.toInt())
            )
        )

        /** 默认套 = 第一套（`selected()` 在键为空/失效时回落于此）。 */
        val DEFAULT: EpubLoadingTemplate get() = BUILT_IN.first()

        /** 按 id 取内置套（未命中返回 null）。 */
        fun builtInById(id: String?): EpubLoadingTemplate? = BUILT_IN.firstOrNull { it.id == id }
    }
}

/**
 * 首屏配色六色（与参考实现同口径，但取值自研）。
 *
 * 六色的分工：`background` 全屏底 / `horizon` 地平线渐变中段 / `ink` 文字与主线条 /
 * `muted` 次级文字与弱线条 / `accent` 强调（题记小字、状态点）/ `glow` 光晕（径向渐变中心）。
 */
data class Palette(
    val background: Int,
    val horizon: Int,
    val ink: Int,
    val muted: Int,
    val accent: Int,
    val glow: Int
) {

    /**
     * 系统栏图标是否应取**深色**（＝背景较亮）——由 [luminance] 判定，与参考实现同口径。
     *
     * 为什么用自研 `luminance` 而不引 `androidx.core.graphics.ColorUtils.calculateLuminance`：
     * ①本函数要在**纯 JVM 单测**里被逐值断言（Android 类在 JVM 上不可用）；
     * ②只用到"相对亮度"这一个量，自实现更省一条 Android 依赖边界。
     */
    val darkStatusIcons: Boolean get() = luminance(background) > LUMINANCE_THRESHOLD
    val darkNavigationIcons: Boolean get() = luminance(horizon) > LUMINANCE_THRESHOLD

    /** SVG 参数化占位符 → 颜色（`{{token}}` → `#RRGGBB`），供绘制期替换。 */
    fun tokens(): Map<String, Int> = mapOf(
        "background" to background,
        "horizon" to horizon,
        "ink" to ink,
        "muted" to muted,
        "accent" to accent,
        "glow" to glow
    )

    companion object {
        /** 明暗判定阈值（0.5 = 中间灰，与参考实现取值一致）。 */
        const val LUMINANCE_THRESHOLD = 0.5f
    }
}

/**
 * 内置场景（每个场景绑定一份**手写 SVG**，见 `res/raw/loading_*.svg`）。
 *
 * `viewBox` 统一 `0 0 360 336`（宽高比 = [ART_ASPECT]），几何计算在 `LoadingTemplateChrome` 单源。
 */
enum class Scene(val key: String, @param:RawRes val artwork: Int) {
    INK("ink", R.raw.loading_ink),
    ORBIT("orbit", R.raw.loading_orbit),
    BLOOM("bloom", R.raw.loading_bloom);

    companion object {
        /** 插画基准宽高比（高/宽）= 336/360（绘制侧按此等比缩放，见 `LoadingTemplateChrome`）。 */
        const val ART_ASPECT = 336f / 360f

        /** 场景 key 白名单（导入校验用）。 */
        val KEYS: List<String> = entries.map { it.key }

        fun fromKey(key: String?): Scene? = entries.firstOrNull { it.key == key }
    }
}

// ===== 纯函数（可 JVM 直测；无 Android 依赖）=====

/**
 * 解析 `#RRGGBB` 为 ARGB `Int`（α 固定 FF）。非法输入（长度/字符不符）返回 null。
 *
 * 为什么不引 `android.graphics.Color.parseColor`：①JVM 单测不可用；②它对非法输入抛异常，
 * 在"导入 JSON 里混进脏色值"时会让解析链整体失败 —— 这里用 **可空返回** 让上层的校验与回落更直白。
 */
fun parseHexColor(value: String?): Int? {
    val body = value?.trim()?.removePrefix("#") ?: return null
    if (body.length != 6) return null
    if (!body.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
    return (0xFF000000L or body.toLong(16)).toInt()
}

/**
 * 相对亮度（0..1，sRGB 线性化后按 0.2126/0.7152/0.0722 加权）—— 与参考实现同口径。
 *
 * 用途：判断系统栏图标该取深色还是浅色（见 [Palette.darkStatusIcons]）。
 */
fun luminance(argb: Int): Float {
    fun channel(raw: Int): Float {
        val c = (raw and 0xFF) / 255f
        return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
    val r = channel(argb shr 16)
    val g = channel(argb shr 8)
    val b = channel(argb)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/** `#RRGGBB` 文本（供 SVG 占位符替换；α 恒 FF 故省略）。用 ROOT locale，避免区域化数字。 */
fun argbToHex(argb: Int): String =
    String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and argb)
