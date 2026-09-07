package io.legado.app.ui.widget.compose

import androidx.compose.runtime.snapshots.SnapshotStateList

// bugfix-0908 T5：返回是否存在实际变更（供调用方作为重组版本信号，identical 重发射不触发 UI 重置）
internal fun <T> SnapshotStateList<T>.replaceByIndex(
    items: List<T>,
    sameContent: (old: T, new: T) -> Boolean = { old, new -> old == new }
): Boolean {
    var changed = false
    var index = 0
    while (index < items.size) {
        val next = items[index]
        if (index < size) {
            if (!sameContent(this[index], next)) {
                replaceAt(index, next)
                changed = true
            }
        } else {
            add(next)
            changed = true
        }
        index++
    }
    while (size > items.size) {
        removeAt(lastIndex)
        changed = true
    }
    return changed
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

@PublishedApi
internal fun <T> SnapshotStateList<T>.replaceAt(index: Int, next: T) {
    // bugfix-0908 T5：等值分支直接 no-op——keyed LazyColumn 下原 removeAt+add 同位重插
    // 不产生任何视觉变化，仅徒增 O(n) 移位与双写失效（1 万条列表卡死根因之一）
    if (this[index] != next) {
        this[index] = next
    }
}
