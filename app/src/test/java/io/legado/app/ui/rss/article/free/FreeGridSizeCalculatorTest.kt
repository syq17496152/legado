package io.legado.app.ui.rss.article.free

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「自由」布局预计算器单元测试（纯 JVM，无 Android 依赖）。
 *
 * 对应 `docs/specs/rss-free-layout/tasks.md` 任务 2.2.x 的验证标准：
 * - 行宽严格撑满 availableWidth（末位补偿）
 * - 增量重算结果与全量重算逐字节一致
 * - footer 独占整行、不参与图片分行
 * - 极端 ratio（全 0 / 超长数组）不 hang、不 OOM
 * - 末行不足时左对齐留白且**不被拉伸**（图片仍按原比例）
 * - 文字块高度加入行高但不参与宽度分配
 */
class FreeGridSizeCalculatorTest {

    /**
     * 常用几何：360px 可用宽、4px 间距、目标行高 151px、竖屏 4 张上限、文字块 46px、footer 高 48px。
     *
     * 文字块高 46px 对应 `res/values/dimens.xml` 的 `rss_free_text_block_height`（46sp，密度 1 时即 46px）。
     */
    private fun geometry(
        availableWidth: Int = 360,
        spacing: Int = 4,
        targetRowHeight: Int = 151,
        maxItemsPerRow: Int = FreeGridSizeCalculator.MAX_ITEMS_PORTRAIT,
        textBlockHeight: Int = TEXT_BLOCK_HEIGHT,
        blockItemHeight: Int = FreeGridSizeCalculator.BLOCK_ITEM_HEIGHT_DP
    ) = FreeGridGeometry(
        availableWidth = availableWidth,
        spacing = spacing,
        targetRowHeight = targetRowHeight,
        maxItemsPerRow = maxItemsPerRow,
        textBlockHeight = textBlockHeight,
        blockItemHeight = blockItemHeight
    )

    /** 按 top 坐标把 item 分组成行（同 top 即同一行） */
    private fun rowsOf(calc: FreeGridSizeCalculator): List<List<Int>> {
        val grouped = LinkedHashMap<Int, MutableList<Int>>()
        for (i in 0 until calc.itemCount) {
            grouped.getOrPut(calc.getTop(i)) { mutableListOf() }.add(i)
        }
        return grouped.values.toList()
    }

    /** 图片区高度 = 整格高 - 文字块高 */
    private fun imageHeightOf(
        calc: FreeGridSizeCalculator,
        position: Int,
        textBlock: Int = TEXT_BLOCK_HEIGHT
    ) = calc.getHeight(position) - textBlock

    @Test
    fun fullRows_fillAvailableWidthExactly() {
        val ratios = floatArrayOf(1.5f, 0.75f, 1.0f, 1.78f, 0.8f, 1.33f, 1.2f, 0.9f)
        val calc = FreeGridSizeCalculator().apply { build(geometry(), ratios) }
        val rows = rowsOf(calc)
        assertTrue("应产生多行", rows.size >= 2)

        // 除最后一行外，完整行必须精确撑满，且间距严格等于 spacing
        for (row in rows.dropLast(1)) {
            assertEquals("行左边界必须为 0", 0, calc.getLeft(row.first()))
            assertEquals(
                "行右边界必须精确等于 availableWidth",
                360, calc.getRight(row.last())
            )
            for (k in 0 until row.size - 1) {
                assertEquals(
                    "相邻图片间距必须等于 spacing",
                    4, calc.getLeft(row[k + 1]) - calc.getRight(row[k])
                )
            }
        }
    }

    @Test
    fun sameRow_allItemsShareSameImageHeight() {
        val ratios = floatArrayOf(1.5f, 0.75f, 1.0f, 1.78f, 0.8f, 1.33f)
        val calc = FreeGridSizeCalculator().apply { build(geometry(), ratios) }
        for (row in rowsOf(calc)) {
            val heights = row.map { imageHeightOf(calc, it) }.distinct()
            assertEquals("同一行内图片区高度必须唯一", 1, heights.size)
            assertTrue("图片区高度必须为正", heights.first() > 0)
        }
    }

    @Test
    fun widthIsProportionalToRatio() {
        // 两图同行时，宽度比应等于 ratio 比（容差 1px 取整误差）
        val ratios = floatArrayOf(1.5f, 1.0f)
        val calc = FreeGridSizeCalculator().apply { build(geometry(), ratios) }
        val row = rowsOf(calc).first()
        if (row.size >= 2) {
            val w0 = calc.getWidth(row[0])
            val w1 = calc.getWidth(row[1])
            val expected = 1.5f
            val actual = w0.toFloat() / w1.toFloat()
            assertTrue(
                "宽度比应近似等于 ratio 比，实际 $actual",
                Math.abs(actual - expected) < 0.05f
            )
        }
    }

    @Test
    fun incrementalRecalc_matchesFullBuildByteForByte() {
        val ratios = floatArrayOf(
            1.5f, 0.75f, 1.0f, 1.78f, 0.8f, 1.33f, 1.2f, 0.9f, 1.6f, 0.7f, 1.1f, 2.0f, 0.65f
        )
        val g = geometry()
        val full = FreeGridSizeCalculator().apply { build(g, ratios) }

        // 对每个 position 都做一次增量重算，结果必须与全量完全一致
        for (position in ratios.indices) {
            val incremental = FreeGridSizeCalculator().apply { build(g, ratios) }
            val startPosition = incremental.recalcFrom(position, ratios)
            assertEquals(
                "position=$position 的增量重算结果与全量不一致（起始 position=$startPosition）",
                dumpOf(full), dumpOf(incremental)
            )
        }
    }

    @Test
    fun blockItem_takesWholeRowAlone() {
        // 索引 3 与 7 为非图片项（footer 型 viewType）
        val ratios = floatArrayOf(1.5f, 1.0f, 0.8f, 0f, 1.2f, 0.9f, 1.33f, 0f)
        val viewTypes = IntArray(ratios.size) { 0 }
        viewTypes[3] = FreeGridSizeCalculator.DEFAULT_BLOCK_ITEM_THRESHOLD
        viewTypes[7] = FreeGridSizeCalculator.DEFAULT_BLOCK_ITEM_THRESHOLD
        val calc = FreeGridSizeCalculator().apply { build(geometry(), ratios, viewTypes) }

        for (block in intArrayOf(3, 7)) {
            assertEquals("block 项左边界", 0, calc.getLeft(block))
            assertEquals("block 项右边界必须撑满", 360, calc.getRight(block))
            assertEquals("block 项高度必须等于 blockItemHeight", 48, calc.getHeight(block))
            // 独占行：没有其他 item 与之同 top
            val sameTop = (0 until calc.itemCount).filter { calc.getTop(it) == calc.getTop(block) }
            assertEquals("block 项必须独占整行，实际同 top 项=$sameTop", 1, sameTop.size)
        }
        // 图片行不得跨越 block 项
        for (row in rowsOf(calc)) {
            val hasBlock = row.any { it == 3 || it == 7 }
            if (!hasBlock) {
                assertTrue("图片行不应包含 block 项", row.none { it == 3 || it == 7 })
            }
        }
    }

    @Test
    fun lastRow_whenUnderfilled_isLeftAlignedWithoutStretching() {
        // 前两张把第 1 行塞满，最后剩 1 张必然装不满
        val ratios = floatArrayOf(1.5f, 1.5f, 1.0f)
        val calc = FreeGridSizeCalculator().apply { build(geometry(), ratios) }
        val rows = rowsOf(calc)
        val lastRow = rows.last()
        assertEquals("最后一行应只剩 1 张", 1, lastRow.size)

        val last = lastRow.first()
        val imgH = imageHeightOf(calc, last)
        assertTrue("末行图片区高不得超过目标行高", imgH <= 151)

        // 关键：宽度必须仍按原比例（≈ imgH × ratio），而不是被拉伸到 360
        val expectedWidth = Math.round(imgH * 1.0f)
        assertEquals(
            "末行图片不得被拉伸，宽度应保持原比例", expectedWidth, calc.getWidth(last)
        )
        assertTrue("末行必须留有右侧空白", calc.getRight(last) < 360)
        assertEquals("末行必须左对齐", 0, calc.getLeft(last))
    }

    @Test
    fun textBlockHeight_addsToRowHeightButNotToWidthDistribution() {
        val ratios = floatArrayOf(1.5f, 1.0f, 0.8f, 1.2f, 1.33f, 1.78f)
        val withText = FreeGridSizeCalculator().apply {
            build(geometry(textBlockHeight = 46), ratios)
        }
        val withoutText = FreeGridSizeCalculator().apply {
            build(geometry(textBlockHeight = 0), ratios)
        }

        // 宽度分配与文字块无关
        for (i in ratios.indices) {
            assertEquals(
                "文字块高度不得影响第 $i 项的宽度",
                withoutText.getWidth(i), withText.getWidth(i)
            )
        }
        // 行高恰好相差文字块高度（逐行校验）
        val rowsWith = rowsOf(withText)
        rowsWith.dropLast(1).forEach { row ->
            row.forEach { i ->
                assertEquals(
                    "完整行第 $i 项的行高应恰好增加 46px",
                    46, withText.getHeight(i) - withoutText.getHeight(i)
                )
            }
        }
    }

    @Test
    fun ratioClamping_appliedToExtremeValues() {
        // ratio 10.0 应被钳制到 3.0；ratio 0.1 应被钳制到 0.4
        val clampHigh = FreeGridSizeCalculator().apply { build(geometry(), floatArrayOf(10f)) }
        val clampHighRef = FreeGridSizeCalculator().apply { build(geometry(), floatArrayOf(3f)) }
        assertEquals(
            "超大 ratio 必须钳制到 MAX_RATIO",
            clampHighRef.getWidth(0), clampHigh.getWidth(0)
        )

        val clampLow = FreeGridSizeCalculator().apply { build(geometry(), floatArrayOf(0.1f)) }
        val clampLowRef = FreeGridSizeCalculator().apply { build(geometry(), floatArrayOf(0.4f)) }
        assertEquals(
            "超小 ratio 必须钳制到 MIN_RATIO",
            clampLowRef.getWidth(0), clampLow.getWidth(0)
        )
    }

    @Test
    fun allZeroRatios_fallsBackToDefaultWithoutHanging() {
        val ratios = FloatArray(50) { 0f }
        val calc = FreeGridSizeCalculator().apply { build(geometry(), ratios) }
        assertEquals(50, calc.itemCount)
        assertTrue("必须产出有效矩形表", calc.contentHeight > 0)
        for (i in ratios.indices) {
            assertTrue("第 $i 项宽度必须为正", calc.getWidth(i) > 0)
            assertTrue("第 $i 项高度必须为正", calc.getHeight(i) > 0)
        }
    }

    @Test
    fun hugeInput_completesAndProducesFullTable() {
        // 10000 项：验证不 hang、不越界；矩形表长度为 4 * itemCount
        val size = 10000
        val ratios = FloatArray(size) { 0.5f + (it % 7) * 0.25f }
        val calc = FreeGridSizeCalculator().apply { build(geometry(), ratios) }
        assertEquals(size, calc.itemCount)
        assertTrue("内容总高必须为正", calc.contentHeight > 0)
        // 最后一项的 bottom 必须等于 contentHeight
        assertEquals(calc.contentHeight, calc.getBottom(size - 1))
        // 所有项的 bottom 单调不减
        var previousBottom = -1
        for (i in 0 until size) {
            val bottom = calc.getBottom(i)
            assertTrue("bottom 必须单调不减（第 $i 项）", bottom >= previousBottom)
            previousBottom = bottom
        }
    }

    @Test
    fun maxItemsPerRow_isRespected() {
        // 极窄长图 ratio=MIN_RATIO 时若无上限会挤很多张
        val ratios = FloatArray(20) { FreeGridSizeCalculator.MIN_RATIO }
        val calc = FreeGridSizeCalculator().apply { build(geometry(), ratios) }
        for (row in rowsOf(calc)) {
            assertTrue(
                "单行张数不得超过 maxItemsPerRow，实际 ${row.size}",
                row.size <= FreeGridSizeCalculator.MAX_ITEMS_PORTRAIT
            )
        }
    }

    @Test
    fun emptyInput_isSafe() {
        val calc = FreeGridSizeCalculator().apply { build(geometry(), FloatArray(0)) }
        assertEquals(0, calc.itemCount)
        assertEquals(0, calc.rows)
        assertEquals(0, calc.contentHeight)
    }

    /**
     * 锁定典型源的行组成（防算法无意变更）。
     *
     * 数据来源：`docs/specs/rss-free-layout/design.md` 常量表中 `TARGET_ROW_HEIGHT_RATIO` 的实测校准值。
     * 若本用例失败，说明分行规则被改动，需同步更新设计文档中的实测数据表。
     */
    @Test
    fun typicalSources_rowCompositionMatchesDesignDoc() {
        val cases = listOf(
            // 源图片比例 -> 期望每行张数 / 期望图片区行高
            Triple("4:3 横图", List(8) { 1.33f }, Pair(2, 134)),
            Triple("16:9 宽图", List(8) { 1.78f }, Pair(2, 100)),
            Triple("1:1 方图", List(8) { 1.0f }, Pair(2, 178)),
            Triple("3:4 竖图", List(8) { 0.75f }, Pair(3, 156))
        )
        for ((label, ratios, expected) in cases) {
            val calc = FreeGridSizeCalculator().apply { build(geometry(), ratios.toFloatArray()) }
            val rows = rowsOf(calc)
            // 除末行外（可能被收敛到目标行高），所有行组成应一致
            val fullRows = rows.dropLast(1)
            assertTrue("$label 应产生完整行", fullRows.isNotEmpty())
            for (row in fullRows) {
                assertEquals("$label 每行张数", expected.first, row.size)
                assertEquals("$label 图片区行高", expected.second, imageHeightOf(calc, row.first()))
            }
        }
    }

    /** 把整张矩形表序列化为字符串，用于「增量 == 全量」的逐字节比较 */
    private fun dumpOf(calc: FreeGridSizeCalculator): String {
        val sb = StringBuilder()
        for (i in 0 until calc.itemCount) {
            sb.append(calc.getLeft(i)).append(',')
                .append(calc.getTop(i)).append(',')
                .append(calc.getRight(i)).append(',')
                .append(calc.getBottom(i)).append(';')
        }
        return sb.toString()
    }

    companion object {
        /** 对应 dimens.xml 的 rss_free_text_block_height（46sp，密度 1 时即 46px） */
        private const val TEXT_BLOCK_HEIGHT = 46
    }
}
