package io.legado.app.service

/**
 * 朗读跟随状态机（R4）。
 *
 * 背景：此前「朗读位置是否跟随可视阅读位置」依赖 1500ms 时间窗启发式
 * （`markReadAloudUserNavigation` 写时间戳 + 消费点比对），超窗即视为用户操作、
 * 窗口内的真实意图被吞；且跨章返回无会话身份可校验 → 从页首重播。
 *
 * 本状态机把四件事收口：
 * 1. 是否跟随（[followReadAloudPosition]）
 * 2. 用户手动导航 → [detachForManualNavigation]
 * 3. 新朗读会话 → [restoreForNewSpeechSession]
 * 4. 章节读完后的下一章决策 → [nextChapterDecision]
 *
 * 与既有分支的关系（互斥矩阵，实施铁律）：
 * | 跟随状态 | 翻页模式 | 章节完成 | 归属行为 |
 * |---------|---------|---------|---------|
 * | 跟随中 | 滚动(`pageAnim()==3`) | 是 | **既有分支胜出**：`ReadAloud.pause`（原语义不变） |
 * | 跟随中 | 非滚动 | 是 | [nextChapterDecision] 决策 |
 * | 脱离 | 任意 | 是 | 仅朗读推进（同步写入点被本状态机拦截） |
 * | 脱离 | 任意 | 否 | 朗读按自身游标推进 |
 * | 服务已停(`isRun=false`) | 任意 | 任意 | 写入一律拦截 |
 *
 * 线程安全：所有读写均在 `@Synchronized` 下进行（阅读核心为全局单例，多线程共享）。
 */
object SpeechFollowState {

    /** 章节读完后的下一章决策 */
    enum class NextChapterDecision {
        /** 继续朗读，并同步可视阅读位置 */
        CONTINUE_WITH_VISIBLE_SYNC,

        /** 仅推进朗读，不拉回可视页 */
        CONTINUE_SPEECH_ONLY,

        /** 停止朗读 */
        STOP
    }

    @Volatile
    var followReadAloudPosition: Boolean = true
        private set

    /** 用户手动导航（非朗读来源）→ 脱离跟随 */
    @Synchronized
    fun detachForManualNavigation() {
        followReadAloudPosition = false
    }

    /** 新朗读会话启动 → 恢复跟随 */
    @Synchronized
    fun restoreForNewSpeechSession() {
        followReadAloudPosition = true
    }

    /** 复位（停止朗读 / 切换书籍）：等价于「等待下一会话」，回到跟随态 */
    @Synchronized
    fun reset() {
        followReadAloudPosition = true
    }

    /**
     * 是否允许把朗读进度写回可视阅读位置（消费点门控）。
     *
     * @param isSpeechPlaying 朗读服务是否处于播放中（`BaseReadAloudService.isPlay()`）
     * @return 服务未播放 → 恒定 false（禁止「已停止仍写可视页」）；否则取决于是否处于跟随态
     */
    @Synchronized
    fun shouldApplySpeechProgressToVisibleReader(isSpeechPlaying: Boolean): Boolean {
        if (!isSpeechPlaying) return false
        return followReadAloudPosition
    }

    /**
     * 章节读完后的下一章决策。
     *
     * @param hasNextSpeechChapter 是否还有下一章可朗读
     * @param visibleSyncMoved     可视阅读位置是否已随朗读同步到位
     */
    @Synchronized
    fun nextChapterDecision(
        hasNextSpeechChapter: Boolean,
        visibleSyncMoved: Boolean
    ): NextChapterDecision {
        if (!hasNextSpeechChapter) return NextChapterDecision.STOP
        return if (followReadAloudPosition && visibleSyncMoved) {
            NextChapterDecision.CONTINUE_WITH_VISIBLE_SYNC
        } else {
            NextChapterDecision.CONTINUE_SPEECH_ONLY
        }
    }
}