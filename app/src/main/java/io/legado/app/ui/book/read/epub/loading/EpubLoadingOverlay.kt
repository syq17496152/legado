package io.legado.app.ui.book.read.epub.loading

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Picture
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import io.legado.app.help.config.EpubLoadingTemplate
import io.legado.app.help.config.Palette
import io.legado.app.help.config.Scene
import io.legado.app.help.config.argbToHex
import io.legado.app.utils.SvgUtils
import kotlin.math.max
import kotlin.math.min

/**
 * 首屏加载模板的**绘制器**（窗口遮罩与预览**共享**同一实现 ⇒ 预览所见即开书所见）。
 *
 * 三条纪律（都是本项目踩过的坑）：
 * 1. **无动画、无定时重绘**：只画"一帧确定性构图"，由调用方 `invalidate()` 驱动 ——
 *    加载遮罩常在开书瞬间出现，任何 `Choreographer`/属性动画都会与首屏渲染抢资源（真机观感＝卡一下）。
 * 2. **重活只做一次**：`(Scene, Palette) → Picture` 的 **SVG 解析**是最重操作，必须缓存；
 *    渐变 Shader 同样按"尺寸 + 取色 + insets"缓存（`FrameKey`）。文字度量很便宜，每帧现算。
 * 3. **失败即回落**：任何一步不可用（解析失败 / 尺寸非法 / 无插画）都返回 `false`，
 *    由 `EpubReadView` 走既有遮罩 —— 绝不出现"模板没画出来 = 白屏"。
 *
 * 素材合规：插画是**手写 SVG + `{{token}}` 参数化**（`res/raw/loading_*.svg`），绘制期按当前 Palette
 * 替换成 `#RRGGBB`（AGENTS.md §11：入库图片必须能由代码/脚本重放；零 AI 生图）。
 */
internal class EpubLoadingOverlay(private val resources: Resources) {

    /** 缓存键：尺寸 + 密度 + 取色 + insets ⇒ 只有这些都变了才重建 Shader。 */
    private data class FrameKey(
        val width: Int,
        val height: Int,
        val palette: Palette,
        val insets: LoadingTemplateChrome.EdgeInsets?
    )

    /** Shader 组（背景线性渐变 + 顶部径向光晕）。 */
    private class Shaders(val background: LinearGradient?, val glow: RadialGradient?)

    /** 插画缓存：`scene.key|paletteHash` → Picture（**SVG 解析只做一次**）。 */
    private val artworkCache = HashMap<String, Picture?>()

    private var frameKey: FrameKey? = null
    private var shaders: Shaders? = null

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    /**
     * 画一帧模板首屏。
     *
     * @return `true` 表示**模板已画**（调用方不再走既有遮罩）；`false` 表示不可用（调用方必须回落）。
     */
    fun draw(
        canvas: Canvas,
        template: EpubLoadingTemplate,
        night: Boolean,
        message: String,
        failed: Boolean,
        insets: LoadingTemplateChrome.EdgeInsets?
    ): Boolean {
        val palette = template.palette(night)
        val picture = artwork(template.scene, palette) ?: return false
        if (LoadingTemplateOverlayPolicy.resolve(template, true, canvas.width, canvas.height)
            != LoadingOverlayMode.TEMPLATE
        ) {
            return false
        }
        val width = canvas.width.toFloat()
        val height = canvas.height.toFloat()
        val clamped = LoadingTemplateChrome.clampInsets(
            insets?.left ?: 0, insets?.right ?: 0, insets?.top ?: 0, insets?.bottom ?: 0,
            canvas.width, canvas.height
        ) ?: return false

        drawBackground(canvas, width, height, palette, clamped)
        drawArtwork(canvas, width, height, template, picture, clamped)
        drawTexts(canvas, width, height, template, palette, message, failed, clamped)
        return true
    }

    /** 背景：底色 + 顶部光晕 + 底部渐隐带（线性渐变）。 */
    private fun drawBackground(
        canvas: Canvas,
        width: Float,
        height: Float,
        palette: Palette,
        insets: LoadingTemplateChrome.EdgeInsets
    ) {
        canvas.drawColor(palette.background)
        val cache = ensureShaders(canvas.width, canvas.height, palette, insets)
        cache.background?.let {
            fillPaint.shader = it
            canvas.drawRect(0f, height * 0.62f, width, height, fillPaint)
        }
        cache.glow?.let {
            fillPaint.shader = it
            canvas.drawRect(0f, 0f, width, height, fillPaint)
        }
        fillPaint.shader = null
    }

    /** 插画：按 [LoadingTemplateChrome.artGeometry] 等比定位在文字块上方居中。 */
    private fun drawArtwork(
        canvas: Canvas,
        width: Float,
        height: Float,
        template: EpubLoadingTemplate,
        picture: Picture,
        insets: LoadingTemplateChrome.EdgeInsets
    ) {
        val availW = (width - insets.width).coerceAtLeast(1f)
        val availH = (height - insets.height).coerceAtLeast(1f)
        val wide = LoadingTemplateChrome.isWide(
            (width - insets.width).toInt(), (height - insets.height).toInt(),
            resources.displayMetrics.density
        )
        var art = LoadingTemplateChrome.artGeometry(availW, availH * 0.58f, template.artScale, wide) ?: return
        // 插画高度上限：留出文字块与状态行的空间（避免插画把文字挤出屏幕）。
        // 取值 0.48（真机预览实测 0.56 时插画底边与分割线贴到一起 ⇒ 观感拥挤）。
        val maxH = availH * 0.48f
        if (art.height > maxH) {
            val k = maxH / art.height
            art = LoadingTemplateChrome.ArtGeometry(art.width * k, art.height * k)
        }
        val left = insets.left + (availW - art.width) / 2f
        val top = insets.top + availH * 0.06f
        canvas.drawPicture(picture, RectF(left, top, left + art.width, top + art.height))
    }

    /** 文字块：分割线 → 题记 → 标题 → 副文案 → 状态行（超宽整体等比回缩，不裁剪）。 */
    private fun drawTexts(
        canvas: Canvas,
        width: Float,
        height: Float,
        template: EpubLoadingTemplate,
        palette: Palette,
        message: String,
        failed: Boolean,
        insets: LoadingTemplateChrome.EdgeInsets
    ) {
        val metrics = resources.displayMetrics
        val availW = (width - insets.width).coerceAtLeast(1f)
        val title = template.titleSize * metrics.scaledDensity
        val eyebrowSize = title * 0.40f
        val captionSize = title * 0.46f
        val statusSize = title * 0.48f

        // 先量最宽一行 ⇒ 超宽则整体回缩（保证题记/标题都不被裁）
        val widest = listOf(
            measure(template.eyebrow, eyebrowSize, letterSpacingEm = 0.28f),
            measure(template.name, title, bold = true, typefaceName = template.titleFont),
            measure(template.caption, captionSize),
            measure(message, statusSize)
        ).maxOrNull() ?: 0f
        val scale = LoadingTemplateChrome.textScale(widest, availW * 0.92f)

        // 文字块起点：0.66（略下移，与插画留出可见间隙；实测 0.62 时两者贴合）
        val blockTop = insets.top + (height - insets.height) * 0.66f
        val centerX = insets.left + availW / 2f
        canvas.save()
        canvas.scale(scale, scale, centerX, blockTop)

        var y = blockTop
        // 分割线（短，居中）
        linePaint.color = palette.muted
        linePaint.alpha = 0x66
        linePaint.strokeWidth = max(1f, metrics.density)
        val half = min(availW * 0.18f, title * 3.2f)
        canvas.drawLine(centerX - half, y, centerX + half, y, linePaint)
        y += title * 0.9f

        drawCentered(canvas, template.eyebrow, centerX, y, eyebrowSize, palette.accent, letterSpacingEm = 0.28f)
        y += eyebrowSize * 1.7f
        drawCentered(
            canvas, template.name, centerX, y, title, palette.ink,
            bold = true, typefaceName = template.titleFont
        )
        y += title * 1.35f
        drawCentered(canvas, template.caption, centerX, y, captionSize, palette.muted)
        y += captionSize * 2.1f

        // 状态行：前面一个状态点（失败时取 accent 并略亮，正常取 muted）
        textPaint.typeface = Typeface.DEFAULT
        textPaint.letterSpacing = 0f
        textPaint.color = if (failed) palette.accent else palette.muted
        textPaint.textSize = statusSize
        val statusAlpha = if (failed) 0xF0 else 0xB0
        val statusWidth = textPaint.measureText(message)
        fillPaint.color = palette.accent
        fillPaint.alpha = if (failed) 0xFF else 0x88
        val dotR = statusSize * 0.22f
        val dotX = centerX - statusWidth / 2f - dotR * 2.4f
        canvas.drawCircle(dotX, y - statusSize * 0.34f, dotR, fillPaint)
        textPaint.alpha = statusAlpha
        canvas.drawText(message, centerX, y, textPaint)
        canvas.restore()
    }

    private fun drawCentered(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        size: Float,
        color: Int,
        bold: Boolean = false,
        typefaceName: String? = null,
        letterSpacingEm: Float = 0f
    ) {
        if (text.isBlank()) return
        textPaint.color = color
        textPaint.alpha = 0xFF
        textPaint.textSize = size
        textPaint.letterSpacing = size * letterSpacingEm
        textPaint.typeface = if (typefaceName != null) {
            Typeface.create(typefaceName, if (bold) Typeface.BOLD else Typeface.NORMAL)
        } else {
            Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }
        canvas.drawText(text, centerX, baselineY, textPaint)
    }

    private fun measure(
        text: String,
        size: Float,
        bold: Boolean = false,
        typefaceName: String? = null,
        letterSpacingEm: Float = 0f
    ): Float {
        if (text.isBlank()) return 0f
        textPaint.textSize = size
        textPaint.letterSpacing = size * letterSpacingEm
        textPaint.typeface = if (typefaceName != null) {
            Typeface.create(typefaceName, if (bold) Typeface.BOLD else Typeface.NORMAL)
        } else {
            Typeface.DEFAULT
        }
        return textPaint.measureText(text) + size * letterSpacingEm
    }

    /** Shader 缓存（按尺寸/取色/insets 重建）。 */
    private fun ensureShaders(
        width: Int,
        height: Int,
        palette: Palette,
        insets: LoadingTemplateChrome.EdgeInsets
    ): Shaders {
        val key = FrameKey(width, height, palette, insets)
        shaders?.let { if (frameKey == key) return it }
        val w = width.toFloat()
        val h = height.toFloat()
        val background = LinearGradient(
            0f, h * 0.62f, 0f, h,
            intArrayOf(alphaOf(palette.horizon, 0x12), alphaOf(palette.horizon, 0x9A)),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        val glow = RadialGradient(
            w / 2f, h * 0.34f, max(w, h) * 0.62f,
            intArrayOf(
                alphaOf(palette.glow, 0xF0), alphaOf(palette.glow, 0x48), alphaOf(palette.glow, 0x00)
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        val fresh = Shaders(background, glow)
        frameKey = key
        shaders = fresh
        return fresh
    }

    /** 插画：读 `res/raw/loading_<scene>.svg` → `{{token}}` 替换 → `Picture`（失败 ⇒ null ⇒ 回落）。 */
    private fun artwork(scene: Scene, palette: Palette): Picture? {
        val key = scene.key + "|" + palette.hashCode()
        if (artworkCache.containsKey(key)) return artworkCache[key]
        val picture = kotlin.runCatching {
            val raw = resources.openRawResource(scene.artwork).use { it.readBytes().decodeToString() }
            var svg = raw
            palette.tokens().forEach { (token, color) ->
                svg = svg.replace("{{$token}}", argbToHex(color))
            }
            val stream = svg.toByteArray(Charsets.UTF_8).inputStream()
            SvgUtils.createDrawable(stream)?.first?.picture
        }.getOrNull()
        artworkCache[key] = picture
        return picture
    }

    private fun alphaOf(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)
}
