package io.legado.app.model.localBook.epubcore.direct

import io.legado.app.model.localBook.epubcore.EpubRegex
import org.jsoup.nodes.Document

/**
 * 五态分类中枢：判定一个 spine 文档是「可重排纯文字」还是「出版方控制型/固定版式/媒体/交互」。
 *
 * 判定依据（出版方声明 + DOM 结构 + 屏幕相关 CSS 三重证据）：
 * - 媒体型（image/video/audio 或紧凑媒体页）⇒ MEDIA
 * - Duokan 图库 / 含脚本的固定画布 ⇒ INTERACTIVE
 * - `rendition:layout=pre-paginated` 或固定画布/整页图（无出版方排版）⇒ FIXED
 * - 含出版方语义排版（多栏/竖排/绝对定位/变换）⇒ PUBLISHER_STYLED
 * - 其余 ⇒ REFLOWABLE（可套用阅读器主题）
 *
 * 迁移自 archive v15（纯算法，未改）。
 */
internal object EpubDirectContentClassifier {

    data class Profile(
        val layoutMode: EpubDirectLayoutMode,
        val viewportWidth: Float?,
        val viewportHeight: Float?,
        val fullPageArtwork: Boolean,
        val implicitSinglePage: Boolean,
        val duokanGallery: Boolean,
        val scripted: Boolean = false,
        val publisherPageBackground: Boolean = false
    )

    fun classify(
        renditionLayout: String?,
        spineProperties: Set<String>,
        manifestProperties: Set<String>,
        mediaType: String?,
        sourceHtml: String,
        publisherCss: String = ""
    ): EpubDirectLayoutMode {
        return analyze(
            renditionLayout = renditionLayout,
            spineProperties = spineProperties,
            manifestProperties = manifestProperties,
            mediaType = mediaType,
            sourceHtml = sourceHtml,
            publisherCss = publisherCss
        ).layoutMode
    }

    fun analyze(
        renditionLayout: String?,
        spineProperties: Set<String>,
        manifestProperties: Set<String>,
        mediaType: String?,
        sourceHtml: String,
        packageViewportWidth: Float? = null,
        packageViewportHeight: Float? = null,
        publisherCss: String = ""
    ): Profile {
        return analyze(
            renditionLayout = renditionLayout,
            spineProperties = spineProperties,
            manifestProperties = manifestProperties,
            mediaType = mediaType,
            parsedSource = EpubDirectParsedSource(sourceHtml),
            packageViewportWidth = packageViewportWidth,
            packageViewportHeight = packageViewportHeight,
            publisherCss = publisherCss
        )
    }

    internal fun analyze(
        renditionLayout: String?,
        spineProperties: Set<String>,
        manifestProperties: Set<String>,
        mediaType: String?,
        parsedSource: EpubDirectParsedSource,
        packageViewportWidth: Float? = null,
        packageViewportHeight: Float? = null,
        publisherCss: String = ""
    ): Profile {
        val normalizedType = mediaType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val document = parsedSource.document()
        val documentViewport = document?.publisherViewport()
        val viewport = documentViewport ?: if (
            packageViewportWidth != null && packageViewportHeight != null &&
            packageViewportWidth > 0f && packageViewportHeight > 0f
        ) {
            packageViewportWidth to packageViewportHeight
        } else {
            null
        }
        val fullPageArtwork = document?.isFullPageArtwork() == true || normalizedType.startsWith("image/")
        val decorativeSinglePage = document?.isDecorativeSinglePage() == true
        val implicitSinglePage = fullPageArtwork || decorativeSinglePage
        val duokanGallery = document?.hasDuokanGallery() == true
        val scripted = (spineProperties + manifestProperties).any { it.equals("scripted", true) }
        val publisherPageBackground = document?.hasPublisherPageBackground(publisherCss) == true
        if (normalizedType.startsWith("image/") ||
            normalizedType.startsWith("video/") ||
            normalizedType.startsWith("audio/") ||
            document?.isCompactMediaPage() == true
        ) {
            return Profile(
                layoutMode = EpubDirectLayoutMode.MEDIA,
                viewportWidth = viewport?.first,
                viewportHeight = viewport?.second,
                fullPageArtwork = fullPageArtwork,
                implicitSinglePage = true,
                duokanGallery = false,
                scripted = scripted,
                publisherPageBackground = publisherPageBackground
            )
        }

        val properties = (spineProperties + manifestProperties).mapTo(HashSet()) { it.lowercase() }
        val explicitlyReflowable = "rendition:layout-reflowable" in properties
        val explicitlyFixed = !explicitlyReflowable && (
            renditionLayout.equals("pre-paginated", true) ||
                "rendition:layout-pre-paginated" in properties
            )
        if (duokanGallery || (scripted && (explicitlyFixed || document?.looksLikeFixedCanvas(publisherCss) == true))) {
            return Profile(
                EpubDirectLayoutMode.INTERACTIVE,
                viewport?.first,
                viewport?.second,
                fullPageArtwork,
                true,
                duokanGallery,
                scripted,
                publisherPageBackground
            )
        }

        val publisherStyled = document?.hasPublisherLayout(publisherCss) == true
        val mode = if (explicitlyFixed ||
            (!explicitlyReflowable &&
                (document?.looksLikeFixedCanvas(publisherCss) == true ||
                    (fullPageArtwork && !publisherStyled)))
        ) {
            EpubDirectLayoutMode.FIXED
        } else if (publisherStyled) {
            EpubDirectLayoutMode.PUBLISHER_STYLED
        } else {
            EpubDirectLayoutMode.REFLOWABLE
        }
        return Profile(
            mode,
            viewport?.first,
            viewport?.second,
            fullPageArtwork,
            implicitSinglePage,
            duokanGallery,
            scripted,
            publisherPageBackground
        )
    }

    private fun Document.hasDuokanGallery(): Boolean = select(".duokan-image-gallery").any { gallery ->
        gallery.children().count { it.hasClass("duokan-image-gallery-cell") } >= 2
    }

    private fun Document.hasPublisherPageBackground(publisherCss: String): Boolean {
        if (select("[background]").any { it.attr("background").isNotBlank() }) return true
        val pageRoots = listOfNotNull(selectFirst("html"), body())
        if (pageRoots.any { element ->
                element.hasAttr("bgcolor") || hasVisibleBackgroundDeclaration(element.attr("style"))
            }
        ) {
            return true
        }
        val css = buildString {
            screenStyleSources().forEach { append(it).append('\n') }
            append(EpubDirectCssMediaPolicy.screenRelevantCss(publisherCss))
        }
        return PUBLISHER_BACKGROUND_IMAGE.containsMatchIn(css)
    }

    private fun hasVisibleBackgroundDeclaration(style: String): Boolean {
        return PUBLISHER_BACKGROUND_DECLARATION.findAll(style).any { match ->
            val value = match.groupValues.getOrNull(1)
                ?.substringBefore("!important")
                ?.trim()
                ?.lowercase()
                .orEmpty()
            value.isNotBlank() && value !in TRANSPARENT_BACKGROUND_VALUES &&
                value != "rgba(0, 0, 0, 0)" && value != "rgba(0,0,0,0)"
        }
    }

    private fun Document.isCompactMediaPage(): Boolean {
        val body = body() ?: return false
        if (body.select("video,audio").isEmpty()) return false
        val meaningfulChildren = body.children().filterNot {
            it.normalName() == "script" || it.normalName() == "style" || it.normalName() == "link"
        }
        return meaningfulChildren.size <= 4 && body.text().length <= 240
    }

    private fun Document.looksLikeFixedCanvas(publisherCss: String): Boolean {
        val body = body() ?: return false
        val visual = body.selectFirst("img,svg,video,canvas,object") ?: return false
        val svgCanvas = visual.normalName() == "svg" &&
            body.childrenSize() == 1 &&
            (visual.hasAttr("viewBox") || (visual.hasAttr("width") && visual.hasAttr("height")))
        if (publisherViewport() == null && !svgCanvas) return false
        val compactText = body.text().replace(WHITESPACE, "")
        if (compactText.length > FIXED_TEXT_LIMIT || body.allElements.size > FIXED_ELEMENT_LIMIT) return false
        if (svgCanvas || body.childrenSize() == 1 || isDecorativeSinglePage()) return true
        val css = buildString {
            screenStyleSources().forEach { append(it).append('\n') }
            body.select("[style]").forEach { append(it.attr("style")).append('\n') }
            append(EpubDirectCssMediaPolicy.screenRelevantCss(publisherCss))
        }
        return FIXED_POSITION.containsMatchIn(css)
    }

    private fun Document.hasPublisherLayout(publisherCss: String): Boolean {
        if (hasPublisherSemanticLayout()) return true
        val snippets = buildList {
            screenStyleSources().forEach { source ->
                source.split('}').filterTo(this) { it.isNotBlank() }
            }
            select("[style]").forEach { add(it.attr("style")) }
            EpubDirectCssMediaPolicy.screenRelevantCss(publisherCss).split('}')
                .filterTo(this) { it.isNotBlank() }
        }
        if (snippets.any(PUBLISHER_COLUMNS::containsMatchIn) ||
            snippets.any(PUBLISHER_WRITING_MODE::containsMatchIn)
        ) return true
        if (snippets.any { snippet ->
                PUBLISHER_POSITION.containsMatchIn(snippet) &&
                    (PUBLISHER_INSET.containsMatchIn(snippet) ||
                        PUBLISHER_BOX_EDGE.findAll(snippet).count() >= 2)
            }
        ) return true
        val compactViewportCanvas = publisherViewport() != null &&
            body().text().length <= PUBLISHER_CANVAS_TEXT_LIMIT
        return compactViewportCanvas && snippets.any(PUBLISHER_TRANSFORM::containsMatchIn)
    }

    private fun Document.screenStyleSources(): Sequence<String> {
        return select("style").asSequence()
            .filterNot { it.hasAttr("disabled") }
            .filter { EpubDirectCssMediaPolicy.mayApplyToScreen(it.attr("media")) }
            .map { EpubDirectCssMediaPolicy.screenRelevantCss(it.data().ifBlank { it.html() }) }
    }

    private fun Document.hasPublisherSemanticLayout(): Boolean {
        val body = body() ?: return false
        val meaningfulChildren = body.children().filterNot(::isMetadataElement)
        if (meaningfulChildren.size != 1 || body.text().length > 1200) return false
        val wrapper = meaningfulChildren.single()
        if (wrapper.select("p").size !in 1..8) return false
        if (wrapper.select("img,svg,canvas,video,audio,table,ol,ul").isNotEmpty()) return false
        return sequenceOf(body, wrapper).any { element ->
            element.classNames().any { className ->
                PUBLISHER_SEMANTIC_CLASSES.any { keyword -> className.contains(keyword, true) }
            }
        }
    }

    private fun Document.isFullPageArtwork(): Boolean {
        val body = body() ?: return false
        // 封面/扉页常带隐藏的无障碍标题：它是元数据而非可见内容，不得因此进入可重排文字管线。
        val visibleBody = body.clone()
        visibleBody.select("script,style,noscript,template,[hidden],[aria-hidden=true]").remove()
        visibleBody.select("[style]").filter { element ->
            DISPLAY_NONE.containsMatchIn(element.attr("style"))
        }.forEach { it.remove() }
        if (visibleBody.text().isNotBlank()) return false
        return body.selectFirst("img,svg,video,canvas") != null
    }

    private fun Document.isDecorativeSinglePage(): Boolean {
        val body = body() ?: return false
        val meaningfulChildren = body.children().filterNot(::isMetadataElement)
        if (meaningfulChildren.size == 1 && body.text().length <= 800) {
            val wrapper = meaningfulChildren.single()
            val hasBackgroundArtwork = body.attr("style").contains("url(", true) &&
                (wrapper.select("table,ol,ul,svg,img,canvas").isNotEmpty() ||
                    sequenceOf(body, wrapper).hasClassKeyword(DECORATIVE_PAGE_CLASSES))
            if (hasBackgroundArtwork) return true
        }
        if (meaningfulChildren.size !in 2..3 || body.text().length > 120) return false
        if (body.select("p,blockquote,ul,ol,table").isNotEmpty()) return false
        val headings = meaningfulChildren.filter { it.normalName() in HEADING_TAGS }
        if (headings.size != 1) return false
        val artwork = meaningfulChildren.filterNot { it in headings }
        if (artwork.isEmpty() || artwork.any { element ->
                element.text().isNotBlank() &&
                    element.normalName() !in VISUAL_TAGS &&
                    element.select("img,svg,canvas").isEmpty()
            }
        ) {
            return false
        }
        if (body.select("img,svg,canvas").size != 1) return false
        return (sequenceOf(body) + meaningfulChildren.asSequence())
            .hasClassKeyword(TITLE_PAGE_CLASSES)
    }

    private fun Sequence<org.jsoup.nodes.Element>.hasClassKeyword(keywords: Set<String>): Boolean {
        return any { element ->
            element.classNames().any { className -> keywords.any { className.contains(it, true) } }
        }
    }

    private fun isMetadataElement(element: org.jsoup.nodes.Element): Boolean {
        return element.normalName() == "script" ||
            element.normalName() == "style" ||
            element.normalName() == "link"
    }

    private fun Document.publisherViewport(): Pair<Float, Float>? {
        val content = selectFirst("meta[name=viewport]")?.attr("content").orEmpty()
        val width = VIEWPORT_WIDTH.find(content)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val height = VIEWPORT_HEIGHT.find(content)?.groupValues?.getOrNull(1)?.toFloatOrNull()
        if (width != null && height != null && width > 0f && height > 0f) return width to height
        val svg = selectFirst("svg[viewBox],svg[viewbox]")
        val values = svg?.attr("viewBox")?.ifBlank { svg.attr("viewbox") }
            ?.trim()?.split(WHITESPACE_OR_COMMA)?.mapNotNull(String::toFloatOrNull)
        return if (values != null && values.size >= 4 && values[2] > 0f && values[3] > 0f) {
            values[2] to values[3]
        } else {
            null
        }
    }

    private const val FIXED_TEXT_LIMIT = 80
    private const val FIXED_ELEMENT_LIMIT = 40
    private const val PUBLISHER_CANVAS_TEXT_LIMIT = 2000
    private val PUBLISHER_SEMANTIC_CLASSES = setOf(
        "box",
        "card",
        "notice",
        "copyright",
        "imprint",
        "credits",
        "colophon"
    )
    private val DECORATIVE_PAGE_CLASSES = setOf(
        "intro",
        "cover",
        "role",
        "character",
        "gallery",
        "catalog",
        "poster"
    )
    private val TITLE_PAGE_CLASSES = setOf(
        "volume",
        "cover",
        "divider",
        "title-page",
        "titlepage",
        "frontispiece"
    )
    private val HEADING_TAGS = setOf("h1", "h2", "h3", "h4", "h5", "h6")
    private val VISUAL_TAGS = setOf("img", "svg", "canvas")
    private val WHITESPACE = EpubRegex.compile("\\s+")
    private val WHITESPACE_OR_COMMA = EpubRegex.compile("[\\s,]+")
    private val FIXED_POSITION = EpubRegex.compile(
        "(?:position\\s*:\\s*(?:absolute|fixed)|transform\\s*:)",
        RegexOption.IGNORE_CASE
    )
    private val PUBLISHER_POSITION = EpubRegex.compile(
        "position\\s*:\\s*(?:absolute|fixed)",
        RegexOption.IGNORE_CASE
    )
    private val PUBLISHER_INSET = EpubRegex.compile(
        "(?:^|[;{\\s])inset(?:-[a-z]+)?\\s*:",
        RegexOption.IGNORE_CASE
    )
    private val PUBLISHER_BOX_EDGE = EpubRegex.compile(
        "(?:^|[;{\\s])(?:top|right|bottom|left|width|height)\\s*:",
        RegexOption.IGNORE_CASE
    )
    private val PUBLISHER_TRANSFORM = EpubRegex.compile(
        "(?:^|[;{\\s])transform\\s*:",
        RegexOption.IGNORE_CASE
    )
    private val PUBLISHER_COLUMNS = EpubRegex.compile(
        "(?:-webkit-)?column-(?:count|width|gap|fill)\\s*:",
        RegexOption.IGNORE_CASE
    )
    private val PUBLISHER_WRITING_MODE = EpubRegex.compile(
        "(?:-webkit-)?writing-mode\\s*:",
        RegexOption.IGNORE_CASE
    )
    private val PUBLISHER_BACKGROUND_IMAGE = EpubRegex.compile(
        "background(?:-image)?\\s*:[^;{}]*(?:url\\s*\\(|(?:repeating-)?(?:linear|radial|conic)-gradient\\s*\\()",
        RegexOption.IGNORE_CASE
    )
    private val DISPLAY_NONE = EpubRegex.compile(
        "(?:^|[;\\s])display\\s*:\\s*none(?:\\s*!important)?(?:\\s*;|$)",
        RegexOption.IGNORE_CASE
    )
    private val PUBLISHER_BACKGROUND_DECLARATION = EpubRegex.compile(
        "(?:^|;)\\s*background(?:-color|-image)?\\s*:\\s*([^;]+)",
        RegexOption.IGNORE_CASE
    )
    private val TRANSPARENT_BACKGROUND_VALUES = setOf(
        "none",
        "transparent",
        "initial",
        "inherit",
        "unset",
        "revert",
        "revert-layer"
    )
    private val VIEWPORT_WIDTH = EpubRegex.compile(
        "(?:^|[,;\\s])width\\s*=\\s*([0-9.]+)",
        RegexOption.IGNORE_CASE
    )
    private val VIEWPORT_HEIGHT = EpubRegex.compile(
        "(?:^|[,;\\s])height\\s*=\\s*([0-9.]+)",
        RegexOption.IGNORE_CASE
    )
}