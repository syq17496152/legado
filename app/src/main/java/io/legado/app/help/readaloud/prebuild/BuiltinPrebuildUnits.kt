package io.legado.app.help.readaloud.prebuild

import io.legado.app.help.readaloud.casting.CastingRuleSet
import io.legado.app.help.readaloud.casting.CastingSegment
import io.legado.app.help.readaloud.casting.CastingTag
import io.legado.app.help.readaloud.casting.CharacterVoiceAssignService
import io.legado.app.help.readaloud.casting.TtsCastingStore
import io.legado.app.help.readaloud.casting.TtsTagSplitter
import io.legado.app.help.readaloud.role.BookAliasStore
import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.help.tts.builtin.BuiltinTtsRuntime

/**
 * 内置引擎预合成的单元构造（T3.2 单声 / T3.3 多角色逐段）。
 *
 * 为什么必须按段+角色构造单元：播放侧缓存键含**段级音色**（`id|显示名`），
 * 若预合成整段用单一音色，键必然对不上 ⇒ 预合成白跑、二刷仍不命中（本轮实测结论）。
 * 因此多角色路径与播放同链路：模板切段 → 离线归因 → 六级链取 toneID → 空则回落旁白。
 */
internal object BuiltinPrebuildUnits {

    /** 单声档：整章按段落切，统一音色（快档 vits 不依赖参考音频，voiceKey 固定） */
    fun singleVoiceUnits(unitText: String, voiceKey: String): List<BuiltinTtsRuntime.PrebuildUnit> =
        paragraphsOf(unitText).map { BuiltinTtsRuntime.PrebuildUnit(it, voiceKey) }

    /** 多角色：按模板规则逐段解析音色，与播放侧缓存键一致 */
    suspend fun multiRoleUnits(
        unitText: String,
        bookKey: String,
        chapterIndex: Int,
        ruleSet: CastingRuleSet,
        fallbackVoiceName: String
    ): List<BuiltinTtsRuntime.PrebuildUnit> {
        val paragraphs = paragraphsOf(unitText)
        if (paragraphs.isEmpty()) return emptyList()
        // 归因缓存优先（内存 → 落库 → 现算）；失败不阻断，退化为旁白音色
        runCatching {
            val characters = CharacterVoiceAssignService.roleCharacters(bookKey)
            OfflineRoleResolver.ensure(
                bookKey = bookKey,
                chapterIndex = chapterIndex,
                chapterTitle = "",
                paragraphs = paragraphs,
                characters = characters,
                aliasStore = BookAliasStore(BuiltinTtsRuntime.paths().rulesRoot)
            )
        }
        val out = ArrayList<BuiltinTtsRuntime.PrebuildUnit>(paragraphs.size)
        paragraphs.forEachIndexed { paragraphIndex, paragraph ->
            val segments = TtsTagSplitter.splitParagraph(paragraphIndex, paragraph, ruleSet.rules)
            if (segments.isEmpty()) {
                out.add(BuiltinTtsRuntime.PrebuildUnit(paragraph, fallbackVoiceName))
                return@forEachIndexed
            }
            segments.forEach { segment ->
                val speech = segmentSpeech(paragraph, segment)
                if (speech.isBlank()) return@forEach
                val hit = OfflineRoleResolver.lookup(
                    bookKey, chapterIndex, paragraphIndex,
                    segment.offsetInParagraph, segment.length
                )
                val tag = hit?.let { CastingTag.AI_PREFIX + it.characterName } ?: segment.tag
                val toneId = runCatching {
                    TtsCastingStore.resolveSourceForTag(
                        bookKey, tag, ruleSet, hit?.characterId ?: 0L
                    )
                }.getOrNull()?.toneID.orEmpty()
                out.add(
                    BuiltinTtsRuntime.PrebuildUnit(
                        speech, voiceFor(toneId, fallbackVoiceName)
                    )
                )
            }
        }
        return out
    }

    /** 段落切分（纯函数可单测）：按换行、去空白、丢空段 */
    internal fun paragraphsOf(unitText: String): List<String> =
        unitText.split('\n').map { it.trim() }.filter { it.isNotBlank() }

    /** 段内送合成文本（纯函数可单测）：对白段剥离引号，与播放侧 `stripQuotesForSpeech` 同口径 */
    internal fun segmentSpeech(paragraph: String, segment: CastingSegment): String {
        val start = segment.offsetInParagraph.coerceIn(0, paragraph.length)
        val end = (segment.offsetInParagraph + segment.length).coerceIn(start, paragraph.length)
        val raw = paragraph.substring(start, end)
        val text = if (segment.tag != CastingTag.NARRATION) {
            TtsTagSplitter.stripQuotesForSpeech(raw)
        } else {
            raw
        }
        return text.trim()
    }

    /** 段级音色（纯函数可单测）：解析出的 toneID 优先，空则回落旁白/首音 */
    internal fun voiceFor(toneId: String, fallbackVoiceName: String): String =
        if (toneId.isBlank()) fallbackVoiceName else toneId
}
