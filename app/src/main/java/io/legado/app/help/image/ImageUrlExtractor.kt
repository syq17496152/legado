package io.legado.app.help.image

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.data.entities.RssSource
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.rss.Rss
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 图片 URL 提取器（三层降级链路）
 *
 * 设计参考：docs/specs/image-sniffer-optimization/design.md §1.1
 *
 * 三层降级链路：
 * - Layer 1: 静态解析（enhancedParseImageUrls）
 *   - 用户规则优先（ruleContent + ruleImage 走 AnalyzeRule）
 *   - 9 个策略：纯 URL 列表 / ruleImage 选择器 / <img> 标签 / <picture><source> /
 *     CSS background-image / og:image Meta / Script JSON / JS 变量 / 所有 URL 正则 / 单 URL 兜底
 *   - 失败条件：返回图片数 < 3 时触发 L2
 *
 * - Layer 2: WebView 嗅探（extractWithWebView）
 *   - 调用 ImageSnifferWebView 加载页面
 *   - IMAGE_SNIFF_JS 注入（5 路 hook: Image.src/fetch/XHR/IO/document.write）
 *   - shouldInterceptRequest 拦截图片资源
 *   - 6s 超时兜底
 *
 * - Layer 3: 兜底返回（L1 + L2 合并去重）
 *
 * 并发守卫：Mutex 确保同一时间仅 1 个 WebView 嗅探实例（避免 WebView 池耗尽）
 *
 * 日志规范：
 * - tag: AppLog.TAG_IMAGE_SNIFF
 * - URL 路径模式化（sanitizeUrl），不输出真实域名/token
 */
object ImageUrlExtractor {

    private const val TAG = "ImageUrlExtractor"

    /** 总超时：12s（L1 500ms + L2 6s + 缓冲 5.5s） */
    private const val TOTAL_TIMEOUT_MS = 12_000L

    /** L1 静态解析超时：500ms（防止 body 过大阻塞主流程） */
    private const val L1_STATIC_TIMEOUT_MS = 500L

    /** L2 WebView 嗅探超时：6s（对齐 VideoUrlExtractor.R5_TIMEOUT） */
    private const val L2_WEBVIEW_TIMEOUT_MS = 6_000L

    /** L1 失败阈值：< 3 张图片触发 L2（design.md §3.3：3 张以上才算正文图片列表） */
    private const val L1_FAIL_THRESHOLD = 3

    /** WebView 嗅探并发守卫（避免 WebView 池耗尽） */
    private val webviewMutex = Mutex()

    /**
     * 提取图片 URL 列表（三层降级链路入口）
     *
     * @param article RssArticle 文章对象（含 link / title / origin）
     * @param rssSource RssSource 订阅源（含 ruleContent / ruleImage / sourceUrl）
     * @param ruleContent 用户内容规则（可为空，空时用 "body@html" 兜底）
     * @param ruleImage 用户图片规则（可为空）
     * @return List<String> 图片 URL 列表（可能为空，不抛异常）
     */
    suspend fun extractImageList(
        article: RssArticle,
        rssSource: RssSource,
        ruleContent: String?,
        ruleImage: String?
    ): List<String> {
        val startTime = System.currentTimeMillis()
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_SNIFF,
            "extractImageList start: link=${ImageSnifferWebView.sanitizeUrl(article.link)} ruleContentLen=${ruleContent?.length ?: 0} ruleImageLen=${ruleImage?.length ?: 0}",
            level = AppLog.Level.INFO
        )

        // rss-image-load-optimization: URL 解析结果缓存命中则直接返回，跳过网络请求与 WebView 嗅探
        ImageUrlCache.get(article.link)?.let { cached ->
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "extractImageList cache hit: count=${cached.size} elapsedMs=${System.currentTimeMillis() - startTime}",
                level = AppLog.Level.INFO
            )
            return cached
        }

        val result = try {
            withTimeoutOrNull(TOTAL_TIMEOUT_MS) {
                val l1Urls = extractWithStatic(article, rssSource, ruleContent, ruleImage)
                currentCoroutineContext().ensureActive()
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "L1 static parse done: count=${l1Urls.size} elapsedMs=${System.currentTimeMillis() - startTime}",
                    level = AppLog.Level.INFO
                )

                // L1 ≥ 3 张：直接返回（用户规则场景或静态 HTML 站点）
                if (l1Urls.size >= L1_FAIL_THRESHOLD) {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_SNIFF,
                        "extractImageList done(L1): total=${l1Urls.size} elapsedMs=${System.currentTimeMillis() - startTime}",
                        level = AppLog.Level.INFO
                    )
                    return@withTimeoutOrNull l1Urls
                }

                // L1 < 3 张：触发 L2 WebView 嗅探
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "L1 count<$L1_FAIL_THRESHOLD, trigger L2 webview sniff",
                    level = AppLog.Level.INFO
                )
                val l2Urls = extractWithWebView(article, rssSource)
                currentCoroutineContext().ensureActive()

                // L3 合并去重（L1 优先，L2 补充）
                val merged = LinkedHashSet<String>(l1Urls).apply { addAll(l2Urls) }.toList()
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "extractImageList done(L1+L2 merged): l1=${l1Urls.size} l2=${l2Urls.size} merged=${merged.size} elapsedMs=${System.currentTimeMillis() - startTime}",
                    level = AppLog.Level.INFO
                )
                merged
            } ?: run {
                // 总超时兜底：返回空列表
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "extractImageList total timeout(${TOTAL_TIMEOUT_MS}ms), return empty",
                    level = AppLog.Level.WARN
                )
                emptyList()
            }
        } catch (e: CancellationException) {
            throw e  // 协程取消必须传播
        } catch (e: Exception) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "extractImageList error: ${e::class.simpleName} msg=${e.message?.take(200)}",
                throwable = e,
                level = AppLog.Level.ERROR
            )
            emptyList()
        }

        // rss-image-load-optimization: 解析成功后写入 URL 缓存
        if (result.isNotEmpty()) {
            ImageUrlCache.put(article.link, result)
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "extractImageList cache written: count=${result.size} elapsedMs=${System.currentTimeMillis() - startTime}",
                level = AppLog.Level.INFO
            )
        }
        return result
    }

    // ==================== Layer 1: 静态解析 ====================

    /**
     * L1 静态解析（增强版 parseImageUrls）
     *
     * 流程：
     * 1. 调用 Rss.getContentAwait 获取 body
     * 2. 调用 enhancedParseImageUrls 解析 body 为图片 URL 列表
     *
     * @return List<String> 解析到的图片 URL（可能为空）
     */
    private suspend fun extractWithStatic(
        article: RssArticle,
        rssSource: RssSource,
        ruleContent: String?,
        ruleImage: String?
    ): List<String> {
        return try {
            // ruleContent 为空时，用 body@html 获取文章详情页 HTML
            val effectiveRule = if (ruleContent.isNullOrBlank()) "body@html" else ruleContent
            var body = Rss.getContentAwait(article, effectiveRule, rssSource)
            currentCoroutineContext().ensureActive()

            if (body.isBlank()) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "L1 body is blank, skip static parse",
                    level = AppLog.Level.WARN
                )
                return emptyList()
            }

            // 诊断日志：记录 body 关键标签检测结果（帮助定位嗅探失败根因）
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "L1 body analysis: bodyLen=${body.length} hasImg=${body.contains("<img")} hasPicture=${body.contains("<picture")} hasSource=${body.contains("<source")} hasOgImage=${body.contains("og:image")} hasScript=${body.contains("<script")} hasStyle=${body.contains("<style")} hasHtml=${body.contains("<html")} hasBody=${body.contains("<body")}",
                level = AppLog.Level.INFO
            )

            // 修复：ruleContent 解析后内容不含图片标签时，回退到 body@html 获取完整页面 HTML
            // 铁证：read.php 原始 bodyLen=15471，ruleContent 解析后 contentLen=2509 hasImg=false
            // 原因：ruleContent 规则只提取了部分内容（如正文文本），丢失了 <img> 标签
            if (!ruleContent.isNullOrBlank() && body.length < 10000 && !body.contains("<img")) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "L1 ruleContent body has no <img>, fallback to body@html, bodyLen=${body.length}",
                    level = AppLog.Level.WARN
                )
                val fullBody = Rss.getContentAwait(article, "body@html", rssSource)
                currentCoroutineContext().ensureActive()
                if (fullBody.isNotBlank() && fullBody.length > body.length) {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_SNIFF,
                        "L1 fallback body@html success: oldLen=${body.length} newLen=${fullBody.length} hasImg=${fullBody.contains("<img")}",
                        level = AppLog.Level.INFO
                    )
                    body = fullBody
                }
            }

            enhancedParseImageUrls(body, article.link, ruleImage, rssSource)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "L1 extractWithStatic error: ${e::class.simpleName} msg=${e.message?.take(150)}",
                throwable = e,
                level = AppLog.Level.WARN
            )
            emptyList()
        }
    }

    /**
     * 增强版图片 URL 解析（9 策略降级链路）
     *
     * 策略优先级：
     * 1. 纯 URL 列表 split（body 不含 HTML 标签）
     * 2. HTML + ruleImage 选择器（AnalyzeRule）
     * 3. <img> 标签正则（src/data-src/data-original 等懒加载属性）
     * 3.5. <picture>/<source> 标签 srcset
     * 3.6. CSS background-image
     * 3.7. og:image Meta 标签
     * 3.8. Script JSON 提取
     * 3.9. JS 变量提取
     * 4. 所有 http/https URL 正则 + 图片扩展名白/黑名单
     * 5. 单 URL 兜底（body 本身就是单个 URL）
     */
    private fun enhancedParseImageUrls(
        body: String,
        baseUrl: String?,
        ruleImage: String?,
        rssSource: RssSource
    ): List<String> {
        val base = baseUrl ?: ""
        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_SNIFF,
            "enhancedParseImageUrls: bodyLen=${body.length} bodyHasHtml=${body.contains("<")} ruleImageLen=${ruleImage?.length ?: 0}",
            level = AppLog.Level.INFO
        )

        // 策略1：纯 URL 列表（body 不含 HTML 标签时 split）
        // 修复 %0A 残留导致 404（铁证：003 日志 33 次 404）
        if (!body.contains("<")) {
            val rawSegments = body.split("\n", "\r\n", "\r", "%0A", "%0a")
            val urlList = rawSegments
                .map { it.trim() }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .map { NetworkUtils.getAbsoluteURL(base, it) }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
            if (urlList.isNotEmpty()) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "strategy 1 (newline split) success: count=${urlList.size}",
                    level = AppLog.Level.INFO
                )
                return filterImageUrls(urlList)
            }
        }

        // 策略2：HTML + ruleImage 选择器
        // 修复（image-canvas-3fix-20260728 Q2修复2）：命中数 < 3 时不直接 return，继续策略3 合并结果
        // 根因：HTTP 429 限流时 ruleContent 从错误页面解析，策略2 仅命中 1 张，但策略3（regex img tag）
        // 可能从其他来源提取更多 URL。原逻辑命中后直接 return，导致只有 1 张图（铁证：008 日志 L2493 count=1）。
        // 修复：命中数 >= 3 视为"充足"直接 return；命中数 < 3 保存结果继续策略3 合并。
        var strategy2Result: List<String> = emptyList()
        if (body.contains("<") && !ruleImage.isNullOrBlank()) {
            try {
                val analyzeRule = AnalyzeRule(null, rssSource)
                analyzeRule.setContent(body)
                    .setBaseUrl(NetworkUtils.getAbsoluteURL(rssSource.sourceUrl, base))
                val imgUrls = (analyzeRule.getStringList(ruleImage) ?: emptyList())
                    .map { NetworkUtils.getAbsoluteURL(base, it.trim()) }
                    .filter { it.startsWith("http://") || it.startsWith("https://") }
                    .distinct()
                if (imgUrls.isNotEmpty()) {
                    AppLog.putDebugWithTag(
                        AppLog.TAG_IMAGE_SNIFF,
                        "strategy 2 (ruleImage selector) success: count=${imgUrls.size}",
                        level = AppLog.Level.INFO
                    )
                    if (imgUrls.size >= 3) {
                        return filterImageUrls(imgUrls)
                    }
                    strategy2Result = imgUrls  // < 3 张，保存继续策略3
                }
            } catch (e: Exception) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "strategy 2 (ruleImage selector) failed: ${e::class.simpleName} ${e.message?.take(100)}",
                    level = AppLog.Level.WARN
                )
            }
        }

        // 策略3：正则提取 <img> 标签的所有 src 属性（含懒加载属性扩展）
        if (body.contains("<img")) {
            val lazyAttrs = LAZY_LOAD_ATTRS.joinToString("|")
            val imgRegex = Regex(
                """<img[^>]+(?:$lazyAttrs)\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            )
            val regexUrls = imgRegex.findAll(body)
                .map { matchResult ->
                    val url = matchResult.groupValues[1].trim()
                    // srcset 多分辨率按空格分割取第一个
                    val cleanUrl = if (url.contains(" ")) url.split(" ")[0] else url
                    NetworkUtils.getAbsoluteURL(base, cleanUrl)
                }
                .filter { it.startsWith("http://") || it.startsWith("https://") }
                .distinct()
                .toList()
            if (regexUrls.isNotEmpty()) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "strategy 3 (regex img tag) success: count=${regexUrls.size}",
                    level = AppLog.Level.INFO
                )
                // 合并策略2 + 策略3 结果（去重）
                val merged = (strategy2Result + regexUrls).distinct()
                return filterImageUrls(merged)
            }
        }

        // 策略3 未命中但策略2 有结果（< 3 张），返回策略2 结果
        if (strategy2Result.isNotEmpty()) {
            return filterImageUrls(strategy2Result)
        }

        // 策略3.5: <picture>/<source> 标签嗅探
        val pictureUrls = parsePictureSource(body, base)
        if (pictureUrls.isNotEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "strategy 3.5 (picture/source) success: count=${pictureUrls.size}",
                level = AppLog.Level.INFO
            )
            return filterImageUrls(pictureUrls)
        }

        // 策略3.6: CSS background-image 嗅探
        val bgUrls = parseBackgroundImage(body, base)
        if (bgUrls.isNotEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "strategy 3.6 (css background-image) success: count=${bgUrls.size}",
                level = AppLog.Level.INFO
            )
            return filterImageUrls(bgUrls)
        }

        // 策略3.7: og:image Meta 标签嗅探
        val ogUrls = parseOgImage(body, base)
        if (ogUrls.isNotEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "strategy 3.7 (og:image meta) success: count=${ogUrls.size}",
                level = AppLog.Level.INFO
            )
            return filterImageUrls(ogUrls)
        }

        // 策略3.8: Script JSON 提取
        val scriptUrls = parseScriptJson(body, base)
        if (scriptUrls.isNotEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "strategy 3.8 (script json) success: count=${scriptUrls.size}",
                level = AppLog.Level.INFO
            )
            return filterImageUrls(scriptUrls)
        }

        // 策略3.9: JS 变量提取
        val jsVarUrls = parseJsVariables(body, base)
        if (jsVarUrls.isNotEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "strategy 3.9 (js variables) success: count=${jsVarUrls.size}",
                level = AppLog.Level.INFO
            )
            return filterImageUrls(jsVarUrls)
        }

        // 策略4：提取 body 中所有 http/https URL + 图片扩展名白/黑名单过滤
        val allUrlRegex = Regex("""https?://[^\s"'<>\]\)]+""", RegexOption.IGNORE_CASE)
        val allUrls = allUrlRegex.findAll(body)
            .map { NetworkUtils.getAbsoluteURL(base, it.value.trim()) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
        if (allUrls.isNotEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "strategy 4 (all url regex) success: count=${allUrls.size}",
                level = AppLog.Level.INFO
            )
            return filterImageUrls(allUrls)
        }

        // 策略5：单 URL 兜底（body 本身就是单个 URL）
        val bodyTrimmed = body.trim()
        if (bodyTrimmed.startsWith("http://") || bodyTrimmed.startsWith("https://")) {
            val singleUrl = NetworkUtils.getAbsoluteURL(base, bodyTrimmed)
            if (singleUrl.startsWith("http://") || singleUrl.startsWith("https://")) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "strategy 5 (single url fallback) success",
                    level = AppLog.Level.INFO
                )
                return listOf(singleUrl)
            }
        }

        AppLog.putDebugWithTag(
            AppLog.TAG_IMAGE_SNIFF,
            "all static strategies failed, no urls found, bodyLen=${body.length}",
            level = AppLog.Level.WARN
        )
        return emptyList()
    }

    /**
     * 策略3.5: <picture>/<source> 标签嗅探
     * 提取 <picture><source srcset="url1 480w, url2 800w"> 中的 srcset
     */
    private fun parsePictureSource(body: String, baseUrl: String): List<String> {
        val sourceRegex = Regex(
            """<source[^>]+srcset\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        return sourceRegex.findAll(body)
            .flatMap { matchResult ->
                // srcset 按逗号分割，每段取第一个空格前的 URL
                matchResult.groupValues[1].split(",")
                    .map { it.trim().split(" ")[0] }
                    .filter { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/") }
            }
            .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
    }

    /**
     * 策略3.6: CSS background-image 嗅探
     * 提取 background-image: url(...) / background: url(...)
     */
    private fun parseBackgroundImage(body: String, baseUrl: String): List<String> {
        val bgRegex = Regex(
            """background(?:-image)?\s*:\s*url\(["']?([^"')]+)["']?\)""",
            RegexOption.IGNORE_CASE
        )
        return bgRegex.findAll(body)
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("/") }
            .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
    }

    /**
     * 策略3.7: og:image Meta 标签嗅探
     * 提取 <meta property="og:image" content="...">
     */
    private fun parseOgImage(body: String, baseUrl: String): List<String> {
        val metaRegex = Regex(
            """<meta[^>]+property\s*=\s*["']og:image(?:url)?["'][^>]+content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        return metaRegex.findAll(body)
            .map { it.groupValues[1].trim() }
            .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .toList()
    }

    /**
     * 策略3.8: Script JSON 提取
     * 提取 <script> 标签内 JSON 中的图片 URL
     */
    private fun parseScriptJson(body: String, baseUrl: String): List<String> {
        val scriptRegex = Regex("""<script[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE)
        val jsonUrlRegex = Regex(
            """"(?:image|images|image_url|image_list|url|@id)"\s*:\s*(?:"([^"]+)"|\[([^\]]+)\])""",
            RegexOption.IGNORE_CASE
        )
        return scriptRegex.findAll(body)
            .flatMap { scriptMatch ->
                jsonUrlRegex.findAll(scriptMatch.groupValues[1])
                    .flatMap { jsonMatch ->
                        val singleUrl = jsonMatch.groupValues[1]
                        val urlArray = jsonMatch.groupValues[2]
                        if (singleUrl.isNotEmpty()) listOf(singleUrl)
                        else urlArray.split(",")
                            .map { it.trim().trim('"') }
                            .filter { it.isNotEmpty() }
                    }
            }
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
            .distinct()
            .toList()
    }

    /**
     * 策略3.9: JS 变量提取
     * 提取 var/let/const images = ["url1","url2"] 形式的图片 URL
     */
    private fun parseJsVariables(body: String, baseUrl: String): List<String> {
        val jsVarRegex = Regex(
            """(?:var|let|const)\s+\w*(?:image|img|pic|photo)s?\w*\s*=\s*\[([^\]]+)\]""",
            RegexOption.IGNORE_CASE
        )
        val urlRegex = Regex("""["']([^"']+)["']""")
        return jsVarRegex.findAll(body)
            .flatMap { varMatch ->
                urlRegex.findAll(varMatch.groupValues[1])
                    .map { it.groupValues[1].trim() }
            }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
            .distinct()
            .toList()
    }

    /**
     * 策略4 增强：图片扩展名白名单/黑名单过滤
     */
    private fun filterImageUrls(urls: List<String>): List<String> {
        val before = urls.size
        val filtered = urls.filter { url ->
            val ext = url.substringBefore("?").substringAfterLast(".").lowercase()
            when {
                ext in IMAGE_EXTENSION_WHITELIST -> true
                ext in URL_EXTENSION_BLACKLIST -> false
                else -> true // 无扩展名或未知扩展名，保留（Content-Type 校验交给 Glide）
            }
        }
        // 诊断日志：记录过滤前后数量（帮助定位是否过滤掉所有 URL）
        if (before != filtered.size) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "filterImageUrls: before=$before after=${filtered.size} filtered=${before - filtered.size}",
                level = AppLog.Level.INFO
            )
        }
        // 修复：过滤后为空但原列表非空时，保留原结果（宁滥勿缺）
        // 铁证：photo/id-xxx.html strategy 2 命中 1 张被黑名单过滤 → count=0 → L1 失败
        // 风险评估：Glide 加载非图片 URL 会走降级链路，不会崩溃
        if (filtered.isEmpty() && urls.isNotEmpty()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "filterImageUrls: all filtered out, keep original (宁滥勿缺), before=${urls.size}",
                level = AppLog.Level.WARN
            )
            return urls
        }
        return filtered
    }

    // ==================== Layer 2: WebView 嗅探 ====================

    /**
     * L2 WebView 嗅探
     *
     * 触发条件：L1 返回 < 3 张图片
     * 实现方式：ImageSnifferWebView 加载页面 + IMAGE_SNIFF_JS hook + shouldInterceptRequest 拦截
     * 超时：L2_WEBVIEW_TIMEOUT_MS（6s）
     * 并发守卫：webviewMutex 确保同一时间仅 1 个 WebView 嗅探实例
     *
     * @return List<String> 嗅探到的图片 URL（可能为空，不抛异常）
     */
    private suspend fun extractWithWebView(
        article: RssArticle,
        rssSource: RssSource
    ): List<String> {
        val link = article.link
        if (link.isNullOrBlank()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "L2 skip: article.link is null or blank",
                level = AppLog.Level.WARN
            )
            return emptyList()
        }

        // 构造 headerMap（防盗链 Referer + UA）
        val headerMap = hashMapOf<String, String>()
        headerMap["Referer"] = rssSource.sourceUrl
        // 互斥守卫：同一时间仅 1 个 WebView 嗅探（避免 WebView 池耗尽）
        return webviewMutex.withLock {
            try {
                // 修复（image-canvas-3fix-20260728 Q2修复1）：移除外层 withTimeoutOrNull
                // 根因：sniffImageUrls() 内部已有 withTimeoutOrNull(timeout) 超时机制（L78），
                // 超时后返回 collectedUrls.toList()（L106，即已收集的 51 张 URL）。
                // 外层又包了一层 withTimeoutOrNull(L2_WEBVIEW_TIMEOUT_MS)，两者超时时间相同（6s），
                // 当 sniffImageUrls 内部超时（6s 整）返回 51 张 URL 时，外层 withTimeoutOrNull 也到达 6s 阈值，
                // 外层超时先生效，直接返回 null → 转为 emptyList，丢弃 sniffImageUrls 的 51 张 URL。
                // 铁证：008 日志 L2553 sniffImageUrls 内部超时日志 collected=51，但 L2555 l2=0。
                // 修复：移除外层 withTimeoutOrNull，sniffImageUrls 内部超时返回 collectedUrls 能正确传递给调用方。
                ImageSnifferWebView(
                    url = link,
                    headerMap = headerMap,
                    tag = rssSource.sourceUrl,
                    timeout = L2_WEBVIEW_TIMEOUT_MS,
                    delayTime = 1500L
                ).sniffImageUrls()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "L2 webview sniff error: ${e::class.simpleName} msg=${e.message?.take(150)}",
                    throwable = e,
                    level = AppLog.Level.WARN
                )
                emptyList()
            }
        }
    }

    /**
     * 图片书源（BookSourceType.image）章节图片嗅探兜底
     *
     * 复用 ImageSnifferWebView，将 RSS 图片源 L2 嗅探能力迁移到图片书源：
     * - 触发条件：ReadManga.getManageChapter 静态解析（BookHelp.flowImages）0 图
     * - headerMap 构造：AnalyzeUrl 按书源规则生成（UA/需要登录的 Cookie），Referer 缺省时补章节页 URL
     * - 超时：L2_WEBVIEW_TIMEOUT_MS（6s），与 RSS L2 一致
     * - 并发守卫：webviewMutex（同一时间仅 1 个 WebView 嗅探实例）
     *
     * @param chapter 当前章节（取 chapter.url 作为嗅探页面）
     * @param book 书籍对象（作为 AnalyzeUrl ruleData/章节上下文，供书源变量）
     * @param bookSource 书源（headerMap UA/Cookie 来源）
     * @return List<String> 嗅探到的图片 URL（可能为空，不抛异常）
     */
    suspend fun sniffBookChapterImages(
        chapter: BookChapter,
        book: Book,
        bookSource: BookSource
    ): List<String> {
        val url = chapter.url
        if (url.isNullOrBlank()) {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "book-chapter sniff skip: chapter.url is null or blank bookUrl=${sanitizeBookUrl(book.bookUrl)}",
                level = AppLog.Level.WARN
            )
            return emptyList()
        }
        // 用书源规则构造 headerMap（UA/Cookie），Referer 缺省时用章节页 URL
        val headerMap = runCatching {
            AnalyzeUrl(url, source = bookSource, ruleData = book, chapter = chapter).headerMap
        }.getOrElse {
            AppLog.putDebugWithTag(
                AppLog.TAG_IMAGE_SNIFF,
                "bookChapter sniff header build error: ${it::class.simpleName} msg=${it.message?.take(150)}",
                throwable = it,
                level = AppLog.Level.WARN
            )
            LinkedHashMap<String, String>()
        }
        if (!headerMap.any { it.key.equals("Referer", ignoreCase = true) }) {
            headerMap["Referer"] = url
        }
        return webviewMutex.withLock {
            try {
                ImageSnifferWebView(
                    url = url,
                    headerMap = headerMap,
                    tag = "book-${bookSource.getKey()}",
                    timeout = L2_WEBVIEW_TIMEOUT_MS,
                    delayTime = 1500L
                ).sniffImageUrls()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.putDebugWithTag(
                    AppLog.TAG_IMAGE_SNIFF,
                    "bookChapter webview sniff error: ${e::class.simpleName} msg=${e.message?.take(150)}",
                    throwable = e,
                    level = AppLog.Level.WARN
                )
                emptyList()
            }
        }
    }

    /** 日志脱敏：仅保留书 URL 末尾片段，不输出完整 bookUrl */
    private fun sanitizeBookUrl(bookUrl: String?): String =
        bookUrl?.takeLast(40) ?: "null"

    // ==================== 常量定义 ====================

    /**
     * 图片扩展名白名单（用于 filterImageUrls 策略4 增强）
     */
    private val IMAGE_EXTENSION_WHITELIST = setOf(
        "jpg", "jpeg", "png", "webp", "gif", "svg", "avif", "bmp"
    )

    /**
     * URL 扩展名黑名单（用于 filterImageUrls 策略4 增强）
     */
    private val URL_EXTENSION_BLACKLIST = setOf(
        "js", "css", "html", "htm", "json", "woff", "woff2", "ttf", "eot", "ico"
    )

    /**
     * 懒加载属性列表（策略3 增强）
     * - src/data-src/data-original/data-lazy-src/data-lazy/realsrc/data-srcset/srcset（现有）
     * - data-url/data-img/data-lazy-srcset/data-original-src/data-echo/data-img-src/data-delay/data-lazy（新增）
     */
    private val LAZY_LOAD_ATTRS = listOf(
        "src", "data-src", "data-original", "data-lazy-src", "data-lazy",
        "realsrc", "data-srcset", "srcset",
        "data-url", "data-img", "data-lazy-srcset", "data-original-src",
        "data-echo", "data-img-src", "data-delay", "data-lazy"
    )

    /**
     * IMAGE_SNIFF_JS：5 路 hook 脚本（参考 VIDEO_SNIFF_JS 设计）
     *
     * Hook 设计（5 路）：
     * 1. HTMLImageElement.prototype.src setter（捕获 .src = url 直接赋值）
     * 2. window.fetch（捕获 fetch 图片请求）
     * 3. XMLHttpRequest.prototype.open（捕获 XHR 图片请求）
     * 4. window.IntersectionObserver（捕获懒加载触发后的 src）
     * 5. document.write（捕获内联脚本中的图片）
     *
     * 收集 URL 到 window.__imageUrls__ 数组（去重）
     *
     * 设计要点：
     * - try-catch 保护：hook 异常不影响原逻辑
     * - 保持原语义：仅记录 URL，不修改行为
     * - 幂等性：window.__imageUrls__ 已存在时跳过初始化
     * - 图片扩展名匹配：\.(jpg|jpeg|png|webp|gif|svg|avif|bmp)/i
     */
    const val IMAGE_SNIFF_JS = """
(function() {
    if (window.__imageUrls__) return;
    window.__imageUrls__ = [];
    function pushUrl(url) {
        if (typeof url !== 'string' || url.length === 0) return;
        if (window.__imageUrls__.indexOf(url) === -1) {
            window.__imageUrls__.push(url);
        }
    }
    var imgExtRegex = /\.(jpg|jpeg|png|webp|gif|svg|avif|bmp)(\?|$)/i;

    // 1. Hook HTMLImageElement.prototype.src setter
    try {
        var origSrc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');
        if (origSrc && origSrc.set) {
            Object.defineProperty(HTMLImageElement.prototype, 'src', {
                set: function(val) {
                    if (val && typeof val === 'string' && val.match(/^https?:\/\//)) {
                        pushUrl(val);
                    }
                    origSrc.set.call(this, val);
                },
                get: function() { return origSrc.get.call(this); },
                configurable: true
            });
        }
    } catch(e) {}

    // 2. Hook window.fetch
    try {
        var origFetch = window.fetch;
        window.fetch = function(input, init) {
            var url = typeof input === 'string' ? input : (input && input.url);
            if (url && url.match(imgExtRegex)) {
                pushUrl(url);
            }
            return origFetch.apply(this, arguments);
        };
    } catch(e) {}

    // 3. Hook XMLHttpRequest.prototype.open
    try {
        var origOpen = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url) {
            if (url && url.match(imgExtRegex)) {
                pushUrl(url);
            }
            return origOpen.apply(this, arguments);
        };
    } catch(e) {}

    // 4. Hook window.IntersectionObserver（懒加载触发后回传 src）
    try {
        var origIO = window.IntersectionObserver;
        if (origIO) {
            window.IntersectionObserver = function(callback, options) {
                var wrappedCallback = function(entries, observer) {
                    try {
                        entries.forEach(function(entry) {
                            if (entry.target && entry.target.tagName === 'IMG' && entry.target.src) {
                                pushUrl(entry.target.src);
                            }
                        });
                    } catch(e) {}
                    return callback(entries, observer);
                };
                return new origIO(wrappedCallback, options);
            };
        }
    } catch(e) {}

    // 5. Hook document.write（捕获内联脚本中的图片）
    try {
        var origWrite = document.write;
        document.write = function(content) {
            if (content && typeof content === 'string') {
                var imgTagRegex = /<img[^>]+src\s*=\s*["']([^"']+)["']/gi;
                var match;
                while ((match = imgTagRegex.exec(content)) !== null) {
                    if (match[1] && match[1].match(/^https?:\/\//)) {
                        pushUrl(match[1]);
                    }
                }
            }
            return origWrite.apply(this, arguments);
        };
    } catch(e) {}
})();
"""
}
