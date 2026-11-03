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
import kotlinx.coroutines.delay

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

    /**
     * 等当前段"开始出声"的上限：引擎迟迟不回调 `onStart` 时也照常尝试预取（fail-open）。
     * 取 5s 是为了覆盖冷启动首段的长合成（真机实测单段合成最长 5.4s）。
     */
    internal const val PLAYBACK_START_WAIT_MS = 5_000L

    /** 轮询步进（毫秒）：足够细，且不产生可感知的额外开销 */
    internal const val PLAYBACK_START_POLL_MS = 50L

    /**
     * 预取时机契约（纯函数可单测）：内置引擎激活时才预取。
     *
     * 2026-11-03 真机取证：旧实现等 `utterance()`（整段播完）才预取下一段 ⇒ 段间静默等 1~2s 合成，
     * 用户听到"一顿一顿"；流水线化后当前段播放期间即可完成下一段合成。
     */
    internal fun shouldPrefetch(builtinActive: Boolean): Boolean = builtinActive

    /**
     * 预取让行契约（纯函数可单测）：当前段尚未出声时继续等待，超过上限后放弃等待直接预取。
     *
     * 为什么要等：合成是进程级串行的（[BuiltinTtsRuntime] `synthesisMutex`）；
     * 若在 speak 提交前就抢锁合成下一段，当前段反而要排在后面 ⇒ 起播被推迟 1~2s。
     */
    internal fun shouldWaitForPlaybackStart(playbackStarted: Boolean, waitedMs: Long): Boolean =
        !playbackStarted && waitedMs < PLAYBACK_START_WAIT_MS

    suspend fun prefetchNext(
        builtinActive: Boolean,
        playbackStarted: () -> Boolean,
        paragraph: String,
        segments: List<CastingSegment>,
        segIndex: Int,
        roleBookKey: String,
        chapterIndex: Int,
        paragraphIndex: Int,
        ruleSet: CastingRuleSet,
        fallbackVoiceRef: TtsVoiceRef?,
        lastDialogueVoiceRef: TtsVoiceRef? = null
    ) {
        if (!shouldPrefetch(builtinActive)) return
        var waitedMs = 0L
        while (shouldWaitForPlaybackStart(playbackStarted(), waitedMs)) {
            delay(PLAYBACK_START_POLL_MS)
            waitedMs += PLAYBACK_START_POLL_MS
        }
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
        // 与主循环同一稳定性口径：下一段是未识别对白时沿用上一句角色音色，
        // 否则缓存键会与播放期实际音色不一致（预取落空）且听起来会"跳回旁白音"
        val effectiveVoice = stableDialogueVoice(
            isNarration = nextSegment.tag == CastingTag.NARRATION,
            hasRole = nextHit != null,
            lastDialogueVoice = lastDialogueVoiceRef,
            current = TtsVoiceRef(nextVoice, nextVoice, TtsVoiceRef.CHANNEL_SYSTEM)
        )?.voiceId.orEmpty()
        runCatching {
            BuiltinTtsRuntime.prefetchAsync(
                nextSpeech, effectiveVoice, (AppConfig.ttsSpeechRate + 5) / 10f
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
