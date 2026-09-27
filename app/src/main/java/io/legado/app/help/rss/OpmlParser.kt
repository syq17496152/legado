package io.legado.app.help.rss

import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** OPML 中的单条订阅（分组已按「扁平标签」规则收敛）。 */
data class OpmlFeed(
    val title: String,
    val xmlUrl: String,
    val htmlUrl: String?,
    /** 扁平化后的分组标签（多级嵌套按 `父/子/孙` 拼成**单个**标签）；空 = 无分组 */
    val groupTags: List<String>
)

/** 解析结果（错误原因用枚举 ⇒ 解析器零 UI 依赖，文案由调用方映射）。 */
sealed class OpmlParseResult {

    data class Ok(
        val feeds: List<OpmlFeed>,
        /** 被扁平化的**多级**分组路径数（回执用；单层分组不计入） */
        val flattenedGroups: Int
    ) : OpmlParseResult()

    data class Error(val reason: Reason) : OpmlParseResult()

    enum class Reason { TOO_LARGE, TOO_DEEP, INVALID, EMPTY }
}

/**
 * W4 / REQ-19（AD-09）：OPML 2.0 解析器（纯逻辑，可 JVM 单测）。
 *
 * 规范口径：
 * - `outline` 递归；订阅节点特征 = 带 `xmlUrl` 属性；标题取 `title`，回落 `text`；
 * - **分组映射**：项目分组模型是**逗号分隔扁平串**（`RssSource.sourceGroup`，无层级语义）
 *   ⇒ 多级嵌套按 `父/子/孙` 用 `/` 拼成**单个扁平标签**（避免与逗号分隔符冲突），
 *   并统计「被扁平化的多级分组数」供回执 —— **不承诺层级 100% 还原**；
 * - **安全（AD-18）**：禁用 DTD 与外部实体（XXE）；**文件 ≤ 2MB**、**嵌套深度 ≤ 8**，超限拒绝；
 * - 解析失败/非法结构一律返回 [OpmlParseResult.Error]，不抛异常。
 */
object OpmlParser {

    /** 文件大小上限（仅本地文件解析，2MB 足够容纳万级订阅）。 */
    const val MAX_BYTES = 2 * 1024 * 1024

    /** 嵌套深度上限（含 body 之下的 outline 层数）。 */
    const val MAX_DEPTH = 8

    /** 多级分组扁平化时的连接符（与逗号分隔符冲突，故选 `/`）。 */
    const val GROUP_SEPARATOR = "/"

    fun parse(bytes: ByteArray, charset: java.nio.charset.Charset = Charsets.UTF_8): OpmlParseResult {
        if (bytes.size > MAX_BYTES) return OpmlParseResult.Error(OpmlParseResult.Reason.TOO_LARGE)
        val text = runCatching { String(bytes, charset) }.getOrNull()
            ?: return OpmlParseResult.Error(OpmlParseResult.Reason.INVALID)
        return parseText(text)
    }

    /** 文本入口（单测直接喂字符串）。 */
    fun parseText(text: String): OpmlParseResult {
        if (text.isBlank()) return OpmlParseResult.Error(OpmlParseResult.Reason.INVALID)
        val doc = runCatching {
            newBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
        }.getOrNull() ?: return OpmlParseResult.Error(OpmlParseResult.Reason.INVALID)

        val root = doc.documentElement ?: return OpmlParseResult.Error(OpmlParseResult.Reason.INVALID)
        if (!root.nodeName.equals("opml", ignoreCase = true)) {
            return OpmlParseResult.Error(OpmlParseResult.Reason.INVALID)
        }
        val body = directChildren(root).firstOrNull { it.nodeName.equals("body", ignoreCase = true) }
            ?: return OpmlParseResult.Error(OpmlParseResult.Reason.INVALID)

        val feeds = ArrayList<OpmlFeed>()
        val multiLevelPaths = HashSet<String>()
        /** xmlUrl → feeds 下标：同一订阅在多个分组下重复出现时**合并标签**（保「标签集合一致」判据） */
        val feedIndexOf = HashMap<String, Int>()
        var tooDeep = false

        fun walk(element: Element, path: List<String>, depth: Int) {
            if (tooDeep) return
            if (depth > MAX_DEPTH) {
                tooDeep = true
                return
            }
            for (child in directChildren(element)) {
                if (!child.nodeName.equals("outline", ignoreCase = true)) continue
                val xmlUrl = attr(child, "xmlUrl")?.trim().orEmpty()
                if (xmlUrl.isNotEmpty()) {
                    val tag = if (path.isEmpty()) null else path.joinToString(GROUP_SEPARATOR)
                    if (tag != null && path.size >= 2) multiLevelPaths.add(tag)
                    val index = feedIndexOf[xmlUrl]
                    if (index == null) {
                        feedIndexOf[xmlUrl] = feeds.size
                        feeds += OpmlFeed(
                            title = titleOf(child) ?: xmlUrl,
                            xmlUrl = xmlUrl,
                            htmlUrl = attr(child, "htmlUrl"),
                            groupTags = if (tag == null) emptyList() else listOf(tag)
                        )
                    } else if (tag != null) {
                        // 同一订阅在多分组下重复出现 ⇒ 合并标签（不丢分组）
                        val old = feeds[index]
                        if (tag !in old.groupTags) {
                            feeds[index] = old.copy(groupTags = old.groupTags + tag)
                        }
                    }
                    // 订阅节点内部若还有嵌套（少见），沿用同一层级继续遍历
                    walk(child, path, depth + 1)
                } else {
                    val name = titleOf(child)
                    walk(child, if (name == null) path else path + name, depth + 1)
                }
            }
        }

        walk(body, emptyList(), 1)
        if (tooDeep) return OpmlParseResult.Error(OpmlParseResult.Reason.TOO_DEEP)
        if (feeds.isEmpty()) return OpmlParseResult.Error(OpmlParseResult.Reason.EMPTY)
        return OpmlParseResult.Ok(feeds, multiLevelPaths.size)
    }

    /** DTD/外部实体一律禁用的构建器（AD-18）；个别实现不支持某 feature 时静默跳过。 */
    private fun newBuilder() = DocumentBuilderFactory.newInstance().apply {
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isExpandEntityReferences = false
        isNamespaceAware = false
    }.newDocumentBuilder()

    private fun directChildren(element: Element): List<Element> {
        val list = ArrayList<Element>()
        val nodes = element.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) list += node as Element
        }
        return list
    }

    /** 属性读取（OPML 各导出方大小写不一，`xmlUrl` 与 `xmlurl` 都收）。 */
    private fun attr(element: Element, name: String): String? {
        element.getAttribute(name)?.takeIf { it.isNotEmpty() }?.let { return it }
        element.getAttribute(name.lowercase())?.takeIf { it.isNotEmpty() }?.let { return it }
        return null
    }

    private fun titleOf(element: Element): String? =
        attr(element, "title")?.takeIf { it.isNotBlank() } ?: attr(element, "text")?.takeIf { it.isNotBlank() }
}