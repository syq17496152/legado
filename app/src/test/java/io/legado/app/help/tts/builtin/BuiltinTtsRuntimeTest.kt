package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 运行时空态安全（G-01 配对 BuiltinTtsRuntime 改动）：
 * TextToSpeechService 在 `super.onCreate()` 内就会回调语言查询，运行时空态必须返回 NONE 而非抛异常。
 */
class BuiltinTtsRuntimeTest {

    @Test
    fun `未初始化时状态查询不抛异常且返回 NONE`() {
        assertFalse(BuiltinTtsRuntime.isInitialized())
        assertEquals(BuiltinEngineState.NONE, BuiltinTtsRuntime.state())
        assertFalse(BuiltinTtsRuntime.isReady())
        assertEquals(BuiltinEngineState.NONE, BuiltinTtsRuntime.stats().state)
    }
}
