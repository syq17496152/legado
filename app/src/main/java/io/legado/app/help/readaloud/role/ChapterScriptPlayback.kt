package io.legado.app.help.readaloud.role

import io.legado.app.help.tts.builtin.BuiltinTtsPaths
import io.legado.app.ui.book.read.page.entities.TextChapter
import splitties.init.appCtx
import java.io.File

/**
 * 剧本 → 播报的桥（3.6）。
 *
 * 读取顺序是**一条链**，不是三套实现：
 * `ChapterScript`（AI 增强后的章节剧本）→ `OfflineRoleCacheStore`（规则归因缓存）→ 规则兜底。
 * 这里只负责第一层与字段映射；未命中返回 null，调用方继续走原链路（绝不改变对外结构）。
 *
 * 关键防错（都有单测）：
 * - 剧本文件缺失 / 半写 / 坏 JSON / schema 过期 / 正文变化率 >10% ⇒ null；
 * - 逐段指纹数必须与正文段数一致，否则判为「剧本与正文不对齐」⇒ null；
 * - 区间越界的段（正文小改后残留）直接剔除；剔空 ⇒ null（宁可回落，也不切出空文本）。
 */
object ChapterScriptPlayback {

    /** 读取可复用的本章剧本；任何异常都只当作「未命中」，不能影响朗读 */
    fun readForPlayback(
        bookKey: String,
        chapterIndex: Int,
        paragraphs: List<String>,
        root: File = defaultRoot()
    ): ChapterScript? {
        if (bookKey.isBlank() || paragraphs.isEmpty()) return null
        return runCatching { store(root).reusable(bookKey, chapterIndex, paragraphs) }.getOrNull()
    }

    /** 剧本根（filesDir/scripts）：与 [BuiltinTtsPaths.scriptsDir] 单源 */
    fun defaultRoot(): File = BuiltinTtsPaths.of(appCtx).scriptsDir

    fun store(root: File): ChapterScriptStore = ChapterScriptStore(root)

    /**
     * 对齐校验：剧本必须「同段数 + 段区间落在正文内」，否则返回 null 让调用方回落。
     * 返回的是剔除越界段后的剧本副本（不修改磁盘文件）。
     */
    fun align(script: ChapterScript?, paragraphTexts: List<String>): ChapterScript? {
        if (script == null) return null
        if (script.segments.isEmpty()) return null
        // 指纹数 = 正文段数；不一致说明剧本产自另一次排版/另一份正文，索引会错位
        if (script.paragraphHashes.size != paragraphTexts.size) return null
        val kept = script.segments.filter { segment ->
            val text = paragraphTexts.getOrNull(segment.paragraphIndex) ?: return@filter false
            segment.start >= 0 && segment.end <= text.length && segment.start < segment.end
        }
        if (kept.isEmpty()) return null
        return if (kept.size == script.segments.size) script else script.copy(segments = kept)
    }

    /** 剧本段 → 播报段（离线归因链路口径：narrator / character / thought） */
    fun toOfflineSegments(script: ChapterScript): List<OfflineRoleSegment> {
        return script.segments
            .map { segment ->
                OfflineRoleSegment(
                    paragraphIndex = segment.paragraphIndex,
                    start = segment.start,
                    end = segment.end,
                    roleType = offlineRoleType(segment.roleType),
                    characterName = segment.characterName,
                    characterId = segment.characterId,
                    confidence = segment.confidence,
                    reason = segment.source.ifBlank { "unknown" }
                )
            }
            .sortedWith(compareBy<OfflineRoleSegment> { it.paragraphIndex }
                .thenBy { it.start }
                .thenBy { it.end })
    }

    /** 按段分组（朗读计划按 cue/段取用；顺序稳定便于单测锁定） */
    fun groupsByParagraph(script: ChapterScript): Map<Int, List<ScriptSegment>> {
        return script.segments
            .sortedWith(compareBy<ScriptSegment> { it.paragraphIndex }
                .thenBy { it.start }
                .thenBy { it.end })
            .groupBy { it.paragraphIndex }
    }

    /** 剧本 roleType（narration/dialogue/thought）→ 既有归因 roleType */
    fun offlineRoleType(scriptRoleType: String): String = when (scriptRoleType) {
        "narration", "narrator" -> "narrator"
        "thought" -> "thought"
        else -> "character"
    }

    /**
     * 播放单元表：与 `BaseReadAloudService.contentList` **完全同源**（页面可见文本按行拆分、去空），
     * 剧本指纹/归因/朗读三处必须用同一口径，否则「同一章在不同入口算出不同指纹」⇒ 剧本永远不命中。
     */
    fun unitsOf(chapter: TextChapter?, pageSplit: Boolean): List<String> {
        val textChapter = chapter ?: return emptyList()
        if (!textChapter.isCompleted) return emptyList()
        return runCatching {
            textChapter.getNeedReadAloud(0, pageSplit, 0)
                .split("\n")
                .filter { it.isNotEmpty() }
        }.getOrDefault(emptyList())
    }
}
