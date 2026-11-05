package io.legado.app.help.llm

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.5 客户端超时契约（模拟器 E2E 实证）：
 * `:llm` 进程冷启要加载 native 库（实测 20s+），连接超时若只有 1~2s，首次 bind 必失败并把
 * 重试额度烧光 ⇒ 剧本永远"本地模型未就绪"。这里把下限写死，防止以后被"优化"回去。
 */
class LlmClientContractTest {

    @Test
    fun `连接超时必须覆盖推理进程冷启`() {
        assertTrue(
            "CONNECT_TIMEOUT_MS=${LlmClient.CONNECT_TIMEOUT_MS} 太短，冷启加载 native 库会超时",
            LlmClient.CONNECT_TIMEOUT_MS >= 10_000L
        )
    }

    @Test
    fun `加载与生成超时必须比运行时自身超时更宽`() {
        // 跨进程多一次往返，外层超时若等于内层，会先于服务回执触发"无回执"
        assertTrue(LlmClient.LOAD_TIMEOUT_MS > LlmRuntime.LOAD_TIMEOUT_MS)
        assertTrue(LlmClient.GENERATE_TIMEOUT_MS > LlmRuntime.GENERATE_TIMEOUT_MS)
    }

    @Test
    fun `状态查询超时保持短平快`() {
        // 状态查询只用于 ready 判定，不该拖住生成路径
        assertTrue(LlmClient.STATUS_TIMEOUT_MS <= LlmClient.CONNECT_TIMEOUT_MS)
    }
}
