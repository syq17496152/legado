package io.legado.app.help.config

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AD-05 Compose 主题编辑器：从图片提取主题配色（纯 Kotlin 方案 B）。
 *
 * 算法移植自 legado-theme-stylist/templates/extract_palette.py（quantize+tone 逻辑），
 * PIL MEDIANCUT 简化为「RGB 桶计数 + 按占比排序取主色」，不引入 androidx.palette 依赖。
 * 降采样上限 512px，IO/Default 线程执行，任何失败静默降级（返回 null，不弹错）。
 */
object ThemePaletteExtractor {

    /** 取色降采样上限（最长边） */
    private const val MAX_SAMPLE_SIDE = 512

    /** 主色数量 k（对齐 py 脚本 --k 6） */
    private const val K = 6

    /** 参与建议映射的最小占比（对齐 py 脚本 ratio >= 0.02） */
    private const val MIN_RATIO = 0.02f

    /** 日/夜背景与卡片明度目标（对齐 py 脚本 palette-design.md 可读性规则） */
    private const val DAY_BG_LUM = 0.93f
    private const val DAY_CARD_F = 1.04f
    private const val NIGHT_BG_LUM = 0.14f
    private const val NIGHT_CARD_LUM = 0.19f

    /** 单个主色（含占比/饱和度/明度） */
    data class PaletteColor(
        val rgb: Int,
        val ratio: Float,
        val sat: Float,
        val lum: Float
    )

    /** 单模式主题色建议（hex 均 #AARRGGBB） */
    data class ThemeCandidate(
        val primary: String,
        val accent: String,
        val background: String,
        val card: String,
        val muted: String,
        val tabBackground: String,
        val searchFieldBackground: String,
        val bottomBackground: String,
        val shelf: String
    )

    /** 从图取色结果：日/夜两套建议 + 主导色 */
    data class ExtractedPalette(
        val day: ThemeCandidate,
        val night: ThemeCandidate,
        val dominant: Int
    )

    /**
     * 从 uri 提取配色。内部完成 ≤512px 降采样（调用方无需预处理），失败返回 null 静默降级。
     */
    suspend fun extract(context: Context, uri: Uri): ExtractedPalette? {
        return withContext(Dispatchers.Default) {
            kotlin.runCatching {
                val bitmap = decodeSampled(context, uri) ?: return@runCatching null
                val colors = bucketColors(bitmap, K)
                if (colors.isEmpty()) {
                    bitmap.recycle()
                    return@runCatching null
                }
                val palette = suggest(colors)
                bitmap.recycle()
                palette
            }.getOrNull()
        }
    }

    /** 解码 uri 并降采样到最长边 ≤512px */
    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SAMPLE_SIDE) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    /**
     * PIL quantize(MEDIANCUT) 简化实现：每通道 16 级分桶（4096 桶），
     * 累计计数与通道和，代表色取桶内均值，按计数降序取前 k。
     */
    private fun bucketColors(bitmap: Bitmap, k: Int): List<PaletteColor> {
        data class Bucket(var count: Int = 0, var rSum: Long = 0, var gSum: Long = 0, var bSum: Long = 0)

        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val buckets = HashMap<Int, Bucket>(512)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val key = ((r shr 4) shl 8) or ((g shr 4) shl 4) or (b shr 4)
            val bucket = buckets.getOrPut(key) { Bucket() }
            bucket.count++
            bucket.rSum += r
            bucket.gSum += g
            bucket.bSum += b
        }
        val total = pixels.size.toFloat()
        return buckets.values
            .sortedByDescending { it.count }
            .take(k)
            .map { bucket ->
                val rgb = ((bucket.rSum / bucket.count).toInt() shl 16) or
                    ((bucket.gSum / bucket.count).toInt() shl 8) or
                    (bucket.bSum / bucket.count).toInt()
                PaletteColor(
                    rgb = rgb or 0xFF000000.toInt(),
                    ratio = bucket.count / total,
                    sat = saturationOf(rgb),
                    lum = luminanceOf(rgb)
                )
            }
    }

    /** 对齐 py saturatioin：HSV 风格饱和度（max-min)/max */
    private fun saturationOf(rgb: Int): Float {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        return if (mx == 0f) 0f else (mx - mn) / mx
    }

    /** 对齐 py luminance：Rec.709 加权亮度（0-1） */
    private fun luminanceOf(rgb: Int): Float {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        return 0.2126f * r + 0.7152f * g + 0.0722f * b
    }

    /** 对齐 py mix_gray：保留色相、把饱和度压到 satF 比例 */
    private fun mixGray(rgb: Int, satF: Float): FloatArray {
        val r = ((rgb shr 16) and 0xFF) / 255f
        val g = ((rgb shr 8) and 0xFF) / 255f
        val b = (rgb and 0xFF) / 255f
        val gray = 0.2126f * r + 0.7152f * g + 0.0722f * b
        return floatArrayOf(
            gray + (r - gray) * satF,
            gray + (g - gray) * satF,
            gray + (b - gray) * satF
        )
    }

    /** 对齐 py tone：去饱和 + 校准到目标明度，输出 #AARRGGBB */
    private fun tone(rgb: Int, satF: Float, lumTarget: Float): String {
        val ch = mixGray(rgb, satF)
        val cur = 0.2126f * ch[0] + 0.7152f * ch[1] + 0.0722f * ch[2]
        val f = if (cur > 1e-4f) lumTarget / cur else 1f
        return channelsToHex(floatArrayOf(ch[0] * f, ch[1] * f, ch[2] * f))
    }

    /** 对齐 py scale_hex：通道乘系数后输出 hex */
    private fun scaleHex(ch01: FloatArray, f: Float): String {
        return channelsToHex(floatArrayOf(ch01[0] * f, ch01[1] * f, ch01[2] * f))
    }

    private fun channelsToHex(ch: FloatArray): String {
        val r = ((ch[0].coerceIn(0f, 1f) * 255f).toInt()).coerceIn(0, 255)
        val g = ((ch[1].coerceIn(0f, 1f) * 255f).toInt()).coerceIn(0, 255)
        val b = ((ch[2].coerceIn(0f, 1f) * 255f).toInt()).coerceIn(0, 255)
        return String.format(java.util.Locale.US, "#FF%02X%02X%02X", r, g, b)
    }

    /** 建议映射（对齐 py suggest：日间最亮主色→浅背景压暗主色；夜间最暗主色→深背景提亮主色） */
    private fun suggest(colors: List<PaletteColor>): ExtractedPalette {
        val cols = colors.filter { it.ratio >= MIN_RATIO }.ifEmpty { colors }
        val mostSat = cols.maxWith(compareBy({ it.sat }, { it.ratio }))
        val rest = cols.filter { it !== mostSat }
        val secondSat = if (rest.isEmpty()) mostSat else rest.maxWith(compareBy({ it.sat }, { it.ratio }))
        val brightest = cols.maxBy { it.lum }
        val darkest = cols.minBy { it.lum }

        val dayBg = mixGray(brightest.rgb, 0.30f)
        val dayBgLum = 0.2126f * dayBg[0] + 0.7152f * dayBg[1] + 0.0722f * dayBg[2]
        val dayBgF = if (dayBgLum > 1e-4f) DAY_BG_LUM / dayBgLum else 1f
        val dayBgCalibrated = floatArrayOf(dayBg[0] * dayBgF, dayBg[1] * dayBgF, dayBg[2] * dayBgF)

        val nightBg = mixGray(darkest.rgb, 0.22f)
        val nightBgLum = 0.2126f * nightBg[0] + 0.7152f * nightBg[1] + 0.0722f * nightBg[2]
        val nightBgF = if (nightBgLum > 1e-4f) NIGHT_BG_LUM / nightBgLum else 1f
        val nightBgCalibrated = floatArrayOf(nightBg[0] * nightBgF, nightBg[1] * nightBgF, nightBg[2] * nightBgF)

        val day = ThemeCandidate(
            primary = tone(mostSat.rgb, 0.65f, 0.42f),
            accent = tone(secondSat.rgb, 0.75f, 0.50f),
            background = scaleHex(dayBgCalibrated, 1.0f),
            card = scaleHex(dayBgCalibrated, DAY_CARD_F),
            muted = scaleHex(dayBgCalibrated, 0.965f),
            tabBackground = scaleHex(dayBgCalibrated, 0.965f),
            searchFieldBackground = scaleHex(dayBgCalibrated, 0.965f),
            bottomBackground = scaleHex(dayBgCalibrated, 1.0f),
            shelf = scaleHex(dayBgCalibrated, 1.0f)
        )
        val night = ThemeCandidate(
            primary = tone(mostSat.rgb, 0.90f, 0.62f),
            accent = tone(secondSat.rgb, 0.95f, 0.68f),
            background = scaleHex(nightBgCalibrated, 1.0f),
            card = tone(darkest.rgb, 0.28f, NIGHT_CARD_LUM),
            muted = scaleHex(nightBgCalibrated, 0.72f),
            tabBackground = scaleHex(nightBgCalibrated, 0.72f),
            searchFieldBackground = scaleHex(nightBgCalibrated, 0.72f),
            bottomBackground = scaleHex(nightBgCalibrated, 1.0f),
            shelf = scaleHex(nightBgCalibrated, 1.0f)
        )
        return ExtractedPalette(
            day = day,
            night = night,
            dominant = android.graphics.Color.argb(
                255,
                ((mostSat.rgb shr 16) and 0xFF),
                ((mostSat.rgb shr 8) and 0xFF),
                (mostSat.rgb and 0xFF)
            )
        )
    }

    /** hex（#RRGGBB/#AARRGGBB）转 Argb，失败返回 null（编辑器内部防御解析） */
    fun parseHexOrNull(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val normalized = when {
            raw.startsWith("#") -> raw
            else -> "#$raw"
        }
        return kotlin.runCatching {
            android.graphics.Color.parseColor(normalized)
        }.getOrNull()
    }

    /** Argb 转 #RRGGBB（去掉 alpha，编辑器统一 6 位展示） */
    fun toHex6(argb: Int): String {
        return String.format(java.util.Locale.US, "#%06X", 0xFFFFFF and argb)
    }

    /** compose Color 转 #RRGGBB */
    fun colorToHex(color: androidx.compose.ui.graphics.Color): String {
        return toHex6(color.toArgb())
    }

    /** 通道绝对差判断近似同色（建议色高亮用） */
    fun isSameColor(a: Int, b: Int): Boolean {
        return abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= 2 &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= 2 &&
            abs((a and 0xFF) - (b and 0xFF)) <= 2
    }
}
