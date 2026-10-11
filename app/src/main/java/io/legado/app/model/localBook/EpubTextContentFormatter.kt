package io.legado.app.model.localBook

import io.legado.app.utils.HtmlFormatter
import org.jsoup.nodes.TextNode
import org.jsoup.select.Elements

/**
 * EPUB 章节正文归一（迁移自 archive v15 `model/localBook/EpubTextContentFormatter.kt`）。
 *
 * **抽取理由（两点，第二点是真实可见缺陷）**：
 * 1. 该逻辑原**内联在 `EpubFile.getContent()`** 中 ⇒ jsoup 归一本质是纯函数，却被绑在依赖
 *    `ContentResolver` 的类里，**无任何单测覆盖**；
 * 2. 原内联实现**未剔除 `script`/`style`** —— 而 [HtmlFormatter.formatKeepImg] 只按**标签名**
 *    过滤（`notImgHtmlRegex`），因此 `<style>`/`<script>` 的**元素内容**（CSS/JS 源码文本）
 *    会原样留在正文中当可见文本显示。
 *
 * 归一清单：
 * - 剔除 `title/script/style/link/meta`（**连同其文本内容**，这正是"删元素"与"只剥标签"的差别）；
 * - 剔除 `display:none`（兼容 `display:none` 与 `display: none` 两种书写）与 HTML5 `hidden` 元素；
 * - 封面占位图 `img[src="cover.jpeg"]` 只保留第一张（多资源拼接时同一封面会重复出现）；
 * - `img` 只保留 `src`（显式清掉 `width/height/style` 等，尺寸交给阅读器样式决定）；
 * - `img` 的破图与懒加载语义分开处理（见 [format] 内注释），不误删 `data-src` 类图；
 * - 按需剔除注音 `ruby`：去掉 `rp`/`rt` 后把文本并回相邻文本节点，避免留下多余空格。
 */
internal object EpubTextContentFormatter {

    /**
     * @param elements 章节正文（多个资源拼接后的 DOM 片段）
     * @param removeRubyAnnotations 是否剔除注音（跟随书籍「去除标签」设置）
     */
    fun format(elements: Elements, removeRubyAnnotations: Boolean): String {
        elements.select("title,script,style,link,meta").remove()
        elements.select("[style*=display:none], [style*=display: none], [hidden]").remove()
        elements.select("img[src=\"cover.jpeg\"]").forEachIndexed { index, image ->
            if (index > 0) image.remove()
        }
        elements.select("img").forEach { image ->
            val hasSrc = image.hasAttr("src")
            val src = image.attr("src")
            when {
                // src 存在但为空 ⇒ 必然是破图，直接剔除
                hasSrc && src.isBlank() -> image.remove()
                // 无 src（如 data-src/data-original 懒加载）⇒ 原样透传，交 HtmlFormatter 的回退属性解析
                !hasSrc -> return@forEach
                // 只有 src 一个属性 ⇒ 无可清理，保持原样（避免无谓重建节点）
                image.attributesSize() <= 1 -> return@forEach
                else -> {
                    image.clearAttributes()
                    image.attr("src", src)
                }
            }
        }
        if (removeRubyAnnotations) {
            elements.select("ruby").forEach { ruby ->
                ruby.select("rp, rt").remove()
                val textNode = TextNode(ruby.text())
                ruby.replaceWith(textNode)
                val previous = textNode.previousSibling()
                val next = textNode.nextSibling()
                // 与前后文本节点合并 ⇒ 否则注音剔除处会留下多余空格（对齐 legado-E PR#451）
                when {
                    previous is TextNode -> {
                        previous.text(previous.text() + textNode.text())
                        textNode.remove()
                        if (next is TextNode) {
                            previous.text(previous.text() + next.text())
                            next.remove()
                        }
                    }

                    next is TextNode -> {
                        textNode.text(textNode.text() + next.text())
                        next.remove()
                    }
                }
            }
        }
        return HtmlFormatter.formatKeepImg(elements.outerHtml())
    }
}