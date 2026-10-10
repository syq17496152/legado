package io.legado.app.model.localBook.epubcore.template

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板**稳定随机种子**单测（epub-md-rich-rendering 阶段 4.15 C4 配对）。
 *
 * 为什么值得逐条钉住：种子是"装饰看似随机但**可复现**"的唯一来源。它一旦变成
 * 时间戳/随机数（或对短字符串分布不佳的哈希），症状是"每次重排装饰都换一张画面"
 * ——观感像页面在乱动，且无法复现，截图/他人观感全对不上。
 */
class ReaderTemplateSeedTest {

    /** 测试侧**独立实现** FNV-1a（用于交叉校验算法未被替换；与生产实现不同源）。 */
    private fun expectedFnv1a(bookName: String, chapterTitle: String): Int {
        var hash = 2166136261L
        "$bookName\u0000$chapterTitle".forEach { char ->
            hash = hash xor char.code.toLong()
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return ((hash and 0x7FFFFFFF) % 10_000).toInt()
    }

    @Test
    fun `同章任何时候得到同一种子`() {
        // 契约核心：重排/回首页/读快照/切主题再回来 ⇒ 必须同值
        val first = ReaderTemplateSeed.of("样例书", "第一章")
        repeat(5) {
            assertEquals(first, ReaderTemplateSeed.of("样例书", "第一章"))
        }
    }

    @Test
    fun `不同章或不同书得到不同种子`() {
        val base = ReaderTemplateSeed.of("样例书", "第一章")
        assertNotEquals(base, ReaderTemplateSeed.of("样例书", "第二章"))
        assertNotEquals(base, ReaderTemplateSeed.of("另一本书", "第一章"))
        // 书名与章节名必须都被哈希吸收：交换两者不能得到同值（分隔符存在的意义）
        assertNotEquals(ReaderTemplateSeed.of("A", "B"), ReaderTemplateSeed.of("B", "A"))
    }

    @Test
    fun `种子恒为非负且在取值域内`() {
        // 作者按 `--rp-seed` 当随机索引使用 ⇒ 负值/越界会让模板取不到值（外观静默退回默认）
        val samples = listOf("", "a", "中文标题", "\uD83D\uDE00 emoji", "很长很长很长的章节标题".repeat(20))
        samples.forEach { title ->
            val seed = ReaderTemplateSeed.of("样例书", title)
            assertTrue("种子必须非负：$seed（章节=$title）", seed >= 0)
            assertTrue("种子必须落在取值域内：$seed", seed < ReaderTemplateSeed.Modulus)
        }
        assertEquals(
            "空书名空章节也必须给出确定值",
            ReaderTemplateSeed.of("", ""),
            ReaderTemplateSeed.of("", "")
        )
    }

    @Test
    fun `算法为 FNV-1a 定值实现（独立实现交叉校验）`() {
        // 防"换哈希算法"的静默行为变更（同章画面会全部换掉）；也防改用 `String.hashCode()`
        // （实现细节 + 对短字符串分布不佳）
        listOf(
            "样例书" to "第一章",
            "书" to "第 1 章",
            "A" to "B",
            "" to ""
        ).forEach { (book, chapter) ->
            assertEquals(
                "种子算法必须等价于 FNV-1a 取模（book=$book, chapter=$chapter）",
                expectedFnv1a(book, chapter),
                ReaderTemplateSeed.of(book, chapter)
            )
        }
    }

    @Test
    fun `运行时把种子注入为属性与变量`() {
        val js = SourceFileProbe.assetRawText("md/template-runtime.js")
        assertTrue("必须注入 --rp-seed 变量", js.contains("--rp-seed"))
        assertTrue("必须注入 data-rp-seed 属性（CSS 选择器可用）", js.contains("data-rp-seed"))
        assertTrue("种子缺失时不得写坏（按需注入）", js.contains("config.seed !== undefined"))
    }
}