package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLog.Level
import io.legado.app.constant.AppLog.TAG_SEARCH_STORAGE
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.SearchBookStoragePolicy
import io.legado.app.data.entities.sanitizedForStorage

private const val SAFE_SEARCH_BOOK_ROW =
    "length(cast(coalesce(bookUrl, '') as blob)) <= ${SearchBookStoragePolicy.MAX_BOOK_URL_BYTES} and " +
        "length(cast(coalesce(origin, '') as blob)) <= ${SearchBookStoragePolicy.MAX_ORIGIN_BYTES} and " +
        "length(cast(coalesce(originName, '') as blob)) <= ${SearchBookStoragePolicy.MAX_ORIGIN_NAME_BYTES} and " +
        "length(cast(coalesce(name, '') as blob)) <= ${SearchBookStoragePolicy.MAX_NAME_BYTES} and " +
        "length(cast(coalesce(author, '') as blob)) <= ${SearchBookStoragePolicy.MAX_AUTHOR_BYTES} and " +
        "length(cast(coalesce(kind, '') as blob)) <= ${SearchBookStoragePolicy.MAX_KIND_BYTES} and " +
        "length(cast(coalesce(coverUrl, '') as blob)) <= ${SearchBookStoragePolicy.MAX_COVER_URL_BYTES} and " +
        "length(cast(coalesce(intro, '') as blob)) <= ${SearchBookStoragePolicy.MAX_INTRO_BYTES} and " +
        "length(cast(coalesce(wordCount, '') as blob)) <= ${SearchBookStoragePolicy.MAX_WORD_COUNT_BYTES} and " +
        "length(cast(coalesce(latestChapterTitle, '') as blob)) <= ${SearchBookStoragePolicy.MAX_CHAPTER_TITLE_BYTES} and " +
        "length(cast(coalesce(tocUrl, '') as blob)) <= ${SearchBookStoragePolicy.MAX_TOC_URL_BYTES} and " +
        "length(cast(coalesce(variable, '') as blob)) <= ${SearchBookStoragePolicy.MAX_VARIABLE_BYTES} and " +
        "length(cast(coalesce(chapterWordCountText, '') as blob)) <= ${SearchBookStoragePolicy.MAX_CHAPTER_WORD_COUNT_TEXT_BYTES}"

private const val SAFE_SEARCH_BOOK_ROW_T1 =
    "length(cast(coalesce(t1.bookUrl, '') as blob)) <= ${SearchBookStoragePolicy.MAX_BOOK_URL_BYTES} and " +
        "length(cast(coalesce(t1.origin, '') as blob)) <= ${SearchBookStoragePolicy.MAX_ORIGIN_BYTES} and " +
        "length(cast(coalesce(t1.originName, '') as blob)) <= ${SearchBookStoragePolicy.MAX_ORIGIN_NAME_BYTES} and " +
        "length(cast(coalesce(t1.name, '') as blob)) <= ${SearchBookStoragePolicy.MAX_NAME_BYTES} and " +
        "length(cast(coalesce(t1.author, '') as blob)) <= ${SearchBookStoragePolicy.MAX_AUTHOR_BYTES} and " +
        "length(cast(coalesce(t1.kind, '') as blob)) <= ${SearchBookStoragePolicy.MAX_KIND_BYTES} and " +
        "length(cast(coalesce(t1.coverUrl, '') as blob)) <= ${SearchBookStoragePolicy.MAX_COVER_URL_BYTES} and " +
        "length(cast(coalesce(t1.intro, '') as blob)) <= ${SearchBookStoragePolicy.MAX_INTRO_BYTES} and " +
        "length(cast(coalesce(t1.wordCount, '') as blob)) <= ${SearchBookStoragePolicy.MAX_WORD_COUNT_BYTES} and " +
        "length(cast(coalesce(t1.latestChapterTitle, '') as blob)) <= ${SearchBookStoragePolicy.MAX_CHAPTER_TITLE_BYTES} and " +
        "length(cast(coalesce(t1.tocUrl, '') as blob)) <= ${SearchBookStoragePolicy.MAX_TOC_URL_BYTES} and " +
        "length(cast(coalesce(t1.variable, '') as blob)) <= ${SearchBookStoragePolicy.MAX_VARIABLE_BYTES} and " +
        "length(cast(coalesce(t1.chapterWordCountText, '') as blob)) <= ${SearchBookStoragePolicy.MAX_CHAPTER_WORD_COUNT_TEXT_BYTES}"

private const val SEARCH_BOOK_STORAGE_BATCH_SIZE = 16

@Dao
interface SearchBookDao {

    @Query("select * from searchBooks where bookUrl = :bookUrl and $SAFE_SEARCH_BOOK_ROW")
    fun getSearchBook(bookUrl: String): SearchBook?

    @Query("select * from searchBooks where name = :name and author = :author and origin in (select bookSourceUrl from book_sources) and $SAFE_SEARCH_BOOK_ROW order by originOrder limit 1")
    fun getFirstByNameAuthor(name: String, author: String): SearchBook?

    @Query(
        """select t1.name, t1.author, t1.origin, t1.originName, t1.coverUrl, t1.bookUrl, 
        t1.type, t1.time, t1.intro, t1.kind, t1.latestChapterTitle, t1.tocUrl, t1.variable, 
        t1.wordCount, t2.customOrder as originOrder, t1.chapterWordCountText, t1.respondTime, t1.chapterWordCount
        from searchBooks as t1 inner join book_sources as t2 
        on t1.origin = t2.bookSourceUrl 
        where t1.name = :name and t1.author like '%'||:author||'%' 
        and t2.enabled = 1 and t2.bookSourceGroup like '%'||:sourceGroup||'%'
        and $SAFE_SEARCH_BOOK_ROW_T1
        order by t2.customOrder"""
    )
    fun changeSourceByGroup(name: String, author: String, sourceGroup: String): List<SearchBook>

    @Query(
        """select t1.name, t1.author, t1.origin, t1.originName, t1.coverUrl, t1.bookUrl, 
        t1.type, t1.time, t1.intro, t1.kind, t1.latestChapterTitle, t1.tocUrl, t1.variable, 
        t1.wordCount, t2.customOrder as originOrder, t1.chapterWordCountText, t1.respondTime, t1.chapterWordCount
        from searchBooks as t1 inner join book_sources as t2 
        on t1.origin = t2.bookSourceUrl 
        where t1.name = :name and t1.author like '%'||:author||'%'
        and t2.bookSourceGroup like '%'||:sourceGroup||'%'
        and (originName like '%'||:key||'%' or t1.latestChapterTitle like '%'||:key||'%')
        and t2.enabled = 1 
        and $SAFE_SEARCH_BOOK_ROW_T1
        order by t2.customOrder"""
    )
    fun changeSourceSearch(
        name: String,
        author: String,
        key: String,
        sourceGroup: String
    ): List<SearchBook>

    @Query(
        """
        select t1.name, t1.author, t1.origin, t1.originName, t1.coverUrl, t1.bookUrl, 
        t1.type, t1.time, t1.intro, t1.kind, t1.latestChapterTitle, t1.tocUrl, t1.variable, 
        t1.wordCount, t2.customOrder as originOrder, t1.chapterWordCountText, t1.respondTime, t1.chapterWordCount
        from searchBooks as t1 inner join book_sources as t2 
        on t1.origin = t2.bookSourceUrl 
        where t1.name = :name and t1.author = :author and t1.coverUrl is not null and t1.coverUrl <> '' and t2.enabled = 1
        and $SAFE_SEARCH_BOOK_ROW_T1
        order by t2.customOrder
        """
    )
    fun getEnableHasCover(name: String, author: String): List<SearchBook>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRaw(vararg searchBook: SearchBook): List<Long>

    @Transaction
    fun insert(vararg searchBook: SearchBook): List<Long> {
        val safeBooks = ArrayList<SearchBook>(SEARCH_BOOK_STORAGE_BATCH_SIZE)
        val safeIndices = ArrayList<Int>(SEARCH_BOOK_STORAGE_BATCH_SIZE)
        val result = MutableList(searchBook.size) { -1L }
        fun flushBatch() {
            if (safeBooks.isEmpty()) return
            val insertedIds = insertRaw(*safeBooks.toTypedArray())
            safeIndices.forEachIndexed { safeIndex, originalIndex ->
                result[originalIndex] = insertedIds[safeIndex]
            }
            safeBooks.clear()
            safeIndices.clear()
        }
        searchBook.forEachIndexed { index, book ->
            val safeBook = book.sanitizedForStorage()
            if (safeBook != null) {
                if (safeBook != book) {
                    AppLog.putDebugWithTag(
                        TAG_SEARCH_STORAGE,
                        "搜索记录字段截断: origin=${book.origin.take(80)} name=${book.name.take(80)}",
                        level = Level.INFO
                    )
                }
                safeBooks.add(safeBook)
                safeIndices.add(index)
                if (safeBooks.size == SEARCH_BOOK_STORAGE_BATCH_SIZE) {
                    flushBatch()
                }
            } else {
                AppLog.putDebugWithTag(
                    TAG_SEARCH_STORAGE,
                    "搜索记录跳过存储: origin=${book.origin.take(80)} name=${book.name.take(80)} " +
                        "bookUrl=${book.bookUrl.take(80)} totalBytes=${SearchBookStoragePolicy.storedUtf8ByteCount(book)}",
                    level = Level.WARN
                )
            }
        }
        flushBatch()
        return result
    }

    @Query("delete from searchBooks where name = :name and author = :author")
    fun clear(name: String, author: String)

    @Query("delete from searchBooks where time < :time")
    fun clearExpired(time: Long)

    @Query("delete from searchBooks where not ($SAFE_SEARCH_BOOK_ROW)")
    fun clearUnsafeRows(): Int

    @Update
    fun updateRaw(vararg searchBook: SearchBook)

    @Transaction
    fun update(vararg searchBook: SearchBook) {
        val safeBooks = ArrayList<SearchBook>(SEARCH_BOOK_STORAGE_BATCH_SIZE)
        fun flushBatch() {
            if (safeBooks.isEmpty()) return
            updateRaw(*safeBooks.toTypedArray())
            safeBooks.clear()
        }
        searchBook.forEach { book ->
            book.sanitizedForStorage()?.let {
                safeBooks.add(it)
                if (safeBooks.size == SEARCH_BOOK_STORAGE_BATCH_SIZE) {
                    flushBatch()
                }
            }
        }
        flushBatch()
    }

    @Delete
    fun deleteRaw(vararg searchBook: SearchBook)

    @Transaction
    fun delete(vararg searchBook: SearchBook) {
        val safeBooks = searchBook.filter {
            SearchBookStoragePolicy.cleanupBookUrl(it.bookUrl) != null
        }
        if (safeBooks.isNotEmpty()) {
            deleteRaw(*safeBooks.toTypedArray())
        }
    }
}