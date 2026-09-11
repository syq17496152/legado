package io.legado.app.ui.rss.article.free

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 「自由」布局几何参数（一次布局所需的所有外部输入）。
 *
 * 全部为**像素**值（dp→px 换算由 LayoutManager 在构造时完成，本类不感知 dp）。
 *
 * @param availableWidth RecyclerView 可用宽度 = `width - paddingLeft - paddingRight`（**不含** item 间距）
 * @param spacing 行内相邻图片间距，同时用作行与行之间的垂直间距
 * @param targetRowHeight 目标**图片区**高度（不含文字块），= `TARGET_ROW_HEIGHT_RATIO × 屏幕宽`
 * @param maxItemsPerRow 单行图片张数上限（竖屏 4 / 横屏 6）
 * @param textBlockHeight 图下文字块固定高；必须为常量，否则「行高统一」性质失效。
 *        权威数值定义在 `res/values/dimens.xml` 的 `rss_free_text_block_height`（46sp），
 *        由 LayoutManager 读取后传入 —— 单位用 sp 以随系统字号缩放，XML 与算法共用同一资源，避免两处数值漂移
 * @param blockItemHeight 非图片项（header/footer）独占行时的高度
 * @param blockItemThreshold 非图片项的 viewType 判定阈值（>= 该值视为 footer 型）
 */
data class FreeGridGeometry(
    val availableWidth: Int,
    val spacing: Int,
    val targetRowHeight: Int,
    val maxItemsPerRow: Int,
    val textBlockHeight: Int,
    val blockItemHeight: Int,
    val blockItemThreshold: Int = FreeGridSizeCalculator.DEFAULT_BLOCK_ITEM_THRESHOLD
)

/**
 * 「自由」布局（等行高流式自由网格 / Justified Photo Grid）的矩形预计算器。
 *
 * 设计依据：`docs/specs/rss-free-layout/design.md`（算法一「分行 + 行内宽度分配」）。
 *
 * 架构思路参照 500px/greedo-layout-for-android（MIT）的「预计算 Rect 表 + 薄 LayoutManager」，
 * 用 Kotlin 重写并扩展本项目所需能力：footer 独占整行、文字块常量高、增量重算、脏标记区间重排。
 *
 * **本类为纯计算层**：不依赖任何 Android API，可直接 JVM 单测（见 FreeGridSizeCalculatorTest）。
 *
 * 核心不变式（由单测锁定）：
 * 1. 同一行内所有 item 的**图片区高度**相等，且 = `(availableWidth - (k-1)*spacing) / sum(ratio)`
 * 2. 完整行内 `Σ width + (k-1)*spacing == availableWidth` **严格成立**（末位 item 吸收舍入误差）；
 *    **例外**：装不满的最后一行（自然行高 > 目标行高）收敛到目标行高并左对齐留白，
 *    此时不做末位补偿，以保证各图仍按原比例显示、不被横向拉伸
 * 3. 非图片项独占整行，不参与图片分行
 * 4. `recalcFrom(p)` 的结果与全量 `build()` 逐字节一致
 */
class FreeGridSizeCalculator {

    /** 扁平矩形表：每 item 占 4 个 int —— `[left, top, right, bottom]`，长度 = `4 * itemCount` */
    private var rects: IntArray = IntArray(0)

    /** 每行起始 position（仅图片行与独占行各记一项），用于 [recalcFrom] 的二分定位 */
    private var rowStarts: IntArray = IntArray(0)

    private var rowCount = 0
    private var ratioArray: FloatArray = FloatArray(0)
    private var viewTypeArray: IntArray? = null
    private var geometry: FreeGridGeometry = FreeGridGeometry(0, 0, 1, 1, 0, 0)

    /** 当前 item 总数（含 footer 等非图片项） */
    val itemCount: Int get() = ratioArray.size

    /** 行数（图片行 + 独占行） */
    val rows: Int get() = rowCount

    /** 内容总高（最后一行 bottom，不含尾随 spacing） */
    val contentHeight: Int
        get() = if (itemCount == 0) 0 else rects[itemCount * 4 - 1]

    fun getLeft(position: Int): Int = rects[position * 4]

    fun getTop(position: Int): Int = rects[position * 4 + 1]

    fun getRight(position: Int): Int = rects[position * 4 + 2]

    fun getBottom(position: Int): Int = rects[position * 4 + 3]

    fun getWidth(position: Int): Int = getRight(position) - getLeft(position)

    fun getHeight(position: Int): Int = getBottom(position) - getTop(position)

    /**
     * 全量重算：从 position 0 起铺满整个列表。
     *
     * @param ratios 每 item 的宽高比（w/h）；非图片项的值会被忽略，传 0f 即可
     * @param viewTypes 每 item 的 viewType；传 null 表示全部为图片项
     */
    fun build(
        geometry: FreeGridGeometry,
        ratios: FloatArray,
        viewTypes: IntArray? = null
    ) {
        this.geometry = geometry
        this.ratioArray = ratios
        this.viewTypeArray = viewTypes
        this.rects = IntArray(ratios.size * 4)
        // 行数必然 <= itemCount（每行至少 1 项，独占行亦各占 1 行）
        this.rowStarts = IntArray(ratios.size)
        this.rowCount = 0
        layoutFrom(0, 0)
    }

    /**
     * 增量重算：从 [position] **所属行的起点**开始重算到末尾。
     *
     * 不能从 position 本身开始 —— 否则会打断该行已有的行内宽度分配。
     * 该行之前的内容保持不变（含 y 坐标）。
     *
     * @return 实际开始重算的 position（该行起点），调用方可据此决定重排范围
     */
    fun recalcFrom(
        position: Int,
        ratios: FloatArray,
        viewTypes: IntArray? = null
    ): Int {
        if (ratios.isEmpty() || rowCount == 0) return 0
        // 数据尺寸变化（增删）时无法增量，退化为全量
        if (ratios.size != itemCount) {
            build(geometry, ratios, viewTypes)
            return 0
        }
        this.ratioArray = ratios
        this.viewTypeArray = viewTypes
        val target = position.coerceIn(0, itemCount - 1)
        val rowIndex = findRowIndex(target)
        val startPosition = rowStarts[rowIndex]
        val startY = rects[startPosition * 4 + 1]
        // 从该行起覆盖写入：rowCount 回退到 rowIndex，layoutFrom 会从该槽位重新记录行起点
        rowCount = rowIndex
        layoutFrom(startPosition, startY)
        return startPosition
    }

    /**
     * 二分查找包含 [position] 的行下标。
     * `rowStarts` 严格递增，故取「最后一个 <= position 的行起点」即为所属行。
     */
    private fun findRowIndex(position: Int): Int {
        var low = 0
        var high = rowCount - 1
        var result = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (rowStarts[mid] <= position) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    /**
     * 从 [startPosition] 开始、以 [startY] 为起始纵坐标，逐行铺满到列表末尾。
     */
    private fun layoutFrom(startPosition: Int, startY: Int) {
        var index = startPosition
        var y = startY
        while (index < itemCount) {
            // ---- 非图片项（header/footer）：独占整行，不参与图片分行 ----
            if (isBlockItem(index)) {
                rowStarts[rowCount++] = index
                setRect(index, 0, y, geometry.availableWidth, y + geometry.blockItemHeight)
                y += geometry.blockItemHeight + geometry.spacing
                index++
                continue
            }

            val rowStart = index
            var sumRatio = 0f
            var count = 0

            // ---- 贪心装填：装到「再加一张会偏离目标行高更远」为止 ----
            while (index < itemCount) {
                if (isBlockItem(index)) break
                val ratio = sanitizeRatio(ratioArray[index])
                val heightIfStop = usableWidth(sumRatio, count)
                val heightIfTake = usableWidth(sumRatio + ratio, count + 1)
                // 已达目标行高：收行
                if (count > 0 && heightIfStop <= geometry.targetRowHeight) break
                // 再装一张会过矮：收行（避免一行挤入过多窄图）
                if (heightIfTake < geometry.targetRowHeight * SHRINK_TOLERANCE) break
                // 张数封顶
                if (count + 1 > geometry.maxItemsPerRow) break
                sumRatio += ratio
                count++
                index++
            }

            // 兜底：正常路径不可达，防极端输入导致死循环
            if (count == 0) {
                index++
                continue
            }

            // ---- 回退修正：若「少装一张」比「当前张数」更接近目标行高，则退回一张 ----
            if (count >= 2) {
                val lastRatio = sanitizeRatio(ratioArray[rowStart + count - 1])
                val heightWith = usableWidth(sumRatio, count)
                val heightWithout = usableWidth(sumRatio - lastRatio, count - 1)
                if (abs(heightWithout - geometry.targetRowHeight) <
                    abs(heightWith - geometry.targetRowHeight)
                ) {
                    sumRatio -= lastRatio
                    count--
                    index--
                }
            }

            // ---- 行高：图片区高度（+ 文字块）----
            // 最后一行若「装不满」（自然行高 > 目标行高），收敛到目标行高并**保持各图原比例左对齐**，
            // 右侧余量留白；此时**不得**做末位宽度补偿，否则最后一张图会被横向拉伸变形。
            val naturalHeight = usableWidth(sumRatio, count)
            val isLastRow = index >= itemCount
            val isPartialRow = isLastRow && naturalHeight > geometry.targetRowHeight
            val imageHeight = if (isPartialRow) geometry.targetRowHeight else naturalHeight
            rowStarts[rowCount++] = rowStart

            // ---- 行内宽度分配：只用 imageHeight，末位 item 吸收舍入误差 ----
            val rowEnd = rowStart + count
            var x = 0
            var position = rowStart
            while (position < rowEnd) {
                val width = if (!isPartialRow && position == rowEnd - 1) {
                    // 末位补偿：保证 Σ width + (k-1)*spacing 严格等于 availableWidth
                    (geometry.availableWidth - x).coerceAtLeast(1)
                } else {
                    (imageHeight * sanitizeRatio(ratioArray[position])).roundToInt()
                }
                setRect(
                    position, x, y, x + width,
                    y + imageHeight + geometry.textBlockHeight
                )
                x += width + geometry.spacing
                position++
            }
            y += imageHeight + geometry.textBlockHeight + geometry.spacing
        }
    }

    private fun setRect(position: Int, left: Int, top: Int, right: Int, bottom: Int) {
        val base = position * 4
        rects[base] = left
        rects[base + 1] = top
        rects[base + 2] = right
        rects[base + 3] = bottom
    }

    /**
     * 行内 k 张图时，能分给「图片总宽度」的像素数除以 ratio 之和，即图片区行高。
     *
     * @return `count <= 0`（首轮试探）或 `sumRatio <= 0` 时返回 [Int.MAX_VALUE] 作为「未装填」哨兵；
     *         可用宽度被间距吃光时返回 1（触发收行而非产出异常行高）
     */
    private fun usableWidth(sumRatio: Float, count: Int): Int {
        if (count <= 0 || sumRatio <= 0f) return Int.MAX_VALUE
        val usable = geometry.availableWidth - (count - 1) * geometry.spacing
        if (usable <= 0) return 1
        return (usable / sumRatio).roundToInt().coerceAtLeast(1)
    }

    /** ratio 归一化：非法值回落默认比例，越界值钳制到 [MIN_RATIO, MAX_RATIO]（AD-09 策略 X） */
    private fun sanitizeRatio(ratio: Float): Float {
        if (ratio.isNaN() || ratio <= 0f) return DEFAULT_RATIO
        return ratio.coerceIn(MIN_RATIO, MAX_RATIO)
    }

    /** 非图片项判定：header 型 viewType 为负，footer 型 viewType >= 阈值 */
    private fun isBlockItem(position: Int): Boolean {
        val types = viewTypeArray ?: return false
        if (position !in types.indices) return false
        val type = types[position]
        return type < 0 || type >= geometry.blockItemThreshold
    }

    companion object {
        // ---- 比例钳制区间（AD-09 策略 X：钳制 + CENTER_CROP 裁边）----
        /**
         * 行分配用最小宽高比 0.9。用户反馈铁证（2026-09-11）：旧下限 0.4 时窄竖图一行可挤 4 张，
         * 与宽横图的 2 张/行形成强烈跳变（"第一排 2 个第二排 4 个"），观感差。
         * 收紧到 0.9 后每行收敛到 2-3 张；1:1 以下图片裁掉少量上下边（CENTER_CROP）。
         */
        const val MIN_RATIO = 0.9f

        /**
         * 行分配用最大宽高比 2.2。旧上限 3.0 时全景图独占行高过矮；收紧后裁掉左右多余部分
         * （CENTER_CROP），行高更统一。若实测裁切过激可放宽至 2.5。
         */
        const val MAX_RATIO = 2.2f

        /** 全局兜底比例 4:3；用于 ratio 未知（首次加载）或解码失败时估算布局 */
        const val DEFAULT_RATIO = 1.33f

        // ---- 分行阈值 ----
        /**
         * 目标图片区行高占屏幕宽的比例。
         *
         * 取值 0.42 经算法实测（360px 宽 / 间距 4px / 文字块 46px / 比例钳制 [0.9, 2.2]）：
         * | 源图片比例 | 每行张数 | 图片区行高 |
         * |-----------|---------|-----------|
         * | 4:3 全横图 | 2 | 134px |
         * | 16:9 全宽图 | 2 | 100px |
         * | 1:1 全方图 | 2 | 178px |
         * | 3:4 竖图（钳到 0.9） | 3 | 130px |
         * | 混合比例 | 2（偶有 3） | 100–178px |
         *
         * 不取更小的 0.33 是为避免行高过矮 —— 实测 0.33 时混合行高仅约 98px，缩略图辨识度明显下降。
         */
        const val TARGET_ROW_HEIGHT_RATIO = 0.42f

        /** 再加一张后行高低于「目标 × 该系数」即收行，避免一行挤入过多窄图 */
        const val SHRINK_TOLERANCE = 0.55f

        /** 单行图片张数上限（竖屏）。4 张时单图约屏宽 22%，再小则缩略图无辨识度 */
        const val MAX_ITEMS_PORTRAIT = 4

        /** 单行图片张数上限（横屏） */
        const val MAX_ITEMS_LANDSCAPE = 6

        // ---- 尺寸常量（dp / sp，由 LayoutManager 换算为 px 后传入）----
        /**
         * 间距，与 articleStyle=4（三列）的 `setPadding(4,0,4,0)` 视觉口径一致。
         */

        const val SPACING_DP = 4

        /** 非图片项（footer）高度保守估计；LayoutManager 实测到真实高度后会触发一次重算 */
        const val BLOCK_ITEM_HEIGHT_DP = 48

        /**
         * 非图片项 viewType 阈值默认值，等于 `RecyclerAdapter.TYPE_FOOTER_VIEW`。
         * 此处以字面量重复定义是为了保持本类**无 Android 依赖**（RecyclerAdapter 是 Android 类），
         * LayoutManager 传入时应使用 `RecyclerAdapter.TYPE_FOOTER_VIEW` 保持一致。
         */
        const val DEFAULT_BLOCK_ITEM_THRESHOLD = Int.MAX_VALUE - 999
    }
}
