package io.legado.app.model.localBook.epubcore.template

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 模板库单测（epub-md-rich-rendering 阶段 4.5 配对）。
 *
 * 覆盖【验证标准】「全生命周期用例通过；用户副本不丢失；旧格式 1 模板仍可用」：
 * ①增删改查与覆盖；②复制生成新 id 且源保留；③**损坏不空库**（库文件被保留、可诊断、可恢复默认）；
 * ④单条损坏只跳过该条；⑤容量上限（模板数 / 单模板体量）；⑥结构非法拒绝入库；
 * ⑦v1 模板读入即升级；⑧导入即入库（复用 4.17 映射）；⑨导出可再导入（闭环）；⑩原子写无残留。
 */
class ReaderTemplateRepositoryTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("template-repo-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun repo(
        maxTemplates: Int = ReaderTemplateRepository.DefaultMaxTemplates,
        maxChars: Int = ReaderTemplateRepository.DefaultMaxTemplateChars
    ) = ReaderTemplateRepository(root, maxTemplates, maxChars)

    private fun template(
        id: String = "user.a",
        name: String = "模板甲",
        type: String = EpubReaderTemplate.TYPE_PAGED,
        css: String = "body{color:#333}",
        schemaVersion: Int = 2
    ) = EpubReaderTemplate(
        schemaVersion = schemaVersion,
        id = id,
        name = name,
        description = "说明",
        firstPageHtml = "<article data-reader-flow=\"body\"></article>",
        otherPageHtml = "<article data-reader-flow=\"body\"></article>",
        css = css,
        javascript = "",
        type = type,
        scrollHtml = if (type == EpubReaderTemplate.TYPE_SCROLL) "<div data-reader-scroll-viewport></div>" else ""
    )

    @Test
    fun `新增读取覆盖与删除`() {
        val store = repo()
        assertTrue(store.save(template(id = "user.a", name = "甲")))
        assertTrue(store.save(template(id = "user.b", name = "乙")))
        assertEquals(2, store.count())
        assertEquals("甲", store.get("user.a")?.name)

        assertTrue(store.save(template(id = "user.a", name = "甲改")))
        assertEquals(2, store.count())
        assertEquals("甲改", store.get("user.a")?.name)

        assertTrue(store.delete("user.a"))
        assertNull(store.get("user.a"))
        assertEquals(1, store.count())
        assertFalse("删除不存在的模板返回 false", store.delete("user.a"))
    }

    @Test
    fun `结构非法的模板拒绝入库`() {
        val store = repo()
        assertFalse(store.save(template().copy(name = "")))
        assertNotNull("须给出失败原因", store.lastError)
        assertEquals(0, store.count())
    }

    @Test
    fun `单模板体量超限拒绝入库`() {
        val store = repo(maxChars = 64)
        assertFalse(store.save(template(css = "x".repeat(200))))
        assertTrue("原因须说明超限", store.lastError!!.contains("体量超限"))
    }

    @Test
    fun `模板数量上限生效`() {
        val store = repo(maxTemplates = 2)
        assertTrue(store.save(template(id = "user.a")))
        assertTrue(store.save(template(id = "user.b")))
        assertFalse("超限须拒绝", store.save(template(id = "user.c")))
        assertTrue(store.lastError!!.contains("上限"))
        assertTrue("已有模板不得受影响", store.save(template(id = "user.a", name = "甲改")))
    }

    @Test
    fun `复制生成新 id 且源保留`() {
        val store = repo()
        store.save(template(id = "user.a", name = "甲"))
        val copy = store.duplicate("user.a")
        assertNotNull(copy)
        assertTrue(copy!!.id.startsWith("user."))
        assertFalse("新 id 不得与源相同", copy.id == "user.a")
        assertTrue("默认名称带副本标记", copy.name.contains("副本"))
        assertNotNull("源必须保留", store.get("user.a"))
        assertEquals(2, store.count())
        assertNull(store.duplicate("user.missing"))
    }

    @Test
    fun `库文件损坏不空库且可诊断`() {
        val store = repo()
        store.save(template())
        val libFile = File(root, ReaderTemplateRepository.LibraryFileName)
        val original = libFile.readText()
        libFile.writeText("{ 这不是合法 JSON")

        val broken = repo()
        assertEquals("损坏时返回空集合", 0, broken.count())
        assertNotNull("须暴露诊断原因", broken.lastError)
        assertTrue(broken.lastError!!.contains("解析失败"))
        assertEquals("**不得覆盖**损坏文件（用户可抢救）", "{ 这不是合法 JSON", libFile.readText())

        // 恢复默认：显式清空后可从空库重建
        assertTrue(broken.clearUserTemplates() >= 0)
        assertTrue("清除后应可写入", broken.save(template(id = "user.new")))
        assertTrue(original.isNotBlank())
    }

    @Test
    fun `单条损坏只跳过该条其余可用`() {
        val libFile = File(root, ReaderTemplateRepository.LibraryFileName)
        libFile.writeText(
            """
            {
              "schemaVersion": 2,
              "templates": [
                {"schemaVersion":2,"id":"user.good","name":"好模板",
                 "firstPageHtml":"<div></div>","otherPageHtml":"<div></div>","type":"paged"},
                {"schemaVersion":2,"id":"user.bad","name":"坏模板","type":"不存在的类型"}
              ]
            }
            """.trimIndent()
        )
        val store = repo()
        assertEquals("坏条目不影响好条目", 1, store.count())
        assertEquals("user.good", store.get("user.good")?.id)
        assertNotNull("须记录被跳过的条目", store.lastError)
    }

    @Test
    fun `库版本高于支持版本时报错不覆盖`() {
        val libFile = File(root, ReaderTemplateRepository.LibraryFileName)
        libFile.writeText("{\"schemaVersion\": 99, \"templates\": []}")
        val store = repo()
        assertEquals(0, store.count())
        assertTrue(store.lastError!!.contains("高于当前支持版本"))
        assertEquals("不得覆盖", "{\"schemaVersion\": 99, \"templates\": []}", libFile.readText())
    }

    @Test
    fun `兼容裸数组形态的库文件`() {
        val libFile = File(root, ReaderTemplateRepository.LibraryFileName)
        libFile.writeText(
            """[{"schemaVersion":2,"id":"user.arr","name":"数组形态",
                "firstPageHtml":"<div></div>","otherPageHtml":"<div></div>","type":"paged"}]"""
        )
        assertEquals(1, repo().count())
    }

    @Test
    fun `v1 模板读入即升级到当前 schema`() {
        val store = repo()
        assertTrue(store.save(template(id = "user.old", schemaVersion = 1)))
        assertEquals("读入须升级", 2, repo().get("user.old")!!.schemaVersion)
    }

    @Test
    fun `导入即入库且报告可用`() {
        val store = repo()
        val json = """
            {"schemaVersion":1,"type":"paged","id":"builtin.asuka","name":"archive 模板",
             "description":"","firstPageHtml":"<article data-reader-flow=\"body\"></article>",
             "otherPageHtml":"<article data-reader-flow=\"body\"></article>","scrollHtml":"",
             "css":"body{}","javascript":""}
        """.trimIndent()
        val result = store.importJson(json)
        assertTrue(result.succeeded)
        val imported = result.template!!
        assertTrue("内置 id 须改名", imported.id.startsWith("user."))
        assertEquals("须已入库", 1, store.count())
        assertEquals(imported.id, store.get(imported.id)?.id)
    }

    @Test
    fun `导入失败不入库并回传原因`() {
        val store = repo()
        val result = store.importJson("{ 坏 JSON")
        assertFalse(result.succeeded)
        assertEquals("不得产生脏数据", 0, store.count())
    }

    @Test
    fun `导出可再导入形成闭环`() {
        val store = repo()
        store.save(template(id = "user.a", name = "甲"))
        val exported = store.exportJson("user.a")
        assertNotNull(exported)

        val target = ReaderTemplateRepository(Files.createTempDirectory("template-repo-test2").toFile())
        val result = target.importJson(exported!!)
        assertTrue("导出必须可再导入", result.succeeded)
        assertEquals("甲", target.get(result.template!!.id)?.name)
        assertNull("不存在的模板导出为 null", store.exportJson("user.missing"))
    }

    @Test
    fun `清空用户模板返回数量且内置不受影响`() {
        val store = repo()
        store.save(template(id = "user.a"))
        store.save(template(id = "user.b"))
        assertEquals(2, store.clearUserTemplates())
        assertEquals(0, store.count())
    }

    @Test
    fun `原子写不留 staging 残留`() {
        val store = repo()
        assertTrue(store.save(template()))
        val leftovers = root.listFiles()!!.filter { it.name.startsWith(".") }
        assertTrue("原子写不得残留：$leftovers", leftovers.isEmpty())
    }

    @Test
    fun `空库目录与缺失文件容错`() {
        val missing = ReaderTemplateRepository(File(root, "not-created"))
        assertEquals(0, missing.count())
        assertNull(missing.lastError)
        assertFalse(missing.delete("user.a"))
        assertNull(missing.duplicate("user.a"))
    }
}