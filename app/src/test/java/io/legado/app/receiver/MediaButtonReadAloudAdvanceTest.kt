package io.legado.app.receiver

import io.legado.app.model.ReadAloudChapterAdvance
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IF-14-M 配对用例：耳机键「上一章/下一章」在朗读运行中必须按**朗读推进**处理。
 *
 * 回归背景：`MediaButtonReceiver` 曾以 `ReadBook.moveToNextChapter(true)` 位置传参调用，
 * `true` 绑定 `upContent` ⇒ `fromReadAloud=false` ⇒ 跳章被当成手动导航，
 * 跟随状态机脱离且待续播索引不登记 ⇒ 跳章后不再起播（真机 2026-11-01 铁证）。
 */
class MediaButtonReadAloudAdvanceTest {

    @Test
    fun `朗读服务运行中耳机键跨章按朗读推进`() {
        assertEquals(true, ReadAloudChapterAdvance.fromReadAloudForService(true))
    }

    @Test
    fun `无朗读服务时耳机键跨章按手动导航`() {
        assertEquals(false, ReadAloudChapterAdvance.fromReadAloudForService(false))
    }

    @Test
    fun `耳机键跨章标记可登记待续播索引`() {
        val fromReadAloud = ReadAloudChapterAdvance.fromReadAloudForService(true)
        assertEquals(9, ReadAloudChapterAdvance.pendingIndex(fromReadAloud, false, 9))
    }
}
