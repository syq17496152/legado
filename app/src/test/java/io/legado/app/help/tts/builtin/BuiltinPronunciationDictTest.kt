package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 发音纠错（AD-06 / C-7 / T1.7）：最长匹配优先、替换后不再被二次命中、导入格式容错。
 */
class BuiltinPronunciationDictTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun dictFile() = temp.newFolder("pron").let { java.io.File(it, "dict.json") }

    @Test
    fun `最长匹配优先`() {
        val dict = BuiltinPronunciationDict(dictFile())
        dict.save(
            listOf(
                PronRule(from = "重", to = "虫", mode = "char"),
                PronRule(from = "重楼", to = "虫楼", mode = "char")
            )
        )
        assertEquals("虫楼很高", dict.transform("重楼很高"))
    }

    @Test
    fun `替换结果不会被后续规则二次命中`() {
        val dict = BuiltinPronunciationDict(dictFile())
        dict.save(
            listOf(
                PronRule(from = "A", to = "B", mode = "char"),
                PronRule(from = "B", to = "C", mode = "char")
            )
        )
        assertEquals("BC", dict.transform("AB"))
    }

    @Test
    fun `拼音规则默认不生效`() {
        val dict = BuiltinPronunciationDict(dictFile())
        dict.save(listOf(PronRule(from = "药老", to = "yao lao", mode = "pinyin")))
        assertEquals("药老", dict.transform("药老", allowPinyin = false))
        assertEquals("yao lao", dict.transform("药老", allowPinyin = true))
    }

    @Test
    fun `行式导入 合法条目生效 坏条目计入跳过`() {
        val dict = BuiltinPronunciationDict(dictFile())
        val result = dict.import("药老=妖老\n# 注释\n坏行\n重=虫")
        assertEquals(2, result.imported)
        assertEquals(1, result.skipped)
        assertEquals("妖老", dict.transform("药老"))
    }

    @Test
    fun `损坏词典文件不抛异常且变换原样返回`() {
        val file = dictFile()
        file.parentFile?.mkdirs()
        file.writeText("{not-json")
        val dict = BuiltinPronunciationDict(file)
        assertTrue(dict.rules().isEmpty())
        assertEquals("原文", dict.transform("原文"))
    }
}
