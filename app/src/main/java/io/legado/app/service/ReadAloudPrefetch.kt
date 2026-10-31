package io.legado.app.service

import io.legado.app.help.config.AppConfig
import io.legado.app.help.readaloud.casting.CastingRuleSet
import io.legado.app.help.readaloud.casting.CastingSegment
import io.legado.app.help.readaloud.casting.CastingTag
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.casting.TtsTagSplitter
import io.legado.app.help.readaloud.casting.TtsVoiceRef
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime

/**
 * AD-09 钩子②：下一段预取（从 `TTSReadAloudService` 抽出守住 G-38 行数门禁）。
 *
 * 口径与主循环一致：
 * - 只有内置引擎激活且下一段非空白时才预取；
 * - 下一段音色按"角色命中 → 模板 tag → 旁白/首音兜底"同一链路解析，
 *   否则缓存键对不上、预取永远不命中（IF-07 同类问题）；
 * - 预取失败静默（不拖慢当前句）。
 */
internal object ReadAloudPrefetch {

    suspend fun prefetchNext(
        builtinActive: Boolean,
        paragraph: String,
        segments: List<CastingSegment>,
        segIndex: Int,
        roleBookKey: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        ruleSet: CastingRuleSet,
        fallbackVoiceRef: TtsVoiceRef?
    ) {
        if (!builtinActive) return
        val nextSegment = segments.getOrNull(segIndex + 1) ?: return
        val nextRaw = paragraph.substring(
            nextSegment.offsetInParagraph,
            nextSegment.offsetInParagraph + nextSegment.length
        )
        val nextSpeech = if (nextSegment.tag != CastingTag.NARRATION) {
            TtsTagSplitter.stripQuotesForSpeech(nextRaw)
        } else {
            nextRaw
        }
        if (nextSpeech.isBlank()) return
        val nextHit = OfflineRoleResolver.lookup(
            roleBookKey, chapterIndex, paragraphIndex,
            nextSegment.offsetInParagraph, nextSegment.length
        )
        val nextTag = nextHit?.let { CastingTag.AI_PREFIX + it.characterName } ?: nextSegment.tag
        val nextVoice = runCatching {
            TtsCastingStore.resolveSourceForTag(
                roleBookKey, nextTag, ruleSet, nextHit?.characterId ?: 0L
            )
        }.getOrNull()?.toneID.orEmpty()
            .let { resolvePrefetchVoice(it, fallbackVoiceRef?.voiceId.orEmpty()) }
        runCatching {
            BuiltinTtsRuntime.prefetchAsync(
                nextSpeech, nextVoice, (AppConfig.ttsSpeechRate + 5) / 10f
            )
        }
    }

    /**
     * 预取音色解析（纯函数可单测）：模板解析出的 toneID 优先，空则回落旁白/首音。
     * 与主循环同一兜底口径，否则缓存键对不上、预取永不命中。
     */
    internal fun resolvePrefetchVoice(toneId: String, fallbackVoiceId: String): String =
        if (toneId.isBlank()) fallbackVoiceId else toneId
}
