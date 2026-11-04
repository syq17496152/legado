package io.legado.app.help.readaloud.role

/**
 * 3.5 剧本生成任务队列（纯状态，JVM 可单测）。
 *
 * 只解决「先做谁、重复请求合并、取消」三件事，不碰线程/WorkManager：
 * 真正的执行器（前台服务 / WorkManager 切换、`onTrimMemory` 释放）由后续运行时接入，
 * 但队列语义必须先固定 —— 否则同一章被多个入口重复排队的浪费会一直存在。
 *
 * 优先级：P0（用户正在读的章）> P1（预取下一章）> P2（后台补全）；
 * 同优先级 FIFO；同一 (bookKey, chapterIndex) 只保留一条，后续请求可**升级**优先级。
 */
enum class ScriptPriority(val rank: Int) {
    P0(0),
    P1(1),
    P2(2)
}

class ScriptTaskQueue {

    data class Task(
        val bookKey: String,
        val chapterIndex: Int,
        val priority: ScriptPriority,
        /** 入队序号：同优先级按它 FIFO */
        val seq: Long
    )

    private data class MutableTask(
        val bookKey: String,
        val chapterIndex: Int,
        var priority: ScriptPriority,
        val seq: Long
    )

    private val tasks = LinkedHashMap<String, MutableTask>()
    private var seqCounter = 0L

    val size: Int get() = tasks.size

    val isEmpty: Boolean get() = tasks.isEmpty()

    private fun keyOf(bookKey: String, chapterIndex: Int): String = "$bookKey|$chapterIndex"

    /**
     * 入队 / 合并：同一章已在队列时只升级优先级（不重置 FIFO 序号，避免反复入队把老任务挤后）。
     * @return true = 新增；false = 已存在（合并）或参数非法
     */
    fun enqueue(
        bookKey: String,
        chapterIndex: Int,
        priority: ScriptPriority = ScriptPriority.P1
    ): Boolean {
        if (bookKey.isBlank() || chapterIndex < 0) return false
        val key = keyOf(bookKey, chapterIndex)
        val existing = tasks[key]
        if (existing != null) {
            if (priority.rank < existing.priority.rank) existing.priority = priority
            return false
        }
        seqCounter += 1
        tasks[key] = MutableTask(bookKey, chapterIndex, priority, seqCounter)
        return true
    }

    /** 取下一个任务（出队）：优先级高的先出，同优先级序号小的先出 */
    fun poll(): Task? {
        val next = tasks.values.minWithOrNull(
            compareBy({ it.priority.rank }, { it.seq })
        ) ?: return null
        tasks.remove(keyOf(next.bookKey, next.chapterIndex))
        return Task(next.bookKey, next.chapterIndex, next.priority, next.seq)
    }

    fun peek(): Task? = tasks.values.minWithOrNull(
        compareBy({ it.priority.rank }, { it.seq })
    )?.let { Task(it.bookKey, it.chapterIndex, it.priority, it.seq) }

    fun contains(bookKey: String, chapterIndex: Int): Boolean =
        tasks.containsKey(keyOf(bookKey, chapterIndex))

    /** 取消指定章（跳章 / 用户切走当前书时用） */
    fun cancel(bookKey: String, chapterIndex: Int): Boolean =
        tasks.remove(keyOf(bookKey, chapterIndex)) != null

    /** 取消某本书全部任务（关闭书 / 退出听书） */
    fun cancelBook(bookKey: String): Int {
        val before = tasks.size
        tasks.entries.removeAll { it.value.bookKey == bookKey }
        return before - tasks.size
    }

    /** 清空（`onTrimMemory` 释放 / 关闭后台执行器）；返回清理条数 */
    fun cancelAll(): Int {
        val size = tasks.size
        tasks.clear()
        return size
    }
}
