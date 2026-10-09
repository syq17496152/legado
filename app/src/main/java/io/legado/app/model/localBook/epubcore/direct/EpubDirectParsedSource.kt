package io.legado.app.model.localBook.epubcore.direct

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser

/**
 * 持有「Direct 五态分类」与「文档装配」共用的**唯一一次** Jsoup 解析结果。
 * 约定：分类结束前不得改动该 Document；分类完成后 builder 才可变异它。
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal class EpubDirectParsedSource(
    val sourceHtml: String,
    private val parser: (String) -> Document? = { html ->
        if (html.isBlank()) null else {
            runCatching { Jsoup.parse(html, "", Parser.xmlParser()) }.getOrNull()
        }
    }
) {

    @Volatile
    private var attempted = false
    private var parsedDocument: Document? = null

    /** 惰性且只解析一次；解析失败返回 null（不抛异常）。 */
    fun document(): Document? {
        if (attempted) return parsedDocument
        synchronized(this) {
            if (!attempted) {
                parsedDocument = parser(sourceHtml)
                attempted = true
            }
            return parsedDocument
        }
    }
}