package io.legado.app.service

import android.os.SystemClock
import io.legado.app.constant.AppLog
import io.legado.app.help.readaloud.casting.CastingProsody
import io.legado.app.help.readaloud.speech.SpeechRoute

/**
 * 多角色朗读的 TtsTrace 埋点（T3.1/T3.3 量化用；抽成独立文件以守住 G-38 行数门禁）。
 *
 * 两类证据：
 * - `multiRole 段`：逐段声源/音色/韵律/文本长度（真机逐段换声的既有证据链）；
 * - `multiRole 段间隔`：上一段播完到下一段开始之间的空档毫秒数（缓存命中时用于判定 ≤150ms 指标）。
 *
 * 计时口径：`begin()` 紧贴 `source.utterance(...)` 调用前，`end()` 在 utterance 返回后调用；
 * 首次段没有上一段参照，`end()` 返回 0 且不打日志。预热状态是"本次朗读实例"级的，`reset()` 在每轮开播前调用。
 */
internal object ReadAloudTrace {

    private var lastEndMs = 0L
    private var warmed = false

    fun reset() {
        lastEndMs = 0L
        warmed = false
    }

    fun begin(): Long = SystemClock.elapsedRealtime()

    fun needsWarm(active: Boolean): Boolean = shouldWarm(active, warmed)

    /** 预热门：只有内置引擎激活且本轮还没预热过首段时才预热（纯函数可单测） */
    internal fun shouldWarm(active: Boolean, warmed: Boolean): Boolean = active && !warmed

    fun markWarmed() {
        warmed = true
    }

    /** @return 与上一段结束之间的空档（ms）；首段返回 0 */
    fun end(startMs: Long, utteranceId: String): Long {
        val now = SystemClock.elapsedRealtime()
        val gap = if (lastEndMs > 0) startMs - lastEndMs else 0L
        lastEndMs = now
        if (gap != 0L) {
            AppLog.putDebugWithTag(
                AppLog.TAG_TTS_TRACE,
                gapLogText(gap, utteranceId),
                level = AppLog.Level.INFO
            )
        }
        return gap
    }

    /** 段间隔日志文案（纯函数可单测）：缓存命中段用它判定 ≤150ms 指标 */
    internal fun gapLogText(gapMs: Long, utteranceId: String): String =
        "multiRole 段间隔 gap=${gapMs}ms utteranceId=$utteranceId"

    /** 逐段合成证据（分段来源 tag / 声源 / 音色 / 韵律 / 文本长度） */
    fun segment(
        p: Int,
        offset: Int,
        length: Int,
        tag: String,
        sourceRoute: SpeechRoute,
        voiceDisplayName: String?,
        prosody: CastingProsody,
        utteranceId: String
    ) {
        AppLog.putDebugWithTag(
            AppLog.TAG_TTS_TRACE,
            "multiRole 段 p=$p off=$offset len=$length tag=$tag " +
                "声源=${sourceRoute.engineType}:${sourceRoute.engineValue}:${voiceDisplayName ?: "-"} " +
                "prosody=${prosody.rate}/${prosody.pitch}/${prosody.volume} utteranceId=$utteranceId",
            level = AppLog.Level.INFO
        )
    }
}
