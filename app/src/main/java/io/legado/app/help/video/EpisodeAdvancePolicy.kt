package io.legado.app.help.video

/**
 * 续播目标（design §1.4 决策矩阵）
 */
sealed class AdvanceTarget {
    /** 本作品下一集 */
    data class NextEpisode(val nextIndex: Int) : AdvanceTarget()

    /** 订阅源下一篇 */
    data class NextArticle(val nextIndex: Int) : AdvanceTarget()

    /** 需要用户确认后切换下一线路（默认不自动跨线路，AD-03） */
    data class AskNextRoute(val nextRouteIndex: Int) : AdvanceTarget()

    /** 停止并提示 */
    data class Stop(val reason: String) : AdvanceTarget()
}

/**
 * 续播决策输入（纯数据，无 Android 依赖，可 JVM 直测）
 */
data class AdvanceSnapshot(
    val mode: PlaybackMode,
    /** 书源链路（章节/卷/线路） */
    val isBook: Boolean,
    /** 订阅源文章模式（非集数模式） */
    val isArticleMode: Boolean,
    /** 当前索引（书源为 chapterInVolumeIndex，订阅源为 rssEpisodeIndex / rssArticleIndex） */
    val currentIndex: Int,
    /** 当前作品总集数/总篇数 */
    val total: Int,
    /** 自动续播开关（默认 true） */
    val autoAdvance: Boolean = true,
    /** 本轮已连播集数（AD-15） */
    val playedInSession: Int = 0,
    /** 连播上限，0=不限（AD-15） */
    val sessionLimit: Int = 0,
    /** 当前线路是否已到末集 */
    val routeAtEnd: Boolean = false,
    /** 是否存在下一线路 */
    val hasNextRoute: Boolean = false,
    /** 单 URL（无多集） */
    val isSingleUrl: Boolean = false,
    /**
     * 本集播完即停（预留策略，默认 false）。
     *
     * §11.2：仅预留开关位与决策分支，不接 UI、不改变默认行为；
     * 后续如提供「播完停止」偏好，只需把该字段接到 `VideoPlay` 偏好即可。
     */
    val stopAfterCurrent: Boolean = false
)

/**
 * 唯一续播决策（design §1.4；纯函数，可单测锁定）
 *
 * 规则优先级：单 URL > 开关 > 连播上限 > 模式 > 文章/书源/集数边界
 */
object EpisodeAdvancePolicy {

    const val REASON_SINGLE_URL = "单视频无下一集"
    const val REASON_AUTO_OFF = "自动续播已关闭"
    const val REASON_SESSION_LIMIT = "已达连播上限"
    const val REASON_SINGLE_MODE = "当前为单片模式"
    const val REASON_LAST_ARTICLE = "已到最后一篇"
    const val REASON_LAST_EPISODE = "已播放完"
    const val REASON_ROUTE_END = "本线路已播完，可切换下一线路"
    const val REASON_STOP_AFTER_CURRENT = "已开启「本集播完停止」"

    /**
     * 由播放上下文构建决策输入（纯函数，单测锁定）
     *
     * 单测覆盖：直链单片→SINGLE、书源线路边界（末线路/有下一线路）映射。
     * [AdvanceSnapshot.routeAtEnd] 语义是「本线路剧集已到边界」（`episodes` 为当前卷切片），
     * 因此存在卷划分即成立；是否存在下一线路由 `hasNextRoute` 单独表达。
     * §12.2 落地后入口会注入真实 `modeHint`，届时替换 [mode] 口径即可。
     */
    fun snapshotOf(
        isBook: Boolean,
        isArticleMode: Boolean,
        currentIndex: Int,
        total: Int,
        singleUrl: Boolean,
        volumesSize: Int,
        durVolumeIndex: Int
    ): AdvanceSnapshot = AdvanceSnapshot(
        mode = if (singleUrl) PlaybackMode.SINGLE else PlaybackMode.SERIES,
        isBook = isBook,
        isArticleMode = isArticleMode,
        currentIndex = currentIndex,
        total = total,
        routeAtEnd = isBook && volumesSize > 0,
        hasNextRoute = isBook && volumesSize > 0 && durVolumeIndex + 1 < volumesSize,
        isSingleUrl = singleUrl
    )

    fun decide(s: AdvanceSnapshot): AdvanceTarget {
        if (s.isSingleUrl) return AdvanceTarget.Stop(REASON_SINGLE_URL)
        if (!s.autoAdvance) return AdvanceTarget.Stop(REASON_AUTO_OFF)
        if (s.sessionLimit > 0 && s.playedInSession >= s.sessionLimit) {
            return AdvanceTarget.Stop(REASON_SESSION_LIMIT)
        }
        if (s.mode == PlaybackMode.SINGLE) return AdvanceTarget.Stop(REASON_SINGLE_MODE)
        // §11.2 预留：用户显式开启「本集播完停止」时不续播（默认 false，行为不变）
        if (s.stopAfterCurrent) return AdvanceTarget.Stop(REASON_STOP_AFTER_CURRENT)

        val next = s.currentIndex + 1
        if (s.isArticleMode) {
            return if (next in 0 until s.total) {
                AdvanceTarget.NextArticle(next)
            } else {
                AdvanceTarget.Stop(REASON_LAST_ARTICLE)
            }
        }

        if (next in 0 until s.total) {
            return AdvanceTarget.NextEpisode(next)
        }

        // 已到本作品末集：书源多线路给出确认提示，其余直接停止
        if (s.isBook && s.routeAtEnd && s.hasNextRoute) {
            return AdvanceTarget.Stop(REASON_ROUTE_END)
        }
        return AdvanceTarget.Stop(REASON_LAST_EPISODE)
    }
}
