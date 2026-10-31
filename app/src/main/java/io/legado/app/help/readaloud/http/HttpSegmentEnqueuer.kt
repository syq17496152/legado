package io.legado.app.help.readaloud.http

import io.legado.app.constant.AppPattern
import io.legado.app.help.readaloud.casting.CastingRuleSet
import io.legado.app.help.readaloud.casting.CastingSegment
import io.legado.app.help.readaloud.casting.CastingTag
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.casting.TtsTagSplitter
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * T8.6 HTTP 通道段级换声入队器：段落 → 模板切段 → 逐段解析 toneID → 逐段取流 → 交给宿主入队。
 *
 * 抽成独立类的原因：①`HttpReadAloudService` 已贴 800 行门禁（G-38）；②"切段→归因→取流"可独立演进与单测。
 * 宿主只提供文件读写/取流/入队回调，业务判定全在这里，避免同一套切段逻辑两处漂移。
 */
class HttpSegmentEnqueuer(
    private val bookKey: String,
    private val roleBookKey: String,
    private val chapterIndex: Int,
    private val hooks: Hooks,
    /** 段级音色解析（默认走既有六级声源链；测试可注入假实现，避免依赖 Room） */
    private val toneResolver: suspend (tag: String, ruleSet: CastingRuleSet, characterId: Long) -> String =
        { tag, ruleSet, characterId ->
            runCatching {
                TtsCastingStore.resolveSourceForTag(bookKey, tag, ruleSet, characterId)
            }.getOrNull()?.toneID.orEmpty()
        }
) {

    interface Hooks {
        /** 段级缓存键：必须含音色维度，否则同文本不同角色会串音 */
        fun fileNameOf(text: String, voiceId: String): String
        fun hasFile(fileName: String): Boolean
        fun writeFile(fileName: String, stream: InputStream)
        fun writeSilent(fileName: String)
        /** 按段级音色取流；返回 null 表示该段降级为静音 */
        suspend fun fetch(text: String, toneID: String): InputStream?
        fun addMedia(fileName: String)
    }

    suspend fun enqueue(
        paragraphIndex: Int,
        paragraph: String,
        segments: List<CastingSegment>,
        ruleSet: CastingRuleSet
    ) {
        val lastIndex = segments.lastIndex
        for ((segIndex, segment) in segments.withIndex()) {
            currentCoroutineContext().ensureActive()
            val start = segment.offsetInParagraph.coerceIn(0, paragraph.length)
            val end = (segment.offsetInParagraph + segment.length).coerceIn(start, paragraph.length)
            val raw = paragraph.substring(start, end)
            // 对白段剥离引号（正文显示保留；与系统引擎路径同口径）
            val speech = (if (segment.tag == CastingTag.NARRATION) raw else {
                TtsTagSplitter.stripQuotesForSpeech(raw)
            }).trim().replace(AppPattern.notReadAloudRegex, "")
            if (speech.isBlank()) continue
            val roleHit = runCatching {
                OfflineRoleResolver.lookup(
                    roleBookKey, chapterIndex, paragraphIndex,
                    segment.offsetInParagraph, segment.length
                )
            }.getOrNull()
            val toneID = runCatching {
                val tag = roleHit?.let { CastingTag.AI_PREFIX + it.characterName } ?: segment.tag
                toneResolver(tag, ruleSet, roleHit?.characterId ?: 0L)
            }.getOrDefault("")
            val base = hooks.fileNameOf(speech, toneID)
            val fileName = HttpSegmentVoicePlan.fileName(paragraphIndex, segIndex == lastIndex, base)
            if (!hooks.hasFile(fileName)) {
                val stream = hooks.fetch(speech, toneID)
                if (stream != null) {
                    hooks.writeFile(fileName, stream)
                } else {
                    hooks.writeSilent(fileName)
                }
            }
            hooks.addMedia(fileName)
        }
    }
}
