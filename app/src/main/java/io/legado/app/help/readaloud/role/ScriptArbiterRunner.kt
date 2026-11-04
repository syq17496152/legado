package io.legado.app.help.readaloud.role

import io.legado.app.help.llm.LlmRuntime

/**
 * 3.4 剧本仲裁执行器：把「组批 → 调模型 → 解析 → 合并」串起来（纯业务编排，不碰线程）。
 *
 * 口径（AD-04 / I-3）：
 * - 只送 `needsArbitration` 段；高置信规则段永不进模型；
 * - system prompt 每次 run 只设一次（KV 复用的最小形态；官方 binding 不支持显式 KV reset，靠单会话）；
 * - 任一失败（未就绪/超时/坏 JSON）⇒ 保留规则结果并由 `ScriptArbiter.apply` 标 `degraded`，
 *   **绝不把模型猜测写进正式剧本**（真正的落盘由上层按 `degraded` 决定）。
 *
 * 已知上限：官方 llama.cpp Android binding 未暴露 GBNF 参数 ⇒ [ScriptLlmCodec.gbnf] 暂无法下发，
 * 依赖提示词 + 容错解析；后续 binding 暴露 grammar 时在这里接入（不改 [ScriptArbiter] 契约）。
 */
class ScriptArbiterRunner(private val llm: LlmRuntime?) {

    /** 单批最多生成 token（剧本仲裁只需要紧凑 JSON） */
    private val predictTokens = 256

    data class Outcome(
        val merge: ScriptArbiter.MergeResult,
        val batches: Int,
        val modelCalled: Boolean,
        val errorMessage: String = ""
    )

    suspend fun run(
        script: ChapterScript,
        textOf: (ScriptSegment) -> String,
        candidates: List<String>
    ): Outcome {
        val whitelist = candidates.toSet()
        val batches = ScriptArbiter.batches(script.segments, textOf)
        if (batches.isEmpty()) {
            return Outcome(ScriptArbiter.apply(script, emptyList(), whitelist), batches = 0, modelCalled = false)
        }
        if (llm == null || !llm.isReady) {
            return Outcome(
                ScriptArbiter.apply(script, emptyList(), whitelist),
                batches = batches.size,
                modelCalled = false,
                errorMessage = "本地模型未就绪"
            )
        }
        llm.setSystemPrompt(ScriptPromptTemplates.system(candidates))
        val suggestions = mutableListOf<ScriptArbiter.AiSuggestion>()
        var error = ""
        batches.forEach { batch ->
            val prompt = ScriptPromptTemplates.user(batch.map { it to textOf(it) })
            val gen = llm.generate(prompt, predictLength = predictTokens)
            if (gen.ok) {
                suggestions += ScriptLlmCodec.toSuggestions(
                    items = ScriptLlmCodec.parse(gen.text),
                    batch = batch,
                    candidates = candidates
                )
            } else if (error.isBlank()) {
                error = gen.message
            }
        }
        return Outcome(
            merge = ScriptArbiter.apply(script, suggestions, whitelist),
            batches = batches.size,
            modelCalled = true,
            errorMessage = error
        )
    }
}
