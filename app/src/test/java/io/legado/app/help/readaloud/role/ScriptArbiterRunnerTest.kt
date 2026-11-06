package io.legado.app.help.readaloud.role

import com.arm.aichat.InferenceEngine
import io.legado.app.help.llm.LlmRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 3.4 仲裁执行器：模型调用失败绝不污染正式剧本；只送低置信段；坏 JSON 标降级。
 */
class ScriptArbiterRunnerTest {

    private class FakeBinding(private val replies: List<String>) : LlmRuntime.LlmBinding {
        private val _state = MutableStateFlow<InferenceEngine.State>(InferenceEngine.State.Uninitialized)
        private var call = 0
        override val currentState: InferenceEngine.State get() = _state.value
        override val state: StateFlow<InferenceEngine.State> get() = _state
        override suspend fun loadModel(pathToModel: String) {
            _state.value = InferenceEngine.State.ModelReady
        }
        override suspend fun setSystemPrompt(systemPrompt: String) = Unit
        override fun sendUserPrompt(message: String, predictLength: Int): Flow<String> {
            val reply = replies.getOrElse(call) { "" }
            call += 1
            return flowOf(reply)
        }
        override suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int): String = "ok"
        override fun cleanUp() { _state.value = InferenceEngine.State.Uninitialized }
        override fun destroy() = cleanUp()
    }

    private val script = ChapterScript(
        bookKey = "b",
        chapterIndex = 1,
        segments = listOf(
            ScriptSegment(paragraphIndex = 0, start = 0, end = 5, confidence = 0.5,
                source = "turn", characterName = "待定"),
            ScriptSegment(paragraphIndex = 1, start = 0, end = 5, confidence = 0.95,
                source = "alias", characterName = "小明")
        )
    )

    private fun textOf(segment: ScriptSegment) = "第${segment.paragraphIndex}段正文"

    private suspend fun readyRuntime(vararg replies: String): LlmRuntime {
        val runtime = LlmRuntime(FakeBinding(replies.toList()))
        runtime.load("/m.gguf")
        return runtime
    }

    @Test
    fun `模型未就绪时保留规则结果并标降级`() = runBlocking {
        val outcome = ScriptArbiterRunner(null).run(script, ::textOf, listOf("小明", "小红"))
        assertTrue(outcome.merge.degraded)
        assertEquals(0, outcome.merge.aiApplied)
        assertEquals("待定", outcome.merge.segments[0].characterName)
        assertFalse(outcome.modelCalled)
        assertEquals(1, outcome.batches)
    }

    @Test
    fun `合法 JSON 采用模型候选并保留高置信段`() = runBlocking {
        val runtime = readyRuntime("{\"a\":[{\"i\":0,\"n\":0,\"c\":0.92}]}")
        val outcome = ScriptArbiterRunner(RuntimeScriptLlm(runtime))
            .run(script, ::textOf, listOf("小明", "小红"))
        assertEquals(1, outcome.merge.aiApplied)
        assertEquals("小明", outcome.merge.segments[0].characterName)
        assertEquals("local-ai", outcome.merge.segments[0].source)
        // 高置信段不在批次里，模型结果不得覆盖
        assertEquals("小明", outcome.merge.segments[1].characterName)
        assertEquals("alias", outcome.merge.segments[1].source)
        assertTrue(outcome.modelCalled)
    }

    @Test
    fun `坏 JSON 不污染剧本并标降级`() = runBlocking {
        val runtime = readyRuntime("我不会输出 JSON")
        val outcome = ScriptArbiterRunner(RuntimeScriptLlm(runtime)).run(script, ::textOf, listOf("小明"))
        assertTrue(outcome.merge.degraded)
        assertEquals(0, outcome.merge.aiApplied)
        assertEquals("待定", outcome.merge.segments[0].characterName)
    }

    @Test
    fun `全部高置信时零请求`() = runBlocking {
        val highOnly = ChapterScript(
            bookKey = "b", chapterIndex = 1,
            segments = listOf(
                ScriptSegment(paragraphIndex = 0, start = 0, end = 5, confidence = 0.95,
                    source = "alias", characterName = "小明")
            )
        )
        val runtime = readyRuntime("{\"a\":[]}")
        val outcome = ScriptArbiterRunner(RuntimeScriptLlm(runtime))
            .run(highOnly, ::textOf, listOf("小明"))
        assertEquals(0, outcome.batches)
        assertFalse(outcome.modelCalled)
        assertFalse(outcome.merge.degraded)
    }

    /** 捕获实际下发给推理层的 system prompt 与输出预算（真机 E24：256 会截断、Qwen3 思考链会烧光预算）。 */
    private class CapturingLlm : ScriptLlm {
        var system: String = ""
        var predictTokens: Int = 0
        override suspend fun ready(): Boolean = true
        override suspend fun setSystemPrompt(prompt: String): LlmRuntime.Result {
            system = prompt
            return LlmRuntime.Result()
        }

        override suspend fun generate(prompt: String, predictLength: Int): LlmRuntime.Result {
            predictTokens = predictLength
            return LlmRuntime.Result(text = "{\"a\":[{\"i\":0,\"n\":0,\"c\":0.9}]}", tokenCount = 12)
        }
    }

    @Test
    fun `system prompt 关闭思考链且输出预算不低于 512`() = runBlocking {
        val llm = CapturingLlm()
        ScriptArbiterRunner(llm).run(script, ::textOf, listOf("小明", "小红"))
        assertTrue(
            "Qwen3 思考链会占满输出预算导致 JSON 截断，system prompt 必须带 /no_think",
            llm.system.contains("/no_think")
        )
        assertEquals("满批 JSON 约 300 token，预算需留余量", 512, llm.predictTokens)
    }

    /**
     * 真机实测 Qwen3 置信度退化为 0；原样透传会被仲裁阈值 0.80 拒绝 ⇒ 永远 ai=0。
     * 这里用 c=0 的模型输出跑完整「解析 → 落位 → 合并」，锁定"退化置信度也能被采纳"。
     */
    @Test
    fun `模型置信度为 0 时仍能被仲裁采纳`() = runBlocking {
        val runtime = readyRuntime("{\"a\":[{\"i\":0,\"n\":0,\"c\":0}]}")
        val outcome = ScriptArbiterRunner(RuntimeScriptLlm(runtime))
            .run(script, ::textOf, listOf("小明", "小红"))
        assertEquals(1, outcome.merge.aiApplied)
        assertEquals("小明", outcome.merge.segments[0].characterName)
        assertEquals("local-ai", outcome.merge.segments[0].source)
        assertEquals(0, outcome.merge.rejectedAi)
    }
}
