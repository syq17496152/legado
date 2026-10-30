package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切分与停顿（AD-19 / T8.3）：拼回等于原文、短块合并、引导语粘合、停顿四档。
 */
class BuiltinTextChunkerTest {

    @Test
    fun `切分不丢字且拼接等于原文`() {
        val text = "第一句。第二句！这里还有很长的一段话，需要被正确切分并且不能丢字。\n第三段开始"
        val chunks = BuiltinTextChunker.split(text)
        // 允许把换行归一为停顿（引擎不接收原始换行），但正文一个字都不能少
        val joined = chunks.joinToString("") { it.text }.replace("\n", "")
        assertEquals(text.replace("\n", ""), joined)
        assertTrue(chunks.isNotEmpty())
    }

    @Test
    fun `短块与下一块合并`() {
        val chunks = BuiltinTextChunker.split("好的。这一句话足够长了，用来验证合并逻辑。")
        val first = chunks.first().text
        assertTrue("短块未合并：$first", first.trim().length >= BuiltinTextChunker.MIN_CHUNK_CHARS)
    }

    @Test
    fun `引导语与引号开头合并`() {
        val chunks = BuiltinTextChunker.split("他沉声说道：\n“你走吧，别再回来了。”")
        val cueChunk = chunks.first { it.text.contains("说道") }
        assertTrue("引导语未与引号粘合：${cueChunk.text}", cueChunk.text.contains("“"))
    }

    @Test
    fun `停顿四档取值正确`() {
        assertEquals(BuiltinTextChunker.PAUSE_NEWLINE_MS, BuiltinTextChunker.pauseOf("行尾\n"))
        assertEquals(BuiltinTextChunker.PAUSE_NEWLINE_MS, BuiltinTextChunker.pauseOf("句子。\n"))
        assertEquals(BuiltinTextChunker.PAUSE_SENTENCE_MS, BuiltinTextChunker.pauseOf("句子。"))
        assertEquals(BuiltinTextChunker.PAUSE_COMMA_MS, BuiltinTextChunker.pauseOf("短语，"))
        assertEquals(BuiltinTextChunker.PAUSE_OTHER_MS, BuiltinTextChunker.pauseOf("普通字"))
    }

    @Test
    fun `超长段落按句内标点再切`() {
        val long = "这是一段很长的文字，".repeat(12) + "结束。"
        val chunks = BuiltinTextChunker.split(long)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= BuiltinTextChunker.MAX_CHARS_WITHOUT_QUOTE + 20 })
    }

    @Test
    fun `最后一块无停顿`() {
        val chunks = BuiltinTextChunker.split("甲。乙。")
        assertEquals(0, chunks.last().pauseMs)
    }
}
