package io.legado.app.model.localBook.epubcore.facade

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.util.IdentityHashMap

/**
 * Cuts a logical chapter out of an XHTML resource that is shared by several
 * TOC entries.  The complete source document must remain in the source cache;
 * this operation is deliberately applied to a fresh parse for each window.
 *
 * The interval is [start, end): the element identified by [startFragmentId]
 * and all following content are kept, while the element identified by
 * [endFragmentId] and everything after it are removed.  Only body descendants
 * are touched, so the publication head, stylesheets, scripts and document
 * metadata remain intact.
 */
internal object EpubDirectFragmentWindowPolicy {

    data class Result(
        val html: String,
        val applied: Boolean
    )

    fun apply(
        sourceHtml: String,
        startFragmentId: String?,
        endFragmentId: String?
    ): Result {
        val startId = normalizeFragment(startFragmentId)
        val endId = normalizeFragment(endFragmentId)
        if (startId == null && endId == null) return Result(sourceHtml, false)
        if (sourceHtml.isBlank()) return Result(sourceHtml, false)

        return try {
            val document = parse(sourceHtml) ?: return Result(sourceHtml, false)
            val body = document.body() ?: return Result(sourceHtml, false)
            val start = startId?.let { findTarget(document, it) }
            val end = endId?.let { findTarget(document, it) }

            // A missing boundary is unsafe to interpret as a successful slice. The
            // runtime can still use its original fragment-window logic in this case.
            if ((startId != null && start == null) || (endId != null && end == null)) {
                return Result(sourceHtml, false)
            }
            if (start != null && !isBodyDescendant(body, start)) return Result(sourceHtml, false)
            if (end != null && !isBodyDescendant(body, end)) return Result(sourceHtml, false)

            val ranges = IdentityHashMap<Node, NodeRange>()
            var cursor = 0
            fun index(node: Node) {
                val begin = cursor++
                node.childNodes().forEach(::index)
                ranges[node] = NodeRange(begin, cursor)
            }
            index(body)

            val bodyRange = ranges[body] ?: return Result(sourceHtml, false)
            val lower = start?.let { ranges[it]?.start } ?: firstBodyChildStart(body, ranges)
            val upper = end?.let { ranges[it]?.start } ?: bodyRange.end
            if (lower == null || lower >= upper) return Result(sourceHtml, false)

            // A start after an end is an invalid/ambiguous TOC range. Do not
            // destructively reinterpret it.
            if (start != null && end != null) {
                val startRange = ranges[start] ?: return Result(sourceHtml, false)
                val endRange = ranges[end] ?: return Result(sourceHtml, false)
                if (startRange.start >= endRange.start) return Result(sourceHtml, false)
            }

            trimOutside(body, lower, upper, ranges)
            document.outputSettings()
                .prettyPrint(false)
                .syntax(Document.OutputSettings.Syntax.xml)
            Result(document.outerHtml(), true)
        } catch (_: StackOverflowError) {
            Result(sourceHtml, false)
        } catch (_: RuntimeException) {
            Result(sourceHtml, false)
        }
    }

    private fun parse(sourceHtml: String): Document? {
        return runCatching {
            Jsoup.parse(sourceHtml, "", Parser.xmlParser())
        }.getOrElse {
            runCatching { Jsoup.parse(sourceHtml) }.getOrNull()
        }
    }

    private fun findTarget(document: Document, fragmentId: String): Element? {
        val candidates = fragmentCandidates(fragmentId)
        val body = document.body()
        // Search body first. An id in head (for example a stylesheet marker)
        // must never become a chapter boundary.
        candidates.forEach { candidate ->
            body.allElements.firstOrNull { element ->
                element.attr("id") == candidate ||
                    element.attr("xml:id") == candidate ||
                    (element.normalName().equals("a", true) && element.attr("name") == candidate)
            }?.let { return it }
        }
        return null
    }

    private fun fragmentCandidates(value: String): List<String> {
        val result = LinkedHashSet<String>()
        var current = value
        repeat(3) {
            if (!result.add(current)) return@repeat
            val decoded = runCatching {
                // '+' is a valid fragment character; URLDecoder would turn it
                // into a space unless it is protected first.
                URLDecoder.decode(current.replace("+", "%2B"), Charsets.UTF_8.name())
            }.getOrNull() ?: return@repeat
            if (decoded == current) return@repeat
            current = decoded
        }
        return result.toList()
    }

    private fun normalizeFragment(value: String?): String? {
        return value
            ?.trim()
            ?.removePrefix("#")
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isBodyDescendant(body: Element, node: Node): Boolean {
        var current: Node? = node
        while (current != null) {
            if (current === body) return true
            current = current.parentNode()
        }
        return false
    }

    private fun firstBodyChildStart(
        body: Element,
        ranges: IdentityHashMap<Node, NodeRange>
    ): Int? {
        return body.childNodes().firstOrNull()?.let { ranges[it]?.start }
    }

    private fun trimOutside(
        parent: Node,
        lower: Int,
        upper: Int,
        ranges: IdentityHashMap<Node, NodeRange>
    ) {
        // Snapshot children because removal mutates Jsoup's child list.
        parent.childNodes().toList().forEach { child ->
            val range = ranges[child] ?: return@forEach
            if (range.end <= lower || range.start >= upper) {
                child.remove()
            } else if (range.start < lower || range.end > upper) {
                trimOutside(child, lower, upper, ranges)
            }
        }
    }

    private data class NodeRange(
        val start: Int,
        val end: Int
    )
}
