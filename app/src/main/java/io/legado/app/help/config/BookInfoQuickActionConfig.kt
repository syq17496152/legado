package io.legado.app.help.config

import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

enum class BookInfoQuickActionType {
    SOURCE,
    TOC,
    GALLERY,
    GROUP,
    CLOUD,
    CUSTOM_BUTTON,
    EDIT_INFO,
    SHELF,
    READ
}

data class BookInfoQuickActionItem(
    val type: BookInfoQuickActionType = BookInfoQuickActionType.SOURCE,
    val enabled: Boolean = true,
    val alias: String = ""
)

object BookInfoQuickActionConfig {

    private const val PREF_ACTIONS = "bookInfoQuickActions"
    private const val PREF_CUSTOM_ALIAS_PREFIX = "bookInfoQuickActionCustomAlias_"

    private val defaultItems = listOf(
        BookInfoQuickActionItem(BookInfoQuickActionType.SOURCE),
        BookInfoQuickActionItem(BookInfoQuickActionType.TOC),
        BookInfoQuickActionItem(BookInfoQuickActionType.GALLERY)
    )
    private val supportedTypes = listOf(
        BookInfoQuickActionType.SOURCE,
        BookInfoQuickActionType.TOC,
        BookInfoQuickActionType.GALLERY,
        BookInfoQuickActionType.GROUP,
        BookInfoQuickActionType.CLOUD,
        BookInfoQuickActionType.CUSTOM_BUTTON
    )

    fun load(): List<BookInfoQuickActionItem> {
        val raw = appCtx.getPrefString(PREF_ACTIONS).orEmpty()
        val stored = GSON.fromJsonObject<Settings>(raw).getOrNull()?.actions.orEmpty()
        return normalize(stored.ifEmpty { defaultItems })
    }

    fun save(items: List<BookInfoQuickActionItem>) {
        appCtx.putPrefString(PREF_ACTIONS, GSON.toJson(Settings(normalize(items))))
    }

    fun reset() {
        save(defaultItems)
    }

    fun defaults(): List<BookInfoQuickActionItem> {
        return normalize(defaultItems)
    }

    fun customButtonAlias(sourceUrl: String): String {
        return appCtx.getPrefString(customAliasKey(sourceUrl)).orEmpty()
    }

    fun saveCustomButtonAlias(sourceUrl: String, alias: String) {
        appCtx.putPrefString(customAliasKey(sourceUrl), alias.trim())
    }

    private fun normalize(items: List<BookInfoQuickActionItem>): List<BookInfoQuickActionItem> {
        val used = hashSetOf<BookInfoQuickActionType>()
        val normalized = mutableListOf<BookInfoQuickActionItem>()
        items.forEach { item ->
            if (item.type in supportedTypes && used.add(item.type)) {
                normalized += item.copy(alias = item.alias.trim())
            }
        }
        supportedTypes.forEach { type ->
            if (used.add(type)) {
                normalized += BookInfoQuickActionItem(type, enabled = false)
            }
        }
        return normalized
    }

    private fun customAliasKey(sourceUrl: String): String {
        val key = sourceUrl.trim().ifBlank { "default" }
        return PREF_CUSTOM_ALIAS_PREFIX + key.hashCode().toString(16)
    }

    private data class Settings(
        val actions: List<BookInfoQuickActionItem> = emptyList()
    )
}