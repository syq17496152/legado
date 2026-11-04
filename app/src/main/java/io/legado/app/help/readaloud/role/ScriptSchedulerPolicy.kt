package io.legado.app.help.readaloud.role

/**
 * 剧本调度策略（3.5，纯函数）：把「电量 / 温控 / 后台时长 / 朗读与滑动」翻译成调度决定。
 *
 * 为什么要纯函数：真机上半途暂停/取消是最容易写错的分支（电量、温控、退后台各一条），
 * 必须用单测把「什么情况下必须让位/必须释放」钉死，否则会白烧电或抢朗读 CPU。
 */
object ScriptSchedulerPolicy {

    enum class Decision {
        /** 正常跑：P0/P1/P2 顺序生成 */
        RUN,

        /** 朗读正在播且用户刚滑动页面：让位（可中断，稍后恢复） */
        YIELD,

        /** 暂停：电量/温控不足，等条件恢复 */
        PAUSE,

        /** 释放：后台过久，卸载模型与队列（回到阅读页再重建） */
        RELEASE
    }

    data class Facts(
        val batteryPercent: Int = 100,
        val charging: Boolean = false,
        /** 0 正常 → 3+ 视为熔断 */
        val thermalLevel: Int = 0,
        /** 退到后台的分钟数 */
        val backgroundMinutes: Int = 0,
        val readAloudPlaying: Boolean = false,
        /** 前台朗读中用户刚发生滑动/翻页（会抢帧与 CPU） */
        val userScrollingRecently: Boolean = false
    )

    /** 后台超过该分钟数就释放（design AD-05：>10min 卸载） */
    const val RELEASE_AFTER_BACKGROUND_MINUTES = 10

    /** 电量低于该值且未充电 ⇒ 暂停 */
    const val LOW_BATTERY_PERCENT = 30

    /** 温控达到该级别 ⇒ 暂停 */
    const val THERMAL_BREAK_LEVEL = 3

    fun decide(facts: Facts): Decision = when {
        facts.backgroundMinutes > RELEASE_AFTER_BACKGROUND_MINUTES -> Decision.RELEASE
        facts.batteryPercent < LOW_BATTERY_PERCENT && !facts.charging -> Decision.PAUSE
        facts.thermalLevel >= THERMAL_BREAK_LEVEL -> Decision.PAUSE
        facts.readAloudPlaying && facts.userScrollingRecently -> Decision.YIELD
        else -> Decision.RUN
    }

    /**
     * 预生成窗口（额外章数，不含当前章）：
     * - 快速连续翻章（3 章间隔 <2min）⇒ 只算当前 + 下一章（1）
     * - 停留 ≥5min 或常态 ⇒ 恢复 2 章
     */
    fun windowSize(consecutiveChapterIntervalSec: Long, dwellMinutes: Int): Int =
        if (consecutiveChapterIntervalSec in 1..119) 1 else if (dwellMinutes >= 5) 2 else 2
}
