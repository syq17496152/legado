package io.legado.app.model.localBook.epubcore.direct

import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * 逻辑章节在源 XHTML 中的片段边界（同一 XHTML 内可切出多个逻辑章节）。
 */
internal data class EpubDirectFragmentBoundary(
    val chapterIndex: Int,
    val startFragmentId: String?,
    val endFragmentId: String?
)

/**
 * 源 XHTML 的锚点位置索引：按文档序记录每个 id/xml:id/a[name] 的序号，
 * 用于把「链接里的 fragment」判定到所属逻辑章节。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal class EpubDirectFragmentIndex private constructor(
    private val positions: Map<String, Int>
) {

    fun owner(
        fragmentId: String?,
        candidates: List<EpubDirectFragmentBoundary>,
        currentChapterIndex: Int
    ): Int? {
        if (candidates.isEmpty()) return null
        val fragment = fragmentId?.takeIf { it.isNotBlank() }
        candidates.firstOrNull { fragment != null && it.startFragmentId == fragment }?.let {
            return it.chapterIndex
        }
        val targetPosition = fragment?.let(positions::get)
        if (targetPosition != null) {
            candidates.mapNotNull { candidate ->
                val startPosition = candidate.startFragmentId
                    ?.let(positions::get)
                    ?: if (candidate.startFragmentId.isNullOrBlank()) 0 else return@mapNotNull null
                val endPosition = candidate.endFragmentId
                    ?.let(positions::get)
                    ?: Int.MAX_VALUE
                candidate.takeIf { targetPosition >= startPosition && targetPosition < endPosition }
                    ?.let { startPosition to it.chapterIndex }
            }.maxByOrNull { it.first }?.let { return it.second }
        }
        return candidates.firstOrNull { it.chapterIndex == currentChapterIndex }?.chapterIndex
            ?: candidates.first().chapterIndex
    }

    companion object {
        fun parse(sourceHtml: String): EpubDirectFragmentIndex {
            val positions = LinkedHashMap<String, Int>()
            val document = runCatching {
                Jsoup.parse(sourceHtml, "", Parser.xmlParser())
            }.getOrNull()
            val root = document?.selectFirst("body") ?: document
            root?.allElements?.forEachIndexed { position, element ->
                sequenceOf(
                    element.attr("id"),
                    element.attr("xml:id"),
                    element.attr("name").takeIf { element.normalName() == "a" }.orEmpty()
                ).filter { it.isNotBlank() }.forEach { id ->
                    positions.putIfAbsent(id, position)
                }
            }
            return EpubDirectFragmentIndex(positions)
        }
    }
}