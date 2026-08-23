package io.legado.app.help.config

import androidx.annotation.StringRes
import io.legado.app.R
import io.legado.app.constant.PreferKey
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

enum class BookInfoComponentType(
    @StringRes val titleRes: Int,
    @StringRes val hintRes: Int
) {
    HEADER(R.string.book_info_component_header, R.string.book_info_component_header_hint),
    META(R.string.book_info_component_meta, R.string.book_info_component_meta_hint),
    ACTIONS(R.string.reading, R.string.add_to_bookshelf),
    DETAIL(R.string.book_info_component_detail, R.string.book_info_component_detail_hint),
    CATALOG(R.string.book_info_tab_toc, R.string.view_toc),
    AI_IMAGES(R.string.book_info_component_ai_images, R.string.book_info_component_ai_images_hint);

    companion object {
        fun fromKey(key: String?): BookInfoComponentType? {
            return entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
        }
    }
}

data class BookInfoComponentItem(
    val type: BookInfoComponentType,
    var enabled: Boolean
)

enum class BookInfoPageStyle(val key: String) {
    CLASSIC("classic"),
    IMMERSIVE_COMPOSE("immersive_compose");

    companion object {
        fun fromKey(key: String?): BookInfoPageStyle {
            return entries.firstOrNull { it.key.equals(key, ignoreCase = true) }
                ?: IMMERSIVE_COMPOSE
        }
    }
}

object BookInfoComponentConfig {

    private val defaultOrder = listOf(
        BookInfoComponentType.HEADER,
        BookInfoComponentType.META,
        BookInfoComponentType.DETAIL,
        BookInfoComponentType.CATALOG,
        BookInfoComponentType.AI_IMAGES
    )

    private val manageableTypes = defaultOrder.toSet()
    fun load(): MutableList<BookInfoComponentItem> {
        val raw = appCtx.getPrefString(PreferKey.bookInfoComponents).orEmpty().trim()
        if (raw.isEmpty()) {
            return defaultItems()
        }
        val parsed = raw.split(",")
            .mapNotNull { entry ->
                val parts = entry.split(":")
                val type = BookInfoComponentType.fromKey(parts.getOrNull(0)?.trim())
                    ?.takeIf { it in manageableTypes }
                val enabled = parts.getOrNull(1)?.trim() != "0"
                type?.let { BookInfoComponentItem(it, enabled) }
            }
            .toMutableList()
        defaultOrder.forEach { type ->
            if (parsed.none { it.type == type }) {
                val insertIndex = defaultOrder
                    .takeWhile { it != type }
                    .lastOrNull { previous -> parsed.any { it.type == previous } }
                    ?.let { previous -> parsed.indexOfFirst { it.type == previous } + 1 }
                    ?: parsed.size
                parsed.add(insertIndex.coerceIn(0, parsed.size), BookInfoComponentItem(type, true))
            }
        }
        return parsed.distinctBy { it.type }.toMutableList()
    }

    fun save(items: List<BookInfoComponentItem>) {
        val normalized = items.filter { it.type in manageableTypes }.distinctBy { it.type }.ifEmpty { defaultItems() }
        val safeItems = if (normalized.none { it.enabled }) {
            normalized.mapIndexed { index, item -> item.copy(enabled = index == 0) }
        } else {
            normalized
        }
        val raw = safeItems.joinToString(",") {
            "${it.type.name}:${if (it.enabled) 1 else 0}"
        }
        appCtx.putPrefString(PreferKey.bookInfoComponents, raw)
    }

    fun reset() {
        save(defaultItems())
    }

    fun loadStyle(): BookInfoPageStyle {
        return BookInfoPageStyle.fromKey(appCtx.getPrefString(PreferKey.bookInfoPageStyle))
    }

    fun saveStyle(style: BookInfoPageStyle) {
        appCtx.putPrefString(PreferKey.bookInfoPageStyle, style.key)
    }

    private fun defaultItems(): MutableList<BookInfoComponentItem> {
        return defaultOrder.map { BookInfoComponentItem(it, true) }.toMutableList()
    }
}