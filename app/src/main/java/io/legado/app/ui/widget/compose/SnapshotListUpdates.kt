package io.legado.app.ui.widget.compose

import androidx.compose.runtime.snapshots.SnapshotStateList

internal fun <T> SnapshotStateList<T>.replaceByIndex(
    items: List<T>,
    sameContent: (old: T, new: T) -> Boolean = { old, new -> old == new }
) {
    var index = 0
    while (index < items.size) {
        val next = items[index]
        if (index < size) {
            if (!sameContent(this[index], next)) {
                replaceAt(index, next)
            }
        } else {
            add(next)
        }
        index++
    }
    while (size > items.size) {
        removeAt(lastIndex)
    }
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
    if (this[index] != next) {
        this[index] = next
    } else {
        removeAt(index)
        add(index, next)
    }
}
