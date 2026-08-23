package io.legado.app.model.localBook.epubcore.cache

import io.legado.app.model.localBook.epubcore.layout.EpubCorePage

class EpubCoreMemoryCache(
    private val maxPageSets: Int = 48
) {
    private val pages = object : LinkedHashMap<String, List<EpubCorePage>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<EpubCorePage>>?): Boolean {
            return size > maxPageSets
        }
    }

    @Synchronized
    fun getPages(key: String): List<EpubCorePage>? = pages[key]

    @Synchronized
    fun putPages(key: String, value: List<EpubCorePage>) {
        pages[key] = value
    }

    @Synchronized
    fun clear() {
        pages.clear()
    }
}
