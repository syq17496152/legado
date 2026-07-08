package io.legado.app.help.storage

import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.help.book.BookHelp
import io.legado.app.help.book.getFolderNameNoCache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import splitties.init.appCtx
import java.io.File

/**
 * F-P0-2 备份选择器（借鉴蛋蛋Max）
 * 书籍缓存选择配置，存储用户选择要备份缓存的具体书籍
 */
object BookCacheSelectorConfig {

    private val configPath = FileUtils.getPath(appCtx.filesDir, "bookCacheSelector.json")

    private var selectedBookUrls: MutableSet<String> = load()

    private fun load(): MutableSet<String> {
        val set = HashSet<String>()
        val file = FileUtils.createFileIfNotExist(configPath)
        if (file.exists() && file.length() > 0) {
            val json = file.readText()
            GSON.fromJsonObject<Set<String>>(json).getOrNull()?.let {
                set.addAll(it)
            }
        }
        return set
    }

    /**
     * 获取所有有缓存的书籍
     */
    fun getBooksWithCache(): List<Book> {
        val cacheDir = File(BookHelp.cachePath)
        if (!cacheDir.exists() || !cacheDir.isDirectory) {
            return emptyList()
        }

        val folderNames = cacheDir.listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?: return emptyList()

        return appDb.bookDao.all.filter { book ->
            book.getFolderNameNoCache() in folderNames
        }
    }

    fun isSelected(book: Book): Boolean {
        return selectedBookUrls.contains(book.bookUrl)
    }

    fun setSelected(book: Book, selected: Boolean) {
        if (selected) {
            selectedBookUrls.add(book.bookUrl)
        } else {
            selectedBookUrls.remove(book.bookUrl)
        }
    }

    fun selectAll() {
        getBooksWithCache().forEach {
            selectedBookUrls.add(it.bookUrl)
        }
    }

    fun deselectAll() {
        selectedBookUrls.clear()
    }

    fun getSelectedBooks(): List<Book> {
        val booksWithCache = getBooksWithCache()
        return booksWithCache.filter { isSelected(it) }
    }

    fun hasSelection(): Boolean = selectedBookUrls.isNotEmpty()

    fun isAllSelected(): Boolean {
        val booksWithCache = getBooksWithCache()
        return booksWithCache.isNotEmpty() && booksWithCache.all { isSelected(it) }
    }

    fun isNoneSelected(): Boolean {
        if (selectedBookUrls.isEmpty()) return true
        return getBooksWithCache().none { isSelected(it) }
    }

    fun save() {
        val json = GSON.toJson(selectedBookUrls.toSet())
        FileUtils.createFileIfNotExist(configPath).writeText(json)
    }
}
