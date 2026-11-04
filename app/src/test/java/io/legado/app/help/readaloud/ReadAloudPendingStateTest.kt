package io.legado.app.help.readaloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R15：冷启动朗读待播状态机契约。
 *
 * 关键防回归点：
 * 1. 章节未就绪时必须**保留**标记（否则正文永远等不到补播）；
 * 2. 补播只发生**一次**（同一回调重入不得双播）；
 * 3. 章节槽清空（切书/重载）后标记作废（否则切书回来会莫名开读）。
 */
class ReadAloudPendingStateTest {

    @Test
    fun initialNotPending() {
        val state = ReadAloudPendingState()
        assertFalse(state.isPending)
        assertFalse(state.consumeIfPending(chapterReady = true))
    }

    @Test
    fun markPending_thenConsumeOnce() {
        val state = ReadAloudPendingState()
        state.markPending()
        assertTrue(state.isPending)
        assertTrue("章节就绪时应补播一次", state.consumeIfPending(chapterReady = true))
        assertFalse("补播后必须清标记，防重入双播", state.isPending)
        assertFalse("同一标记不得再次补播", state.consumeIfPending(chapterReady = true))
    }

    @Test
    fun consumeBeforeChapterReady_keepsPending() {
        val state = ReadAloudPendingState()
        state.markPending()
        assertFalse("正文仍为空时不得消耗标记", state.consumeIfPending(chapterReady = false))
        assertTrue("标记必须保留，等下一次排版完成", state.isPending)
        assertTrue(state.consumeIfPending(chapterReady = true))
    }

    @Test
    fun cancel_dropsPending() {
        val state = ReadAloudPendingState()
        state.markPending()
        state.cancel()
        assertFalse(state.isPending)
        assertFalse(state.consumeIfPending(chapterReady = true))
    }

    /**
     * 3.6：计划缓存的 key 必须带剧本指纹 —— 否则「AI 剧本更新后，页面仍显示旧的角色分配」，
     * 而用户完全无从判断（看起来就是"剧本没生效"）。
     */
    @Test
    fun planKeyChangesWithChapterScript() {
        val keyWithoutScript = ReadAloudSpeechPlanner.planKey(
            bookUrl = "book-1",
            chapter = null,
            cues = emptyList(),
            roleCacheKey = "cache-1"
        )
        val keyWithScript = ReadAloudSpeechPlanner.planKey(
            bookUrl = "book-1",
            chapter = null,
            cues = emptyList(),
            roleCacheKey = "cache-1",
            scriptKey = "hash|mixed|1000|6"
        )
        val keyWithOtherScript = ReadAloudSpeechPlanner.planKey(
            bookUrl = "book-1",
            chapter = null,
            cues = emptyList(),
            roleCacheKey = "cache-1",
            scriptKey = "hash2|mixed|1000|6"
        )
        assertNotEquals(keyWithoutScript, keyWithScript)
        assertNotEquals(keyWithScript, keyWithOtherScript)
        assertTrue(keyWithScript.contains("hash|mixed|1000|6"))
        assertFalse(keyWithoutScript.contains("mixed"))
        assertEquals(
            ReadAloudSpeechPlanner.planKey("book-1", null, emptyList(), "cache-1"),
            keyWithoutScript
        )
    }

    /**
     * A2 语速分层：旁白/主角 1.0、老人儿童 0.95、激烈短句 1.05；±5% 硬上限。
     * 基准未知（跟系统语速/自定义引擎参数）时绝不猜，原样返回模板韵律。
     */
    @Test
    fun rateLayerIsBoundedAndRespectsUnknownBase() {
        assertEquals(ReadAloudRateLayer.AGE_OLD, ReadAloudRateLayer.ageBandOf("60岁", ""))
        assertEquals(ReadAloudRateLayer.AGE_OLD, ReadAloudRateLayer.ageBandOf("", "老者"))
        assertEquals(ReadAloudRateLayer.AGE_CHILD, ReadAloudRateLayer.ageBandOf("十二岁", ""))
        assertEquals(ReadAloudRateLayer.AGE_CHILD, ReadAloudRateLayer.ageBandOf("", "少女"))
        assertEquals("", ReadAloudRateLayer.ageBandOf("三十岁", "书生"))

        assertTrue(ReadAloudRateLayer.isIntenseSpeech("“住手！”"))
        assertFalse(ReadAloudRateLayer.isIntenseSpeech("“你来了。”"))
        assertFalse("长句不算激烈对白", ReadAloudRateLayer.isIntenseSpeech("“岂有此理，我一定要讨个说法，你们等着瞧！”"))

        assertEquals(ReadAloudRateLayer.NORMAL, ReadAloudRateLayer.layerOf(true, 2, ReadAloudRateLayer.AGE_OLD, true), 0.0001f)
        assertEquals(ReadAloudRateLayer.SOFT, ReadAloudRateLayer.layerOf(false, 0, ReadAloudRateLayer.AGE_CHILD, false), 0.0001f)
        assertEquals(ReadAloudRateLayer.INTENSE, ReadAloudRateLayer.layerOf(false, 0, "", true), 0.0001f)
        assertEquals(ReadAloudRateLayer.NORMAL, ReadAloudRateLayer.layerOf(false, 2, "", false), 0.0001f)
        // 分层倍率本身夹在 ±5%
        assertTrue(ReadAloudRateLayer.layerOf(false, 0, "", true) <= 1.05f + 0.0001f)

        val plain = io.legado.app.help.readaloud.casting.CastingProsody()
        assertSame("基准未知不得改韵律", plain, ReadAloudRateLayer.mergeProsody(plain, 0.95f, null))
        val explicit = io.legado.app.help.readaloud.casting.CastingProsody(rate = 1.2f)
        assertSame("用户模板显式 rate 优先", explicit, ReadAloudRateLayer.mergeProsody(explicit, 0.95f, 1.0f))
        assertEquals(0.95f, ReadAloudRateLayer.mergeProsody(plain, 0.95f, 1.0f).rate, 0.0001f)
        assertEquals(1.425f, ReadAloudRateLayer.mergeProsody(plain, 0.95f, 1.5f).rate, 0.0001f)
    }
}
