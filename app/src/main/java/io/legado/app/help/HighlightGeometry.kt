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
}
