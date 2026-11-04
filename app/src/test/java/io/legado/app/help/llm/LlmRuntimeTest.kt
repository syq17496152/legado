package io.legado.app.help.llm

import com.arm.aichat.InferenceEngine
import com.arm.aichat.UnsupportedArchitectureException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.2 LlmRuntime：状态机 + 单队列 + 错误码映射。
 * 用假 binding 覆盖「加载成功/架构不支持/未加载就生成/空输出/超时」五类真实失败面，
 * 这些错误码决定调用方回落规则还是提示用户，不能靠猜。
 */
class LlmRuntimeTest {

    private class FakeBinding(
        private val loadError: Exception? = null,
        private val pieces: List<String> = listOf("你好", "世界"),
        private val delayPerPieceMs: Long = 0,
        private val emitNothing: Boolean = false
    ) : LlmRuntime.LlmBinding {
        private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
        override val currentState: InferenceEngine.State get() = _state.value
        override val state: StateFlow<InferenceEngine.State> get() = _state
        var loadedPath: String = ""

        override suspend fun loadModel(pathToModel: String) {
            loadError?.let { throw it }
            loadedPath = pathToModel
            _state.value = InferenceEngine.State.ModelReady
        }

        override suspend fun setSystemPrompt(systemPrompt: String) = Unit

        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
            if (emitNothing) return@flow
            pieces.forEach {
                if (delayPerPieceMs > 0) delay(delayPerPieceMs)
                emit(it)
            }
        }

        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = "ok"

        override fun cleanUp() {
            _state.value = InferenceEngine.State.Uninitialized
        }

        override fun destroy() = cleanUp()
    }

    /** 假时钟：每次读取前进 10ms，避免 tok/s 断言依赖真实调度 */
    private fun tickingClock(): () -> Long {
        var now = 0L
        return { now += 10; now }
    }

    @Test
    fun `加载成功进入 READY 且同路径幂等`() = runBlocking {
        val binding = FakeBinding()
        val runtime = LlmRuntime(binding, tickingClock())
        val first = runtime.load("/models/qwen.gguf")
        assertTrue(first.ok)
        assertEquals(LlmRuntime.Phase.READY, runtime.snapshot.value.phase)
        assertEquals("/models/qwen.gguf", runtime.loadedModelPath)

        val second = runtime.load("/models/qwen.gguf")
        assertEquals(0, second.elapsedMs)
        assertEquals("qwen.gguf", binding.loadedPath.substringAfterLast('/'))
    }

    @Test
    fun `架构不支持映射为 UNSUPPORTED_ARCH`() = runBlocking {
        val runtime = LlmRuntime(FakeBinding(loadError = UnsupportedArchitectureException()), tickingClock())
        val result = runtime.load("/models/bad.gguf")
        assertEquals(LlmRuntime.LlmErrorCode.UNSUPPORTED_ARCH, result.errorCode)
        assertEquals(LlmRuntime.Phase.ERROR, runtime.snapshot.value.phase)
        assertFalse(runtime.isReady)
    }

    @Test
    fun `未加载就生成返回 NOT_LOADED`() = runBlocking {
        val runtime = LlmRuntime(FakeBinding(), tickingClock())
        val result = runtime.generate("hi")
        assertEquals(LlmRuntime.LlmErrorCode.NOT_LOADED, result.errorCode)
        assertFalse(result.ok)
    }

    @Test
    fun `生成按流收集为整段文本并统计 token`() = runBlocking {
        val runtime = LlmRuntime(FakeBinding(pieces = listOf("{\"a\":", "[{\"i\":1}]}")), tickingClock())
        assertTrue(runtime.load("/m.gguf").ok)
        val result = runtime.generate("prompt", predictLength = 32)
        assertTrue(result.ok)
        assertEquals("{\"a\":[{\"i\":1}]}", result.text)
        assertEquals(2, result.tokenCount)
        assertEquals(LlmRuntime.Phase.READY, runtime.snapshot.value.phase)
    }

    @Test
    fun `空输出映射 EMPTY_OUTPUT 且不崩`() = runBlocking {
        val runtime = LlmRuntime(FakeBinding(emitNothing = true), tickingClock())
        assertTrue(runtime.load("/m.gguf").ok)
        val result = runtime.generate("prompt")
        assertEquals(LlmRuntime.LlmErrorCode.EMPTY_OUTPUT, result.errorCode)
        assertEquals(LlmRuntime.Phase.READY, runtime.snapshot.value.phase)
    }

    @Test
    fun `生成超时映射 TIMEOUT 并回到可重试状态`() = runBlocking {
        val runtime = LlmRuntime(
            FakeBinding(pieces = listOf("a", "b", "c"), delayPerPieceMs = 400),
            tickingClock()
        )
        assertTrue(runtime.load("/m.gguf").ok)
        val result = runtime.generate("prompt", timeoutMs = 250)
        assertEquals(LlmRuntime.LlmErrorCode.TIMEOUT, result.errorCode)
        assertEquals(LlmRuntime.Phase.ERROR, runtime.snapshot.value.phase)
        // 超时不是永久损坏：再次生成仍会尝试（不会被 no-op 锁死）
        val retry = runtime.generate("prompt", timeoutMs = 10_000)
        assertTrue(retry.ok)
    }

    @Test
    fun `benchmark 输出加载耗时与 tok 每秒`() = runBlocking {
        val runtime = LlmRuntime(
            FakeBinding(pieces = listOf("x".repeat(4), "y".repeat(4), "z".repeat(4))),
            tickingClock()
        )
        val bench = runtime.benchmark("/m.gguf")
        assertTrue(bench.ok)
        assertEquals(3, bench.tokenCount)
        assertTrue(bench.tokPerSec > 0f)
    }
}
