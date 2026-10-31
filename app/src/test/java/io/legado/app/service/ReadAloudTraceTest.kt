package io.legado.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T3.1/T3.3 预取埋点与预热门的纯函数口径（真机 gap 指标的日志契约）。
 */
class ReadAloudTraceTest {

    @Test
    fun `预热门只在激活且未预热时打开`() {
        assertTrue(ReadAloudTrace.shouldWarm(active = true, warmed = false))
        // 未激活 / 已预热过都不再同步预热（避免每段都阻塞等合成）
        assertFalse(ReadAloudTrace.shouldWarm(active = false, warmed = false))
        assertFalse(ReadAloudTrace.shouldWarm(active = true, warmed = true))
    }

    @Test
    fun `段间隔日志文案含毫秒与段落标识`() {
        assertEquals(
            "multiRole 段间隔 gap=42ms utteranceId=io.legado.app.mr_1_0",
            ReadAloudTrace.gapLogText(42, "io.legado.app.mr_1_0")
        )
        // 负值（跨段时钟回绕/极快切段）也如实打印，便于排查
        assertEquals(
            "multiRole 段间隔 gap=-3ms utteranceId=x",
            ReadAloudTrace.gapLogText(-3, "x")
        )
    }

    @Test
    fun `预取音色回落口径与主循环一致`() {
        assertEquals("voice_a|甲", ReadAloudPrefetch.resolvePrefetchVoice("voice_a|甲", "narration|旁白"))
        // 模板解析不出 toneID（旁白/对白段）必须回落旁白音色，否则预取缓存键永远对不上
        assertEquals("narration|旁白", ReadAloudPrefetch.resolvePrefetchVoice("", "narration|旁白"))
        assertEquals("", ReadAloudPrefetch.resolvePrefetchVoice("", ""))
    }
}
