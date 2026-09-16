package io.legado.app.help

import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.ReadBookConfig
import androidx.core.graphics.toColorInt
import io.legado.app.utils.ColorUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * 高亮语义色板（R12.4）——**颜色唯一真源** + 阅读器三态取值。
 *
 * 设计要点：
 * 1. **单一真源**：取色器预设、手动高亮预设、内置规则默认色三处统一引用本对象，不再各自硬编码。
 * 2. **判定源**：按**实际阅读背景**判定（`ReadBookConfig.durConfig.curBgType()==0` 时解析背景色亮度；
 *    背景图/无法解析时回退到阅读器明暗开关），**不使用**软件主题 `ThemeStore` / `Context.isDarkTheme`。
 * 3. **三态**：`DAY`（浅底）/ `NIGHT`（深底）/ `EINK`（墨水屏，判序最优先）。
 *    墨水屏不依赖色相——正文取黑并在仅有颜色通道时补下划线，保证可辨识。
 * 4. **对比度门禁**：正文色相对实际阅读背景不得低于 [MIN_CONTRAST_RATIO]（由单测锁定）。
 * 5. **适配边界**：仅对**色板派生**样式（`HighlightStyle.paletteSlot != null`）生效；
 *    用户显式手选的颜色（`paletteSlot == null`）原样保留，不被静默改写。
 *
 * 亮度判定复用既有 `io.legado.app.utils.ColorUtils.isColorLight`（禁止自研第二套算法）。
 */
object HighlightPalette {

    /** 阅读器配色三态 */
    enum class Tone { DAY, NIGHT, EINK }

    /** 语义槽位：与内置规则语义一一对应（新增内置规则时在此登记） */
    object Slot {
        const val DIALOGUE = "dialogue"
        const val DASH_DIALOGUE = "dashDialogue"
        const val BOOK_TITLE = "bookTitle"
        const val NOTE = "note"
        const val TITLE_EMPHASIS = "titleEmphasis"
        const val THOUGHT = "thought"
        const val NARRATOR = "narrator"
        const val EMPHASIS = "emphasis"
        const val POETRY = "poetry"
        const val ELLIPSIS = "ellipsis"
        const val NUMBER = "number"
        const val ENGLISH = "english"
        const val DATE_TIME = "dateTime"
        const val SYSTEM_PANEL = "systemPanel"
        const val ONOMATOPOEIA = "onomatopoeia"
        const val MARKDOWN = "markdown"
        const val URL = "url"
    }

    /** WCAG 正文对比度下限（AA 普通字号） */
    const val MIN_CONTRAST_RATIO = 4.5f

    /** 单测用的代表性阅读背景：白底 / 深底 */
    const val BG_DAY = 0xFFFFFFFF.toInt()
    const val BG_NIGHT = 0xFF1A1A1A.toInt()

    /** 墨水屏正文色（黑白，不依赖色相） */
    private const val EINK_TEXT = 0xFF000000.toInt()

    /**
     * 语义槽位色值：浅底（DAY：深色系）/ 深底（NIGHT：浅色系）。
     * 墨水屏统一取黑（[EINK_TEXT]）并靠线型区分。
     */
    private val entries: Map<String, Pair<Int, Int>> = mapOf(
        // 对白/引号类：橙棕系（浅底深、深底浅）
        Slot.DIALOGUE to (0xFFB45309.toInt() to 0xFFFFB74D.toInt()),
        Slot.DASH_DIALOGUE to (0xFF9A5B00.toInt() to 0xFFFFCC80.toInt()),
        Slot.ONOMATOPOEIA to (0xFFBF360C.toInt() to 0xFFFFAB91.toInt()),
        // 书名号：绿系
        Slot.BOOK_TITLE to (0xFF2E7D32.toInt() to 0xFFA5D6A7.toInt()),
        // 括号标注：中性灰
        Slot.NOTE to (0xFF5F6368.toInt() to 0xFFBDBDBD.toInt()),
        // 标题强调：近黑 ↔ 近白（修复"夜间标题不可见"）
        Slot.TITLE_EMPHASIS to (0xFF1F1F1F.toInt() to 0xFFE8E8E8.toInt()),
        // 心理活动：紫系
        Slot.THOUGHT to (0xFF6A1B9A.toInt() to 0xFFCE93D8.toInt()),
        Slot.MARKDOWN to (0xFF7B1FA2.toInt() to 0xFFE1BEE7.toInt()),
        // 旁白/弱化：蓝灰系
        Slot.NARRATOR to (0xFF455A64.toInt() to 0xFFB0BEC5.toInt()),
        Slot.URL to (0xFF546E7A.toInt() to 0xFFB0BEC5.toInt()),
        // 重点：红系
        Slot.EMPHASIS to (0xFFC62828.toInt() to 0xFFEF9A9A.toInt()),
        // 诗词：深灰蓝
        Slot.POETRY to (0xFF37474F.toInt() to 0xFFB0BEC5.toInt()),
        Slot.ELLIPSIS to (0xFF616161.toInt() to 0xFFBDBDBD.toInt()),
        // 数字金额：蓝系；英文：青系（原先两者同色，此处按语义拆分）
        Slot.NUMBER to (0xFF1565C0.toInt() to 0xFF90CAF9.toInt()),
        Slot.ENGLISH to (0xFF00838F.toInt() to 0xFF80DEEA.toInt()),
        Slot.DATE_TIME to (0xFF00695C.toInt() to 0xFF80CBC4.toInt()),
        Slot.SYSTEM_PANEL to (0xFF0277BD.toInt() to 0xFF81D4FA.toInt())
    )

    /** 取色器背景预设（浅底 / 深底；墨水屏无半透明概念，返回空） */
    private val fillsDay = intArrayOf(
        0x80FFF176.toInt(), 0x80AED581.toInt(), 0x804FC3F7.toInt(),
        0x80F06292.toInt(), 0x80FFB74D.toInt()
    )
    private val fillsNight = intArrayOf(
        0x80B28900.toInt(), 0x80558B2F.toInt(), 0x8021568C.toInt(),
        0x808C3B54.toInt(), 0x808C5A00.toInt()
    )

    /** 取色器字色预设（浅底深色 / 深底浅色） */
    private val textsDay = intArrayOf(
        0xFFD32F2F.toInt(), 0xFF1976D2.toInt(), 0xFF2E7D32.toInt(),
        0xFF8D4A00.toInt(), 0xFF7B1FA2.toInt()
    )
    private val textsNight = intArrayOf(
        0xFFEF9A9A.toInt(), 0xFF90CAF9.toInt(), 0xFFA5D6A7.toInt(),
        0xFFFFCC80.toInt(), 0xFFCE93D8.toInt()
    )

    // ---------------- 取值 ----------------

    fun bgPresets(tone: Tone): IntArray = when (tone) {
        Tone.DAY -> fillsDay
        Tone.NIGHT -> fillsNight
        Tone.EINK -> IntArray(0)
    }

    fun textPresets(tone: Tone): IntArray = when (tone) {
        Tone.DAY -> textsDay
        Tone.NIGHT -> textsNight
        Tone.EINK -> intArrayOf(EINK_TEXT)
    }

    /** 全部已登记槽位（供单测遍历校验对比度门禁） */
    fun slots(): Set<String> = entries.keys

    /** 槽位正文色（按阅读器态） */
    fun color(slot: String?, tone: Tone): Int {
        slot ?: return 0
        if (tone == Tone.EINK) return EINK_TEXT
        val pair = entries[slot] ?: return 0
        return if (tone == Tone.NIGHT) pair.second else pair.first
    }

    /** 槽位默认字色（兼容旧语义：跟随阅读字色时返回 0） */
    fun colorOrZero(slot: String?, tone: Tone): Int = color(slot, tone)

    // ---------------- 阅读器态判定 ----------------

    /**
     * 当前阅读器态：墨水屏优先（判序与 `ReadBookConfig` 一致），
     * 其余按**实际阅读背景亮度**判定；背景为图片或无法解析时回退阅读器明暗开关。
     */
    fun currentTone(): Tone {
        if (AppConfig.isEInkMode) return Tone.EINK
        val bg = resolveBgColor()
        if (bg != null) return if (ColorUtils.isColorLight(bg)) Tone.DAY else Tone.NIGHT
        return if (AppConfig.isNightTheme) Tone.NIGHT else Tone.DAY
    }

    /** 解析当前阅读背景色；背景为图片/九宫格或解析失败时返回 null（视为"无法解析单色"） */
    private fun resolveBgColor(): Int? {
        return try {
            if (ReadBookConfig.durConfig.curBgType() != 0) return null
            val str = ReadBookConfig.durConfig.curBgStr()
            if (str.isBlank()) null else str.toColorInt()
        } catch (_: Throwable) {
            null
        }
    }

    // ---------------- 样式解析 ----------------

    /**
     * 把「色板派生样式」解析为当前阅读器态的具体色值。
     *
     * - `paletteSlot == null`（用户手选色/旧数据）→ **原样返回**，绝不改写用户显式选择；
     * - 墨水屏：正文取黑，且当样式仅有颜色通道（无装饰）时补下划线，保证不依赖色相可辨识。
     */
    fun resolve(style: HighlightStyle, tone: Tone = currentTone()): HighlightStyle {
        // 防御：GSON 反序列化缺失字段会写入 null（见 HighlightStyle.sanitized），
        // 否则此处 copy() 会抛「parameter fontPath」NPE（真机 L2 实锤）
        val safe = style.sanitized()
        val slot = safe.paletteSlot ?: return safe
        val text = if (safe.textColor != 0) color(slot, tone) else 0
        val underline = safe.underline?.copy(
            color = if (safe.underline.color != 0) color(slot, tone) else safe.underline.color
        )
        val strike = safe.strike?.copy(
            color = if (safe.strike.color != 0) color(slot, tone) else safe.strike.color
        )
        val box = safe.box?.copy(
            color = if (safe.box.color != 0) color(slot, tone) else safe.box.color
        )
        val emphasis = safe.emphasis?.copy(
            color = if (safe.emphasis.color != 0) color(slot, tone) else safe.emphasis.color
        )
        // R1a：阴影计入装饰（墨水屏"仅有颜色通道才补下划线"据此判定）；
        // 注意 `shadow`/`fill`/`fillShape` **不参与槽位替换**——下方 copy() 原样透传，
        // 避免把用户设置的半透明阴影色覆盖为不透明槽位色
        val hasDecoration = underline != null || strike != null || box != null ||
            emphasis != null || safe.shadow != null
        val einkUnderline = if (tone == Tone.EINK && !hasDecoration) {
            HighlightStyle.Underline(kind = HighlightStyle.Kind.SOLID, color = 0)
        } else {
            null
        }
        return safe.copy(
            textColor = text,
            underline = underline ?: einkUnderline,
            strike = strike,
            box = box,
            emphasis = emphasis
        )
    }

    // ---------------- 对比度 ----------------

    /** WCAG 相对对比度（1.0 ~ 21.0） */
    fun contrastRatio(foreground: Int, background: Int): Float {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05f) / (darker + 0.05f)
    }

    /**
     * 自动对比度校正（供"自动对比度校正"开关使用；默认关闭，故默认不作用于用户手选色）：
     * 在当前背景下把前景色按需向黑/白方向调整至满足 [MIN_CONTRAST_RATIO]。
     */
    fun ensureReadable(foreground: Int, background: Int): Int {
        if (foreground == 0 || contrastRatio(foreground, background) >= MIN_CONTRAST_RATIO) {
            return foreground
        }
        // 方向判定用本对象的 WCAG 相对亮度（与对比度门禁同一套数学，且不依赖 Android 运行时，
        // 便于 JVM 单测；`ColorUtils.isColorLight` 仅在 [currentTone] 的生产路径使用）
        val targetIsDark = relativeLuminance(background) > 0.5f
        var low = 0
        var high = 100
        // 二分找到满足对比度的最小/最大混合比例（向黑或向白混合），保证尽量保留原色相
        val toward = if (targetIsDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        var best = if (targetIsDark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        repeat(12) {
            val mid = (low + high) / 2
            val blended = blend(foreground, toward, mid / 100f)
            if (contrastRatio(blended, background) >= MIN_CONTRAST_RATIO) {
                best = blended
                high = mid
            } else {
                low = mid
            }
        }
        return best
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val r = (((from shr 16) and 0xFF) * (1 - ratio) + ((to shr 16) and 0xFF) * ratio).toInt()
        val g = (((from shr 8) and 0xFF) * (1 - ratio) + ((to shr 8) and 0xFF) * ratio).toInt()
        val b = ((from and 0xFF) * (1 - ratio) + (to and 0xFF) * ratio).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun relativeLuminance(color: Int): Float {
        val r = channel(color shr 16 and 0xFF)
        val g = channel(color shr 8 and 0xFF)
        val b = channel(color and 0xFF)
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    private fun channel(value: Int): Float {
        val c = value / 255f
        return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    }
}