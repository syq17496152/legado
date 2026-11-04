package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TTS 朗读流水线预取口径（G-01 配对 `ReadAloudPrefetch` / `TTSReadAloudService` 改动）。
 *
 * 真机取证（2026-11-03）：旧实现等整段播完才预取下一段 ⇒ 段间静默 1~2s，用户听到"一顿一顿"。
 * 本组用例锁定修复后的时机契约：内置引擎才预取、等当前段出声后再合成、音色兜底与主循环一致。
 */
class ReadAloudPrefetchTest {

    @Test
    fun `仅内置引擎激活时才预取`() {
        assertTrue(ReadAloudPrefetch.shouldPrefetch(true))
        assertFalse(ReadAloudPrefetch.shouldPrefetch(false))
    }

    @Test
    fun `当前段未出声时等待播放起点`() {
        assertTrue(ReadAloudPrefetch.shouldWaitForPlaybackStart(false, 0L))
        assertTrue(ReadAloudPrefetch.shouldWaitForPlaybackStart(false, 1_950L))
    }

    @Test
    fun `当前段已出声后立即预取`() {
        assertFalse(ReadAloudPrefetch.shouldWaitForPlaybackStart(true, 0L))
        assertFalse(ReadAloudPrefetch.shouldWaitForPlaybackStart(true, 2_000L))
    }

    @Test
    fun `等待超过上限后 fail-open 继续预取`() {
        // 引擎可能不回调 onStart：不能因此永久不预取，超窗后照常尝试
        assertTrue(
            ReadAloudPrefetch.shouldWaitForPlaybackStart(
                false, ReadAloudPrefetch.PLAYBACK_START_WAIT_MS - 1
            )
        )
        assertFalse(
            ReadAloudPrefetch.shouldWaitForPlaybackStart(
                false, ReadAloudPrefetch.PLAYBACK_START_WAIT_MS
            )
        )
    }

    @Test
    fun `预取音色优先用模板解析结果`() {
        assertEquals("张三音色", ReadAloudPrefetch.resolvePrefetchVoice("张三音色", "旁白音色"))
    }

    @Test
    fun `模板无音色时回落旁白音色`() {
        // 与主循环同一兜底口径：否则缓存键对不上，预取永不命中
        assertEquals("旁白音色", ReadAloudPrefetch.resolvePrefetchVoice("", "旁白音色"))
        assertEquals("", ReadAloudPrefetch.resolvePrefetchVoice("", ""))
    }

    /**
     * A2：预取语速必须与播放期同算 —— 分层生效时用 base×layer；基准未知/模板显式 rate 时按各自口径。
     */
    @Test
    fun `预取语速与播放期同算`() {
        val plain = io.legado.app.help.readaloud.casting.CastingProsody()
        // 基准已知：0.95 层 ⇒ 0.95
        assertEquals(
            0.95f,
            ReadAloudPrefetch.prefetchRate(plain, 0.95f, 1.0f, fallback = 1.0f),
            0.0001f
        )
        // 基准未知（跟系统/自定义引擎语速）⇒ 回落全局，不做分层
        assertEquals(
            1.0f,
            ReadAloudPrefetch.prefetchRate(plain, 0.95f, rateBase = null, fallback = 1.0f),
            0.0001f
        )
        // 模板显式 rate 优先
        val explicit = io.legado.app.help.readaloud.casting.CastingProsody(rate = 1.2f)
        assertEquals(
            1.2f,
            ReadAloudPrefetch.prefetchRate(explicit, 0.95f, 1.0f, fallback = 1.0f),
            0.0001f
        )
    }
}
