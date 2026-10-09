package io.legado.app.model.localBook.epubcore.direct

import android.net.Uri
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import io.legado.app.model.localBook.epubcore.layout.EpubCoreLayoutConfig
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.util.LinkedHashMap
import java.util.concurrent.CountDownLatch

/**
 * Direct 内容会话：**章节级**惰性加载 + 有界 LRU + 并发去重 + 引用计数租约。
 *
 * 职责（迁移自 archive v15 `EpubDirectModels.kt` 的 `EpubDirectSession`，按职责拆为独立文件）：
 * - [prepareChapter]：按「章号 + 布局配置 + 源版本」为键加载并缓存章节；
 * - [openResource]/[openHeadResource]：以虚拟 origin（`https://epub.local/...`）回源资源，
 *   交出的 [InputStream] 持有租约，读完/关闭才释放 ⇒ 会话 [close] 不会过早关闭底层压缩包；
 * - [resolveLink]/[adjacentChapterIndex] 等：目录/相邻章节解析（回调注入，便于宿主定制）。
 */
class EpubDirectSession internal constructor(
    val bookUrl: String,
    val resourceHost: String = HOST,
    private val chapterLoader: (Int, EpubCoreLayoutConfig) -> EpubDirectChapter,
    private val resourceLoader: (String, String?) -> EpubDirectResource?,
    private val headResourceLoader: (String, String?) -> EpubDirectResource? = resourceLoader,
    private val linkResolver: (String, Int) -> EpubDirectLinkTarget?,
    private val readableChapterResolver: (Int, Int) -> Int? = { index, _ -> index },
    private val adjacentChapterResolver: (Int, Int) -> Int? = { index, direction ->
        index + if (direction < 0) -1 else 1
    },
    private val adjacentLogicalChapterResolver: (Int, Int) -> Int? = adjacentChapterResolver,
    private val closeAction: () -> Unit,
    private val maxCachedChapters: Int = DEFAULT_MAX_CACHED_CHAPTERS,
    private val maxCachedChapterChars: Int = DEFAULT_MAX_CACHED_CHAPTER_CHARS,
    val sourceRevision: Long = 0L,
    private val onCloseRequested: () -> Unit = {},
    private val chapterRevisionProvider: () -> String = { "" }
) : Closeable {

    @Volatile
    private var closed = false

    val isClosed: Boolean
        get() = closed

    private var activeLeases = 0
    private var closeActionInvoked = false
    private val chapterLoads = HashMap<String, PendingChapter>()
    private var chapterCacheSequence = 0L
    private val chapterCacheGroups = HashMap<String, Long>()
    private val chapterCache = LinkedHashMap<String, CachedChapter>(4, 0.75f, true)
    private var chapterCacheChars = 0

    init {
        require(maxCachedChapters > 0)
        require(maxCachedChapterChars > 0)
    }

    fun prepareChapter(
        chapterIndex: Int,
        config: EpubCoreLayoutConfig
    ): EpubDirectChapter {
        val key = chapterCacheKey(chapterIndex, config) + chapterRevisionProvider().let {
            if (it.isEmpty()) "" else "|source-images:$it"
        }
        return prepareChapterForKey(key, chapterIndex.toString()) {
            chapterLoader(chapterIndex, config).let { loaded ->
                if (loaded.chapterIndex == chapterIndex) loaded else loaded.copy(chapterIndex = chapterIndex)
            }
        }
    }

    fun resolveReadableChapterIndex(index: Int, preferredDirection: Int = 1): Int? {
        check(!closed) { "EPUB direct session is closed" }
        return readableChapterResolver(index, preferredDirection)
    }

    fun adjacentChapterIndex(index: Int, direction: Int): Int? {
        check(!closed) { "EPUB direct session is closed" }
        return adjacentChapterResolver(index, direction)
    }

    fun adjacentLogicalChapterIndex(index: Int, direction: Int): Int? {
        check(!closed) { "EPUB direct session is closed" }
        return adjacentLogicalChapterResolver(index, direction)
    }

    internal fun prepareChapterForKey(
        key: String,
        cacheGroup: String? = null,
        loader: () -> EpubDirectChapter
    ): EpubDirectChapter {
        val pending: PendingChapter
        val ownsLoad: Boolean
        synchronized(this) {
            check(!closed) { "EPUB direct session is closed" }
            chapterCache[key]?.chapter?.let {
                cacheGroup?.let { group ->
                    chapterCacheGroups[group] = ++chapterCacheSequence
                }
                return it
            }
            val existing = chapterLoads[key]
            if (existing != null) {
                cacheGroup?.let { group ->
                    val generation = ++chapterCacheSequence
                    chapterCacheGroups[group] = generation
                    if (existing.cacheGroup == group) existing.cacheGeneration = generation
                }
                pending = existing
                ownsLoad = false
            } else {
                val cacheGeneration = if (cacheGroup == null) {
                    0L
                } else {
                    (++chapterCacheSequence).also { chapterCacheGroups[cacheGroup] = it }
                }
                pending = PendingChapter(cacheGroup, cacheGeneration)
                chapterLoads[key] = pending
                activeLeases++
                ownsLoad = true
            }
        }
        if (!ownsLoad) return pending.await()

        return try {
            val loaded = loader()
            val result = synchronized(this) {
                val current = chapterCache[key]?.chapter
                val mayCache = pending.cacheGroup == null ||
                    chapterCacheGroups[pending.cacheGroup] == pending.cacheGeneration
                when {
                    closed -> loaded
                    current != null -> current
                    !mayCache -> loaded
                    else -> loaded.also {
                        pending.cacheGroup?.let { group ->
                            chapterCache.entries
                                .filter { entry -> entry.key != key && entry.value.group == group }
                                .map { entry -> entry.key }
                                .forEach(::removeCachedChapterLocked)
                        }
                        putCachedChapterLocked(key, pending.cacheGroup, it)
                    }
                }
            }
            pending.complete(result)
            result
        } catch (throwable: Throwable) {
            pending.fail(throwable)
            throw throwable
        } finally {
            synchronized(this) {
                if (chapterLoads[key] === pending) chapterLoads.remove(key)
            }
            releaseLease()
        }
    }

    fun openResource(url: String, rangeHeader: String?): EpubDirectResource? {
        return openResource(url, rangeHeader, resourceLoader)
    }

    fun openHeadResource(url: String, rangeHeader: String?): EpubDirectResource? {
        return openResource(url, rangeHeader, headResourceLoader)
    }

    private fun openResource(
        url: String,
        rangeHeader: String?,
        loader: (String, String?) -> EpubDirectResource?
    ): EpubDirectResource? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals(SCHEME, true) || !uri.host.equals(resourceHost, true)) return null
        val encodedPath = uri.encodedPath?.removePrefix("/") ?: return null
        val path = EpubPath.normalize(Uri.decode(encodedPath))
        if (path.isBlank()) return null
        return openResourcePath(path, rangeHeader, loader)
    }

    internal fun openResourcePath(path: String, rangeHeader: String?): EpubDirectResource? {
        return openResourcePath(path, rangeHeader, resourceLoader)
    }

    private fun openResourcePath(
        path: String,
        rangeHeader: String?,
        loader: (String, String?) -> EpubDirectResource?
    ): EpubDirectResource? {
        synchronized(this) {
            if (closed) return null
            activeLeases++
        }
        val resource = try {
            loader(path, rangeHeader)
        } catch (throwable: Throwable) {
            releaseLease()
            throw throwable
        }
        if (resource == null) {
            releaseLease()
            return null
        }
        return resource.copy(
            stream = ManagedResourceInputStream(resource.stream, ::releaseLease)
        )
    }

    fun resolveLink(url: String, currentChapterIndex: Int): EpubDirectLinkTarget? {
        synchronized(this) {
            if (closed) return null
            activeLeases++
        }
        return try {
            linkResolver(url, currentChapterIndex)
        } finally {
            releaseLease()
        }
    }

    override fun close() {
        val invokeCloseAction = synchronized(this) {
            if (closed) return
            closed = true
            chapterCache.clear()
            chapterCacheChars = 0
            chapterCacheGroups.clear()
            claimCloseActionLocked()
        }
        try {
            onCloseRequested()
        } finally {
            if (invokeCloseAction) closeAction()
        }
    }

    private fun releaseLease() {
        val invokeCloseAction = synchronized(this) {
            check(activeLeases > 0) { "EPUB session lease underflow" }
            activeLeases--
            claimCloseActionLocked()
        }
        if (invokeCloseAction) closeAction()
    }

    private fun claimCloseActionLocked(): Boolean {
        if (!closed || activeLeases > 0 || closeActionInvoked) return false
        closeActionInvoked = true
        return true
    }

    private fun putCachedChapterLocked(
        key: String,
        group: String?,
        chapter: EpubDirectChapter
    ) {
        removeCachedChapterLocked(key)
        val weight = chapter.cacheWeightChars()
        if (weight > maxCachedChapterChars) return
        while (chapterCache.isNotEmpty() &&
            (chapterCache.size >= maxCachedChapters || chapterCacheChars + weight > maxCachedChapterChars)
        ) {
            removeCachedChapterLocked(chapterCache.entries.first().key)
        }
        chapterCache[key] = CachedChapter(group, chapter, weight)
        chapterCacheChars += weight
    }

    private fun removeCachedChapterLocked(key: String) {
        chapterCache.remove(key)?.let { cached ->
            chapterCacheChars -= cached.weightChars
        }
    }

    private fun EpubDirectChapter.cacheWeightChars(): Int {
        val imageActionChars = sourceImages?.actions?.entries?.sumOf { (id, action) ->
            id.length.toLong() + action.source.length + action.click.length
        } ?: 0L
        val imageResourceChars = sourceImages?.resources?.entries?.sumOf { (path, request) ->
            val image = request.image
            path.length.toLong() + request.chapterUrl.length + request.renderRevision.length +
                image.source.length + image.renderSource.length + (image.click?.length ?: 0)
        } ?: 0L
        return html.length.toLong()
            .plus(plainText.length)
            .plus(templateSourceHtml?.length ?: 0)
            .plus(readerTemplate?.let { it.firstPageHtml.length.toLong() + it.otherPageHtml.length + it.scrollHtml.length + it.css.length + it.javascript.length } ?: 0L)
            .plus(imageActionChars)
            .plus(imageResourceChars)
            .plus(sourceImages?.sourceKey?.length ?: 0)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun chapterCacheKey(
        chapterIndex: Int,
        config: EpubCoreLayoutConfig
    ): String = buildString {
        append(chapterIndex).append('|')
        append(config.pageWidthPx).append('x').append(config.pageHeightPx).append('|')
        append(config.readerPaddingLeftPx).append(',').append(config.readerPaddingTopPx).append(',')
        append(config.readerPaddingRightPx).append(',').append(config.readerPaddingBottomPx).append('|')
        append(config.readerSafeInsetLeftPx).append(',').append(config.readerSafeInsetTopPx).append(',')
        append(config.readerSafeInsetRightPx).append(',').append(config.readerSafeInsetBottomPx).append('|')
        append(config.textPaint.textSize).append('|').append(config.textPaint.color).append('|')
        append(config.textPaint.letterSpacing).append('|').append(config.lineHeightPx).append('|')
        append(config.paragraphSpacingPx).append('|').append(config.paragraphIndentPx).append('|')
        append(config.textFontWeight).append('|').append(config.textFontItalic).append('|')
        append(config.readerFontFamily).append('|').append(config.readerFontUrl).append('|')
        append(config.readerFontRevision).append('|').append(config.readerFontLength).append('|')
        append(config.readerFontOverridePublisher).append('|')
        append(config.alignment).append('|').append(config.textFullJustify).append('|')
        append(config.textBottomJustify).append('|')
        append(config.scrollMode).append('|').append(config.backgroundColor).append('|')
        append(config.readerBackgroundImage)
        config.readerChromeGeometryKey.takeIf { it.isNotEmpty() }?.let {
            append('|').append(it)
        }
        config.readerTemplateKey.takeIf { it.isNotEmpty() }?.let {
            append("|template:").append(it)
        }
    }

    companion object {
        private const val DEFAULT_MAX_CACHED_CHAPTERS = 4
        private const val DEFAULT_MAX_CACHED_CHAPTER_CHARS = 8 * 1024 * 1024
        const val SCHEME = "https"
        const val HOST = "epub.local"

        fun baseUrl(href: String, host: String = HOST): String {
            val encoded = EpubPath.normalize(href)
                .split('/')
                .joinToString("/") { EpubPath.encodePathSegment(it) }
            return "$SCHEME://$host/$encoded"
        }

        fun isLocalHost(host: String?): Boolean {
            return host.equals(HOST, true)
        }

        fun isLocalUrl(url: String): Boolean = runCatching {
            val uri = Uri.parse(url)
            uri.scheme.equals(SCHEME, true) && isLocalHost(uri.host)
        }.getOrDefault(false)
    }

    private data class CachedChapter(
        val group: String?,
        val chapter: EpubDirectChapter,
        val weightChars: Int
    )

    private class PendingChapter(
        val cacheGroup: String?,
        var cacheGeneration: Long
    ) {
        private val latch = CountDownLatch(1)
        @Volatile
        private var result: EpubDirectChapter? = null
        @Volatile
        private var failure: Throwable? = null

        fun complete(value: EpubDirectChapter) {
            result = value
            latch.countDown()
        }

        fun fail(throwable: Throwable) {
            failure = throwable
            latch.countDown()
        }

        fun await(): EpubDirectChapter {
            try {
                latch.await()
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            }
            failure?.let { throw it }
            return checkNotNull(result) { "EPUB chapter load completed without a result" }
        }
    }

    /** 交出的资源流持有一份租约；读到 EOF 或 close 时释放（幂等）。 */
    private class ManagedResourceInputStream(
        source: InputStream,
        private val onFinished: () -> Unit
    ) : FilterInputStream(source) {
        private var finished = false

        override fun read(): Int {
            return try {
                super.read().also { if (it < 0) finish() }
            } catch (throwable: Throwable) {
                finish()
                throw throwable
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            return try {
                super.read(buffer, offset, length).also { if (it < 0) finish() }
            } catch (throwable: Throwable) {
                finish()
                throw throwable
            }
        }

        override fun close() {
            finish()
        }

        @Synchronized
        private fun finish() {
            if (finished) return
            finished = true
            try {
                super.close()
            } finally {
                onFinished()
            }
        }
    }
}