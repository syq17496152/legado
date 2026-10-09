package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.data.entities.BookChapter

/**
 * 原始目录的不可变索引：**不改写**标题 / 卷标记 / URL，只提供按序号的对齐与相邻解析。
 * 用于「文本渲染模式」下章节身份校验（当前章节是否仍是索引里那一章）。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal class TextReaderChapterIndex(chapters: List<BookChapter>) {
    private val chaptersByIndex = chapters.associateBy { it.index }
    private val indexes = chaptersByIndex.keys.sorted()

    init {
        require(chaptersByIndex.size == chapters.size) { "章节目录包含重复序号" }
    }

    fun chapter(index: Int): BookChapter? = chaptersByIndex[index]

    fun resolve(index: Int): Int? = index.takeIf(chaptersByIndex::containsKey)

    /** 当前章节是否仍与索引中同序号的那一章一致（bookUrl + url 双重校验）。 */
    fun matches(index: Int, current: BookChapter?): Boolean {
        val original = chapter(index) ?: return false
        return current != null && current.index == original.index &&
            current.bookUrl == original.bookUrl && current.url == original.url
    }

    fun adjacent(index: Int, direction: Int): Int? {
        val position = indexes.binarySearch(index)
        val insertion = if (position >= 0) position else -position - 1
        val target = if (direction < 0) insertion - 1 else if (position >= 0) position + 1 else insertion
        return indexes.getOrNull(target)
    }
}