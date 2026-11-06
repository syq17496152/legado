package io.legado.app.help.readaloud.role

import io.legado.app.constant.AppLog

/**
 * 3.4 剧本仲裁执行器：把「组批 → 调模型 → 解析 → 合并」串起来（纯业务编排，不碰线程）。
 *
 * 口径（AD-04 / I-3）：
 * - 只送 `needsArbitration` 段；高置信规则段永不进模型；
 * - system prompt 每次 run 只设一次（KV 复用的最小形态；官方 binding 不支持显式 KV reset，靠单会话）；
 * - 任一失败（未就绪/超时/坏 JSON）⇒ 保留规则结果并由 `ScriptArbiter.apply` 标 `degraded`，
 *   **绝不把模型猜测写进正式剧本**（真正的落盘由上层按 `degraded` 决定）。
 *
 * 推理来源通过 [ScriptLlm] 注入：生产走 `:llm` 独立进程的 `LlmClient`，单测用进程内假实现。
 *
 * 已知上限：官方 llama.cpp Android binding 未暴露 GBNF 参数 ⇒ [ScriptLlmCodec.gbnf] 暂无法下发，
 * 依赖提示词 + 容错解析；后续 binding 暴露 grammar 时在这里接入（不改 [ScriptArbiter] 契约）。
 */
class ScriptArbiterRunner(private val llm: ScriptLlm?) {

    /**
     * 单批最多生成 token（剧本仲裁只需要紧凑 JSON）。
     *
     * 2026-11-06 真机实测：256 会截断（日志 `STOP: hitting stop position: 576`）⇒ JSON 不闭合、
     * 解析为空 ⇒ `ai=0` 降级。12 段满批 + 关闭思考链后实测 300± 足够，留一倍余量取 512。
     */
    private val predictTokens = 512

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
        val whitelist = candidates.map { it.trim() }.toSet()
        val batches = ScriptArbiter.batches(script.segments, textOf)
        if (batches.isEmpty()) {
            return Outcome(ScriptArbiter.apply(script, emptyList(), whitelist), batches = 0, modelCalled = false)
        }
        if (llm == null || !llm.ready()) {
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
                val items = ScriptLlmCodec.parse(gen.text)
                val mapped = ScriptLlmCodec.toSuggestions(items, batch, candidates)
                // 诊断留痕（测试包日志是排障命脉）：只记长度/条数/序号，不含正文。
                // 真机若再出现 ai=0，可据此区分"没出 JSON / 段号越界 / 候选号越界"。
                AppLog.putDebugWithTag(
                    AppLog.TAG_TTS_TRACE,
                    "scriptLlm batch=${batch.size} chars=${gen.text.length} items=${items.size}" +
                        " mapped=${mapped.size} cand=${candidates.size}" +
                        if (items.isNotEmpty() && mapped.isEmpty()) {
                            " firstI=${items.first().segmentIndex} firstN=${items.first().candidateIndex}"
                        } else ""
                )
                suggestions += mapped
            } else if (error.isBlank()) {
                error = gen.message
            }
        }
        val merge = ScriptArbiter.apply(script, suggestions, whitelist)
        // 合并层诊断：区分"没建议 / 被拒（白名单或置信度）/ 落位成功"。真机上曾出现
        // mapped>0 却 ai=0，没有这行就只能靠猜（只记计数/布尔/技术字段，不含角色名与正文）。
        val firstSuggestion = suggestions.firstOrNull()
        val hit = firstSuggestion?.let { s ->
            script.segments.firstOrNull {
                it.paragraphIndex == s.paragraphIndex && it.start == s.start && it.end == s.end
            }
        }
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "scriptMerge sugg=${suggestions.size} applied=${merge.aiApplied} " +
                "rejected=${merge.rejectedAi} targets=${script.segments.count { it.needsArbitration }}" +
                " firstInWhite=${firstSuggestion?.let { it.characterName in whitelist }}" +
                " hit=${hit != null} hitConf=${hit?.confidence} hitSrc=${hit?.source}" +
                " hitNeeds=${hit?.needsArbitration}"
        )
        return Outcome(
            merge = merge,
            batches = batches.size,
            modelCalled = true,
            errorMessage = error
        )
    }
}
