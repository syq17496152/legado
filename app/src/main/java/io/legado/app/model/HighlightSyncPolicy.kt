package io.legado.app.model

import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookHighlight

/**
 * 划线锚点同步策略（**纯函数，零 Android 依赖**，JVM 可直接行为测试）。
 *
 * 背景：`BookHighlight.chapterIndex` 会随目录更新插章 / 切换「反转目录」整体平移，
 * 只按 index 定位会让划线落到**错误章节**（此前无任何自愈机制）。
 * [BookHighlight.chapterUrl] 是稳定锚，[BookHighlight.chapterName] 是次选锚，
 * index 仅作为老数据的兜底。
 *
 * 职责边界：本对象**只做决策**（返回需回写的修正清单），落库与 UI 刷新由
 * `ReadBook.loadHighlights` 负责 —— 策略因此可脱离 `appDb` 单测。
 */
object HighlightSyncPolicy {

    /**
     * 一条「需回写」的锚点修正。
     *
     * @param index 修正后的章节下标（渲染按 `BookChapter.index` 取值）
     * @param url 修正后的章节地址（老数据首次回填时由空变为真实地址）
     */
    data class AnchorFix(
        val highlight: BookHighlight,
        val index: Int,
        val url: String
    )

    /**
     * 计算需要回写的锚点修正；**已正确**的划线不出现在结果里（避免无意义落库与重绘）。
     *
     * 解析优先级：
     *  · ① `chapterUrl` 非空且能在当前目录命中 ⇒ 用该章（URL 是稳定锚，跨插章/反转都成立）
     *  · ② `chapterUrl` 未命中 ⇒ 回退 `chapterName` 精确匹配（换源后地址全变但标题常留存）
     *  · ③ 两者皆无/皆未命中 ⇒ 位置兜底：当 `chapterName` 为空、或与「同下标章节标题一致」时，
     *    认定原下标仍有效并据此**回填 URL**（只做一次，之后即受 ①② 保护）
     *  · ③′ 位置兜底的标题不一致 ⇒ 判为无法定位，**不动该划线**（宁可保持原样，也不锚错章节）
     */
    fun planAnchorFixes(
        highlights: List<BookHighlight>,
        chapters: List<BookChapter>
    ): List<AnchorFix> {
        if (highlights.isEmpty() || chapters.isEmpty()) return emptyList()
        val byUrl = HashMap<String, BookChapter>(chapters.size)
        val byTitle = HashMap<String, BookChapter>(chapters.size)
        for (chapter in chapters) {
            if (chapter.url.isNotBlank()) byUrl.putIfAbsent(chapter.url, chapter)
            if (chapter.title.isNotBlank()) byTitle.putIfAbsent(chapter.title, chapter)
        }
        val fixes = ArrayList<AnchorFix>()
        for (highlight in highlights) {
            val target = resolve(highlight, chapters, byUrl, byTitle)
            if (target == null) continue
            if (target.index == highlight.chapterIndex && target.url == highlight.chapterUrl) continue
            fixes.add(AnchorFix(highlight, target.index, target.url))
        }
        return fixes
    }

    private fun resolve(
        highlight: BookHighlight,
        chapters: List<BookChapter>,
        byUrl: Map<String, BookChapter>,
        byTitle: Map<String, BookChapter>
    ): BookChapter? {
        if (highlight.chapterUrl.isNotBlank()) {
            byUrl[highlight.chapterUrl]?.let { return it }
        }
        if (highlight.chapterName.isNotBlank()) {
            byTitle[highlight.chapterName]?.let { return it }
        }
        // 位置兜底：仅当存的就是「本下标所指章节」（名称为空或与同下标章节标题一致）才认，
        // 否则说明目录已整体重排且无可用锚 ⇒ 返回 null 保持原样（不误锚）
        val atPosition = chapters.firstOrNull { it.index == highlight.chapterIndex } ?: return null
        if (highlight.chapterName.isNotBlank() && atPosition.title != highlight.chapterName) return null
        return atPosition
    }
}
