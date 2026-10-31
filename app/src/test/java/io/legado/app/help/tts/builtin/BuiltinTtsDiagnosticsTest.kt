package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败降级矩阵（T3.5）：每条检测点 → 文案 → 动作；正常时只给"运行正常"。
 */
class BuiltinTtsDiagnosticsTest {

    @Test
    fun `正常档位只给一条正常结论`() {
        val findings = BuiltinTtsDiagnostics.evaluate(
            BuiltinTtsDiagnostics.Input(state = BuiltinEngineState.READY, lastRtf = 0.8f)
        )
        assertEquals(1, findings.size)
        assertEquals(BuiltinTtsDiagnostics.Level.INFO, findings.first().level)
        assertEquals("[正常] 运行正常", BuiltinTtsDiagnostics.render(findings))
    }

    @Test
    fun `未下载与损坏都给出可操作动作`() {
        val none = BuiltinTtsDiagnostics.evaluate(
            BuiltinTtsDiagnostics.Input(state = BuiltinEngineState.NONE)
        )
        assertTrue(none.any { it.code == "model_none" && it.action.contains("下载") })
        val corrupt = BuiltinTtsDiagnostics.evaluate(
            BuiltinTtsDiagnostics.Input(state = BuiltinEngineState.CORRUPT)
        )
        assertTrue(corrupt.any { it.code == "model_corrupt" && it.action.contains("重新") })
    }

    @Test
    fun `ABI 不支持与低内存给出错误或警告`() {
        val abi = BuiltinTtsDiagnostics.evaluate(
            BuiltinTtsDiagnostics.Input(state = BuiltinEngineState.READY, abiSupported = false)
        )
        assertTrue(abi.any { it.code == "abi" && it.level == BuiltinTtsDiagnostics.Level.ERROR })
        val mem = BuiltinTtsDiagnostics.evaluate(
            BuiltinTtsDiagnostics.Input(
                state = BuiltinEngineState.READY,
                requiresReference = true,
                memoryMb = 300
            )
        )
        assertTrue(mem.any { it.code == "memory_tight" })
    }

    @Test
    fun `RTF 过高与最近错误都提示`() {
        val findings = BuiltinTtsDiagnostics.evaluate(
            BuiltinTtsDiagnostics.Input(
                state = BuiltinEngineState.READY,
                lastRtf = 1.5f,
                lastError = "合成失败"
            )
        )
        assertTrue(findings.any { it.code == "rtf_high" })
        assertTrue(findings.any { it.code == "last_error" })
        val text = BuiltinTtsDiagnostics.render(findings)
        assertTrue(text.contains("RTF 1.50") || text.contains("RTF 1.5"))
        assertTrue(text.contains("合成失败"))
    }

    @Test
    fun `首选档不可用回落时给出警告`() {
        val findings = BuiltinTtsDiagnostics.evaluate(
            BuiltinTtsDiagnostics.Input(
                state = BuiltinEngineState.READY,
                preferredId = "clone",
                preferredTitle = "高质克隆档",
                activeId = "fast",
                activeTitle = "极速单声档"
            )
        )
        assertEquals(1, findings.size)
        val fallback = findings.first { it.code == "fallback" }
        assertEquals(BuiltinTtsDiagnostics.Level.WARN, fallback.level)
        assertTrue(BuiltinTtsDiagnostics.render(findings).contains("已回落到「极速单声档」"))
    }

    @Test
    fun `首选档即当前档时不报回落`() {
        val findings = BuiltinTtsDiagnostics.evaluate(
            BuiltinTtsDiagnostics.Input(
                state = BuiltinEngineState.READY,
                preferredId = "fast",
                preferredTitle = "极速单声档",
                activeId = "fast",
                activeTitle = "极速单声档"
            )
        )
        assertEquals(1, findings.size)
        assertEquals("ok", findings.first().code)
    }
}
