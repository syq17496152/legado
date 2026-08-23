package io.legado.app.help.book

import io.legado.app.utils.getPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.putPrefString
import splitties.init.appCtx

enum class BookCloudEntryMode {
    CACHE_PACKAGE,
    LIBRARY_CHAPTER
}

object BookCloudEntryModeStore {

    private const val PREFIX = "bookCloudEntryMode_"

    fun get(bookUrl: String): BookCloudEntryMode {
        return when (appCtx.getPrefString(prefKey(bookUrl))) {
            BookCloudEntryMode.LIBRARY_CHAPTER.name -> BookCloudEntryMode.LIBRARY_CHAPTER
            else -> BookCloudEntryMode.CACHE_PACKAGE
        }
    }

    fun set(bookUrl: String, mode: BookCloudEntryMode) {
        appCtx.putPrefString(prefKey(bookUrl), mode.name)
    }

    fun clear(bookUrl: String) {
        appCtx.removePref(prefKey(bookUrl))
    }

    private fun prefKey(bookUrl: String): String {
        val key = bookUrl.trim().ifBlank { "default" }
        return PREFIX + key.hashCode().toString(16)
    }
}