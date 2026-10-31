package io.legado.app.help.readaloud.http

import io.legado.app.help.readaloud.casting.CastingMatchType
import io.legado.app.help.readaloud.casting.CastingRule
import io.legado.app.help.readaloud.casting.CastingRuleSet
import io.legado.app.help.readaloud.casting.CastingTag
import io.legado.app.help.readaloud.casting.TtsTagSplitter
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T8.6 段级换声入队器（行为断言）：切段 → 每段独立取流/入队 → 末段标记正确 → 对白剥离引号。
 * 音色解析注入假实现（不依赖 Room），断言"同一段内不同角色取到不同 toneID"。
 */
class HttpSegmentEnqueuerTest {

    private class Recorder : HttpSegmentEnqueuer.Hooks {
        val media = mutableListOf<String>()
        val fetched = mutableListOf<Pair<String, String>>()
        val written = mutableListOf<String>()
        val silent = mutableListOf<String>()
        var existingAll = false
        override fun fileNameOf(text: String, voiceId: String) = "k_${voiceId}_$text"
        override fun hasFile(fileName: String) = existingAll
        override fun writeFile(fileName: String, stream: InputStream) {
            written.add(fileName)
        }
        override fun writeSilent(fileName: String) {
            silent.add(fileName)
        }
        override suspend fun fetch(text: String, toneID: String): InputStream? {
            fetched.add(text to toneID)
            return "pcm".byteInputStream()
        }
        override fun addMedia(fileName: String) {
            media.add(fileName)
        }
    }

    private fun quoteRuleSet() = CastingRuleSet(
        templateId = "t",
        name = "test",
        rules = listOf(
            CastingRule(tag = CastingTag.DIALOGUE, matchType = CastingMatchType.BUILTIN_QUOTE)
        )
    )

    @Test
    fun `对话段按角色分别取流 只有末段标记 l1`() = runBlocking {
        val rec = Recorder()
        val ruleSet = quoteRuleSet()
        val paragraph = "\u5F20\u4E09\u8BF4\uFF1A\u201C\u8D70\u5427\u3002\u201D\u5929\u5FEB\u9ED1\u4E86\u3002"
        val segments = TtsTagSplitter.splitParagraph(0, paragraph, ruleSet.rules)
        val enqueuer = HttpSegmentEnqueuer("book", "roleBook", 0, rec) { tag, _, _ ->
            if (tag == CastingTag.DIALOGUE) "male" else "narrator"
        }
        enqueuer.enqueue(0, paragraph, segments, ruleSet)
        // 每段一条媒体项，且只有末段是 l1
        assertEquals(segments.size, rec.media.size)
        assertTrue("首段必须是 l0：${rec.media.first()}", rec.media.first().startsWith("sv_0_l0_"))
        assertTrue("末段必须是 l1：${rec.media.last()}", rec.media.last().startsWith("sv_0_l1_"))
        // 音色分发：对白段走角色音色，旁白段走旁白音色（同段不同角色不串音）
        assertTrue("对白段必须用角色音色：${rec.fetched}", rec.fetched.any { it.second == "male" })
        assertTrue("旁白段必须用旁白音色：${rec.fetched}", rec.fetched.any { it.second == "narrator" })
        // 对白送引擎前剥离引号
        assertTrue(
            "对白不应带引号：${rec.fetched}",
            rec.fetched.none { it.first.contains('\u201C') || it.first.contains('\u201D') }
        )
        // 取到流即落文件，不产生静音替代
        assertFalse("不应有静音替代", rec.silent.isNotEmpty())
    }

    @Test
    fun `已存在的段缓存不重复取流 但仍按序入队`() = runBlocking {
        val rec = Recorder().apply { existingAll = true }
        val ruleSet = quoteRuleSet()
        val paragraph = "\u4E09\u5F20\u4E32\u8D77\u4F86\u8BF4\uFF1A\u201C\u597D\u3002\u201D"
        val segments = TtsTagSplitter.splitParagraph(0, paragraph, ruleSet.rules)
        val enqueuer = HttpSegmentEnqueuer("book", "roleBook", 0, rec) { _, _, _ -> "male" }
        enqueuer.enqueue(0, paragraph, segments, ruleSet)
        assertEquals(0, rec.fetched.size)
        assertEquals(segments.size, rec.media.size)
    }
}
