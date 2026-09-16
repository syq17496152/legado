package io.legado.app.help

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sin

/**
 * F-P1-2 高亮规则系统（借鉴阅读T）
 * 高亮装饰的纯几何计算(无 Android 依赖, 可 JVM 单测)。
 * 入参均为已换算好的像素值;输出坐标交给 Canvas 绘制。
 */
object HighlightGeometry {

    data class Dot(val cx: Float, val cy: Float, val r: Float)

    /**
     * 波浪线采样点,返回 [x0,y0, x1,y1, ...](沿 baseY 上下振幅 amplitude, 周期 wavelength, 步进 step)。
     * 供 Canvas 用 Path 逐点连线绘制。
     */
    fun wavePoints(
        x0: Float, x1: Float, baseY: Float,
        amplitude: Float, wavelength: Float, step: Float
    ): FloatArray {
        if (x1 <= x0 || step <= 0f || wavelength <= 0f) return FloatArray(0)
        val segs = ceil((x1 - x0) / step).toInt()   // 覆盖到 x1 所需步数
        val n = segs + 1                             // 采样点数(末点落在 x1)
        val arr = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = if (i == segs) x1 else x0 + i * step   // 末点夹到 x1, 防右端漏画
            val phase = (x - x0) / wavelength * (2.0 * PI)
            arr[i * 2] = x
            arr[i * 2 + 1] = (baseY + amplitude * sin(phase)).toFloat()
        }
        return arr
    }

    /** 每列一个着重点:starts/ends 为各列 x 区间,圆心取列中点 */
    fun emphasisDots(starts: FloatArray, ends: FloatArray, cy: Float, r: Float): List<Dot> {
        require(starts.size == ends.size) { "starts/ends size mismatch" }
        return List(starts.size) { i -> Dot((starts[i] + ends[i]) / 2f, cy, r) }
    }

    /** R1a：填充带（相对行顶的上下边界；canvas 已在行级平移，故 y 为行内偏移） */
    data class Band(val top: Float, val bottom: Float)

    /**
     * R1a：按填充形状计算填充带（纯几何，无 Android 依赖）。
     *
     * - 矩形 / 圆角 / 胶囊：铺满整行高（与改造前逐列矩形**逐像素等价**）
     * - 荧光笔（MARKER）：高度约 0.72 行高，垂直居中
     * - 半高（HALF）：自基线略上方到行底（下半标记）
     * - 基线带（BASELINE）：基线下方细带
     */
    fun fillBand(
        baseline: Float,
        textSize: Float,
        height: Float,
        shape: HighlightStyle.FillShape
    ): Band {
        return when (shape) {
            HighlightStyle.FillShape.RECTANGLE,
            HighlightStyle.FillShape.ROUNDED,
            HighlightStyle.FillShape.PILL -> Band(0f, height)

            HighlightStyle.FillShape.MARKER -> {
                val h = height * 0.72f
                val top = ((height - h) / 2f).coerceAtLeast(0f)
                Band(top, top + h)
            }

            HighlightStyle.FillShape.HALF -> {
                val top = (baseline - textSize * 0.5f).coerceIn(0f, height)
                Band(top, height)
            }

            HighlightStyle.FillShape.BASELINE -> {
                val top = (baseline + textSize * 0.15f).coerceIn(0f, height)
                Band(top, height)
            }
        }
    }

    /**
     * R1a：胶囊（PILL）端部的**水平**圆角半径。
     *
     * 简化说明：降级取「填充带高的一半」（视觉上接近胶囊），不依赖逐字墨迹盒——
     * 该数据在自研排版路径上的可得性未确证。| 已知上限：端部圆角与字形边缘不严格贴合。
     * | 升级路径：接入逐字墨迹盒后按实际字形端部半径绘制（另行立项）。
     */
    fun pillRadiusX(band: Band): Float = (band.bottom - band.top) / 2f
}
