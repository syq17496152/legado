package io.legado.app.help.readaloud.role

import io.legado.app.help.tts.builtin.BuiltinTtsPaths
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** 章级剧本状态（3.7）：未生成 / 生成中 / 已生成 / 降级 */
enum class ScriptPhase {
    NONE,
    BUILDING,
    READY,
    DEGRADED
}

data class ScriptStatus(
    val bookKey: String = "",
    val chapterIndex: Int = -1,
    val phase: ScriptPhase = ScriptPhase.NONE,
    val segments: Int = 0,
    val source: String = "",
    val message: String = "",
    val updatedAt: Long = 0
) {
    val degraded: Boolean get() = phase == ScriptPhase.DEGRADED
}

/**
 * 剧本状态总线（3.7）：UI 订阅 + 状态流转口径。
 *
 * 设计取舍：
 * - **不做轮询**：状态由生产者（剧本调度/仲裁）调用 [begin]/[done] 推送；UI 首屏用 [resolve] 从磁盘剧本兜底。
 * - **乱序安全**：[advance] 只认时间戳更新的事件，迟到的「生成中」不会把已完成的章节打回中间态。
 * - **纯函数可测**：状态推进与文案都不碰 Android，JVM 单测锁定。
 */
object ScriptStatusBus {

    private val statuses = ConcurrentHashMap<String, ScriptStatus>()
    private val listeners = CopyOnWriteArrayList<(ScriptStatus) -> Unit>()

    fun keyOf(bookKey: String, chapterIndex: Int): String = "$bookKey|$chapterIndex"

    /** 当前状态（未被推送过 ⇒ 未生成，不读磁盘） */
    fun statusOf(bookKey: String, chapterIndex: Int): ScriptStatus =
        statuses[keyOf(bookKey, chapterIndex)] ?: ScriptStatus(bookKey, chapterIndex)

    /** UI 首屏：有推送用推送；否则用磁盘剧本推导（已生成/降级/未生成） */
    fun resolve(bookKey: String, chapterIndex: Int): ScriptStatus {
        if (bookKey.isBlank() || chapterIndex < 0) return ScriptStatus(bookKey, chapterIndex)
        statuses[keyOf(bookKey, chapterIndex)]?.let { return it }
        val script = runCatching {
            ChapterScriptStore(BuiltinTtsPaths.of(appCtx).scriptsDir).read(bookKey, chapterIndex)
        }.getOrNull() ?: return ScriptStatus(bookKey, chapterIndex)
        return ofScript(script)
    }

    fun ofScript(script: ChapterScript): ScriptStatus = ScriptStatus(
        bookKey = script.bookKey,
        chapterIndex = script.chapterIndex,
        phase = if (script.degraded) ScriptPhase.DEGRADED else ScriptPhase.READY,
        segments = script.segments.size,
        source = script.source,
        message = if (script.degraded) script.degradeReason else "",
        updatedAt = script.generatedAt
    )

    /** 开始生成（重算时允许从 已生成/降级 回到 生成中） */
    fun begin(bookKey: String, chapterIndex: Int, now: Long = System.currentTimeMillis()): ScriptStatus =
        publish(
            ScriptStatus(
                bookKey = bookKey,
                chapterIndex = chapterIndex,
                phase = ScriptPhase.BUILDING,
                updatedAt = now
            )
        )

    /** 生成完成；[degraded]=模型失败回落规则 */
    fun done(
        bookKey: String,
        chapterIndex: Int,
        segments: Int,
        source: String,
        degraded: Boolean = false,
        message: String = "",
        now: Long = System.currentTimeMillis()
    ): ScriptStatus = publish(
        ScriptStatus(
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            phase = if (degraded) ScriptPhase.DEGRADED else ScriptPhase.READY,
            segments = segments,
            source = source,
            message = message,
            updatedAt = now
        )
    )

    fun publish(status: ScriptStatus): ScriptStatus {
        val key = keyOf(status.bookKey, status.chapterIndex)
        val next = advance(statusOf(status.bookKey, status.chapterIndex), status)
        if (next == status) {
            statuses[key] = status
            listeners.forEach { listener -> runCatching { listener(status) } }
        }
        return next
    }

    fun clear(bookKey: String, chapterIndex: Int) {
        statuses.remove(keyOf(bookKey, chapterIndex))
    }

    fun clearBook(bookKey: String) {
        statuses.keys.filter { it.startsWith("$bookKey|") }.forEach { statuses.remove(it) }
    }

    /** 清除全部状态（资源页「清除 AI 数据」后，剧本回到未生成） */
    fun clearAll() {
        statuses.clear()
    }

    /** 订阅（返回取消函数）；UI 离开页面必须调用，避免旧页面持有泄漏 */
    fun observe(listener: (ScriptStatus) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /**
     * 状态推进（纯函数）：只认时间戳更新的事件。
     * 迟到的「生成中」（时间戳更旧）不得把已完成的章节打回中间态 —— 后台任务并行时真实会发生。
     */
    fun advance(current: ScriptStatus, incoming: ScriptStatus): ScriptStatus {
        if (incoming.phase == ScriptPhase.NONE) return current
        if (current.phase == ScriptPhase.NONE) return incoming
        if (incoming.updatedAt <= current.updatedAt) return current
        return incoming
    }

    /** 文案（听书中心状态行/剧本行共用）：四种状态各自可区分，不出现"已生成"却无段的含糊说法 */
    fun labelOf(status: ScriptStatus): String = when (status.phase) {
        ScriptPhase.NONE -> "未生成"
        ScriptPhase.BUILDING -> "生成中…"
        ScriptPhase.DEGRADED -> "已降级（本次用规则结果）"
        ScriptPhase.READY -> when {
            status.segments <= 0 -> "已生成"
            status.source == "rule" -> "已生成（规则）· ${status.segments} 段"
            else -> "已生成（AI 增强）· ${status.segments} 段"
        }
    }
}
