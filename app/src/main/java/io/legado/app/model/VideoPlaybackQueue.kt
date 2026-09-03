package io.legado.app.model

import io.legado.app.data.entities.RssEpisode

/**
 * 统一播放队列（video-playlist-continuity 组件化核心，AD-01/AD-06）
 *
 * 把"多集下滑下一集、末集/单集下滑下一个影片、上滑回退"的播放会话模型抽成源类型无关组件，
 * 订阅源（ruleRoutes/ruleEpisodes 多线路多集 / 普通文章模式）与书源（type=video 卷章）双接入。
 *
 * 模型：
 * - [QueueUnit]：一个影片单元 = 标题 + 统一集列表（RssEpisode）+ 状态机 + 源侧元数据 meta
 *   （meta 为源侧句柄：订阅源=RssArticle；书源=Book——组件不感知源类型，播放分派由 VideoPlay 按 meta 类型路由）
 * - 扁平位映射：ViewPager position ↔ (unitIdx, epIdx)；itemCount = Σ(READY 单元集数) + 占位
 * - 状态机：LOADING（追加中）→ READY（可播）/ FAILED（可重试）
 * - [generation] 统一异步守卫：替代多 token 叠加，任何追加/切换完成回调校验 generation 过期即丢弃
 *
 * 状态权威契约（AD-06）：
 * - 队列是跨影片回退/追加的持久权威（谁在队列里、各影片集列表）
 * - VideoPlay 全局字段（book/toc/volumes/episodes/rssRoutes/rssEpisodes/各索引）是"当前播放单元"的
 *   执行投影——单元切换由 VideoPlay.switchToUnitState 原子重建投影，本组件不直接写投影字段
 */
object VideoPlaybackQueue {

    enum class UnitState { LOADING, READY, FAILED }

    /**
     * 影片单元。episodes 统一用 RssEpisode 模型（与书源卷章范式对称，RssRoute 注释佐证）。
     * meta：订阅源=RssArticle（影片文章）；书源=Book（详情/目录句柄）。组件不感知。
     */
    data class QueueUnit(
        val title: String,
        var episodes: List<RssEpisode>,
        var state: UnitState,
        val meta: Any?
    )

    /** 源侧追加分派接口（VideoPlay 内实现，组件零感知源类型） */
    interface UnitProvider {
        fun hasNext(): Boolean
        fun hasPrev(): Boolean
        suspend fun appendNext(): QueueUnit?
        suspend fun appendPrev(): QueueUnit?
    }

    private val units = mutableListOf<QueueUnit>()

    /** 统一异步守卫计数器：追加/切换发起时 +1，回调校验 */
    @Volatile
    var generation: Int = 0
        private set

    fun nextGeneration(): Int = ++generation

    /** 每单元的集数缓存（含 LOADING 单元按 0 计），用于扁平位映射 */
    private val unitEpisodeCounts = mutableListOf<Int>()

    @Synchronized
    fun reset(first: QueueUnit) {
        units.clear()
        unitEpisodeCounts.clear()
        units.add(first)
        unitEpisodeCounts.add(if (first.state == UnitState.READY) first.episodes.size else 0)
        nextGeneration()
    }

    @Synchronized
    fun unitCount(): Int = units.size

    @Synchronized
    fun unitAt(unitIdx: Int): QueueUnit? = units.getOrNull(unitIdx)

    @Synchronized
    fun currentUnitIndexByMeta(meta: Any?): Int {
        if (meta == null) return -1
        return units.indexOfFirst { it.meta === meta || it.meta == meta }
    }

    /**
     * 扁平位 → (unitIdx, epIdx)。
     * LOADING/FAILED 单元占 0 位（占位页由 Adapter 层处理，不在此映射内）。
     */
    @Synchronized
    fun locate(position: Int): Pair<Int, Int>? {
        var remain = position
        for (i in units.indices) {
            val count = unitEpisodeCounts[i]
            if (remain < count) {
                return i to remain
            }
            remain -= count
        }
        return null
    }

    /** 指定单元的扁平起始位 */
    @Synchronized
    fun unitStart(unitIdx: Int): Int {
        var start = 0
        for (i in 0 until unitIdx.coerceAtMost(units.size)) {
            start += unitEpisodeCounts[i]
        }
        return start
    }

    /** 队列扁平总集数（不含占位） */
    @Synchronized
    fun flatSize(): Int = unitEpisodeCounts.sum()

    /** 尾部占位是否可追加（provider.hasNext 由 VideoPlay 结合列表上下文提供） */
    @Volatile
    var canAppendNext: Boolean = false

    @Volatile
    var canAppendPrev: Boolean = false

    /** 追加单元（next/prev），返回追加后的 unitIdx；失败返回 -1 */
    @Synchronized
    fun append(unit: QueueUnit, atEnd: Boolean): Int {
        if (atEnd) {
            units.add(unit)
            unitEpisodeCounts.add(if (unit.state == UnitState.READY) unit.episodes.size else 0)
            return units.size - 1
        }
        units.add(0, unit)
        unitEpisodeCounts.add(0, if (unit.state == UnitState.READY) unit.episodes.size else 0)
        return 0
    }

    /** 单元集列表就绪（LOADING → READY），更新扁平计数 */
    @Synchronized
    fun markReady(unitIdx: Int, episodes: List<RssEpisode>) {
        val unit = units.getOrNull(unitIdx) ?: return
        unit.episodes = episodes
        unit.state = UnitState.READY
        unitEpisodeCounts[unitIdx] = episodes.size
    }

    @Synchronized
    fun markFailed(unitIdx: Int) {
        units.getOrNull(unitIdx)?.state = UnitState.FAILED
    }

    /** 当前单元集列表刷新（换线路场景）：READY 单元的 episodes 原位替换 */
    @Synchronized
    fun replaceUnitEpisodes(unitIdx: Int, episodes: List<RssEpisode>) {
        val unit = units.getOrNull(unitIdx) ?: return
        unit.episodes = episodes
        unitEpisodeCounts[unitIdx] = episodes.size
    }

    /** 清理（VideoPlayerActivity onDestroy） */
    @Synchronized
    fun clear() {
        units.clear()
        unitEpisodeCounts.clear()
        canAppendNext = false
        canAppendPrev = false
        nextGeneration()
    }
}
