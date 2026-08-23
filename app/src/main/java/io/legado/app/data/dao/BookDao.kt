package io.legado.app.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.TypeConverters
import androidx.room.Update
import io.legado.app.constant.BookType
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.help.book.isNotShelf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min

@Dao
interface BookDao {

    fun flowByGroup(groupId: Long): Flow<List<Book>> {
        return when (groupId) {
            BookGroup.IdRoot -> flowRoot()
            BookGroup.IdAll -> flowAll()
            BookGroup.IdLocal -> flowLocal()
            BookGroup.IdAudio -> flowAudio()
            BookGroup.IdNetNone -> flowNetNoGroup()
            BookGroup.IdLocalNone -> flowLocalNoGroup()
            BookGroup.IdVideo -> flowVideo()
            BookGroup.IdError -> flowUpdateError()
            else -> flowByUserGroup(groupId)
        }.map { list ->
            list.filterNot { it.isNotShelf }
        }
    }

    fun flowShelfByGroup(groupId: Long): Flow<List<BookShelfDisplay>> {
        return when (groupId) {
            BookGroup.IdRoot -> flowShelfRoot()
            BookGroup.IdAll -> flowShelfAll()
            BookGroup.IdLocal -> flowShelfLocal()
            BookGroup.IdAudio -> flowShelfAudio()
            BookGroup.IdNetNone -> flowShelfNetNoGroup()
            BookGroup.IdLocalNone -> flowShelfLocalNoGroup()
            BookGroup.IdVideo -> flowShelfVideo()
            BookGroup.IdError -> flowShelfUpdateError()
            else -> flowShelfByUserGroup(groupId)
        }
    }

    @Query(
        """
        select * from books where type & ${BookType.text} > 0
        and type & ${BookType.local} = 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        and (select show from book_groups where groupId = ${BookGroup.IdNetNone}) != 1
        """
    )
    fun flowRoot(): Flow<List<Book>>

    @Query("SELECT * FROM books order by durChapterTime desc")
    fun flowAll(): Flow<List<Book>>

    @Query(
        """
        SELECT bookUrl, origin, originName, name, author, intro, customIntro, customTag, coverUrl, customCoverUrl,
        type, `group`, latestChapterTitle, latestChapterTime, lastCheckCount, totalChapterNum,
        durChapterTitle, durChapterIndex, durChapterTime, canUpdate, `order`, readConfig
        FROM books
        WHERE (type & ${BookType.notShelf}) = 0
        ORDER BY durChapterTime DESC
        """
    )
    fun flowShelfAll(): Flow<List<BookShelfDisplay>>

    @Query(
        """
        SELECT bookUrl, origin, originName, name, author, intro, customIntro, customTag, coverUrl, customCoverUrl,
        type, `group`, latestChapterTitle, latestChapterTime, lastCheckCount, totalChapterNum,
        durChapterTitle, durChapterIndex, durChapterTime, canUpdate, `order`, readConfig
        FROM books
        WHERE (type & ${BookType.notShelf}) = 0
        AND (type & ${BookType.text}) > 0
        AND (type & ${BookType.local}) = 0
        AND ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        AND (select show from book_groups where groupId = ${BookGroup.IdNetNone}) != 1
        ORDER BY durChapterTime DESC
        """
    )
    fun flowShelfRoot(): Flow<List<BookShelfDisplay>>

    @Query(
        """
        SELECT bookUrl, origin, originName, name, author, intro, customIntro, customTag, coverUrl, customCoverUrl,
        type, `group`, latestChapterTitle, latestChapterTime, lastCheckCount, totalChapterNum,
        durChapterTitle, durChapterIndex, durChapterTime, canUpdate, `order`, readConfig
        FROM books
        WHERE (type & ${BookType.notShelf}) = 0
        AND (type & ${BookType.audio}) > 0
        ORDER BY durChapterTime DESC
        """
    )
    fun flowShelfAudio(): Flow<List<BookShelfDisplay>>

    @Query(
        """
        SELECT bookUrl, origin, originName, name, author, intro, customIntro, customTag, coverUrl, customCoverUrl,
        type, `group`, latestChapterTitle, latestChapterTime, lastCheckCount, totalChapterNum,
        durChapterTitle, durChapterIndex, durChapterTime, canUpdate, `order`, readConfig
        FROM books
        WHERE (type & ${BookType.notShelf}) = 0
        AND (type & ${BookType.video}) > 0
        ORDER BY durChapterTime DESC
        """
    )
    fun flowShelfVideo(): Flow<List<BookShelfDisplay>>

    @Query(
        """
        SELECT bookUrl, origin, originName, name, author, intro, customIntro, customTag, coverUrl, customCoverUrl,
        type, `group`, latestChapterTitle, latestChapterTime, lastCheckCount, totalChapterNum,
        durChapterTitle, durChapterIndex, durChapterTime, canUpdate, `order`, readConfig
        FROM books
        WHERE (type & ${BookType.notShelf}) = 0
        AND (type & ${BookType.local}) > 0
        ORDER BY durChapterTime DESC
        """
    )
    fun flowShelfLocal(): Flow<List<BookShelfDisplay>>

    @Query(
        """
        SELECT bookUrl, origin, originName, name, author, intro, customIntro, customTag, coverUrl, customCoverUrl,
        type, `group`, latestChapterTitle, latestChapterTime, lastCheckCount, totalChapterNum,
        durChapterTitle, durChapterIndex, durChapterTime, canUpdate, `order`, readConfig
        FROM books
        WHERE (type & ${BookType.notShelf}) = 0
        AND (type & ${BookType.audio}) = 0
        AND (type & ${BookType.local}) = 0
        AND (type & ${BookType.video}) = 0
        AND ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        ORDER BY durChapterTime DESC
        """
    )
    fun flowShelfNetNoGroup(): Flow<List<BookShelfDisplay>>

    @Query(
        """
        SELECT bookUrl, origin, originName, name, author, intro, customIntro, customTag, coverUrl, customCoverUrl,
        type, `group`, latestChapterTitle, latestChapterTime, lastCheckCount, totalChapterNum,
        durChapterTitle, durChapterIndex, durChapterTime, canUpdate, `order`, readConfig
        FROM books
        WHERE (type & ${BookType.notShelf}) = 0
        AND (type & ${BookType.local}) > 0
        AND ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        ORDER BY durChapterTime DESC
        """
    )
    fun flowShelfLocalNoGroup(): Flow<List<BookShelfDisplay>>

    @Query(
        """
        SELECT bookUrl, origin, originName, name, author, intro, customIntro, customTag, coverUrl, customCoverUrl,
        type, `group`, latestChapterTitle, latestChapterTime, lastCheckCount, totalChapterNum,
        durChapterTitle, durChapterIndex, durChapterTime, canUpdate, `order`, readConfig
        FROM books
        WHERE (type & ${BookType.notShelf}) = 0
        AND (`group` & :group) > 0
        ORDER BY durChapterTime DESC
        """
    )
    fun flowShelfByUserGroup(group: Long): Flow<List<BookShelfDisplay>>

    @Query(
        """
        SELECT bookUrl, origin, originName, name, author, intro, customIntro, customTag, coverUrl, customCoverUrl,
        type, `group`, latestChapterTitle, latestChapterTime, lastCheckCount, totalChapterNum,
        durChapterTitle, durChapterIndex, durChapterTime, canUpdate, `order`, readConfig
        FROM books
        WHERE (type & ${BookType.notShelf}) = 0
        AND (type & ${BookType.updateError}) > 0
        ORDER BY durChapterTime DESC
        """
    )
    fun flowShelfUpdateError(): Flow<List<BookShelfDisplay>>

    @Query("SELECT bookUrl, name, author, (type & ${BookType.notShelf}) > 0 AS isNotShelf FROM books")
    fun flowShelfIdentities(): Flow<List<BookShelfIdentity>>

    @Query(
        """
        SELECT bookUrl, name, author, origin, originName, coverUrl, customCoverUrl,
        durChapterTime, type FROM books
        WHERE bookUrl IN (:bookUrls)
        """
    )
    fun getDisplayInfosByUrls(bookUrls: List<String>): List<BookDisplayInfo>

    @Query(
        """
        SELECT bookUrl, name, author, origin, originName, coverUrl, customCoverUrl,
        durChapterTime, type FROM books
        WHERE name IN (:names)
        AND bookUrl = (
            SELECT b2.bookUrl FROM books b2
            WHERE b2.name = books.name
            ORDER BY b2.durChapterTime DESC, b2.bookUrl ASC
            LIMIT 1
        )
        """
    )
    fun getLatestDisplayInfosByNames(names: List<String>): List<BookDisplayInfo>

    @Query(
        """
        SELECT bookUrl, name, author, origin, originName, coverUrl, customCoverUrl,
        durChapterTime, type FROM books
        WHERE name like '%'||:key||'%' or author like '%'||:key||'%'
        ORDER BY durChapterTime DESC
        LIMIT :limit
        """
    )
    fun flowSearchDisplayInfos(key: String, limit: Int = 80): Flow<List<BookDisplayInfo>>

    @Query("SELECT * FROM books WHERE type & ${BookType.audio} > 0")
    fun flowAudio(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE type & ${BookType.video} > 0")
    fun flowVideo(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE type & ${BookType.local} > 0")
    fun flowLocal(): Flow<List<Book>>

    @Query(
        """
        select * from books where type & ${BookType.audio} = 0 and type & ${BookType.local} = 0 and type & ${BookType.video} = 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        """
    )
    fun flowNetNoGroup(): Flow<List<Book>>

    @Query(
        """
        select * from books where type & ${BookType.local} > 0
        and ((SELECT sum(groupId) FROM book_groups where groupId > 0) & `group`) = 0
        """
    )
    fun flowLocalNoGroup(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE (`group` & :group) > 0")
    fun flowByUserGroup(group: Long): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE name like '%'||:key||'%' or author like '%'||:key||'%'")
    fun flowSearch(key: String): Flow<List<Book>>

    @Query("SELECT * FROM books where type & ${BookType.updateError} > 0 order by durChapterTime desc")
    fun flowUpdateError(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE (`group` & :group) > 0")
    fun getBooksByGroup(group: Long): List<Book>

    @Query("SELECT * FROM books WHERE `name` in (:names)")
    fun findByName(vararg names: String): List<Book>

    @Query("select * from books where originName = :fileName")
    fun getBookByFileName(fileName: String): Book?

    @Query("SELECT * FROM books WHERE bookUrl = :bookUrl")
    fun getBook(bookUrl: String): Book?

    @Query("SELECT * FROM books WHERE bookUrl IN (:bookUrls)")
    fun getBooks(bookUrls: List<String>): List<Book>

    fun getBooksSafe(bookUrls: List<String>, chunkSize: Int = 100): List<Book> {
        if (bookUrls.isEmpty()) return emptyList()
        val bookMap = bookUrls
            .filter { it.isNotBlank() }
            .distinct()
            .chunked(chunkSize)
            .flatMap { getBooks(it) }
            .associateBy { it.bookUrl }
        return bookUrls.mapNotNull { bookMap[it] }
    }

    @Query("SELECT * FROM books WHERE name = :name and author = :author")
    fun getBook(name: String, author: String): Book?

    @Query("""select distinct bs.* from books, book_sources bs 
        where origin == bookSourceUrl and origin not like '${BookType.localTag}%' 
        and origin not like '${BookType.webDavTag}%'""")
    fun getAllUseBookSource(): List<BookSource>

    @Query("SELECT * FROM books WHERE name = :name and origin = :origin")
    fun getBookByOrigin(name: String, origin: String): Book?

    @get:Query("select count(bookUrl) from books where (SELECT sum(groupId) FROM book_groups)")
    val noGroupSize: Int

    @get:Query("SELECT * FROM books where type & ${BookType.local} = 0")
    val webBooks: List<Book>

    @get:Query("SELECT * FROM books where type & ${BookType.local} = 0 and canUpdate = 1")
    val hasUpdateBooks: List<Book>

    @get:Query(
        """
        SELECT bookUrl, origin, type, totalChapterNum, durChapterIndex, readConfig
        FROM books
        WHERE type & ${BookType.local} = 0
        AND canUpdate = 1
        """
    )
    val updateBookInfos: List<BookUpdateInfo>

    @get:Query("SELECT * FROM books")
    val all: List<Book>

    @Query("SELECT * FROM books where type & :type > 0 and type & ${BookType.local} = 0")
    fun getByTypeOnLine(type: Int): List<Book>

    @get:Query("SELECT * FROM books where type & ${BookType.text} > 0 ORDER BY durChapterTime DESC limit 1")
    val lastReadBook: Book?

    @get:Query("SELECT bookUrl FROM books")
    val allBookUrls: List<String>

    @get:Query("SELECT bookUrl, name, author, customTag, type, `group` FROM books")
    val allTagInfos: List<BookTagInfo>

    @get:Query("SELECT COUNT(*) FROM books")
    val allBookCount: Int

    @get:Query("select min(`order`) from books")
    val minOrder: Int

    @get:Query("select max(`order`) from books")
    val maxOrder: Int

    @Query("select exists(select 1 from books where origin = :origin)")
    fun hasBookByOrigin(origin: String): Boolean

    @Query("update books set origin = :newUrl where origin = :oldUrl")
    fun updateOrigin(oldUrl: String, newUrl: String): Int

    @Query("select exists(select 1 from books where bookUrl = :bookUrl)")
    fun has(bookUrl: String): Boolean

    @Query("select exists(select 1 from books where name = :name and author = :author)")
    fun has(name: String, author: String): Boolean

    @Query(
        """select exists(select 1 from books where type & ${BookType.local} > 0 
        and (originName = :fileName or (origin != '${BookType.localTag}' and origin like '%' || :fileName)))"""
    )
    fun hasFile(fileName: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertRaw(vararg book: Book)

    @Update
    fun updateRaw(vararg book: Book)

    @Transaction
    fun insert(vararg book: Book) {
        book.forEach { it.sanitizeForStorage() }
        insertRaw(*book)
    }

    @Transaction
    fun update(vararg book: Book) {
        book.forEach { it.sanitizeForStorage() }
        updateRaw(*book)
    }

    @Delete
    fun delete(vararg book: Book)

    @Transaction
    fun replace(oldBook: Book, newBook: Book) {
        newBook.sanitizeForStorage()
        delete(oldBook)
        insertRaw(newBook)
    }

    @Query("update books set durChapterPos = :pos where bookUrl = :bookUrl")
    fun upProgress(bookUrl: String, pos: Int)

    @Query(
        """
        update books set
        lastCheckCount = :lastCheckCount,
        durChapterTitle = :durChapterTitle,
        durChapterIndex = :durChapterIndex,
        durChapterPos = :durChapterPos,
        durChapterTime = :durChapterTime
        where bookUrl = :bookUrl
        """
    )
    fun updateReadProgress(
        bookUrl: String,
        lastCheckCount: Int,
        durChapterTitle: String?,
        durChapterIndex: Int,
        durChapterPos: Int,
        durChapterTime: Long
    )

    @Query("update books set customTag = :customTag where bookUrl = :bookUrl")
    fun updateCustomTag(bookUrl: String, customTag: String?)

    @Query("update books set `group` = :newGroupId where `group` = :oldGroupId")
    fun upGroup(oldGroupId: Long, newGroupId: Long)

    @Query("update books set `group` = `group` - :group where `group` & :group > 0")
    fun removeGroup(group: Long)

    @Query("delete from books where type & ${BookType.notShelf} > 0")
    fun deleteNotShelfBook()
}

data class BookShelfIdentity(
    val bookUrl: String,
    val name: String,
    val author: String,
    val isNotShelf: Boolean
)

data class BookTagInfo(
    val bookUrl: String,
    val name: String,
    val author: String,
    val customTag: String?,
    val type: Int,
    val group: Long
)

@TypeConverters(Book.Converters::class)
data class BookUpdateInfo(
    val bookUrl: String,
    val origin: String,
    val type: Int,
    val totalChapterNum: Int,
    val durChapterIndex: Int,
    val readConfig: Book.ReadConfig?
) {
    val isLocal: Boolean
        get() {
            if (type == 0) {
                return origin == BookType.localTag || origin.startsWith(BookType.webDavTag)
            }
            return type and BookType.local > 0
        }

    fun getUnreadChapterNum(): Int {
        val config = readConfig ?: return max(totalChapterNum - durChapterIndex - 1, 0)
        if (!config.readSimulating) {
            return max(totalChapterNum - durChapterIndex - 1, 0)
        }
        val startDate = config.startDate ?: return max(totalChapterNum - durChapterIndex - 1, 0)
        val daysPassed = ChronoUnit.DAYS.between(startDate, LocalDate.now()).toInt() + 1
        val chaptersToUnlock =
            max(0, (config.startChapter ?: 0) + (daysPassed * config.dailyChapters))
        return max(min(totalChapterNum, chaptersToUnlock) - durChapterIndex - 1, 0)
    }
}

@TypeConverters(Book.Converters::class)
data class BookShelfDisplay(
    val bookUrl: String,
    val origin: String,
    val originName: String,
    val name: String,
    val author: String,
    val intro: String?,
    val customIntro: String?,
    val customTag: String?,
    val coverUrl: String?,
    val customCoverUrl: String?,
    val type: Int,
    val group: Long,
    val latestChapterTitle: String?,
    val latestChapterTime: Long,
    val lastCheckCount: Int,
    val totalChapterNum: Int,
    val durChapterTitle: String?,
    val durChapterIndex: Int,
    val durChapterTime: Long,
    val canUpdate: Boolean,
    val order: Int,
    val readConfig: Book.ReadConfig?
) {
    val isLocal: Boolean
        get() {
            if (type == 0) {
                return origin == BookType.localTag || origin.startsWith(BookType.webDavTag)
            }
            return type and BookType.local > 0
        }

    val isAudio: Boolean
        get() = type and BookType.audio > 0

    val isVideo: Boolean
        get() = type and BookType.video > 0

    val isImage: Boolean
        get() = type and BookType.image > 0

    fun getDisplayCover(): String? {
        return if (customCoverUrl.isNullOrEmpty()) coverUrl else customCoverUrl
    }

    fun getDisplayIntro(): String? {
        return if (customIntro.isNullOrEmpty()) intro else customIntro
    }

    fun getUnreadChapterNum(): Int {
        return max(simulatedTotalChapterNum() - durChapterIndex - 1, 0)
    }

    private fun simulatedTotalChapterNum(): Int {
        val config = readConfig ?: return totalChapterNum
        if (!config.readSimulating) {
            return totalChapterNum
        }
        val startDate = config.startDate ?: return totalChapterNum
        val daysPassed = ChronoUnit.DAYS.between(startDate, LocalDate.now()).toInt() + 1
        val chaptersToUnlock =
            max(0, (config.startChapter ?: 0) + (daysPassed * config.dailyChapters))
        return min(totalChapterNum, chaptersToUnlock)
    }

    fun toMinimalBook(): Book {
        return Book(
            bookUrl = bookUrl,
            origin = origin,
            originName = originName,
            name = name,
            author = author,
            customTag = customTag,
            coverUrl = coverUrl,
            customCoverUrl = customCoverUrl,
            type = type,
            group = group,
            latestChapterTitle = latestChapterTitle,
            latestChapterTime = latestChapterTime,
            lastCheckCount = lastCheckCount,
            totalChapterNum = totalChapterNum,
            durChapterTitle = durChapterTitle,
            durChapterIndex = durChapterIndex,
            durChapterTime = durChapterTime,
            canUpdate = canUpdate,
            order = order,
            readConfig = readConfig
        )
    }
}

data class BookDisplayInfo(
    val bookUrl: String,
    val name: String,
    val author: String,
    val origin: String,
    val originName: String,
    val coverUrl: String?,
    val customCoverUrl: String?,
    val durChapterTime: Long,
    val type: Int
) {
    fun toBook(): Book {
        return Book(
            bookUrl = bookUrl,
            name = name,
            author = author,
            origin = origin,
            originName = originName,
            coverUrl = coverUrl,
            customCoverUrl = customCoverUrl,
            durChapterTime = durChapterTime,
            type = type
        )
    }
}
