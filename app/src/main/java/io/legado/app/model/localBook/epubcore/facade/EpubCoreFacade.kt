package io.legado.app.model.localBook.epubcore.facade

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BookChapter
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.archive.ZipEpubArchive
import io.legado.app.model.localBook.epubcore.cache.EpubCoreMemoryCache
import io.legado.app.model.localBook.epubcore.cache.EpubCoreDiskCache
import io.legado.app.model.localBook.epubcore.font.EpubFontCatalog
import io.legado.app.model.localBook.epubcore.font.EpubTypefaceResolver
import io.legado.app.model.localBook.epubcore.image.EpubImageResolver
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import io.legado.app.model.localBook.epubcore.layout.EpubCorePage
import io.legado.app.model.localBook.epubcore.pkg.EpubPackage
import io.legado.app.model.localBook.epubcore.pkg.EpubPackageParser
import io.legado.app.model.localBook.epubcore.toc.EpubTocParser
import io.legado.app.model.localBook.epubcore.toc.TocItem
import io.legado.app.model.localBook.epubcore.web.EpubWebLayoutAdapter
import io.legado.app.model.localBook.epubcore.web.EpubWebLayoutJsonParser
import io.legado.app.model.localBook.epubcore.web.EpubWebLayoutRequest
import io.legado.app.model.localBook.epubcore.web.EpubWebLayoutSession
import io.legado.app.model.localBook.epubcore.web.EpubWebSelectionAction
import io.legado.app.model.localBook.epubcore.web.EpubWebSelectionLayerSession
import io.legado.app.model.localBook.epubcore.web.EpubWebSelectionPageContext
import io.legado.app.model.localBook.epubcore.web.EpubWebSelectionPayload
import io.legado.app.utils.GSON
import io.legado.app.utils.MD5Utils
import android.os.SystemClock
import splitties.init.appCtx
import java.io.Closeable
import java.io.File

class EpubCoreFacade private constructor(
    private val archive: EpubArchive,
    private val bookUrl: String,
    private val bookSignature: String,
    private val bookCacheDir: File,
    private val pkg: EpubPackage,
    private val toc: List<TocItem>,
    private val cache: EpubCoreMemoryCache = EpubCoreMemoryCache()
) : Closeable {

    private val imageResolver = EpubImageResolver(archive)
    private val webLayoutAdapter = EpubWebLayoutAdapter()
    private var selectionLayerSession: EpubWebSelectionLayerSession? = null
    private val webLayoutSessionLock = Any()
    private var foregroundWebLayoutSession: EpubWebLayoutSession? = null
    private val backgroundWebLayoutSessions = mutableMapOf<Int, EpubWebLayoutSession>()
    private var typefaceResolver: EpubTypefaceResolver? = null
    private val chapters: List<BookChapter> by lazy { buildChapters() }

    fun book(): EpubCoreBook {
        return EpubCoreBook(
            bookUrl = bookUrl,
            metadata = pkg.metadata,
            chapters = chapters(),
            coverHref = pkg.coverHref
        )
    }

    fun chapters(): List<BookChapter> = chapters

    suspend fun paginate(
        chapterIndex: Int,
        config: EpubCoreLayoutConfig,
        backgroundSlot: Int? = null
    ): List<EpubCorePage> {
        val chapter = chapters.getOrNull(chapterIndex) ?: error("Chapter index out of range: $chapterIndex")
        return paginateCanonical(chapter, config, backgroundSlot)
    }

    suspend fun paginate(
        chapter: BookChapter,
        config: EpubCoreLayoutConfig,
        backgroundSlot: Int? = null
    ): List<EpubCorePage> {
        val canonicalChapter = chapters.getOrNull(chapter.index)?.takeIf {
            it.bookUrl == bookUrl && !it.url.startsWith("skip:")
        } ?: chapter
        return paginateCanonical(canonicalChapter, config, backgroundSlot)
    }

    fun peekPages(chapterIndex: Int, config: EpubCoreLayoutConfig): List<EpubCorePage>? {
        val chapter = chapters.getOrNull(chapterIndex) ?: return null
        return peekCanonicalPages(chapter, config)
    }

    fun peekPages(chapter: BookChapter, config: EpubCoreLayoutConfig): List<EpubCorePage>? {
        val canonicalChapter = chapters.getOrNull(chapter.index)?.takeIf {
            it.bookUrl == bookUrl && !it.url.startsWith("skip:")
        } ?: chapter
        return peekCanonicalPages(canonicalChapter, config)
    }

    private fun peekCanonicalPages(
        chapter: BookChapter,
        config: EpubCoreLayoutConfig
    ): List<EpubCorePage>? {
        val resolvedChapter = resolveChapter(chapter)
        val key = pageCacheKey(resolvedChapter, config)
        cache.getPages(key)?.let { return it }
        EpubCoreDiskCache.readLayoutRaw(bookCacheDir, bookSignature, key)?.let { raw ->
            EpubWebLayoutJsonParser.parseJson(raw)?.let { document ->
                val pages = webLayoutAdapter.toPages(document)
                if (pages.isNotEmpty()) {
                    cache.putPages(key, pages)
                    AppLog.putDebug(
                        "EPUB Web layout peek disk hit: chapter=${chapter.index}, href=${EpubPath.stripFragment(resolvedChapter.url)}, pages=${pages.size}"
                    )
                    return pages
                }
            }
        }
        return null
    }

    private suspend fun paginateCanonical(
        chapter: BookChapter,
        config: EpubCoreLayoutConfig,
        backgroundSlot: Int? = null
    ): List<EpubCorePage> {
        val resolvedChapter = resolveChapter(chapter)
        val href = EpubPath.stripFragment(resolvedChapter.url)
        val paint = config.textPaint
        val key = pageCacheKey(resolvedChapter, config)
        cache.getPages(key)?.let {
            AppLog.putDebug("EPUB Web layout cache hit: chapter=${chapter.index}, href=$href, pages=${it.size}")
            return it
        }
        EpubCoreDiskCache.readLayoutRaw(bookCacheDir, bookSignature, key)
            ?.let { raw ->
                runCatching {
                    val document = EpubWebLayoutJsonParser.parseJson(raw)
                    document?.let {
                        val pages = webLayoutAdapter.toPages(it)
                        if (pages.isNotEmpty()) {
                            cache.putPages(key, pages)
                            AppLog.putDebug("EPUB Web layout disk hit: chapter=${chapter.index}, href=$href, pages=${pages.size}")
                            return pages
                        }
                    }
                }
            }
        val startedAt = SystemClock.elapsedRealtime()
        val html = readChapterHtml(resolvedChapter, href)
        val readAt = SystemClock.elapsedRealtime()
        val document = getWebLayoutSession(backgroundSlot).layout(
            buildWebLayoutRequest(resolvedChapter, config, html)
        ) ?: error("EPUB Web layout failed")
        val layoutAt = SystemClock.elapsedRealtime()
        val pages = webLayoutAdapter.toPages(document).takeIf { it.isNotEmpty() }
            ?: error("EPUB Web layout returned empty page")
        val adaptedAt = SystemClock.elapsedRealtime()
        AppLog.putDebug(
            "EPUB Web layout summary: chapter=${chapter.index}:${chapter.title}, " +
                    "href=$href, pages=${pages.size}, fragments=${pages.sumOf { it.fragments.size }}, " +
                    "read=${readAt - startedAt}ms, web=${layoutAt - readAt}ms, adapt=${adaptedAt - layoutAt}ms"
        )
        cache.putPages(key, pages)
        EpubCoreDiskCache.writeLayoutRaw(bookCacheDir, bookSignature, key, io.legado.app.utils.GSON.toJson(document))
        return pages
    }

    private fun buildWebLayoutRequest(
        chapter: BookChapter,
        config: EpubCoreLayoutConfig,
        html: String = readChapterHtml(chapter, EpubPath.stripFragment(chapter.url))
    ): EpubWebLayoutRequest {
        val href = EpubPath.stripFragment(chapter.url)
        val paint = config.textPaint
        val lineHeight = (paint.textSize * config.lineSpacingMultiplier + config.lineSpacingExtraPx)
            .coerceAtLeast(paint.textSize)
        return EpubWebLayoutRequest(
            chapterIndex = chapter.index,
            chapterHref = href,
            title = chapter.title,
            html = html,
            startFragmentId = chapter.startFragmentId,
            endFragmentId = chapter.endFragmentId,
            viewportWidthPx = config.pageWidthPx,
            viewportHeightPx = config.pageHeightPx,
            fontSizePx = paint.textSize,
            textColor = paint.color,
            lineHeightPx = lineHeight,
            readerPaddingLeftPx = config.readerPaddingLeftPx,
            readerPaddingTopPx = config.readerPaddingTopPx,
            readerPaddingRightPx = config.readerPaddingRightPx,
            readerPaddingBottomPx = config.readerPaddingBottomPx,
            readerFontFamily = config.readerFontFamily,
            readerFontUrl = config.readerFontUrl,
            readerFontPath = config.readerFontPath,
            letterSpacingEm = paint.letterSpacing,
            textFullJustify = config.textFullJustify
        )
    }

    private fun pageCacheKey(chapter: BookChapter, config: EpubCoreLayoutConfig): String {
        val href = EpubPath.stripFragment(chapter.url)
        val paint = config.textPaint
        return buildString {
            append(href)
            append('|').append(chapter.startFragmentId.orEmpty())
            append('|').append(chapter.endFragmentId.orEmpty())
            append('|').append(continuationHrefs(chapter).joinToString(","))
            append("|webLayout:v20-elastic-mono")
            append('|').append(if (config.scrollMode) "scroll" else "paged")
            append('|').append(config.pageWidthPx).append('x').append(config.pageHeightPx)
            append('|').append(config.paddingLeftPx).append(',').append(config.paddingTopPx)
            append(',').append(config.paddingRightPx).append(',').append(config.paddingBottomPx)
            append('|').append(config.readerPaddingLeftPx).append(',').append(config.readerPaddingTopPx)
            append(',').append(config.readerPaddingRightPx).append(',').append(config.readerPaddingBottomPx)
            append('|').append(config.paragraphSpacingPx)
            append('|').append(config.alignment)
            append('|').append(config.textFullJustify)
            append('|').append(config.lineSpacingMultiplier).append('|').append(config.lineSpacingExtraPx)
            append('|').append(paint.textSize)
            append('|').append(paint.letterSpacing).append('|').append(paint.typeface?.style ?: 0)
            append('|').append(paint.color)
            append('|').append(config.readerFontFamily.orEmpty())
            append('|').append(config.readerFontUrl.orEmpty())
            append('|').append(config.readerFontPath.orEmpty())
        }
    }

    suspend fun selectText(
        page: EpubCorePage,
        chapterIndex: Int,
        pageIndex: Int,
        config: EpubCoreLayoutConfig,
        action: EpubWebSelectionAction,
        x: Float,
        y: Float
    ): EpubWebSelectionPayload? {
        val chapter = chapters.getOrNull(chapterIndex) ?: return null
        val resolvedChapter = resolveChapter(chapter)
        val href = EpubPath.stripFragment(resolvedChapter.url)
        val paint = config.textPaint
        val lineHeight = (paint.textSize * config.lineSpacingMultiplier + config.lineSpacingExtraPx)
            .coerceAtLeast(paint.textSize)
        val request = EpubWebLayoutRequest(
            chapterIndex = chapter.index,
            chapterHref = href,
            title = chapter.title.ifBlank { resolvedChapter.title },
            html = readChapterHtml(resolvedChapter, href),
            startFragmentId = resolvedChapter.startFragmentId,
            endFragmentId = resolvedChapter.endFragmentId,
            viewportWidthPx = config.pageWidthPx,
            viewportHeightPx = config.pageHeightPx,
            fontSizePx = paint.textSize,
            textColor = paint.color,
            lineHeightPx = lineHeight,
            readerPaddingLeftPx = config.readerPaddingLeftPx,
            readerPaddingTopPx = config.readerPaddingTopPx,
            readerPaddingRightPx = config.readerPaddingRightPx,
            readerPaddingBottomPx = config.readerPaddingBottomPx,
            readerFontFamily = config.readerFontFamily,
            readerFontUrl = config.readerFontUrl,
            readerFontPath = config.readerFontPath,
            letterSpacingEm = paint.letterSpacing,
            textFullJustify = config.textFullJustify
        )
        return getSelectionLayerSession().select(
            request = request,
            pageIndex = pageIndex,
            pageContext = EpubWebSelectionPageContext.from(page),
            action = action,
            x = x,
            y = y
        )
    }

    fun imageResolver(): EpubImageResolver = imageResolver

    fun typefaceResolver(): EpubTypefaceResolver {
        return typefaceResolver ?: EpubTypefaceResolver(
            context = appCtx,
            archive = archive,
            fontFaces = EpubFontCatalog.fromPackage(archive, pkg)
        ).also {
            typefaceResolver = it
        }
    }

    fun cancelBackgroundLayouts() {
        synchronized(webLayoutSessionLock) {
            // Prefetch coroutines own active layout cancellation. Keep the background
            // WebViews warm so page-budget reschedules can reuse the same slots.
            pruneBackgroundWebLayoutSessions()
        }
    }

    fun cancelForegroundLayout() {
        synchronized(webLayoutSessionLock) {
            foregroundWebLayoutSession?.close()
            foregroundWebLayoutSession = null
        }
    }

    override fun close() {
        cache.clear()
        imageResolver.clear()
        typefaceResolver?.clear()
        typefaceResolver = null
        selectionLayerSession?.close()
        selectionLayerSession = null
        cancelForegroundLayout()
        closeBackgroundLayoutSessions()
        archive.close()
    }

    private fun getSelectionLayerSession(): EpubWebSelectionLayerSession {
        return selectionLayerSession ?: EpubWebSelectionLayerSession(archive).also {
            selectionLayerSession = it
        }
    }

    private fun getWebLayoutSession(backgroundSlot: Int?): EpubWebLayoutSession {
        return synchronized(webLayoutSessionLock) {
            if (backgroundSlot == null) {
                foregroundWebLayoutSession ?: EpubWebLayoutSession(archive).also {
                    foregroundWebLayoutSession = it
                }
            } else {
                val slot = backgroundWebLayoutSlot(backgroundSlot)
                backgroundWebLayoutSessions[slot] ?: EpubWebLayoutSession(archive).also {
                    backgroundWebLayoutSessions[slot] = it
                    pruneBackgroundWebLayoutSessions()
                }
            }
        }
    }

    private fun backgroundWebLayoutSlot(backgroundSlot: Int): Int {
        return backgroundSlot.coerceAtLeast(0) % MaxBackgroundWebLayoutSessions
    }

    private fun pruneBackgroundWebLayoutSessions() {
        if (backgroundWebLayoutSessions.size <= MaxBackgroundWebLayoutSessions) return
        val overflowSlots = backgroundWebLayoutSessions.keys
            .sorted()
            .drop(MaxBackgroundWebLayoutSessions)
        overflowSlots.forEach { slot ->
            backgroundWebLayoutSessions.remove(slot)?.close()
        }
    }

    private fun closeBackgroundLayoutSessions() {
        synchronized(webLayoutSessionLock) {
            if (backgroundWebLayoutSessions.isEmpty()) return
            backgroundWebLayoutSessions.values.forEach { it.close() }
            backgroundWebLayoutSessions.clear()
        }
    }

    private fun buildChapters(): List<BookChapter> {
        val flatToc = flattenToc(toc)
        val navHref = pkg.navHref?.let { EpubPath.stripFragment(it) }
        val ncxHref = pkg.ncxHref?.let { EpubPath.stripFragment(it) }
        val coverHref = pkg.coverHref?.let { EpubPath.stripFragment(it) }
        fun isReadableHref(href: String): Boolean {
            val clean = EpubPath.stripFragment(href)
            if (clean.isBlank()) return false
            if (clean == navHref || clean == ncxHref) return false
            if (clean == coverHref) return false
            val fileName = clean.substringAfterLast('/').lowercase()
            if (fileName in setOf("nav.xhtml", "toc.xhtml", "toc.html", "toc.htm", "cover.xhtml", "cover.html", "cover.htm")) {
                return false
            }
            return true
        }
        val readableSpine = pkg.spine.filter { it.linear && isReadableHref(it.href) }
        val spineOrder = readableSpine
            .mapIndexed { order, spineItem -> EpubPath.stripFragment(spineItem.href) to order }
            .toMap()
        data class TocChapterEntry(
            val spineOrder: Int,
            val tocOrder: Int,
            val cleanHref: String?,
            val chapter: BookChapter
        )
        val tocEntries = arrayListOf<TocChapterEntry>()
        var tocOrder = 0
        fun visitToc(item: TocItem): Int? {
            val order = tocOrder++
            val childFirstOrder = item.children.mapNotNull(::visitToc).minOrNull()
            val href = item.href.trim()
            val cleanHref = EpubPath.stripFragment(href)
            val hrefOrder = if (href.isNotBlank() && isReadableHref(href)) {
                spineOrder[cleanHref]
            } else {
                null
            }
            val placement = hrefOrder ?: childFirstOrder
            if (placement != null && item.title.isNotBlank()) {
                val chapter = if (hrefOrder != null) {
                    BookChapter(
                        url = href,
                        title = item.title,
                        isVolume = item.children.isNotEmpty(),
                        baseUrl = pkg.opfPath,
                        bookUrl = bookUrl,
                        startFragmentId = item.fragment ?: EpubPath.fragment(href)
                    )
                } else {
                    BookChapter(
                        url = "skip:$order:${item.title}",
                        title = item.title,
                        isVolume = true,
                        baseUrl = pkg.opfPath,
                        bookUrl = bookUrl
                    )
                }
                tocEntries += TocChapterEntry(
                    spineOrder = placement,
                    tocOrder = order,
                    cleanHref = hrefOrder?.let { cleanHref },
                    chapter = chapter
                )
            }
            return placement
        }
        toc.forEach(::visitToc)
        val entriesBySpineOrder = tocEntries
            .sortedWith(compareBy<TocChapterEntry> { it.spineOrder }.thenBy { it.tocOrder })
            .groupBy { it.spineOrder }
        val chapters = if (readableSpine.isNotEmpty()) {
            val result = arrayListOf<BookChapter>()
            val addedUrls = hashSetOf<String>()
            fun addChapter(chapter: BookChapter) {
                if (!addedUrls.add(chapter.url)) return
                result += chapter
            }
            fun attachContinuationToPrevious(href: String): Boolean {
                val targetIndex = result.indexOfLast { !it.url.startsWith("skip:") && !it.isVolume }
                if (targetIndex < 0) return false
                val target = result[targetIndex]
                val hrefs = (continuationHrefs(target) + href)
                    .map { EpubPath.stripFragment(it) }
                    .filter { it.isNotBlank() }
                    .distinct()
                val variables = target.variableMap.toMutableMap()
                variables[ContinuationHrefsKey] = hrefs.joinToString("\n")
                result[targetIndex] = target.copy(variable = GSON.toJson(variables))
                addedUrls.add(href)
                return true
            }
            readableSpine.forEachIndexed { order, spineItem ->
                val cleanHref = EpubPath.stripFragment(spineItem.href)
                val entries = entriesBySpineOrder[order].orEmpty()
                entries.forEach { addChapter(it.chapter) }
                val hasSpineContent = entries.any { it.cleanHref == cleanHref }
                if (!hasSpineContent) {
                    if (!shouldAttachFallbackSpine(order, cleanHref) || !attachContinuationToPrevious(spineItem.href)) {
                        addChapter(
                            BookChapter(
                                url = spineItem.href,
                                title = "Chapter ${spineItem.index + 1}",
                                baseUrl = pkg.opfPath,
                                bookUrl = bookUrl
                            )
                        )
                    }
                }
            }
            result
        } else {
            flatToc.mapNotNull { item ->
                when {
                    item.href.isNotBlank() && isReadableHref(item.href) -> BookChapter(
                        url = item.href,
                        title = item.title.ifBlank { "Chapter" },
                        isVolume = item.children.isNotEmpty(),
                        baseUrl = pkg.opfPath,
                        bookUrl = bookUrl,
                        startFragmentId = item.fragment ?: EpubPath.fragment(item.href)
                    )
                    item.title.isNotBlank() -> BookChapter(
                        url = "skip:0:${item.title}",
                        title = item.title,
                        isVolume = true,
                        baseUrl = pkg.opfPath,
                        bookUrl = bookUrl
                    )
                    else -> null
                }
            }
        }
        return normalizeChapters(chapters).also {
            AppLog.putDebug(
                "EPUB core chapters built: count=${it.size}, " +
                        "nav=$navHref ncx=$ncxHref cover=$coverHref"
            )
        }
    }

    private fun shouldAttachFallbackSpine(spineOrder: Int, href: String): Boolean {
        if (spineOrder >= FrontMatterFallbackSpineLimit) return false
        val fileName = href.substringAfterLast('/').lowercase()
        return fileName.matches(Regex("""(top|qmp|front|frontmatter|preface|intro|insert)\d*\.(xhtml|html|htm)"""))
    }

    private fun normalizeChapters(chapters: List<BookChapter>): List<BookChapter> {
        return chapters.mapIndexed { index, chapter ->
            val next = chapters.drop(index + 1).firstOrNull { !it.url.startsWith("skip:") }
            val normalizedUrl = if (
                chapter.isVolume &&
                next != null &&
                !chapter.url.startsWith("skip:") &&
                EpubPath.stripFragment(chapter.url) == EpubPath.stripFragment(next.url)
            ) {
                "skip:$index:${chapter.url}"
            } else {
                chapter.url
            }
            chapter.copy(
                index = index,
                bookUrl = bookUrl,
                url = normalizedUrl,
                startFragmentId = if (normalizedUrl.startsWith("skip:")) null else chapter.startFragmentId,
                endFragmentId = next?.startFragmentId,
                variable = GSON.toJson(
                    chapter.variableMap.toMutableMap().apply {
                        val nextUrl = next?.url
                        if (nextUrl.isNullOrBlank()) remove("nextUrl") else put("nextUrl", nextUrl)
                    }
                )
            )
        }
    }

    private fun resolveChapter(chapter: BookChapter): BookChapter {
        if (chapter.url.startsWith("skip:")) {
            chapters.firstOrNull { it.index > chapter.index && !it.url.startsWith("skip:") }?.let {
                return it
            }
        }
        return chapter
    }

    private fun readChapterHtml(chapter: BookChapter, href: String): String {
        val primaryHtml = archive.readText(href)
        val continuationHrefs = continuationHrefs(chapter)
        if (continuationHrefs.isEmpty()) return primaryHtml
        val additions = continuationHrefs.joinToString("\n") { continuationHref ->
            val cleanHref = EpubPath.stripFragment(continuationHref)
            val html = archive.readText(cleanHref)
            val head = extractHeadContent(html)
            val body = extractBodyContent(html)
            """$head<section data-epub-continuation-href="${escapeHtmlAttr(cleanHref)}">$body</section>"""
        }
        val bodyClose = Regex("</body\\s*>", RegexOption.IGNORE_CASE)
        val bodyCloseMatch = bodyClose.find(primaryHtml)
        return if (bodyCloseMatch != null) {
            primaryHtml.substring(0, bodyCloseMatch.range.first) +
                additions +
                primaryHtml.substring(bodyCloseMatch.range.first)
        } else {
            "$primaryHtml\n$additions"
        }
    }

    private fun continuationHrefs(chapter: BookChapter): List<String> {
        return chapter.variableMap[ContinuationHrefsKey]
            ?.lineSequence()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?.toList()
            .orEmpty()
    }

    private fun extractBodyContent(html: String): String {
        val match = Regex("<body\\b[^>]*>([\\s\\S]*?)</body\\s*>", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.getOrNull(1) ?: html
    }

    private fun extractHeadContent(html: String): String {
        val match = Regex("<head\\b[^>]*>([\\s\\S]*?)</head\\s*>", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }.orEmpty()
    }

    private fun escapeHtmlAttr(value: String): String {
        return value
            .replace("&", "&amp;")
            .replace("\"", "&quot;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    private fun flattenToc(items: List<TocItem>): List<TocItem> {
        val result = ArrayList<TocItem>()
        fun visit(item: TocItem) {
            result.add(item)
            item.children.forEach(::visit)
        }
        items.forEach(::visit)
        return result
    }

    companion object {
        private const val MaxBackgroundWebLayoutSessions = 5
        private const val ContinuationHrefsKey = "epubContinuationHrefs"
        private const val FrontMatterFallbackSpineLimit = 20

        fun open(
            file: File,
            bookUrl: String = file.absolutePath,
            bookCacheDir: File
        ): EpubCoreFacade {
            val archive = ZipEpubArchive(file)
            return try {
                val pkg = EpubPackageParser().parse(archive)
                val toc = EpubTocParser().parse(archive, pkg)
                EpubCoreFacade(
                    archive = archive,
                    bookUrl = bookUrl,
                    bookSignature = MD5Utils.md5Encode16("${file.absolutePath}|${file.length()}|${file.lastModified()}|epubCoreSchema=2"),
                    bookCacheDir = bookCacheDir,
                    pkg = pkg,
                    toc = toc
                )
            } catch (throwable: Throwable) {
                archive.close()
                throw throwable
            }
        }
    }
}

