package io.legado.app.model

/**
 * R9（B2，2026-09-23）：自动任务「更新目录」的**结构化 diff**（纯函数、无副作用）。
 *
 * 背景（原缺陷）：`AutoTaskProtocol.handleRefreshToc` 用 `afterCount - beforeCount` 推断新增数，
 * 并把缓存区间写成 `[beforeCount, afterCount-1]` —— 该口径**只在「纯末尾追加」时成立**：
 * - 目录**中间插入**时，区间起点偏大 ⇒ 漏缓存真正的新章；
 * - 目录同时有**删除**时，`afterCount - beforeCount` 甚至为 0/负数 ⇒ 新增数算错、通知不触发；
 * - 中间插入还会把「既有章节」误当新章缓存（白耗流量与时间）。
 *
 * 本模块改为**按身份（章节 url）匹配**：
 * - `addedCount`：出现在 after、身份不在 before 的章节数（**重复标题天然免疫**，因按 url 而非标题）；
 * - `removedCount`：原在 before、身份已不在 after 的章节数；
 * - `reordered`：两侧共有章节的相对顺序是否改变；
 * - `ranges`：把新增章节**下标**合并为**连续区间**（供 `CacheBook.start` 按段拉取）；
 * - `degraded`：合并后的区间数超过阈值时**降级**为首尾单区间（宁可多缓存一点，也不发过多请求）。
 */
internal object AutoTaskTocDiff {

    /** 连续区间数上限：超过即降级为单区间。 */
    const val DEFAULT_MAX_RANGES = 4

    data class Result(
        val addedCount: Int,
        val removedCount: Int,
        val reordered: Boolean,
        val ranges: List<IntRange>,
        val degraded: Boolean
    ) {
        /** 是否有任何可感知变化（用于通知/摘要判定）。 */
        val changed: Boolean get() = addedCount > 0 || removedCount > 0 || reordered
    }

    /**
     * @param beforeIds 更新前的章节身份序列（`BookChapter.url`）
     * @param afterIds  更新后的章节身份序列
     * @param maxRanges 区间合并上限（<=0 视为 1）
     */
    fun diff(
        beforeIds: List<String>,
        afterIds: List<String>,
        maxRanges: Int = DEFAULT_MAX_RANGES
    ): Result {
        val beforeSet = beforeIds.toHashSet()
        val afterSet = afterIds.toHashSet()
        val newIndexes = afterIds.indices.filter { afterIds[it] !in beforeSet }
        val removedCount = beforeIds.count { it !in afterSet }
        val reordered = commonOrder(beforeIds, afterIds) != commonOrder(afterIds, beforeIds)
        val merged = mergeRanges(newIndexes)
        val limit = maxRanges.coerceAtLeast(1)
        val degraded = merged.size > limit
        val ranges = if (degraded && newIndexes.isNotEmpty()) {
            listOf(newIndexes.first()..newIndexes.last())
        } else {
            merged
        }
        return Result(newIndexes.size, removedCount, reordered, ranges, degraded)
    }

    /** 把升序下标合并为连续区间。 */
    private fun mergeRanges(indexes: List<Int>): List<IntRange> {
        if (indexes.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        var start = indexes.first()
        var prev = start
        for (i in 1 until indexes.size) {
            val cur = indexes[i]
            if (cur == prev + 1) {
                prev = cur
            } else {
                out.add(start..prev)
                start = cur
                prev = cur
            }
        }
        out.add(start..prev)
        return out
    }

    /** 按 base 顺序取出「两侧共有身份」的子序列（多重集口径，重复身份亦正确）。 */
    private fun commonOrder(base: List<String>, other: List<String>): List<String> {
        val counts = HashMap<String, Int>()
        other.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        val out = ArrayList<String>()
        base.forEach { id ->
            val c = counts[id] ?: 0
            if (c > 0) {
                counts[id] = c - 1
                out.add(id)
            }
        }
        return out
    }
}