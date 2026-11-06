package io.legado.app.help.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自检结果契约（`MSG_BENCH`）。
 *
 * 两条被真实踩到过的红线：
 * - **0 tok/s 不能报成功**：加载失败/空输出时错误码可能为空，只判错误码会让 UI 显示"自检完成"；
 * - **失败原因必须带出**：只说"自检失败"用户无从下手（与 E7「静默跳过」同类）。
 */
class LlmBenchContractTest {

    @Test
    fun `有速度且无错误才算成功`() {
        val ok = LlmBenchResult.of(
            loadMs = 32_870L,
            generateMs = 6_800L,
            tokenCount = 64,
            tokPerSec = 9.48f,
            errorCode = "",
            message = ""
        )
        assertTrue(ok.ok)
        assertEquals(32_870L, ok.loadMs)
        assertEquals(64, ok.tokenCount)
        assertEquals(9.48f, ok.tokPerSec)
    }

    @Test
    fun `零速度即使没有错误码也不算成功`() {
        val zero = LlmBenchResult.of(
            loadMs = 1_000L,
            generateMs = 0L,
            tokenCount = 0,
            tokPerSec = 0f,
            errorCode = "",
            message = "空输出"
        )
        assertFalse("0 tok/s 是没跑通，不能显示为成功", zero.ok)
    }

    @Test
    fun `有错误码即失败`() {
        val failed = LlmBenchResult.of(
            loadMs = 5_000L,
            generateMs = 0L,
            tokenCount = 0,
            tokPerSec = 0f,
            errorCode = "TIMEOUT",
            message = "加载超时"
        )
        assertFalse(failed.ok)
        assertEquals("TIMEOUT", failed.errorCode)
    }

    @Test
    fun `failure 工厂永远不是成功且保留错误码`() {
        val busy = LlmBenchResult.failure(LlmIpc.ERROR_BAD_JOB, "推理队列忙")
        assertFalse(busy.ok)
        assertEquals(LlmIpc.ERROR_BAD_JOB, busy.errorCode)
        assertEquals("推理队列忙", busy.message)
    }

    /**
     * 自检必须由推理进程承载：消息号与既有协议不冲突，且客户端超时要覆盖「加载 + 生成」两段。
     */
    @Test
    fun `bench 消息号不冲突且超时覆盖两段`() {
        val used = listOf(
            LlmIpc.MSG_STATUS, LlmIpc.MSG_LOAD, LlmIpc.MSG_GENERATE,
            LlmIpc.MSG_CANCEL, LlmIpc.MSG_RELEASE
        )
        assertFalse("MSG_BENCH 不得与既有消息号撞号", LlmIpc.MSG_BENCH in used)
        assertTrue(
            "自检超时必须 ≥ 加载 + 生成两段之和",
            LlmClient.BENCH_TIMEOUT_MS >= LlmRuntime.LOAD_TIMEOUT_MS + LlmRuntime.GENERATE_TIMEOUT_MS
        )
    }
}
