package io.legado.app.help.rss

import io.legado.app.constant.AppPattern
import io.legado.app.data.entities.RssSource
import io.legado.app.utils.splitNotBlank

/**
 * W4 / REQ-19（AD-09）：OPML 2.0 导出器（纯逻辑，可 JVM 单测）。
 *
 * 导出规则（与 [OpmlParser] 的扁平标签口径对齐）：
 * - 每个**扁平标签**导出为一个**单层** `outline`（不含嵌套）⇒ Feedly / Inoreader 可直接导入；
 * - 无分组源直接挂在 `body` 下；
 * - 输出 **UTF-8 + XML 声明**；特殊字符转义；
 * - 不写 `htmlUrl`：本应用订阅模型无「站点首页」字段（`sortUrl` 是分类地址，语义不同）
 *   ⇒ 不臆造数据（导入侧仍兼容读取外部 OPML 的 htmlUrl）。
 *
 * 往返判据声明：**标签集合一致**（非层级树一致）—— 多级嵌套在导入时已被扁平化。
 */
object OpmlExporter {

    private const val INDENT = "  "

    fun export(sources: List<RssSource>): String {
        // 标签 → 该标签下的源（同一源可有多个标签 ⇒ 多处出现，导入时按 xmlUrl 去重合并）
        val grouped = LinkedHashMap<String, MutableList<RssSource>>()
        val ungrouped = ArrayList<RssSource>()
        sources.forEach { source ->
            val tags = source.sourceGroup.orEmpty()
                .splitNotBlank(AppPattern.splitGroupRegex)
                .filter { it.isNotBlank() }
            when {
                tags.isEmpty() -> ungrouped += source
                else -> tags.forEach { tag -> grouped.getOrPut(tag) { ArrayList() } += source }
            }
        }

        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<opml version=\"2.0\">\n")
            append(INDENT).append("<head>\n")
            append(INDENT).append(INDENT).append("<title>").append(escape("订阅源"))
                .append("</title>\n")
            append(INDENT).append("</head>\n")
            append(INDENT).append("<body>\n")
            grouped.forEach { (tag, list) ->
                append(INDENT).append(INDENT)
                    .append("<outline text=\"").append(escape(tag))
                    .append("\" title=\"").append(escape(tag)).append("\">\n")
                list.forEach { appendFeed(it, INDENT.repeat(3)) }
                append(INDENT).append(INDENT).append("</outline>\n")
            }
            ungrouped.forEach { appendFeed(it, INDENT.repeat(2)) }
            append(INDENT).append("</body>\n")
            append("</opml>\n")
        }
    }

    private fun StringBuilder.appendFeed(source: RssSource, indent: String) {
        append(indent)
            .append("<outline type=\"rss\" text=\"").append(escape(source.sourceName))
            .append("\" title=\"").append(escape(source.sourceName))
            .append("\" xmlUrl=\"").append(escape(source.sourceUrl))
            .append("\"/>\n")
    }

    private fun escape(raw: String): String = buildString(raw.length + 8) {
        raw.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }
}