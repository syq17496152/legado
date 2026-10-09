package io.legado.app.model.localBook.epubcore.direct

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Canonical ordinary-book text shared by the visible document and read aloud. */
object TextReaderDocument {
    const val MAX_SOURCE_CHARS = 2 * 1024 * 1024
    const val MAX_BLOCKS = 20_000
    data class InlineImage(val offset: Int, val image: TextReaderImage)
    data class Block(val text: String = "", val image: TextReaderImage? = null,
                     val inlineImages: List<InlineImage> = emptyList())
    data class Content(val title: String, val blocks: List<Block>) {
        fun images(): List<TextReaderImage> = blocks.flatMap { block ->
            listOfNotNull(block.image) + block.inlineImages.map { it.image }
        }

        fun imageActions(): Map<String, TextReaderImageAction> = buildMap {
            blocks.forEach { block ->
                (listOfNotNull(block.image) + block.inlineImages.map { it.image }).forEach { image ->
                    image.click?.let { put(image.id, TextReaderImageAction(image.source, it)) }
                }
            }
        }

        fun paragraphs(includeTitle: Boolean): List<String> = buildList {
            if (includeTitle && title.isNotBlank()) add(title)
            blocks.filter { it.image == null && it.text.isNotEmpty() }.forEach { add(it.text) }
        }

        fun plainText(includeTitle: Boolean): String = paragraphs(includeTitle).let {
            if (it.isEmpty()) "" else it.joinToString("\n", postfix = "\n")
        }

        fun html(includeTitle: Boolean, sourceActionsEnabled: Boolean = true, deferredImages: Boolean = false,
                 imageUrl: (String) -> String): String = htmlWithImages(includeTitle, sourceActionsEnabled, deferredImages) {
            imageUrl(it.renderSource)
        }

        internal fun htmlWithImages(includeTitle: Boolean, sourceActionsEnabled: Boolean = true,
                                    deferredImages: Boolean = false,
                                    preparedImages: Map<String, TextReaderPreparedImage> = emptyMap(),
                                    imageUrl: (TextReaderImage) -> String): String {
            val document = org.jsoup.nodes.Document.createShell("")
            document.outputSettings().prettyPrint(false)
            document.head().appendElement("meta").attr("charset", "UTF-8")
            document.head().appendElement("title").text(title)
            document.head().appendElement("style").appendText(
                "body[data-legado-text-reader] .legado-text-image-frame{" +
                    "display:inline-flex;align-items:center;vertical-align:baseline;line-height:normal;" +
                    "text-indent:0;max-width:100%;}" +
                    // The invisible ideographic space supplies the reading font's
                    // actual CJK baseline. CSS 'middle' uses the Latin x-height and
                    // drops short-comment artwork below the surrounding characters.
                    "body[data-legado-text-reader] .legado-text-image-frame::before{" +
                    "content:'\\3000';white-space:pre;visibility:hidden;flex:none;width:0;}" +
                    "body[data-legado-text-reader] .legado-text-inline-image," +
                    "body[data-legado-text-reader] .legado-text-bubble{" +
                    "display:inline-block;height:1em;width:auto;max-height:1.5em;max-width:100%;" +
                    "vertical-align:middle;object-fit:contain;margin:0 .12em;}" +
                    // Match the native paragraph bubble's CJK-width baseline. A
                    // managed bubble replaces the source image's size as well as
                    // its pixels; ordinary images keep their explicit dimensions.
                    // Bound tall package artwork to the same box so it cannot
                    // stretch a text line across an entire page.
                    "body[data-legado-text-reader] .legado-text-bubble{" +
                    "width:1.5556em!important;height:auto!important;max-height:1.5556em!important;}" +
                    "body[data-legado-text-reader] a[data-legado-image-action]{" +
                    "color:inherit;text-decoration:none;}" +
                    "body[data-legado-text-reader] a[data-legado-image-action]," +
                    "body[data-legado-text-reader] img[data-legado-image-id]{" +
                    "-webkit-tap-highlight-color:transparent;}"
            )
            val body = document.body().attr("data-legado-text-reader", "true")
            fun appendImage(parent: Element, image: TextReaderImage) {
                // An anchor makes Android's native hit test consume the tap before
                // the asynchronous JavaScript bridge can race with page turning.
                val container = if (sourceActionsEnabled && image.click != null) {
                    parent.appendElement("a").attr("href", "#__legado_source_image_${image.id}")
                        .attr("data-legado-image-action", image.id)
                        .also { if (image.inline) it.addClass("legado-text-image-frame") }
                } else if (image.inline) {
                    parent.appendElement("span").addClass("legado-text-image-frame")
                } else parent
                val prepared = preparedImages[image.id]
                val url = prepared?.dataUri ?: imageUrl(image)
                // Locally prepared bubbles participate in the ordinary image decode
                // gate. They already have their final pixels and geometry at first layout.
                val deferred = prepared == null && deferredImages && !url.startsWith("data:image/", true)
                val element = container.appendElement("img")
                    .attr("src", if (deferred) LOADING_IMAGE else url).attr("alt", "")
                    .attr("data-legado-image-id", image.id)
                if (deferred) {
                    element.attr("data-legado-image-resource", url).attr("data-legado-image-state", "pending")
                        .attr("aria-label", "图片加载中")
                }
                if (image.inline) element.addClass("legado-text-inline-image")
                if (prepared?.isBubble == true) element.addClass("legado-text-bubble")
                val style = buildString {
                    image.width?.let { append("width:$it;") }
                    image.height?.let { append("height:$it;") }
                    if (image.inline && image.width != null && image.height == null) append("height:auto;")
                    // Keep the same scale contract as deferred bubble presentation.
                    if (prepared?.isBubble == true && prepared.scale in .5f..1.5f && prepared.scale != 1f) {
                        append("font-size:${prepared.scale * 100f}%;")
                    }
                }
                if (style.isNotEmpty()) element.attr("style", style)
            }
            var offset = 0
            if (includeTitle && title.isNotBlank()) {
                body.appendElement("h2").addClass("reader-chapter-title")
                    .attr("data-reader-block", "title").attr("data-reader-kind", "title")
                    .attr("data-legado-text-offset", "0").text(title)
                offset = title.length + 1
            }
            blocks.forEachIndexed { index, block ->
                if (block.image != null) {
                    val figure = body.appendElement("figure")
                        .attr("data-reader-block", "block-$index").attr("data-reader-kind", "image")
                    block.image.alignment?.let { figure.attr("style", "text-align:$it;") }
                    appendImage(figure, block.image)
                } else if (block.text.isNotEmpty() || block.inlineImages.isNotEmpty()) {
                    val paragraph = body.appendElement("p")
                        .attr("data-reader-block", "block-$index")
                        .attr("data-reader-kind", if (block.text.isNotEmpty()) "paragraph" else "image")
                    // Prose keeps its indentation even when inline artwork makes it
                    // unsuitable for the pure-text line grid. Image-only rows do not.
                    if (block.text.isNotEmpty()) {
                        paragraph.addClass("reader-paragraph")
                            .attr("data-legado-text-offset", offset.toString())
                    }
                    var copied = 0
                    block.inlineImages.forEach { inline ->
                        paragraph.appendText(block.text.substring(copied, inline.offset))
                        appendImage(paragraph, inline.image)
                        copied = inline.offset
                    }
                    paragraph.appendText(block.text.substring(copied))
                    if (block.text.isNotEmpty()) offset += block.text.length + 1
                }
            }
            if (body.childrenSize() == 0) body.appendElement("p")
                .attr("data-reader-block", "empty").attr("data-reader-kind", "placeholder")
                .text("本章暂无正文")
            return document.outerHtml()
        }
    }

    // A visible, bounded placeholder keeps source image click targets usable while
    // the independent image worker is loading. It contributes no spoken characters.
    private const val LOADING_IMAGE = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='32' height='24' viewBox='0 0 32 24'%3E%3Crect x='1' y='1' width='30' height='22' rx='4' fill='%23888888' fill-opacity='.18'/%3E%3Cpath d='M7 12h18' stroke='%23888888' stroke-width='2'/%3E%3C/svg%3E"

    private val markup = Regex("<\\s*(?:html|body|p|br|div|img|usehtml|span|h[1-6]|section|article|blockquote|ul|ol|li|table|pre|b|i|em|strong|a|script|style)\\b", RegexOption.IGNORE_CASE)

    fun prepare(title: String, source: String): Content {
        check(source.length <= MAX_SOURCE_CHARS) { "本章内容过大，EPUB 渲染暂不支持，请切回原生渲染" }
        val blocks = ArrayList<Block>()
        fun appendBlock(block: Block) {
            check(blocks.size < MAX_BLOCKS) { "本章段落过多，EPUB 渲染暂不支持，请切回原生渲染" }
            blocks.add(block)
        }
        fun appendText(text: String) {
            text.lineSequence().map { it.trim { c -> c.isWhitespace() || c == '\u3000' } }
                .filter { it.isNotEmpty() }.forEach { appendBlock(Block(text = it)) }
        }
        val raw = source.removePrefix("\ufeff").replace("\r\n", "\n").replace('\r', '\n')
        if (markup.containsMatchIn(raw)) {
            val images = TextReaderImageMarkup.prepare(raw)
            val doc = Jsoup.parseBodyFragment(images.html)
            doc.select("script,style,noscript,template,iframe,object").remove()
            val text = StringBuilder()
            val inlineImages = ArrayList<InlineImage>()
            fun flushText() {
                var start = 0
                var imageIndex = 0
                while (start <= text.length) {
                    val end = text.indexOf("\n", start).takeIf { it >= 0 } ?: text.length
                    var trimmedStart = start
                    var trimmedEnd = end
                    while (trimmedStart < end && text[trimmedStart].isWhitespace()) trimmedStart++
                    while (trimmedEnd > trimmedStart && text[trimmedEnd - 1].isWhitespace()) trimmedEnd--
                    val line = text.substring(trimmedStart, trimmedEnd)
                    val lineImages = ArrayList<InlineImage>()
                    while (imageIndex < inlineImages.size && inlineImages[imageIndex].offset <= end) {
                        val inline = inlineImages[imageIndex++]
                        lineImages.add(inline.copy(offset = (inline.offset - trimmedStart).coerceIn(0, line.length)))
                    }
                    if (line.isNotEmpty() || lineImages.isNotEmpty()) appendBlock(Block(text = line, inlineImages = lineImages))
                    if (end == text.length) break
                    start = end + 1
                }
                text.setLength(0)
                inlineImages.clear()
            }
            // wholeText alone joins adjacent block elements with no separator. Traverse
            // iteratively so nested markup keeps its paragraph boundaries without tokens.
            NodeTraversor.traverse(object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when (node) {
                        is TextNode -> text.append(node.wholeText)
                        is Element -> when {
                            node.normalName() == "img" -> {
                                val image = node.attr("data-legado-image-index").toIntOrNull()
                                    ?.let(images.images::getOrNull)
                                if (image?.inline == true) inlineImages.add(InlineImage(text.length, image))
                                else if (image != null) {
                                    flushText()
                                    appendBlock(Block(image = image))
                                }
                            }
                            node.isBlock || node.normalName() == "br" -> text.append('\n')
                        }
                    }
                }
                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.isBlock) text.append('\n')
                }
            }, doc.body())
            flushText()
        } else appendText(raw)
        // A title already present in a downloaded chapter must not be spoken twice.
        if (blocks.firstOrNull()?.let { it.image == null && it.inlineImages.isEmpty() && it.text == title.trim() } == true) blocks.removeAt(0)
        return Content(title.trim(), blocks)
    }
}
