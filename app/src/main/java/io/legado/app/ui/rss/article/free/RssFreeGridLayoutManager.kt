package io.legado.app.ui.rss.article.free

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.help.config.LocalConfig
import io.legado.app.utils.dpToPx
import kotlin.math.abs

/**
 * 「自由」布局的 LayoutManager —— 等行高流式自由网格（Justified Photo Grid）。
 *
 * 设计依据：`docs/specs/rss-free-layout/design.md`。
 *
 * 架构：**薄适配层**。所有几何计算交给 [FreeGridSizeCalculator]（纯函数、可单测），
 * 本类只负责三件事：
 * 1. 按矩形表摆放/回收子 View；
 * 2. 维护滚动偏移与滚动位置存取；
 * 3. 尺寸回填后的**抗抖动重排**（脏标记 + 滚动补偿）。
 *
 * 与 `StaggeredGridLayoutManager` 的本质差异：
 * 瀑布流是「列宽固定、高度自由」（底边错落）；本类是「行高统一、宽度自由」（每行撑满）。
 *
 * @param context 用于读取屏幕方向（决定单行张数上限）与 dp/sp 资源
 * @param ratioProvider 按 **layout position** 取图片宽高比（w/h）；footer 等非图片项的返回值被忽略
 * @param itemCountProvider 取当前 item 总数（含 header/footer）
 * @param viewTypeProvider 按 position 取 viewType，用于识别 footer 等「非图片项」
 *
 * 说明：数据源通过**构造参数注入**而非从宿主 RecyclerView 反查 ——
 * androidx 的 `RecyclerView.LayoutManager` 并不暴露 `getAdapter()`，
 * 注入方式同时让本类与 Adapter 实现完全解耦，便于单测与复用。
 */
class RssFreeGridLayoutManager(
    private val context: Context,
    private val ratioProvider: (position: Int) -> Float,
    private val itemCountProvider: () -> Int,
    private val viewTypeProvider: (position: Int) -> Int
) : RecyclerView.LayoutManager() {

    private val calculator = FreeGridSizeCalculator()

    /** 行内/行间间距 */
    private val spacingPx: Int = FreeGridSizeCalculator.SPACING_DP.dpToPx()

    /**
     * 图下文字块高。
     *
     * 权威数值取自 `res/values/dimens.xml` 的 `rss_free_text_block_height`（46sp）——
     * XML 布局与算法共用同一资源，避免两处数值漂移；单位为 sp 使其随系统字号缩放，
     * 大字号下文字块同步变高，标题不会被裁切。同一配置下该值为常量，满足算法不变式。
     */
    private val textBlockHeightPx: Int =
        context.resources.getDimensionPixelSize(R.dimen.rss_free_text_block_height)

    /** footer 等非图片项的高度；首次布局实测到真实高度后会重算一次并收敛 */
    private var blockItemHeightPx: Int = FreeGridSizeCalculator.BLOCK_ITEM_HEIGHT_DP.dpToPx()

    /** 当前滚动偏移（内容坐标系，0 = 内容顶部与视口顶部对齐） */
    private var scrollOffset = 0

    /** 矩形表是否需要重算 */
    private var rectsValid = false

    /** 上一次构建矩形表时使用的可用宽度，用于检测宽度变化（旋转/分屏/折叠屏） */
    private var lastBuiltWidth = -1

    /** ratio 回填后待重排的起始 position；-1 表示无 */
    private var pendingDirtyFrom = -1

    /** 布局输入快照。用于判断 `onItemsUpdated` 是否真的改变了几何（已读态变更不应触发重排） */
    private var ratioCache: FloatArray = FloatArray(0)
    private var viewTypeCache: IntArray = IntArray(0)

    /** 本帧是否需要在布局结束后再请求一次布局（footer 实测高度变化） */
    private var needRelayout = false

    /**
     * 数据整体替换标记（v1.3，rss-free-layout-polish AD-11）。
     *
     * 与 [rectsValid] 分离：
     * - [rectsValid] 表达「矩形表是否可用」（几何脏）；
     * - 本标记表达「已挂载 child 的 position 可能过期，必须经 scrap 通道重解析」（数据脏）。
     *
     * `notifyDataSetChanged` 后 holder 的 position 会停留旧值（见 [onItemsChanged] 的崩溃铁证），
     * 因此只要发生数据整体替换就必须 scrap；但若几何输入（比例 / viewType / 数量）未变，
     * 则无需重算矩形表 —— 这是把「数据刷新」与「全表重算」解耦的关键。
     */
    private var dataSetChanged = false

    // ---------------------------------------------------------------- 诊断日志节流（compose-shell-binding-fix）

    /**
     * 全量日志开关的**实例级缓存**。
     * ⚠️ 不可在布局路径每帧读 [LocalConfig.rssFreeFullLog]——其底层 CacheManager
     * 为 runBlocking(IO)，主线程每帧调用必 ANR。onAttachedToWindow 读取一次。
     */
    private var fullLogEnabled = false

    /** 节流窗口起始时刻（elapsedRealtime，ms） */
    private var throttleWindowStart = 0L

    /** 当前窗口内被抑制的日志条数 */
    private var suppressedInWindow = 0

    /**
     * 常规逐帧诊断日志节流输出：2000ms 窗口内仅输出首条真实值，
     * 窗口到期后的下一次调用先补发上窗口合并摘要（仅条数）。
     * 关键事件（提前返回/回填/footer 校准）不走本函数，始终全量。
     *
     * v1.3（AD-13）：参数改为**惰性** `() -> String` —— 布局路径每帧都会调用本函数，
     * 若在调用点先拼接字符串再被节流丢弃，是纯浪费；抑制期间一律不求值。
     */
    private fun putThrottledLog(message: () -> String) {
        if (fullLogEnabled) {
            AppLog.put(message())
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - throttleWindowStart < THROTTLE_WINDOW_MS) {
            if (suppressedInWindow == 0) throttleWindowStart = now
            suppressedInWindow++
            return
        }
        if (suppressedInWindow > 0) {
            // 只回显条数：窗口到期时再求值旧 lambda 会输出陈旧数据，误导诊断
            AppLog.put("RssFree[节流] 上窗口合并 suppressed=$suppressedInWindow 条")
            suppressedInWindow = 0
        }
        throttleWindowStart = now
        AppLog.put(message())
    }

    override fun onAttachedToWindow(view: RecyclerView?) {
        super.onAttachedToWindow(view)
        // 开关只在此读取一次（实例级缓存），布局路径零阻塞
        fullLogEnabled = LocalConfig.rssFreeFullLog
    }

    // ---------------------------------------------------------------- 基础能力

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(
            RecyclerView.LayoutParams.WRAP_CONTENT,
            RecyclerView.LayoutParams.WRAP_CONTENT
        )

    override fun canScrollVertically(): Boolean = true

    /** 内容总高（含上下 padding），用于滚动范围与最大偏移计算 */
    private val totalContentHeight: Int
        get() = calculator.contentHeight + paddingTop + paddingBottom

    /** 视口高度（不含上下 padding） */
    private val viewportHeight: Int
        get() = (height - paddingTop - paddingBottom).coerceAtLeast(0)

    /**
     * 最大滚动偏移。
     *
     * 推导：内容顶部在屏幕 `paddingTop` 处，内容底部在屏幕 `paddingTop + contentHeight` 处。
     * 完全滚到底时要求「内容底部 + paddingBottom == 视口底部」，即
     * `paddingTop + contentHeight - offset + paddingBottom == height`，
     * 解得 `offset = totalContentHeight - height`。
     */
    private val maxScrollOffset: Int
        get() = (totalContentHeight - height).coerceAtLeast(0)

    // ---------------------------------------------------------------- 布局输入

    /**
     * 刷新输入快照。
     *
     * @return 布局输入（比例 / viewType）是否发生变化。
     *         已读态等 payload 更新只影响文字颜色，不改变几何，故不应触发重排。
     */
    private fun refreshInputs(): Boolean {
        val count = itemCountProvider()
        if (count != ratioCache.size) {
            ratioCache = FloatArray(count)
            viewTypeCache = IntArray(count)
            fillInputs(count)
            return true
        }
        if (count == 0) return false
        var changed = false
        for (i in 0 until count) {
            val ratio = ratioProvider(i)
            if (abs(ratio - ratioCache[i]) > RATIO_EPSILON) changed = true
            ratioCache[i] = ratio
            val viewType = viewTypeProvider(i)
            if (viewType != viewTypeCache[i]) changed = true
            viewTypeCache[i] = viewType
        }
        return changed
    }

    private fun fillInputs(count: Int) {
        for (i in 0 until count) {
            ratioCache[i] = ratioProvider(i)
            viewTypeCache[i] = viewTypeProvider(i)
        }
    }

    /** 当前可用宽度（不含左右 padding，也不含 item 间距） */
    private fun currentAvailableWidth(): Int =
        (width - paddingLeft - paddingRight).coerceAtLeast(0)

    /** 目标图片区行高 = 可用宽度 × [FreeGridSizeCalculator.TARGET_ROW_HEIGHT_RATIO] */
    private fun currentTargetRowHeight(): Int =
        (currentAvailableWidth() * FreeGridSizeCalculator.TARGET_ROW_HEIGHT_RATIO).toInt()
            .coerceAtLeast(1)

    /** 单行张数上限按当前屏幕方向取值（横屏更宽，可容纳更多张） */
    private fun currentMaxItemsPerRow(): Int {
        val landscape = context.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE
        return if (landscape) {
            FreeGridSizeCalculator.MAX_ITEMS_LANDSCAPE
        } else {
            FreeGridSizeCalculator.MAX_ITEMS_PORTRAIT
        }
    }

    private fun buildGeometry() = FreeGridGeometry(
        availableWidth = currentAvailableWidth(),
        spacing = spacingPx,
        targetRowHeight = currentTargetRowHeight(),
        maxItemsPerRow = currentMaxItemsPerRow(),
        textBlockHeight = textBlockHeightPx,
        blockItemHeight = blockItemHeightPx,
        blockItemThreshold = RecyclerAdapter.TYPE_FOOTER_VIEW
    )

    /** 全量重算矩形表。调用后 [rectsValid] 为 true */
    private fun rebuild() {
        val count = itemCountProvider()
        val availableWidth = currentAvailableWidth()
        if (count == 0) {
            rectsValid = false
            lastBuiltWidth = availableWidth
            return
        }
        // 可用宽度变化（旋转 / 分屏 / 折叠屏）：行组成需按新宽度重新定型（AD-10）。
        // lastBuiltWidth > 0 保证首次构建不触发解锁 —— 首次正是定型时机
        if (lastBuiltWidth > 0 && availableWidth != lastBuiltWidth) {
            calculator.unlockItemsPerRow()
        }
        if (count != ratioCache.size) {
            ratioCache = FloatArray(count)
            viewTypeCache = IntArray(count)
        }
        fillInputs(count)
        calculator.build(buildGeometry(), ratioCache, viewTypeCache)
        rectsValid = true
        lastBuiltWidth = availableWidth
        pendingDirtyFrom = -1
    }

    // ---------------------------------------------------------------- 数据变化回调

    override fun onItemsAdded(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        rectsValid = false
        dataSetChanged = true
    }

    override fun onItemsRemoved(recyclerView: RecyclerView, positionStart: Int, itemCount: Int) {
        rectsValid = false
        dataSetChanged = true
    }

    override fun onItemsMoved(recyclerView: RecyclerView, from: Int, to: Int, itemCount: Int) {
        rectsValid = false
        dataSetChanged = true
    }

    /**
     * 白屏根因修复（真机实锤 2026-09-11）：notifyDataSetChanged → processDataSetCompletelyChanged
     * 走批量回调 onItemsChanged（不触发 onItemsAdded/Updated），本类原先未监听 → 矩形表停留在
     * 首次 rebuild 的"仅 1 行 footer"且 rectsValid=true → 后续数据到达不再 rebuild → fill() 只在
     * 1 条矩形里补齐 → 0 个文章 View 被 attach = 白屏。前台 isResumed 时 diff 路径恒回退全量
     * notifyDataSetChanged（RecyclerAdapter.kt:170-174），故前台必现。
     *
     * v1.3（AD-11）：矩形表是否作废改由**几何输入是否真变化**决定 —— 前台全量刷新频率很高
     * （已读态变更、图片入库等都会触发），若无条件重算，千级列表每次都要 O(n) 重新取比例。
     * 但 [dataSetChanged] 必须置位：notifyDataSetChanged 后 holder 的 position 停留旧值，
     * 不经 scrap 通道会在差量贴位时错位（真机铁证 crash-19-54-47）。
     */
    override fun onItemsChanged(recyclerView: RecyclerView) {
        // 注意：不重置 scrollOffset——加载更多（notifyDataSetChanged）后必须保持滚动位置
        dataSetChanged = true
        if (refreshInputs()) rectsValid = false
    }

    override fun onItemsUpdated(
        recyclerView: RecyclerView,
        positionStart: Int,
        itemCount: Int,
        payload: Any?
    ) {
        // 已读态等 payload 更新不改变几何，只有比例/viewType 真变了才作废矩形表；
        // payload 更新不改变 position 语义，故无需 dataSetChanged
        if (refreshInputs()) rectsValid = false
    }

    override fun onAdapterChanged(
        oldAdapter: RecyclerView.Adapter<*>?,
        newAdapter: RecyclerView.Adapter<*>?
    ) {
        rectsValid = false
        dataSetChanged = true
        ratioCache = FloatArray(0)
        viewTypeCache = IntArray(0)
        scrollOffset = 0
        lastBuiltWidth = -1
        pendingDirtyFrom = -1
        // 换源 / 换布局：行组成解锁，下次构建按新源比例表重新定型（AD-10）
        calculator.unlockItemsPerRow()
    }

    /** 供外部在切换布局/数据整体替换后强制重算 */
    fun invalidateRects() {
        rectsValid = false
    }

    /**
     * 行组成解锁（v1.3，AD-10）：供宿主在需要「重新定型每行张数」时调用。
     *
     * 换源 / 换 Adapter 与可用宽度变化已由本类内部自动解锁，宿主只需处理「用户全量刷新」。
     */
    fun unlockItemsPerRow() {
        calculator.unlockItemsPerRow()
    }

    // ---------------------------------------------------------------- 布局

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0) {
            removeAndRecycleAllViews(recycler)
            rectsValid = false
            // 空列表同样要消费数据脏标记：残留会让下一次布局多走一次无谓的全量 scrap
            dataSetChanged = false
            scrollOffset = 0
            pendingDirtyFrom = -1
            return
        }
        val availableWidth = currentAvailableWidth()
        // 宽度变化（旋转 / 分屏 / 折叠屏）：记录锚点，重算后回到同一 position，避免视觉跳位
        val widthChanged = rectsValid &&
            calculator.itemCount > 0 &&
            availableWidth != lastBuiltWidth
        val anchor = if (widthChanged) firstAttachedPosition() else NO_POSITION
        // 几何脏（矩形表需重算）与数据脏（child 位置需重解析）分离（v1.3，AD-11）。
        // 数据整体替换后即便几何未变，也必须走 scrap 通道：notifyDataSetChanged 后已挂载
        // holder 的 mPosition 停留旧值，直接差量贴位会错位
        //（真机铁证 crash-19-54-47：footer 停在旧位 → findViewByPosition(新位) 落空 →
        // createViewHolder 重建仍挂 parent 的单实例 footer → IllegalStateException）。
        // 走 scrap 通道后 RecyclerView 会重解析位置（invalid holder 重新 bind 到新 position），
        // 且 detach 后单实例 footer 的 parent == null，即使极端路径重建也安全。
        val geometryDirty = !rectsValid
        val needScrap = dataSetChanged
        if (geometryDirty || widthChanged) {
            rebuild()
            if (anchor != NO_POSITION) {
                scrollOffset = calculator.getTop(anchor).coerceAtLeast(0)
            }
        }
        if (needScrap) {
            if (childCount > 0) detachAndScrapAttachedViews(recycler)
            // 无论是否有 child 都必须消费该数据脏标记，避免污染后续帧
            dataSetChanged = false
        }
        scrollOffset = scrollOffset.coerceIn(0, maxScrollOffset)
        fill(recycler, state)
        // 调试：每次布局后汇报关键状态（节流输出，全量开关见 LocalConfig.rssFreeFullLog）
        putThrottledLog {
            "RssFree[布局] itemCount(state)=${state.itemCount} rectsValid=$rectsValid " +
                "calcItem=${calculator.itemCount} contentH=${calculator.contentHeight} " +
                "childCount=$childCount availW=${currentAvailableWidth()} targetH=${currentTargetRowHeight()}"
        }
        consumePendingDirtyIfSafe()
        requestRelayoutIfNeeded()
    }

    /**
     * 按矩形表摆放可见区间的子 View，并回收离开区间的。
     *
     * 采用「绝对定位 + 差量补齐」而非 `detachAndScrapAttachedViews` 全量重建：
     * 本布局每个 item 的位置只依赖矩形表，与相邻 child 无测量依赖链，
     * 因此可直接复用已有 child，只做位置重贴与增删，开销最低。
     */
    private fun fill(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        // 数据变化后可能先发生滚动再触发布局：此处兜底重算，避免用过期矩形表定位
        if (!rectsValid) rebuild()
        val limit = calculator.itemCount
        if (limit == 0 || state.itemCount == 0) {
            AppLog.put(
                "RssFree[填充] 提前返回 limit=$limit stateItem=${state.itemCount} rectsValid=$rectsValid"
            )
            return
        }
        val viewportTop = scrollOffset
        val viewportBottom = scrollOffset + viewportHeight
        // 上下各多铺一屏，避免滑动露白与 consume 不足导致的卡顿
        val buffer = viewportHeight

        putThrottledLog {
            "RssFree[填充] limit=$limit stateItem=${state.itemCount} " +
                "viewportTop=$viewportTop viewportBottom=$viewportBottom"
        }
        // 1) 回收越界 child；其余按最新矩形表重新贴位
        //    数据变化后的全量布局由 onLayoutChildren 先 detach+scrap（holder 位置在 scrap 通道
        //    重解析），本差量路径只在数据未变的滚动/重贴位时执行，child 位置可信。
        //    header/footer 单实例块（footer 为 ViewLoadMoreBinding.bind(loadMoreView) 单例，
        //    5 布局公共注册）永不回收仅重新贴位，避免 createViewHolder 复用仍挂 parent 的旧视图。
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i) ?: continue
            val position = getPosition(child)
            if (position == NO_POSITION || position >= limit) {
                removeAndRecycleViewAt(i, recycler)
                continue
            }
            val outside = calculator.getBottom(position) < viewportTop - buffer ||
                calculator.getTop(position) > viewportBottom + buffer
            if (outside && !isBlockItem(position)) {
                removeAndRecycleViewAt(i, recycler)
            } else {
                layoutChild(child, position)
            }
        }

        // 2) 补齐区间内缺失的 child
        val first = positionAtOrAfter(viewportTop - buffer)
        val last = positionAtOrBefore(viewportBottom + buffer)
        // 先收集已挂载 position（排序后二分）：替代逐 position 的 findViewByPosition 线性扫描，
        // 单帧最坏 O(childCount²) → O(c log c)（v1.3，AD-13）
        val attachedCount = collectAttachedPositions()
        putThrottledLog {
            "RssFree[区间] first=$first last=$last buffer=$buffer limit=$limit attached=$attachedCount"
        }
        if (first != NO_POSITION && last != NO_POSITION) {
            val end = last.coerceAtMost(limit - 1)
            for (position in first..end) {
                if (position >= limit) break
                if (hasAttachedPosition(position, attachedCount)) continue
                val view = recycler.getViewForPosition(position)
                addView(view)
                measureChildForPosition(view, position)
                layoutChild(view, position)
            }
        }
    }

    /**
     * 已挂载 child 的 position 缓存（v1.3，AD-13）。
     *
     * 补齐循环原先对每个 position 调 `findViewByPosition`（其内部线性扫描全部 child），
     * 单帧最坏 O(childCount²)。改为「每帧一次扫描 + 排序 + 二分查找」。
     * 数组按需扩容并跨帧**复用**，避免每帧分配。
     */
    private var attachedPositions = IntArray(0)

    /** 收集当前已挂载 child 的 position 到 [attachedPositions]（升序），返回有效个数 */
    private fun collectAttachedPositions(): Int {
        val count = childCount
        if (attachedPositions.size < count) {
            attachedPositions = IntArray(count)
        }
        var n = 0
        for (i in 0 until count) {
            val position = getChildAt(i)?.let { getPosition(it) } ?: NO_POSITION
            if (position != NO_POSITION) attachedPositions[n++] = position
        }
        // 复用数组的**前 n 个**才是有效数据，排序范围必须限定，避免读到上一帧残留
        java.util.Arrays.sort(attachedPositions, 0, n)
        return n
    }

    /** [position] 是否在 [collectAttachedPositions] 收集的集合内（二分查找） */
    private fun hasAttachedPosition(position: Int, size: Int): Boolean =
        java.util.Arrays.binarySearch(attachedPositions, 0, size, position) >= 0

    /**
     * 按矩形表精确指定宽高后测量。
     *
     * 图片项用 EXACTLY 尺寸，避免 `wrap_content` 的 ImageView 无尺寸可用；
     * 非图片项（footer）改用 `WRAP_CONTENT` 以测得**自然高度** ——
     * 否则实测值恒等于矩形高，永远无法校准预估偏差。
     */
    private fun measureChildForPosition(view: View, position: Int) {
        val lp = view.layoutParams
        if (lp is RecyclerView.LayoutParams) {
            lp.width = calculator.getWidth(position)
            lp.height = if (isBlockItem(position)) {
                RecyclerView.LayoutParams.WRAP_CONTENT
            } else {
                calculator.getHeight(position)
            }
            lp.leftMargin = 0
            lp.rightMargin = 0
            lp.topMargin = 0
            lp.bottomMargin = 0
        }
        measureChildWithMargins(view, 0, 0)
    }

    /**
     * 把 child 贴到矩形表指定的位置（坐标已叠加 RecyclerView padding 与滚动偏移）。
     *
     * 非图片项（footer）额外做实测高度校准：若实测高度与预估不符，更新
     * [blockItemHeightPx] 并在布局结束后申请重算；本帧先按实测高度摆放以免内容被裁切。
     * 校准只会在首次渲染时触发一次 —— 重算后矩形高即等于实测高，判定自然不再成立。
     */
    private fun layoutChild(child: View, position: Int) {
        val left = calculator.getLeft(position) + paddingLeft
        val top = calculator.getTop(position) - scrollOffset + paddingTop
        val right = calculator.getRight(position) + paddingLeft
        val bottom = calculator.getBottom(position) - scrollOffset + paddingTop
        if (isBlockItem(position)) {
            val measuredHeight = getDecoratedMeasuredHeight(child)
            if (measuredHeight > 0 && measuredHeight != calculator.getHeight(position)) {
                // 一次性校准事件，全量输出（compose-shell-binding-fix 新增诊断点）
                AppLog.put(
                    "RssFree[校准] 非图片项实测高 measured=$measuredHeight 预估=${calculator.getHeight(position)} position=$position"
                )
                blockItemHeightPx = measuredHeight
                rectsValid = false
                needRelayout = true
                layoutDecoratedWithMargins(child, left, top, right, top + measuredHeight)
                return
            }
        }
        layoutDecoratedWithMargins(child, left, top, right, bottom)
    }

    /** 在布局/滚动结束后申请一次新布局（footer 高度校准用），避免在布局中递归触发 */
    private fun requestRelayoutIfNeeded() {
        if (needRelayout) {
            needRelayout = false
            requestLayout()
        }
    }

    // ---------------------------------------------------------------- 滚动

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State
    ): Int {
        if (childCount == 0 || dy == 0) return 0
        val target = (scrollOffset + dy).coerceIn(0, maxScrollOffset)
        val consumed = target - scrollOffset
        if (consumed == 0) return 0
        scrollOffset = target
        fill(recycler, state)
        consumePendingDirtyIfSafe()
        requestRelayoutIfNeeded()
        return consumed
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int = scrollOffset

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int = viewportHeight

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int = totalContentHeight

    override fun scrollToPosition(position: Int) {
        if (calculator.itemCount == 0) return
        val target = position.coerceIn(0, calculator.itemCount - 1)
        scrollOffset = calculator.getTop(target).coerceIn(0, maxScrollOffset)
        requestLayout()
    }

    override fun smoothScrollToPosition(
        recyclerView: RecyclerView,
        state: RecyclerView.State,
        position: Int
    ) {
        // 未实现平滑滚动（需 LinearSmoothScroller 子类）。直接跳转，避免默认实现静默失败。
        scrollToPosition(position)
    }

    // ---------------------------------------------------------------- 可见性查询

    /** 最小已附加 position；无子 View 时返回 [NO_POSITION] */
    private fun firstAttachedPosition(): Int {
        var result = NO_POSITION
        for (i in 0 until childCount) {
            val position = getChildAt(i)?.let { getPosition(it) } ?: NO_POSITION
            if (position == NO_POSITION) continue
            if (result == NO_POSITION || position < result) result = position
        }
        return result
    }

    /** 最大已附加 position；无子 View 时返回 [NO_POSITION] */
    private fun lastAttachedPosition(): Int {
        var result = NO_POSITION
        for (i in 0 until childCount) {
            val position = getChildAt(i)?.let { getPosition(it) } ?: NO_POSITION
            if (position == NO_POSITION) continue
            if (result == NO_POSITION || position > result) result = position
        }
        return result
    }

    fun findFirstVisibleItemPosition(): Int = firstAttachedPosition()

    fun findLastVisibleItemPosition(): Int = lastAttachedPosition()

    /** 二分查找：第一个 bottom 大于 [y] 的 position（各 item bottom 单调不减，故可用二分） */
    private fun positionAtOrAfter(y: Int): Int {
        if (calculator.itemCount == 0) return NO_POSITION
        var low = 0
        var high = calculator.itemCount - 1
        var result = NO_POSITION
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (calculator.getBottom(mid) > y) {
                result = mid
                high = mid - 1
            } else {
                low = mid + 1
            }
        }
        return result
    }

    /** 二分查找：最后一个 top 小于 [y] 的 position */
    private fun positionAtOrBefore(y: Int): Int {
        if (calculator.itemCount == 0) return NO_POSITION
        var low = 0
        var high = calculator.itemCount - 1
        var result = NO_POSITION
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (calculator.getTop(mid) < y) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    private fun isBlockItem(position: Int): Boolean {
        val viewType = viewTypeProvider(position)
        return viewType < 0 || viewType >= RecyclerAdapter.TYPE_FOOTER_VIEW
    }

    // ---------------------------------------------------------------- ratio 回填与抗抖动重排

    /**
     * 尺寸供给层解析出新的真实比例后调用（**主线程**）。
     *
     * @param affectedFrom 自该 position 起比例发生变化
     */
    fun onRatiosUpdated(affectedFrom: Int) {
        AppLog.put("RssFree[回填] onRatiosUpdated affectedFrom=$affectedFrom calcItem=${calculator.itemCount} rectsValid=$rectsValid")
        if (affectedFrom < 0 || calculator.itemCount == 0) return
        if (!rectsValid) return // 下次布局会全量重算，无需脏标记
        val last = lastAttachedPosition()
        if (last == NO_POSITION || affectedFrom > last) {
            // 变化点位于视口下方：直接生效，用户完全无感
            applyRecalc(affectedFrom)
        } else {
            // 与视口相交：先挂起，等该区域滚出视口后再重算，避免可见内容跳动
            pendingDirtyFrom = if (pendingDirtyFrom < 0) {
                affectedFrom
            } else {
                minOf(pendingDirtyFrom, affectedFrom)
            }
        }
    }

    /**
     * 消化待重排脏区：仅当脏区**完全离开视口**时执行。
     *
     * 若脏区在视口上方（用户已向下滚过），重算会改变其下方所有行的纵坐标，
     * 因此必须同时补偿 [scrollOffset]，保证当前可见内容的屏幕位置不变。
     */
    private fun consumePendingDirtyIfSafe() {
        val dirty = pendingDirtyFrom
        if (dirty < 0 || !rectsValid) return
        val first = firstAttachedPosition()
        val last = lastAttachedPosition()
        if (first == NO_POSITION || last == NO_POSITION) {
            pendingDirtyFrom = -1
            return
        }
        if (dirty in first..last) return // 仍与视口相交，继续等待
        pendingDirtyFrom = -1
        applyRecalc(dirty)
    }

    /** 执行重排并补偿滚动偏移，使锚点项在屏幕上的位置保持不变 */
    private fun applyRecalc(fromPosition: Int) {
        // 脏区位置判定必须用**重算前**的矩形表：语义是「脏区此刻在屏幕上是否可见」。
        // 重算后 fromPosition 的 y 已改变，用新值判定会误判（v1.3，AD-12）
        val dirtyTopBefore = calculator.getTop(fromPosition)
        val anchor = firstAttachedPosition()
        val topBefore = if (anchor == NO_POSITION) 0 else calculator.getTop(anchor)
        fillInputs(calculator.itemCount)
        calculator.recalcFrom(fromPosition, ratioCache, viewTypeCache)
        val topAfter = if (anchor == NO_POSITION) 0 else calculator.getTop(anchor)
        if (topAfter != topBefore) {
            scrollOffset = (scrollOffset + (topAfter - topBefore)).coerceIn(0, maxScrollOffset)
        }
        // 脏区完全在视口下方：当前屏幕内容不受影响，无需即时布局（用户滚动时自然按新表定位）。
        // 只有脏区与可见区相交、或位于视口上方（已补偿 offset）时才请求布局
        if (dirtyTopBefore < scrollOffset + viewportHeight) {
            requestLayout()
        }
    }

    // ---------------------------------------------------------------- 状态存取

    override fun onSaveInstanceState(): Parcelable = Bundle().apply {
        putInt(KEY_SCROLL_OFFSET, scrollOffset)
        putInt(KEY_ANCHOR_POSITION, firstAttachedPosition())
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state !is Bundle) return
        scrollOffset = state.getInt(KEY_SCROLL_OFFSET, 0).coerceAtLeast(0)
    }

    // ---------------------------------------------------------------- 调试观察（真机验证用）

    /** 当前矩形表内容总高（不含 padding） */
    internal fun debugContentHeight(): Int = calculator.contentHeight

    /** 指定 position 的矩形 `[left, top, right, bottom]` */
    internal fun debugRect(position: Int): IntArray = intArrayOf(
        calculator.getLeft(position),
        calculator.getTop(position),
        calculator.getRight(position),
        calculator.getBottom(position)
    )

    /** 当前矩形表行数 */
    internal fun debugRows(): Int = calculator.rows

    companion object {
        private const val NO_POSITION = RecyclerView.NO_POSITION
        private const val KEY_SCROLL_OFFSET = "rss_free_scroll_offset"
        private const val KEY_ANCHOR_POSITION = "rss_free_anchor_position"

        /** 比例变化判定阈值：差异小于该值视为同一比例，不触发重排 */
        private const val RATIO_EPSILON = 1e-4f

        /** 常规诊断日志节流窗口（compose-shell-binding-fix） */
        private const val THROTTLE_WINDOW_MS = 2000L
    }
}
