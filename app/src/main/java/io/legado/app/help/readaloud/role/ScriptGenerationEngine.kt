package io.legado.app.help.readaloud.role

import io.legado.app.constant.AppLog

/**
 * 3.5 剧本生成引擎（业务编排，线程无关）：规则草稿 → 模型仲裁 → 落盘。
 *
 * 为什么要有这一层：`ScriptArbiterRunner` 只做"合并"，`ChapterScriptStore` 只做"存取"，
 * 真正决定「这一章要不要重算、模型不可用时怎么标、落盘成功与否」的逻辑必须有单一入口，
 * 否则 UI 触发 / 后台执行器 / 预取窗口会各写一套判断。
 *
 * 口径：
 * - **可复用不复用不重算**：正文变化率 ≤10% 直接跳过（省电，也避免把用户手改结果冲掉）；
 * - **模型未就绪不标降级**：只产出规则剧本（`source=rule`），因为"没开 AI"不是"AI 失败"；
 * - **模型失败标降级**：仍保存规则结果供朗读用，但 UI 要显示"已降级（本次用规则结果）"；
 * - **空章不落盘**：避免在磁盘堆 0 段剧本。
 */
class ScriptGenerationEngine(
    private val store: ChapterScriptStore,
    private val llm: ScriptLlm?,
    private val ruleVersion: String = DEFAULT_RULE_VERSION
) {

    enum class Status { SAVED, SKIPPED_REUSABLE, EMPTY, FAILED }

    data class Outcome(
        val status: Status,
        val script: ChapterScript? = null,
        /** 模型侧说明（未就绪/超时/坏 JSON），供状态行与诊断日志使用 */
        val message: String = "",
        val aiApplied: Int = 0,
        val batches: Int = 0
    ) {

        val saved: Boolean get() = status == Status.SAVED
    }

    /**
     * @param segments 规则归因结果（`OfflineRoleResolver` / 缓存）
     * @param candidates 允许模型指派的角色名（白名单，防"模型编人名"）
     * @param force 忽略复用判定（用户点「重新生成」/override 变更）
     * @param aiRequested 本次是否真的打算用模型（true 且模型没答 ⇒ 标降级；false ⇒ 纯规则剧本不标降级）
     */
    suspend fun generate(
        bookKey: String,
        chapterIndex: Int,
        paragraphs: List<String>,
        segments: List<OfflineRoleSegment>,
        candidates: List<String>,
        force: Boolean = false,
        aiRequested: Boolean = llm != null
    ): Outcome {
        if (bookKey.isBlank() || chapterIndex < 0) {
            return Outcome(Status.FAILED, message = "书键或章号非法")
        }
        if (paragraphs.isEmpty() || segments.isEmpty()) {
            return Outcome(Status.EMPTY, message = "本章没有可归因的段落")
        }
        if (!force && store.reusable(bookKey, chapterIndex, paragraphs) != null) {
            return Outcome(Status.SKIPPED_REUSABLE, message = "正文变化 ≤10%，复用已有剧本")
        }

        val overrides = store.overrides(bookKey)
        val draft = ChapterScriptBuilder.build(
            bookKey = bookKey,
            chapterIndex = chapterIndex,
            paragraphs = paragraphs,
            segments = segments,
            ruleVersion = ruleVersion,
            overrides = overrides
        )

        val runner = ScriptArbiterRunner(llm)
        val outcome = runCatching {
            runner.run(draft, textOf(paragraphs), candidates)
        }.getOrElse { error ->
            AppLog.put("剧本仲裁异常（ch=$chapterIndex）：${error.localizedMessage}")
            return Outcome(Status.FAILED, script = draft, message = error.localizedMessage.orEmpty())
        }

        val aiApplied = outcome.merge.aiApplied
        // 降级判定：本次确实要 AI，但模型没答（未就绪/加载失败）或答了却没被采纳
        val degraded = aiRequested && (outcome.merge.degraded || !outcome.modelCalled)
        val source = if (aiApplied > 0) SOURCE_LOCAL_AI else SOURCE_RULE
        val finalScript = draft.copy(
            segments = outcome.merge.segments,
            source = source,
            degraded = degraded,
            degradeReason = when {
                !degraded -> ""
                outcome.errorMessage.isNotBlank() -> outcome.errorMessage
                else -> "模型未采纳，保留规则结果"
            },
            generatedAt = System.currentTimeMillis()
        )

        if (!store.write(finalScript)) {
            return Outcome(Status.FAILED, script = finalScript, message = "剧本落盘失败")
        }
        // 正式诊断日志（保留）：区分「AI 生效 / 降级 / 纯规则」，是验证端侧模型是否真的接管的生命线
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "scriptGen ch=$chapterIndex segments=${finalScript.segments.size} " +
                "source=$source ai=$aiApplied batches=${outcome.batches} " +
                "degraded=$degraded modelCalled=${outcome.modelCalled}",
            level = AppLog.Level.INFO
        )
        return Outcome(
            status = Status.SAVED,
            script = finalScript,
            message = outcome.errorMessage,
            aiApplied = aiApplied,
            batches = outcome.batches
        )
    }

    companion object {

        const val SOURCE_RULE = "rule"
        const val SOURCE_LOCAL_AI = "local-ai"

        /** 规则版本（剧本缓存键的一部分；归因规则升级时必须改版本触发重算） */
        const val DEFAULT_RULE_VERSION = "role-rules-v3"

        /** 段文本切片：越界一律返回空串（不把错位正文喂给模型） */
        fun textOf(paragraphs: List<String>): (ScriptSegment) -> String = { segment ->
            val paragraph = paragraphs.getOrNull(segment.paragraphIndex).orEmpty()
            val start = segment.start.coerceIn(0, paragraph.length)
            val end = segment.end.coerceIn(start, paragraph.length)
            paragraph.substring(start, end)
        }
    }
}
