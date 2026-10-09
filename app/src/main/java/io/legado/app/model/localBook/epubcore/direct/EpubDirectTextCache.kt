package io.legado.app.model.localBook.epubcore.direct

/**
 * 有界文本 LRU：条目数 / 单条字符数 / 总字符数三重上限。
 * 单条超限不入缓存（避免一条巨文本挤掉全部）。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal class EpubDirectTextCache(
    private val maxEntries: Int,
    private val maxEntryChars: Int,
    private val maxTotalChars: Int
) {
    private val entries = LinkedHashMap<String, String>(maxEntries, 0.75f, true)
    private var totalChars = 0

    init {
        require(maxEntries > 0)
        require(maxEntryChars > 0)
        require(maxTotalChars >= maxEntryChars)
    }

    @Synchronized
    operator fun get(key: String): String? = entries[key]

    @Synchronized
    fun put(key: String, value: String): Boolean {
        if (value.length > maxEntryChars) {
            entries.remove(key)?.let { totalChars -= it.length }
            return false
        }
        entries.remove(key)?.let { totalChars -= it.length }
        entries[key] = value
        totalChars += value.length
        trimToBudget()
        return entries[key] === value
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalChars = 0
    }

    private fun trimToBudget() {
        while (entries.size > 1 && (entries.size > maxEntries || totalChars > maxTotalChars)) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
            totalChars -= eldest.value.length
        }
    }
}