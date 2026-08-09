package io.legado.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.legado.app.data.AppDatabase
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun createDb() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun bookDaoInsertAndGet() {
        val book = Book(bookUrl = "test://book1", name = "测试书", author = "作者")
        db.bookDao.insert(book)
        val result = db.bookDao.getBook("test://book1")
        assertNotNull(result)
        assertEquals("测试书", result!!.name)
        assertEquals("作者", result.author)
    }

    @Test
    fun bookDaoUpdate() {
        val book = Book(bookUrl = "test://book3", name = "原始名", author = "作者")
        db.bookDao.insert(book)
        val updated = book.copy(name = "更新名")
        db.bookDao.update(updated)
        val result = db.bookDao.getBook("test://book3")
        assertNotNull(result)
        assertEquals("更新名", result!!.name)
    }

    @Test
    fun bookDaoDelete() {
        val book = Book(bookUrl = "test://book2", name = "待删书", author = "作者")
        db.bookDao.insert(book)
        db.bookDao.delete(book)
        assertNull(db.bookDao.getBook("test://book2"))
    }

    @Test
    fun bookSourceDaoInsertAndGet() {
        val source = BookSource(
            bookSourceUrl = "https://test.com",
            bookSourceName = "测试源",
            bookSourceGroup = "测试"
        )
        db.bookSourceDao.insert(source)
        val result = db.bookSourceDao.getBookSource("https://test.com")
        assertNotNull(result)
        assertEquals("测试源", result!!.bookSourceName)
        assertEquals("测试", result.bookSourceGroup)
    }

    @Test
    fun bookGroupDaoInsertAndGet() {
        val group = BookGroup(groupId = 100L, groupName = "测试分组")
        db.bookGroupDao.insert(group)
        val result = db.bookGroupDao.getByID(100L)
        assertNotNull(result)
        assertEquals("测试分组", result!!.groupName)
    }
}
