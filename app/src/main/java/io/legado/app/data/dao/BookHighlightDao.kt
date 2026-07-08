package io.legado.app.data.dao

import androidx.room.*
import io.legado.app.data.entities.BookHighlight
import kotlinx.coroutines.flow.Flow

@Dao
interface BookHighlightDao {

    @get:Query("select * from highlights order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos")
    val all: List<BookHighlight>

    @Query("select * from highlights order by bookName collate localized, bookAuthor collate localized, chapterIndex, chapterPos")
    fun flowAll(): Flow<List<BookHighlight>>

    @Query(
        """select * from highlights
        where bookName = :bookName and bookAuthor = :bookAuthor
        order by chapterIndex, chapterPos"""
    )
    fun flowByBook(bookName: String, bookAuthor: String): Flow<List<BookHighlight>>

    @Query(
        """SELECT * FROM highlights
        where bookName = :bookName and bookAuthor = :bookAuthor
        and (chapterName like '%'||:key||'%' or bookText like '%'||:key||'%' or note like '%'||:key||'%')
        order by chapterIndex, chapterPos"""
    )
    fun flowSearch(bookName: String, bookAuthor: String, key: String): Flow<List<BookHighlight>>

    @Query(
        """select * from highlights
        where bookName = :bookName and bookAuthor = :bookAuthor
        order by chapterIndex, chapterPos"""
    )
    fun getByBook(bookName: String, bookAuthor: String): List<BookHighlight>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(vararg highlight: BookHighlight)

    @Update
    fun update(highlight: BookHighlight)

    @Delete
    fun delete(vararg highlight: BookHighlight)

}
