package io.legado.app.help.readaloud.role

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AD-16 门面契约：JVM（无 appCtx/无模型）必须安全回落，不得崩溃、不得谎报模型就绪。
 */
class NerTextEngineSignalsTest {

    @Test
    fun `JVM 无模型时 signals 返回未就绪且无信号`() {
        val signals = NerTextEngine.signals("张三说：“走吧。”")
        assertFalse(signals.modelReady)
        assertTrue(signals.cues.isEmpty())
        assertTrue(signals.persons.isEmpty())
    }

    @Test
    fun `空白段落不触发推理`() {
        assertFalse(NerTextEngine.signals("   ").modelReady)
    }

    @Test
    fun `模型未就绪时 isModelReady 为 false`() {
        assertFalse(NerTextEngine.isModelReady())
    }
}
