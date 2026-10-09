package io.legado.app.model.localBook.epubcore.template

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 每模板翻页方式记忆单测（epub-md-rich-rendering 阶段 4.6 配对）。
 *
 * 覆盖【验证标准】「多模板各自记忆且生效」：
 * ①记忆互不串用；②未记忆跟随阅读设置；③滚动模板固定滚动且**不可记忆**；
 * ④非法值归一；⑤删除模板同步清理；⑥损坏文件不覆盖且不抛；⑦原子写无残留。
 */
class ReaderTemplatePreferencesTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("template-prefs-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun prefs() = ReaderTemplatePreferences(root)

    private val paged = EpubReaderTemplate.TYPE_PAGED
    private val scroll = EpubReaderTemplate.TYPE_SCROLL

    @Test
    fun `多模板各自记忆互不串用`() {
        val store = prefs()
        assertTrue(store.rememberPageTurn("user.a", paged, ReaderTemplatePageTurnPolicy.Mode.SIMULATION))
        assertTrue(store.rememberPageTurn("user.b", paged, ReaderTemplatePageTurnPolicy.Mode.SLIDE))
        assertEquals(ReaderTemplatePageTurnPolicy.Mode.SIMULATION, prefs().pageTurnMode("user.a"))
        assertEquals(ReaderTemplatePageTurnPolicy.Mode.SLIDE, prefs().pageTurnMode("user.b"))
        assertEquals(2, prefs().size())
    }

    @Test
    fun `未记忆时返回 null 以跟随阅读设置`() {
        assertNull(prefs().pageTurnMode("user.unknown"))
    }

    @Test
    fun `滚动模板固定滚动且不记忆`() {
        val store = prefs()
        assertFalse("滚动模板不允许记忆", ReaderTemplatePageTurnPolicy.canRemember(scroll))
        assertFalse("写入应被归一为清除", store.rememberPageTurn("user.s", scroll, ReaderTemplatePageTurnPolicy.Mode.SLIDE))
        assertNull(store.pageTurnMode("user.s"))
        assertEquals(
            "解析始终为滚动",
            ReaderTemplatePageTurnPolicy.Mode.SCROLL,
            ReaderTemplatePageTurnPolicy.resolve(scroll, null, ReaderTemplatePageTurnPolicy.Mode.COVER)
        )
        assertEquals(
            "即使被写入非法记忆也仍固定滚动",
            ReaderTemplatePageTurnPolicy.Mode.SCROLL,
            ReaderTemplatePageTurnPolicy.resolve(scroll, ReaderTemplatePageTurnPolicy.Mode.SIMULATION, ReaderTemplatePageTurnPolicy.Mode.COVER)
        )
    }

    @Test
    fun `分页模板解析优先级为记忆优于阅读设置`() {
        assertEquals(
            "有记忆时用记忆",
            ReaderTemplatePageTurnPolicy.Mode.SIMULATION,
            ReaderTemplatePageTurnPolicy.resolve(paged, ReaderTemplatePageTurnPolicy.Mode.SIMULATION, ReaderTemplatePageTurnPolicy.Mode.COVER)
        )
        assertEquals(
            "无记忆时跟随阅读设置",
            ReaderTemplatePageTurnPolicy.Mode.COVER,
            ReaderTemplatePageTurnPolicy.resolve(paged, null, ReaderTemplatePageTurnPolicy.Mode.COVER)
        )
        assertEquals(
            "记忆为跟随且阅读设置也是跟随时保持跟随",
            ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING,
            ReaderTemplatePageTurnPolicy.resolve(paged, ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING, ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING)
        )
    }

    @Test
    fun `非法值与非法翻页记忆被归一`() {
        assertEquals(
            ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING,
            ReaderTemplatePageTurnPolicy.Mode.fromValue(999)
        )
        assertEquals(
            "分页模板不得记忆为滚动（与模板类型冲突）",
            ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING,
            ReaderTemplatePageTurnPolicy.normalizeForStore(paged, ReaderTemplatePageTurnPolicy.Mode.SCROLL.value)
        )
        assertEquals(
            ReaderTemplatePageTurnPolicy.Mode.COVER,
            ReaderTemplatePageTurnPolicy.normalizeForStore(paged, ReaderTemplatePageTurnPolicy.Mode.COVER.value)
        )
    }

    @Test
    fun `翻页取值为既有动画语义`() {
        assertEquals(-1, ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING.value)
        assertEquals(0, ReaderTemplatePageTurnPolicy.Mode.COVER.value)
        assertEquals(1, ReaderTemplatePageTurnPolicy.Mode.COVER_LINKED.value)
        assertEquals(2, ReaderTemplatePageTurnPolicy.Mode.SLIDE.value)
        assertEquals(3, ReaderTemplatePageTurnPolicy.Mode.SIMULATION.value)
        assertEquals(4, ReaderTemplatePageTurnPolicy.Mode.SCROLL.value)
        assertEquals(5, ReaderTemplatePageTurnPolicy.Mode.NO_ANIM.value)
    }

    @Test
    fun `清除记忆与删除模板同步清理`() {
        val store = prefs()
        store.rememberPageTurn("user.a", paged, ReaderTemplatePageTurnPolicy.Mode.NO_ANIM)
        assertTrue(store.forget("user.a"))
        assertNull(store.pageTurnMode("user.a"))
        assertFalse("重复删除返回 false", store.forget("user.a"))

        store.rememberPageTurn("user.a", paged, ReaderTemplatePageTurnPolicy.Mode.COVER)
        store.rememberPageTurn("user.b", paged, ReaderTemplatePageTurnPolicy.Mode.SLIDE)
        assertEquals(2, store.clear())
        assertEquals(0, prefs().size())
    }

    @Test
    fun `写入跟随设置等于清除该模板记忆`() {
        val store = prefs()
        store.rememberPageTurn("user.a", paged, ReaderTemplatePageTurnPolicy.Mode.COVER)
        assertTrue(store.rememberPageTurn("user.a", paged, ReaderTemplatePageTurnPolicy.Mode.FOLLOW_READING))
        assertNull("跟随设置不应留下记忆项", store.pageTurnMode("user.a"))
    }

    @Test
    fun `损坏文件不覆盖且不抛异常`() {
        val store = prefs()
        store.rememberPageTurn("user.a", paged, ReaderTemplatePageTurnPolicy.Mode.COVER)
        val file = File(root, "templatePreferences.json")
        file.writeText("{ 坏 JSON")

        assertEquals("损坏时按无记忆处理", 0, prefs().size())
        assertNull(prefs().pageTurnMode("user.a"))
        assertEquals("**不得覆盖**损坏文件", "{ 坏 JSON", file.readText())
        // 显式重写可恢复
        assertTrue(prefs().rememberPageTurn("user.b", paged, ReaderTemplatePageTurnPolicy.Mode.SLIDE))
        assertEquals(ReaderTemplatePageTurnPolicy.Mode.SLIDE, prefs().pageTurnMode("user.b"))
    }

    @Test
    fun `空标识不写入`() {
        assertFalse(prefs().rememberPageTurn("", paged, ReaderTemplatePageTurnPolicy.Mode.COVER))
        assertEquals(0, prefs().size())
    }

    @Test
    fun `原子写不留 staging 残留且缺失目录容错`() {
        val store = prefs()
        store.rememberPageTurn("user.a", paged, ReaderTemplatePageTurnPolicy.Mode.COVER)
        val leftovers = root.listFiles()!!.filter { it.name.startsWith(".") }
        assertTrue("原子写不得残留：$leftovers", leftovers.isEmpty())

        val missing = ReaderTemplatePreferences(File(root, "not-created"))
        assertNull(missing.pageTurnMode("user.a"))
        assertEquals(0, missing.size())
    }
}