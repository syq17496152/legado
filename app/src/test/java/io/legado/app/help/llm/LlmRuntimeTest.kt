package io.legado.app.help.llm

import com.arm.aichat.InferenceEngine
import com.arm.aichat.TemplateUnavailableException
import com.arm.aichat.UnsupportedArchitectureException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
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
        private val emitNothing: Boolean = false,
        /** crash-hardening AD-05：生成阶段抛出的异常（模拟 native 模板不可用） */
        private val generateError: Exception? = null,
        /** 第 N 次（从 1 起）调用 setSystemPrompt 时抛异常，模拟官方 `_readyForSystemPrompt` 拒绝二次设置 */
        private val failPromptCalls: Set<Int> = emptySet()
    ) : LlmRuntime.LlmBinding {
        private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
        override val currentState: InferenceEngine.State get() = _state.value
        override val state: StateFlow<InferenceEngine.State> get() = _state
        var loadedPath: String = ""
        var loadCalls: Int = 0
        var promptCalls: Int = 0
        var userPromptCalls: Int = 0

        override suspend fun loadModel(pathToModel: String) {
            loadCalls += 1
            loadError?.let { throw it }
            loadedPath = pathToModel
            _state.value = InferenceEngine.State.ModelReady
        }

        override suspend fun setSystemPrompt(systemPrompt: String) {
            promptCalls += 1
            if (promptCalls in failPromptCalls) {
                throw IllegalStateException("System prompt must be set ** RIGHT AFTER ** model loaded!")
            }
        }

        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> = flow {
            userPromptCalls += 1
            generateError?.let { throw it }
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

    /**
     * 引擎是**进程级单例**，`LlmRuntime` 是 Service 级：Service 重建后新实例 phase=UNINITIALIZED，
     * 但底层引擎仍是 ModelReady。此时再调官方 `loadModel` 会被 `check(state is Initialized)` 拒绝
     * （实测报错 `Cannot load model in ModelReady!`），用户看到自检莫名其妙失败。
     * 必须在 `load()` 里按「引擎已加载」对齐状态。
     */
    @Test
    fun `引擎已由上个实例加载时 新实例 load 直接对齐 READY 且不重复加载`() = runBlocking {
        val binding = FakeBinding()
        LlmRuntime(binding, tickingClock()).load("/models/qwen.gguf")
        assertEquals(InferenceEngine.State.ModelReady, binding.currentState)

        // 模拟 Service 重建：同一个 binding（引擎单例），全新的 runtime
        val rebuilt = LlmRuntime(binding, tickingClock())
        assertEquals(LlmRuntime.Phase.UNINITIALIZED, rebuilt.snapshot.value.phase)
        val result = rebuilt.load("/models/qwen.gguf")
        assertTrue("引擎已加载时必须返回成功，不能再抛 Cannot load model in ModelReady", result.ok)
        assertEquals(LlmRuntime.Phase.READY, rebuilt.snapshot.value.phase)
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
    fun `显式取消映射 CANCELLED 且引擎仍可用`() = runBlocking {
        val runtime = LlmRuntime(
            FakeBinding(pieces = listOf("a", "b", "c", "d"), delayPerPieceMs = 300),
            tickingClock()
        )
        assertTrue(runtime.load("/m.gguf").ok)
        val job = launch(Dispatchers.Default) { runtime.generate("prompt", timeoutMs = 10_000) }
        delay(120)
        job.cancelAndJoin()
        // 取消 ≠ 故障：不落 ERROR，且下一次生成仍然可用（否则取消一次就把本地 AI 用废）
        assertEquals(LlmRuntime.Phase.READY, runtime.snapshot.value.phase)
        assertTrue(runtime.isReady)
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

    /**
     * 真机 P0（2026-11-06）：官方 binding 只允许 load 之后设一次 system prompt。
     * 自检用掉窗口后，剧本任务再设会抛 IllegalStateException；旧实现吞掉异常继续 generate，
     * native 侧 chat 模板为空 ⇒ SIGSEGV 打崩 `:llm`，客户端只能看到「推理进程无回执」。
     * 现要求：第二次设置失败 ⇒ 重载模型并重试，成功才算可用。
     */
    @Test
    fun `二次设置 system prompt 失败时重载模型后重试成功`() = runBlocking {
        // 第 2 次设置失败（第 1 次在 load 后由任务 A 用掉），第 3 次（重载后）成功
        val binding = FakeBinding(failPromptCalls = setOf(2))
        val runtime = LlmRuntime(binding, tickingClock())
        assertTrue(runtime.load("/m.gguf").ok)
        assertTrue(runtime.ensureSystemPrompt("任务A").ok)

        val result = runtime.ensureSystemPrompt("任务B")
        assertTrue("重载后第二次设置应成功", result.ok)
        assertEquals("必须触发一次重载", 2, binding.loadCalls)
        assertEquals(LlmRuntime.Phase.READY, runtime.snapshot.value.phase)
    }

    /**
     * 重载后仍设不上 ⇒ 必须返回错误让调用方降级，绝不能放行 generate（否则 native 必崩）。
     */
    @Test
    fun `重载后仍失败返回错误且不触发用户提示`() = runBlocking {
        val binding = FakeBinding(failPromptCalls = setOf(1, 2, 3))
        val runtime = LlmRuntime(binding, tickingClock())
        assertTrue(runtime.load("/m.gguf").ok)

        val result = runtime.ensureSystemPrompt("任务A")
        assertFalse(result.ok)
        assertEquals(LlmRuntime.LlmErrorCode.UNKNOWN, result.errorCode)
        assertEquals("必须重载过一次", 2, binding.loadCalls)
        assertEquals("失败时不得调用 sendUserPrompt", 0, binding.userPromptCalls)
    }

    @Test
    fun `未加载时 ensureSystemPrompt 返回 NOT_LOADED`() = runBlocking {
        val runtime = LlmRuntime(FakeBinding(), tickingClock())
        val result = runtime.ensureSystemPrompt("x")
        assertEquals(LlmRuntime.LlmErrorCode.NOT_LOADED, result.errorCode)
    }

    /** 自检在 system prompt 设不上时必须直接失败，不能继续 generate（否则同样会打崩推理进程）。 */
    @Test
    fun `自检在 system prompt 失败时返回错误且不生成`() = runBlocking {
        val binding = FakeBinding(failPromptCalls = setOf(1, 2, 3))
        val runtime = LlmRuntime(binding, tickingClock())
        val bench = runtime.benchmark("/m.gguf")
        assertFalse(bench.ok)
        assertEquals(LlmRuntime.LlmErrorCode.UNKNOWN, bench.errorCode)
        assertEquals(0, binding.userPromptCalls)
    }

    // ---------------- crash-hardening AD-05：native chat 模板不可用 ----------------

    /**
     * prepare() 失败（native 返回 3）⇒ 必须映射为 TEMPLATE_UNAVAILABLE，
     * 不能再报"架构不受支持"（会误导用户换错方向）。
     */
    @Test
    fun `加载时 chat 模板不可用映射为 TEMPLATE_UNAVAILABLE`() = runBlocking {
        val runtime = LlmRuntime(FakeBinding(loadError = TemplateUnavailableException()))
        val result = runtime.load("/m.gguf")
        assertFalse(result.ok)
        assertEquals(LlmRuntime.LlmErrorCode.TEMPLATE_UNAVAILABLE, result.errorCode)
        assertEquals(LlmRuntime.Phase.ERROR, runtime.snapshot.value.phase)
    }

    /**
     * 生成阶段 native 返回 4（模板为空）⇒ 立刻失败、0 token，
     * 旧实现继续跑 token 循环会在 native 侧解引用空指针并 SIGSEGV。
     */
    @Test
    fun `生成时模板不可用直接失败且不产出 token`() = runBlocking {
        val binding = FakeBinding(generateError = TemplateUnavailableException())
        val runtime = LlmRuntime(binding, tickingClock())
        assertTrue(runtime.load("/m.gguf").ok)
        val result = runtime.generate("prompt")
        assertFalse(result.ok)
        assertEquals(LlmRuntime.LlmErrorCode.TEMPLATE_UNAVAILABLE, result.errorCode)
        assertEquals(0, result.tokenCount)
        assertEquals(1, binding.userPromptCalls)
    }
}
