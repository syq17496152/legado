package io.legado.app.service.kernel

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.SearchBook
import io.legado.app.model.CheckDepth
import io.legado.app.model.ProbeOptions
import io.legado.app.model.SourceQualityChecker
import io.legado.app.model.SourceQualityReport
import io.legado.app.model.SourceQualitySession
import io.legado.app.model.rss.Rss
import io.legado.app.model.webBook.WebBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 源调试域业务内核（web-mcp-productization 二期 · tasks 5.8 / §5.6–5.13）。
 *
 * **归位**：9 个 L3 调试能力的**业务实现落本文件（main 变体）**；MCP 工具声明在 debug 变体的
 * `web/mcp/tools/` 域文件（REQ-2-403：L3 工具只编入 debug 包）。业务逻辑不重复造：本内核
 * **只编排既有能力**——[WebBook] 搜索/发现/目录/正文、[Rss] 列表/正文、[SourceQualityChecker] 质量校验。
 *
 * 契约同 [DiagKernel]：**全链挂起、零阻塞式桥接**、只返回结构化 `Map`、失败以结构化字段表达
 * （下方 `success/step/elapsedMs/detail/error`）。
 *
 * **输出安全**：URL 里的敏感 query 参数复用 [DiagKernel.maskUrl] 打码（本内核不 import `web` 层，见 G-22）。
 *
 * ⚠️ **已知能力上限（如实回告，不假装成功）**：既有解析链（[WebBook] / [Rss]）在规则解析失败时
 * **只抛异常**，不携带"失败发生在哪一条规则/哪一行"。故本内核只能把失败**定位到步骤级**
 * （source/info/toc/content），无法给出更细的规则行——失败信息里显式声明这一点，
 * 不虚报"已定位"。（阶段模型与结果信封已**单源**到 [SourceStepTracer]，见该类的接线状态说明。）
 */
object SourceDebugKernel {

    /** 单个 L3 测试的硬超时：防慢源把 MCP 调用挂死（与 [RssSourceKernel.search] 同量级）。 */
    private const val TEST_TIMEOUT_MS = 30_000L

    /** 正文片段返回长度上限（前 N 字）。 */
    private const val SNIPPET_LIMIT = 200

    /** 结果集/目录列表的预览条数上限。 */
    private const val PREVIEW_LIMIT = 5

    /** 规则未定位到具体行时的固定措辞（避免上层误读为"已精确诊断"）。 */
    private const val NOTE_NO_RULE_LINE = "正文规则解析失败，未定位到具体规则行（既有解析 API 不返回失败规则/行号）"

    // ============================================================ ① 书源：搜索

    /**
     * `source_search_test`：用指定书源搜索关键词。
     *
     * 返回 `{success, step, elapsedMs, detail:{count, books[]}, error}`；失败时 `success=false`
     * 且 `step` 指出失败环节（source=源层，search=搜索环节），**不抛裸异常当成功**。
     */
    suspend fun searchTest(sourceUrl: String, key: String, page: Int = 1): Map<String, Any?> {
        val source = resolveBookSource(sourceUrl) ?: return fail("source", "书源不存在：${DiagKernel.maskUrl(sourceUrl)}")
        if (source.searchUrl.isNullOrBlank()) return fail("source", "书源未配置搜索 url")
        val start = System.currentTimeMillis()
        val result = probe { WebBook.searchBookAwait(source, key, page) }
        val cost = elapsedSince(start)
        return result.fold(
            onSuccess = { books ->
                ok(cost, mapOf("count" to books.size, "books" to books.take(PREVIEW_LIMIT).map { it.toBrief() }))
            },
            onFailure = { fail("search", describeError(it), cost) },
        )
    }

    // ============================================================ ② 书源：基本信息体检

    /**
     * `source_info_test`：书源基本信息体检（不联网）。
     *
     * 返回名称/分组/类型/是否启用，以及关键规则字段是否齐备（入口 + 目录 + 正文）。
     */
    suspend fun infoTest(sourceUrl: String): Map<String, Any?> {
        val start = System.currentTimeMillis()
        val source = resolveBookSource(sourceUrl) ?: return fail("source", "书源不存在：${DiagKernel.maskUrl(sourceUrl)}")
        val ruleFields = mapOf(
            "searchUrl" to !source.searchUrl.isNullOrBlank(),
            "exploreUrl" to !source.exploreUrl.isNullOrBlank(),
            "ruleSearch" to (source.ruleSearch != null),
            "ruleBookInfo" to (source.ruleBookInfo != null),
            "ruleToc" to (source.ruleToc != null),
            "ruleContent" to (source.ruleContent != null),
        )
        // 基础齐备近似判定：存在检索入口（搜索或发现）且声明了目录 + 正文规则。
        val complete = (ruleFields["searchUrl"] == true || ruleFields["exploreUrl"] == true) &&
            ruleFields["ruleToc"] == true && ruleFields["ruleContent"] == true
        val detail = mapOf(
            "bookSourceUrl" to DiagKernel.maskUrl(source.bookSourceUrl),
            "name" to source.bookSourceName,
            "group" to source.bookSourceGroup,
            "type" to source.bookSourceType,
            "enabled" to source.enabled,
            "enabledExplore" to source.enabledExplore,
            "ruleFields" to ruleFields,
            "ruleFieldsComplete" to complete,
        )
        return ok(elapsedSince(start), detail)
    }

    // ============================================================ ③ 书源：目录

    /**
     * `source_toc_test`：拉目录测试。
     *
     * 返回 `{chapterCount, titles[], tocUrl}`；失败时 `step` 指出是详情页（info）还是目录（toc）环节。
     */
    suspend fun tocTest(sourceUrl: String, bookUrl: String): Map<String, Any?> {
        val source = resolveBookSource(sourceUrl) ?: return fail("source", "书源不存在：${DiagKernel.maskUrl(sourceUrl)}")
        val book = SearchBook(bookUrl = bookUrl, origin = sourceUrl).toBook()
        val start = System.currentTimeMillis()
        if (book.tocUrl.isBlank()) {
            val info = probe { WebBook.getBookInfoAwait(source, book) }
            if (info.isFailure) return fail("info", describeError(info.exceptionOrNull()), elapsedSince(start))
        }
        val toc = probe { WebBook.getChapterListAwait(source, book).getOrThrow() }
        val cost = elapsedSince(start)
        return toc.fold(
            onSuccess = { chapters ->
                ok(
                    cost,
                    mapOf(
                        "chapterCount" to chapters.size,
                        "titles" to chapters.take(PREVIEW_LIMIT).map { it.title },
                        "tocUrl" to DiagKernel.maskUrl(book.tocUrl),
                    )
                )
            },
            onFailure = { fail("toc", describeError(it), cost) },
        )
    }

    // ============================================================ ④ 书源：正文

    /**
     * `source_content_test`：拉正文测试，返回正文字符数 + 前 [SNIPPET_LIMIT] 字片段。
     *
     * **失败定位到步骤**（source/info/toc/content）；正文环节失败时额外声明"未定位到具体规则行"
     * ——这是如实回告既有 API 的能力边界，不虚报精确诊断。
     */
    suspend fun contentTest(sourceUrl: String, bookUrl: String, chapterIndex: Int = 0): Map<String, Any?> {
        val source = resolveBookSource(sourceUrl) ?: return fail("source", "书源不存在：${DiagKernel.maskUrl(sourceUrl)}")
        val book = SearchBook(bookUrl = bookUrl, origin = sourceUrl).toBook()
        val start = System.currentTimeMillis()
        if (book.tocUrl.isBlank()) {
            val info = probe { WebBook.getBookInfoAwait(source, book) }
            if (info.isFailure) return fail("info", describeError(info.exceptionOrNull()), elapsedSince(start))
        }
        val toc = probe { WebBook.getChapterListAwait(source, book).getOrThrow() }
        val chapters = toc.getOrElse { return fail("toc", describeError(it), elapsedSince(start)) }
        if (chapters.isEmpty()) return fail("toc", "目录为空", elapsedSince(start))
        if (chapterIndex !in chapters.indices) {
            return fail("toc", "chapterIndex 越界（合法范围 0..${chapters.lastIndex}）", elapsedSince(start))
        }
        val chapter = chapters[chapterIndex]
        val result = probe {
            WebBook.getContentAwait(bookSource = source, book = book, bookChapter = chapter, needSave = false)
        }
        val cost = elapsedSince(start)
        return result.fold(
            onSuccess = { content ->
                ok(
                    cost,
                    mapOf(
                        "chapterIndex" to chapterIndex,
                        "chapterTitle" to chapter.title,
                        "chapterCount" to chapters.size,
                        "length" to content.length,
                        "snippet" to content.take(SNIPPET_LIMIT),
                    )
                )
            },
            onFailure = { fail("content", "${describeError(it)}；$NOTE_NO_RULE_LINE", cost) },
        )
    }

    // ============================================================ ⑤ 书源：发现

    /** `source_explore_test`：发现/探索测试（返回结果集 + 耗时）。 */
    suspend fun exploreTest(sourceUrl: String, url: String, page: Int = 1): Map<String, Any?> {
        val source = resolveBookSource(sourceUrl) ?: return fail("source", "书源不存在：${DiagKernel.maskUrl(sourceUrl)}")
        val start = System.currentTimeMillis()
        val result = probe { WebBook.exploreBookAwait(source, url, page) }
        val cost = elapsedSince(start)
        return result.fold(
            onSuccess = { books ->
                ok(cost, mapOf("count" to books.size, "books" to books.take(PREVIEW_LIMIT).map { it.toBrief() }))
            },
            onFailure = { fail("explore", describeError(it), cost) },
        )
    }

    // ============================================================ ⑥ 订阅源：搜索

    /** `rss_search_test`：订阅源搜索测试（无搜索规则时结构化返回失败，不抛裸异常）。 */
    suspend fun rssSearchTest(rssSourceUrl: String, key: String): Map<String, Any?> {
        val source = resolveRssSource(rssSourceUrl)
            ?: return fail("source", "订阅源不存在：${DiagKernel.maskUrl(rssSourceUrl)}")
        val searchUrl = source.searchUrl
        if (searchUrl.isNullOrBlank()) return fail("source", "订阅源未配置搜索 url")
        val start = System.currentTimeMillis()
        val result = probe {
            Rss.getArticlesAwait(sortName = "搜索", sortUrl = searchUrl, rssSource = source, page = 1, key = key).first
        }
        val cost = elapsedSince(start)
        return result.fold(
            onSuccess = { articles ->
                ok(
                    cost,
                    mapOf(
                        "count" to articles.size,
                        "articles" to articles.take(PREVIEW_LIMIT).map {
                            mapOf(
                                "title" to it.title,
                                "link" to DiagKernel.maskUrl(it.link),
                                "sort" to it.sort,
                                "pubDate" to it.pubDate,
                            )
                        },
                    )
                )
            },
            onFailure = { fail("search", describeError(it), cost) },
        )
    }

    // ============================================================ ⑦ 订阅源：文章正文

    /**
     * `rss_article_test`：订阅文章正文测试。
     *
     * `sort` 为空串时按 `origin + link` 定位（与 [Rss.getContentAwait] 的规则数据同形态）。
     */
    suspend fun rssArticleTest(rssSourceUrl: String, link: String, sort: String = ""): Map<String, Any?> {
        val source = resolveRssSource(rssSourceUrl)
            ?: return fail("source", "订阅源不存在：${DiagKernel.maskUrl(rssSourceUrl)}")
        val ruleContent = source.ruleContent
        if (ruleContent.isNullOrBlank()) return fail("source", "订阅源未配置正文规则")
        val article = RssArticle(origin = rssSourceUrl, link = link, sort = sort)
        val start = System.currentTimeMillis()
        val result = probe { Rss.getContentAwait(rssArticle = article, ruleContent = ruleContent, rssSource = source) }
        val cost = elapsedSince(start)
        return result.fold(
            onSuccess = { content ->
                ok(cost, mapOf("length" to content.length, "snippet" to content.take(SNIPPET_LIMIT)))
            },
            onFailure = { fail("content", "${describeError(it)}；$NOTE_NO_RULE_LINE", cost) },
        )
    }

    // ============================================================ ⑧⑨ 校验（复用心质量校验件）

    /** `validate_source`：单书源校验（复用 [SourceQualityChecker]，L3 深度联网体检）。 */
    suspend fun validateSource(sourceUrl: String): Map<String, Any?> {
        val source = resolveBookSource(sourceUrl) ?: return fail("source", "书源不存在：${DiagKernel.maskUrl(sourceUrl)}")
        val start = System.currentTimeMillis()
        val result = kotlin.runCatching {
            SourceQualityChecker.checkBookSource(source, SourceQualitySession(ProbeOptions(depth = CheckDepth.L3)))
        }
        val cost = elapsedSince(start)
        return result.fold(
            onSuccess = { ok(cost, it.toDetail()) },
            onFailure = { fail("validate", describeError(it), cost) },
        )
    }

    /** `validate_rss_source`：单订阅源校验（复用 [SourceQualityChecker]，L3 深度联网体检）。 */
    suspend fun validateRssSource(rssSourceUrl: String): Map<String, Any?> {
        val source = resolveRssSource(rssSourceUrl)
            ?: return fail("source", "订阅源不存在：${DiagKernel.maskUrl(rssSourceUrl)}")
        val start = System.currentTimeMillis()
        val result = kotlin.runCatching {
            SourceQualityChecker.checkRssSource(source, SourceQualitySession(ProbeOptions(depth = CheckDepth.L3)))
        }
        val cost = elapsedSince(start)
        return result.fold(
            onSuccess = { ok(cost, it.toDetail()) },
            onFailure = { fail("validate", describeError(it), cost) },
        )
    }

    // ============================================================ 内部

    private suspend fun resolveBookSource(url: String): BookSource? = BookSourceKernel.source(url)

    private suspend fun resolveRssSource(url: String): RssSource? = RssSourceKernel.source(url)

    /**
     * 带硬超时的探测执行体 —— **逻辑单源**在 [SourceStepTracer.probe]（三端 WS/REST/MCP 共用）。
     *
     * 口径保持：超时（[TimeoutCancellationException]）按失败返回；**真正的协程取消原样抛出**，
     * 不被 `runCatching` 静默吞掉（防取消语义丢失）。
     */
    private suspend fun <T> probe(block: suspend () -> T): Result<T> =
        SourceStepTracer.probe(TEST_TIMEOUT_MS, block)

    // 结果信封 / 计时 / 错误措辞**单源**在 [SourceStepTracer]（避免同一套
    // success/step/elapsedMs/error 在调试三端各写一遍而漂移）；此处只做薄委托。

    private fun elapsedSince(start: Long): Long = SourceStepTracer.elapsedSince(start)

    private fun ok(elapsedMs: Long, detail: Any?): Map<String, Any?> =
        SourceStepTracer.ok(elapsedMs, detail)

    private fun fail(step: String, message: String, elapsedMs: Long = 0L): Map<String, Any?> =
        SourceStepTracer.fail(step, message, elapsedMs)

    /** 异常转白话（URL 敏感参数打码）。 */
    private fun describeError(e: Throwable?): String = SourceStepTracer.describeError(e)

    private fun SearchBook.toBrief(): Map<String, Any?> = mapOf(
        "name" to name,
        "author" to author,
        "originName" to originName,
        "kind" to kind,
        "latestChapterTitle" to latestChapterTitle,
        "bookUrl" to DiagKernel.maskUrl(bookUrl),
        "tocUrl" to DiagKernel.maskUrl(tocUrl),
    )

    private fun SourceQualityReport.toDetail(): Map<String, Any?> = mapOf(
        "sourceUrl" to DiagKernel.maskUrl(sourceUrl),
        "sourceType" to sourceType,
        "checkedDepth" to checkedDepth,
        "score" to score,
        "coverage" to coverage,
        "suspect" to suspect,
        "suspectReasons" to suspectReasons.map { it.name },
        "failureKind" to failureKind.name,
        "deterministicFail" to deterministicFail,
        "dimensions" to dimensions.map { (dim, r) ->
            mapOf(
                "dim" to dim.name,
                "state" to r.state.name,
                "resultCount" to r.resultCount,
                "durationMs" to r.durationMs,
                "evidence" to r.evidence,
            )
        },
    )
}