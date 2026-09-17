package io.legado.app.ui.widget.compose

import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * 列表写回工具。
 *
 * ⚠️ 实现约束（2026-09-17 真机实证，source-list-refresh-fix）：
 * `SnapshotStateList.set(index, element)` 在本项目页面上下文中**不落地**——写入后立即读取仍是旧元素，
 * 导致「乐观更新」与「DB 回灌」都写不进列表 → 列表界面定格（用户操作看似无效，冷启动后才正确）。
 * 因此本文件的写回统一走「整体重建」（`clear()` + `addAll()`），该路径实测有效。
 *
 * 另：项目内 Room 实体（BookSourcePart/RssSource/RuleSub/DictRule/TxtTocRule/ReplaceRule）的
 * `equals` 多为 key-only，禁止用「内容等值」做写回门禁（会把「同 key、字段已变」的新实例判为相同）。
 */

/**
 * 同位写回门禁：按「对象身份」判定，仅同一实例才跳过。
 * 抽为纯函数以便 JVM 单测锁定语义。
 */
internal fun <T> shouldSkipSameIndexWrite(current: T, next: T): Boolean = current === next

/**
 * 批量写回：返回是否存在实际变更（供调用方作为重组版本信号，identical 重发射不触发 UI 重置）。
 *
 * 一次性整体重建：先计算目标列表，仅在确有变更时 `clear()+addAll()` 一次，
 * 既避免逐项 set 不落地，也避免逐项重建带来的 O(n×k) 开销。
 */
internal fun <T> SnapshotStateList<T>.replaceByIndex(
    items: List<T>,
    sameContent: (old: T, new: T) -> Boolean = { old, new -> old == new }
): Boolean {
    var changed = false
    val target = ArrayList<T>(items.size)
    for ((index, next) in items.withIndex()) {
        if (index < size) {
            val current = this[index]
            if (sameContent(current, next)) {
                target.add(current)
            } else {
                target.add(next)
                changed = true
            }
        } else {
            target.add(next)
            changed = true
        }
    }
    if (size > items.size) changed = true
    if (!changed) return false
    clear()
    addAll(target)
    return true
}

internal inline fun <T> SnapshotStateList<T>.replaceFirst(
    predicate: (T) -> Boolean,
    transform: (T) -> T
): T? {
    val index = indexOfFirst(predicate)
    if (index < 0) return null
    val next = transform(this[index])
    if (this[index] !== next || this[index] != next) {
        replaceAt(index, next)
    }
    return next
}

internal inline fun <T> SnapshotStateList<T>.replaceMatching(
    predicate: (T) -> Boolean,
    transform: (T) -> T
) {
    for (index in indices) {
        val current = this[index]
        if (predicate(current)) {
            val next = transform(current)
            if (current !== next || current != next) {
                replaceAt(index, next)
            }
        }
    }
}

/**
 * 单点写回：整体重建（原因见文件头注释——`set(index, element)` 在本项目页面上下文不落地）。
 */
@PublishedApi
internal fun <T> SnapshotStateList<T>.replaceAt(index: Int, next: T) {
    if (shouldSkipSameIndexWrite(this[index], next)) return
    val rebuilt = toMutableList().also { it[index] = next }
    clear()
    addAll(rebuilt)
}