package io.legado.app.help.tts.builtin

import kotlinx.coroutines.runBlocking
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

    @Test
    fun `未初始化时存储总览为全零`() {
        val storage = BuiltinTtsRuntime.storageBreakdown()
        assertEquals(0L, storage.modelBytes)
        assertEquals(0L, storage.voiceBytes)
        assertEquals(0L, storage.cacheBytes)
        assertEquals(0L, storage.totalBytes)
    }

    /**
     * 流水线预取的让行路径（2026-11-03）：预取失败必须静默返回 false，
     * 不得抛出打断播放循环——runtime 未就绪是最常见的失败态。
     */
    @Test
    fun `未初始化时预取静默失败`() = runBlocking {
        assertFalse(BuiltinTtsRuntime.prefetch("预取测试", "voice|测试", 1f))
    }
}
