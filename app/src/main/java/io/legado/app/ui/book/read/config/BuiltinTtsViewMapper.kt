package io.legado.app.ui.book.read.config

import io.legado.app.help.readaloud.role.OfflineRoleResolver
import io.legado.app.model.ReadBook

/**
 * 管理页视图数据映射（展示层纯读取，不含任何写操作）：
 * 分镜行 / 分镜提示 / 角色识别摘要。抽成独立文件便于 Activity 保持在行数门禁内，
 * 也便于未来单测（只依赖 ReadBook 内存态与离线缓存）。
 */
object BuiltinTtsViewMapper {

    /** 分镜行（T5.5）：只展示当前章已识别的段；未识别段标"未识别"，不静默归旁白 */
    fun storyboard(bookKey: String?): List<StoryboardRow> {
        if (bookKey == null) return emptyList()
        val chapter = ReadBook.curTextChapter ?: return emptyList()
        val segments = OfflineRoleResolver.cached(bookKey, chapter.chapter.index) ?: return emptyList()
        val paragraphs = chapter.paragraphs.map { it.text }
        return segments.map { segment ->
            val paragraph = paragraphs.getOrNull(segment.paragraphIndex).orEmpty()
            StoryboardRow(
                paragraphIndex = segment.paragraphIndex,
                start = segment.start,
                end = segment.end,
                text = BuiltinTtsUiSupport.storyboardSnippet(paragraph, segment.start, segment.end),
                roleLabel = BuiltinTtsUiSupport.storyboardLabel(
                    roleType = segment.roleType,
                    recognized = segment.recognized,
                    characterName = segment.characterName
                ),
                confidence = segment.confidence,
                narrator = segment.roleType == "narrator",
                recognized = segment.recognized
            )
        }
    }

    fun storyboardHint(bookKey: String?): String = when {
        bookKey == null -> "请先打开一本书"
        ReadBook.curTextChapter == null -> "请先在阅读页打开当前章节"
        storyboard(bookKey).isEmpty() -> "本章还没有分镜：先点「识别角色」（离线，零网络）"
        else -> "点任意一段可改绑角色；改动只写本地缓存，不会触发联网"
    }

    /** 角色识别摘要（无书时用调用方给的本地化文案，避免把资源取色/文案耦合进来） */
    fun roleSummary(bookKey: String?, needBookText: String): String {
        if (bookKey == null) return needBookText
        val chapter = ReadBook.curTextChapter
        val segments = chapter?.let { OfflineRoleResolver.cached(bookKey, it.chapter.index) }
        if (segments.isNullOrEmpty()) {
            return "尚未识别（开读或点“识别角色”即可）"
        }
        val recognized = segments.count { it.recognized }
        return "本章 ${segments.size} 段，命中角色 $recognized 段"
    }
}
