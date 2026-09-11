package io.legado.app.ui.rss.article.free

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

    /**
     * 整表统一的每行图片张数（v1.2 行数均衡方案）。
     *
     * 由当前比例表的自平均值反推（见 [computeItemsPerRow]）：横图为主 → 2 张/行、
     * 竖图封面为主 → 3-4 张/行。比例表更新（图片尺寸流式回填）时随 build/recalc 重算，
     * 整表行组成因此保持一致，消除旧行数跳变。
     */
    private var itemsPerRow = 2

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
        itemsPerRow = computeItemsPerRow()
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
        // 比例表已更新，每行张数随之重算（自均值漂移时后续行组成自动跟随）
        itemsPerRow = computeItemsPerRow()
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

            // ---- 每行张数统一切片（2026-09-11 v1.2 用户反馈重构）----
            // 旧贪心按「行高最接近目标」动态收行，横图行 2 张、窄竖图行 4 张交替跳变
            //（用户铁证："第一排只有两个，第二排就来四个"）。v1.1 收紧比例钳制强改行数
            // 被用户否决（裁图违背自由样式初衷）。现改为整表统一每行 [itemsPerRow] 张：
            // 行数稳定，行内宽度仍按原图比例分配、图片不裁剪。
            while (index < itemCount && count < itemsPerRow) {
                if (isBlockItem(index)) break
                sumRatio += sanitizeRatio(ratioArray[index])
                count++
                index++
            }

            // 兜底：正常路径不可达，防极端输入导致死循环
            if (count == 0) {
                index++
                continue
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

    /**
     * 由比例表自均值反推整表统一的每行张数。
     *
     * 推导：行高 = `usable / (k × avgRatio)`，令行高 ≈ 目标行高，得
     * `k = availableWidth / (targetRowHeight × avgRatio)`（忽略间距的近似，误差 ≤ 半张）。
     * 实测（360px / 目标行高 151px）：avg 1.78（16:9 横图）→ 2 张/行、avg 1.0（方图）→ 2、
     * avg 0.75（3:4 竖图）→ 3、avg 0.67（2:3 封面）→ 4。
     */
    private fun computeItemsPerRow(): Int {
        var sum = 0f
        var n = 0
        for (i in ratioArray.indices) {
            if (isBlockItem(i)) continue
            sum += sanitizeRatio(ratioArray[i])
            n++
        }
        if (n == 0 || sum <= 0f) return 2
        val avg = sum / n
        val k = geometry.availableWidth.toFloat() /
            (geometry.targetRowHeight * avg).coerceAtLeast(1f)
        return kotlin.math.round(k).toInt().coerceIn(2, geometry.maxItemsPerRow)
    }

    companion object {
        // ---- 比例钳制区间（AD-09 策略 X：钳制 + CENTER_CROP 裁边，仅拦极端值）----
        /**
         * 最小宽高比 0.4 ≈ 1:2.5 长图；低于此值按 0.4 裁掉上下多余部分。
         * 2026-09-11 v1.1 曾收紧到 0.9 以治行数跳变，用户否决（裁图违背自由样式初衷），
         * v1.2 恢复 0.4——行数跳变改由「每行张数统一」（见 [itemsPerRow]）解决，不再牺牲裁图。
         */
        const val MIN_RATIO = 0.4f

        /** 最大宽高比 3.0 ≈ 3:1 全景；高于此值按 3.0 裁掉左右多余部分。若实测裁切过激可放宽至 4.0 */
        const val MAX_RATIO = 3.0f

        /** 全局兜底比例 4:3；用于 ratio 未知（首次加载）或解码失败时估算布局 */
        const val DEFAULT_RATIO = 1.33f

        // ---- 分行阈值 ----
        /**
         * 目标图片区行高占屏幕宽的比例。
         *
         * 取值 0.42 经算法实测（360px 宽 / 间距 4px / 文字块 46px）。
         * v1.2 起行组成由「整表统一每行张数」决定（张数 = 该值与比例自均值反推），
         * 同类源整表行数一致；下表为对应源类型的实测行高：
         *
         * | 源图片比例 | 每行张数 | 图片区行高 |
         * |-----------|---------|-----------|
         * | 4:3 全横图 | 2 | 134px |
         * | 16:9 全宽图 | 2 | 100px |
         * | 1:1 全方图 | 2 | 178px |
         * | 3:4 全竖图 | 3 | 156px |
         *
         * 不取更小的 0.33 是为避免行高过矮 —— 实测 0.33 时混合行高仅约 98px，缩略图辨识度明显下降。
         * v1.2 起该值仅用于反推「每行张数」（见 [computeItemsPerRow]），不再直接做收行阈值。
         */
        const val TARGET_ROW_HEIGHT_RATIO = 0.42f

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
