package io.legado.app.help.readaloud.role

import io.legado.app.help.llm.LlmRuntime
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * 3.5 剧本生成引擎：复用判定 / 纯规则 / 降级口径 / AI 采纳。
 *
 * 这四种分支决定 UI 上「未生成 / 已生成（规则）/ 已生成（AI 增强）/ 已降级」四种文案，
 * 一旦口径漂移，用户会看到"AI 说已增强但角色全没变"这类假结论，因此用单测钉死。
 */
class ScriptGenerationEngineTest {

    private lateinit var root: File
    private lateinit var store: ChapterScriptStore

    private val paragraphs = listOf("阿明说：你回来了？")

    /** 低置信 + unknown ⇒ 必须进仲裁批次 */
    private val segments = listOf(
        OfflineRoleSegment(
            paragraphIndex = 0,
            start = 0,
            end = 6,
            roleType = "character",
            characterName = "",
            characterId = 0L,
            confidence = 0.0,
            reason = "unknown"
        )
    )

    private val candidates = listOf("阿明", "小美")

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "script-gen-" + UUID.randomUUID())
        store = ChapterScriptStore(root)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** 假推理缝：把 `output` 当模型原始输出（null 模拟超时） */
    private class FakeLlm(private val output: String?) : ScriptLlm {
        override suspend fun ready(): Boolean = true
        override suspend fun setSystemPrompt(prompt: String): LlmRuntime.Result = LlmRuntime.Result()
        override suspend fun generate(prompt: String, predictLength: Int): LlmRuntime.Result =
            if (output == null) {
                LlmRuntime.Result(errorCode = LlmRuntime.LlmErrorCode.TIMEOUT, message = "超时")
            } else {
                LlmRuntime.Result(text = output, tokenCount = 10, elapsedMs = 100)
            }
    }

    private fun generate(
        llm: ScriptLlm?,
        force: Boolean = false,
        aiRequested: Boolean = llm != null,
        paragraphs: List<String> = this.paragraphs,
        segments: List<OfflineRoleSegment> = this.segments
    ) = runBlocking {
        ScriptGenerationEngine(store, llm).generate(
            bookKey = "book-key",
            chapterIndex = 7,
            paragraphs = paragraphs,
            segments = segments,
            candidates = candidates,
            force = force,
            aiRequested = aiRequested
        )
    }

    @Test
    fun `空章不落盘`() {
        val outcome = generate(llm = null, aiRequested = false, paragraphs = emptyList())
        assertEquals(ScriptGenerationEngine.Status.EMPTY, outcome.status)
        assertFalse(store.scriptFile("book-key", 7).exists())
    }

    @Test
    fun `没打算用 AI 时产出纯规则剧本且不标降级`() {
        val outcome = generate(llm = null, aiRequested = false)
        assertEquals(ScriptGenerationEngine.Status.SAVED, outcome.status)
        assertEquals(ScriptGenerationEngine.SOURCE_RULE, outcome.script?.source)
        assertFalse(outcome.script?.degraded == true)
        assertNotNull(store.read("book-key", 7))
    }

    @Test
    fun `打算用 AI 但模型未就绪时标降级但仍落盘规则结果`() {
        val outcome = generate(llm = null, aiRequested = true)
        assertEquals(ScriptGenerationEngine.Status.SAVED, outcome.status)
        assertTrue(outcome.script?.degraded == true)
        assertEquals("本地模型未就绪", outcome.script?.degradeReason)
        // 降级剧本仍可被朗读消费（有段、有正文指纹）
        assertTrue((outcome.script?.segments?.size ?: 0) > 0)
    }

    @Test
    fun `模型合法输出被采纳时来源为 local-ai`() {
        val outcome = generate(llm = FakeLlm("{\"a\":[{\"i\":0,\"n\":0,\"c\":0.95}]}"))
        assertEquals(ScriptGenerationEngine.Status.SAVED, outcome.status)
        assertEquals(ScriptGenerationEngine.SOURCE_LOCAL_AI, outcome.script?.source)
        assertEquals("阿明", outcome.script?.segments?.first()?.characterName)
        assertEquals("local-ai", outcome.script?.segments?.first()?.source)
        assertFalse(outcome.script?.degraded == true)
        assertEquals(1, outcome.aiApplied)
    }

    @Test
    fun `模型超时按降级处理且不写模型猜测`() {
        val outcome = generate(llm = FakeLlm(output = null))
        assertEquals(ScriptGenerationEngine.Status.SAVED, outcome.status)
        assertTrue(outcome.script?.degraded == true)
        assertEquals("", outcome.script?.segments?.first()?.characterName)
        assertEquals(0, outcome.aiApplied)
    }

    @Test
    fun `正文未大改时第二次生成直接复用`() {
        generate(llm = FakeLlm("{\"a\":[{\"i\":0,\"n\":0,\"c\":0.95}]}"))
        val second = generate(llm = FakeLlm("{\"a\":[{\"i\":0,\"n\":1,\"c\":0.95}]}"))
        assertEquals(ScriptGenerationEngine.Status.SKIPPED_REUSABLE, second.status)
        // 复用不得改变已有结果（否则等于每次打开页面都重算）
        assertEquals("阿明", store.read("book-key", 7)?.segments?.first()?.characterName)
    }

    @Test
    fun `force 时忽略复用判定重算`() {
        generate(llm = FakeLlm("{\"a\":[{\"i\":0,\"n\":0,\"c\":0.95}]}"))
        val forced = generate(llm = FakeLlm("{\"a\":[{\"i\":0,\"n\":1,\"c\":0.95}]}"), force = true)
        assertEquals(ScriptGenerationEngine.Status.SAVED, forced.status)
        assertEquals("小美", store.read("book-key", 7)?.segments?.first()?.characterName)
    }

    @Test
    fun `正文大改后旧剧本不再复用`() {
        generate(llm = FakeLlm("{\"a\":[{\"i\":0,\"n\":0,\"c\":0.95}]}"))
        val changed = paragraphs + listOf("完全不同的一段正文", "又一段", "再一段")
        val outcome = generate(llm = FakeLlm("{\"a\":[{\"i\":0,\"n\":1,\"c\":0.95}]}"), paragraphs = changed)
        assertEquals(ScriptGenerationEngine.Status.SAVED, outcome.status)
    }

    @Test
    fun `段文本切片越界返回空串`() {
        val textOf = ScriptGenerationEngine.textOf(listOf("abc"))
        assertEquals("abc", textOf(ScriptSegment(paragraphIndex = 0, start = 0, end = 99)))
        assertEquals("", textOf(ScriptSegment(paragraphIndex = 5, start = 0, end = 3)))
    }
}
