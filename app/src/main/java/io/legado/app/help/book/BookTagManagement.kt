package io.legado.app.help.book

import java.util.Locale

object BookTagManagement {

    fun mergeTags(configured: List<String>, existing: List<String>): List<String> {
        val merged = linkedMapOf<String, String>()
        (configured + existing).forEach { rawTag ->
            val tag = rawTag.trim()
            if (tag.isNotEmpty()) {
                merged.putIfAbsent(tag.lowercase(Locale.ROOT), tag)
            }
        }
        return merged.values.toList()
    }

    fun reusableTags(current: List<String>, all: List<String>): List<String> {
        val currentKeys = current.asSequence()
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .toSet()
        return mergeTags(emptyList(), all).filterNot {
            it.lowercase(Locale.ROOT) in currentKeys
        }
    }

    /** Returns null when the stored value does not need an update. */
    fun updateTag(customTag: String?, tag: String, selected: Boolean): String? {
        val tags = BookTagHelper.parse(customTag).toMutableList()
        val hasTag = tags.any { it.equals(tag, ignoreCase = true) }
        if (hasTag == selected) return null
        if (selected) {
            tags.add(tag)
        } else {
            tags.removeAll { it.equals(tag, ignoreCase = true) }
        }
        return BookTagHelper.join(tags)
    }
}
