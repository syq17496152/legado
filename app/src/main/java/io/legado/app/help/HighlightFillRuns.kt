package io.legado.app.help

/**
 * R1a：背景填充 run 的合并判定（**纯函数，无 Android 依赖 → 可 JVM 单测**）。
 *
 * 背景：背景填充必须"合并连续同填充色 + 同形状的列后一次绘制"——逐列绘制会把一个胶囊
 * 切成 N 个小胶囊（端部圆角每列都出现）。该判等原先内联在
 * `TextLine.drawHighlightFillRuns`（Android 绘制层，JVM 测不到）→ 抽到此处以便单测锁定。
 *
 * 判等字段：`fill`（含 alpha 的颜色）与 `resolvedFillShape`（形状）。
 * 其余通道（字色 / 下划线 / 阴影 / 字体…）**不影响**填充合并。
 */
object HighlightFillRuns {

    /**
     * 相邻两列的样式是否属于同一填充 run。
     *
     * @return 同色同形状（且均非"无填充"）→ true；否则 false
     */
    fun sameFillRun(a: HighlightStyle?, b: HighlightStyle?): Boolean {
        if (a == null || b == null) return false
        if (a.fill == 0 || b.fill == 0) return false
        return a.fill == b.fill && a.resolvedFillShape == b.resolvedFillShape
    }
}