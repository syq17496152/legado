package io.legado.app.help.video

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.http.BackstageWebView
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.utils.NetworkUtils
import org.jsoup.Jsoup
import java.net.URLDecoder

/**
 * R5 自动视频链接抓取器
 *
 * 当订阅源 type=2（视频）且 ruleContent 为空时，自动从文章 HTML 抓取视频链接。
 * 参考 auto-video-player.html 模板的四种方法，适配 Kotlin 原生环境（不走 WebView JS）。
 *
 * 五种提取方法（综合去重，优先精确方法）：
 * ① video/source 标签（jsoup，最精确）
 * ② OG/Meta 标签（jsoup，开放图谱协议）
 * ③ script JSON（jsoup+Regex，视频信息在 JSON 中的站点）
 * ④ JS 变量（Regex，视频地址在 JS 变量中）
 * ⑤ 正则提取（Regex，通用兜底）
 *
 * 不实现：XHR/Fetch 拦截（WebView JS 独有能力，原生 Kotlin 无法拦截浏览器网络请求）
 *
 * 详见 docs/specs/rss-video-player-enhancement/spec.md R5 节
 */
object VideoUrlExtractor {

    // 视频URL正则：匹配 m3u8/mp4 结尾或含 format/type=m3u8 的 URL（忽略大小写）
    private val VIDEO_URL_REGEX =
        Regex("""https?://[^\s"'<>\]\\]+?\.(?:m3u8|mp4)(?:\?[^\s"'<>\]\\]*)?""", RegexOption.IGNORE_CASE)

    // JS变量正则：匹配 var/let/const xxx = "http...m3u8/mp4"
    private val JS_VAR_REGEX =
        Regex("""(?:var|let|const)\s+\w+\s*=\s*["'](https?://[^"']+?\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)

    // script JSON 正则：匹配 "url":"http...m3u8/mp4"
    private val SCRIPT_JSON_REGEX =
        Regex("""["'](?:url|src|video|source|file)["']\s*:\s*["'](https?://[^"']+?\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)

    // 视频流 URL 正则：用于 BackstageWebView SnifferWebClient.shouldInterceptRequest + onLoadResource 匹配网络请求
    // 参考 Fongmi/TV Sniffer.java 的 SNIFFER 正则（生产环境验证方案）
    // 匹配 m3u8/mp4/mkv/flv/mp3/m4a/aac/mpd + video/tos + rtmp，URL长度≥12 过滤短URL误匹配
    // 移除 .ts：避免 HLS 分片先于 m3u8 主playlist 被捕获（ExoPlayer 需 m3u8 主索引，无法单独播放 .ts 分片）
    // 注意：BackstageWebView 用 resUrl.matches(regex) 全匹配，需 .* 前后通配
    val VIDEO_SOURCE_REGEX = """(?i).*(?:https?://[^\s]{12,}\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd)(?:\?.*)?|https?://.*?video/tos[^\s]*|rtmp:[^\s]+).*"""

    // JS 嗅探脚本：5路 hook + Performance API 兜底，捕获播放器动态构造的视频请求
    // 参考 M3U8 Link Finder bookmarklet + MediaSource Hook 技术 + react-native-intercepting-webview
    // 在 onPageStarted 时注入（页面 JS 执行前），将请求 URL 存入 window.__videoUrls__
    // 5路 hook：fetch / XHR / HTMLMediaElement.src setter / URL.createObjectURL / MediaSource.addSourceBuffer
    // 由 BackstageWebView.ReadVideoUrlsRunnable 在 onPageFinished + delayTime 后读取 window.__videoUrls__
    const val VIDEO_SNIFF_JS = """
        (function() {
            if (window.__videoUrls__) return;
            window.__videoUrls__ = [];
            function pushUrl(url) {
                if (typeof url === 'string' && url.length > 0) window.__videoUrls__.push(url);
            }
            // 1. Hook fetch
            var origFetch = window.fetch;
            window.fetch = function(url, opts) {
                pushUrl(typeof url === 'string' ? url : (url && url.url) ? url.url : '');
                return origFetch.apply(this, arguments);
            };
            // 2. Hook XMLHttpRequest.open
            var origOpen = XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open = function(method, url) {
                pushUrl(url);
                return origOpen.apply(this, arguments);
            };
            // 3. Hook HTMLMediaElement.src setter（捕获 video.src = url 直接赋值）
            try {
                var desc = Object.getOwnPropertyDescriptor(HTMLMediaElement.prototype, 'src');
                if (desc && desc.set) {
                    Object.defineProperty(HTMLMediaElement.prototype, 'src', {
                        set: function(v) { pushUrl(v); desc.set.call(this, v); },
                        get: function() { return desc.get.call(this); },
                        configurable: true
                    });
                }
            } catch(e) {}
            // 4. Hook URL.createObjectURL（检测 MSE blob URL 创建）
            var origCOU = URL.createObjectURL;
            URL.createObjectURL = function(obj) {
                var u = origCOU.apply(this, arguments);
                if (obj instanceof MediaSource) pushUrl('__MSE__:' + u);
                return u;
            };
            // 5. Hook MediaSource.addSourceBuffer（捕获 MSE 流的 MIME 类型）
            if (window.MediaSource) {
                var origASB = MediaSource.prototype.addSourceBuffer;
                MediaSource.prototype.addSourceBuffer = function(mimeType) {
                    pushUrl('__MSE_MIME__:' + mimeType);
                    return origASB.apply(this, arguments);
                };
            }
            // 6. Performance API 兜底：页面加载后检查所有资源条目
            function checkPerf() {
                try {
                    var entries = performance.getEntriesByType('resource');
                    for (var i = 0; i < entries.length; i++) pushUrl(entries[i].name);
                } catch(e) {}
            }
            if (document.readyState === 'complete') setTimeout(checkPerf, 1000);
            else window.addEventListener('load', function() { setTimeout(checkPerf, 1000); });
        })();
    """

    /**
     * 精确提取视频 URL（前4种精确方法去重 + 播放器页面 URL 解析）
     *
     * app-stability-round2 P1-4 修复：从 extract 拆分精确方法，供 VideoPlay 优先调用
     * 精确方法命中→直接播放（高可信度，首屏快）；未命中→VideoPlay 调用 extractWithWebView 嗅探
     * 根因：原 extract 5种方法混合调用，正则兜底与精确方法同级，正则抓到非视频链接（?url= 参数页面）
     *       就直接播放，不触发更准确的嗅探
     *
     * @param html 文章页面 HTML
     * @param baseUrl 基础 URL（用于相对路径转绝对路径）
     * @return 去重后的视频 URL 列表（已转绝对路径 + 已解析播放器页面 URL）
     */
    fun extractPrecise(html: String, baseUrl: String): List<String> {
        if (html.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        // 按精确度优先级调用：标签 > Meta > JSON > JS变量
        result.addAll(extractFromVideoTags(html, baseUrl))
        result.addAll(extractFromMeta(html, baseUrl))
        result.addAll(extractFromScriptJson(html, baseUrl))
        result.addAll(extractFromJsVars(html, baseUrl))
        // 3003 Bug 修复：播放器页面 URL 解析
        val resolved = mutableListOf<String>()
        for (url in result) {
            val actualUrl = extractPlayerPageUrl(url) ?: url
            resolved.add(actualUrl)
        }
        AppLog.putDebug("extractPrecise: preciseCount=${resolved.size}, baseUrl=${sanitizeUrl(baseUrl)}")
        return resolved.distinct()
    }

    /**
     * 综合提取视频 URL（保留向后兼容，内部调用 extractPrecise + extractByRegex）
     *
     * @param html 文章页面 HTML
     * @param baseUrl 基础 URL（用于相对路径转绝对路径）
     * @return 去重后的视频 URL 列表（已转绝对路径）
     */
    fun extract(html: String, baseUrl: String): List<String> {
        if (html.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        result.addAll(extractPrecise(html, baseUrl))
        result.addAll(extractByRegex(html, baseUrl))
        return result.toList()
    }

    /**
     * 第二层抓取：BackstageWebView 网络抓包拦截
     *
     * 当 [extract] 静态解析未命中时调用。加载文章页面，监听浏览器网络请求，
     * 正则匹配视频流 URL（m3u8/mp4/mkv/flv/mp3/m4a/aac/mpd + video/tos + rtmp，参考 Fongmi/TV SNIFFER），
     * 绕过前端地址混淆、Blob 封装等伪装手段。
     *
     * 这是用户手填 V2 模板 `java.webViewGetSource(null, baseUrl, null, ".*\\.m3u8.*")` 的等价能力（增强版）。
     * 增强点：shouldInterceptRequest 拦截 fetch/XHR（V2 没有）+ 5路 JS hook + 多格式支持 + Referer 自动注入。
     *
     * 必须在后台线程调用（BackstageWebView 内部 runOnUI，但 getStrResponse 是 suspend）。
     *
     * @param url 文章页面 URL
     * @param source 订阅源（用于构造 AnalyzeUrl 获取 headerMap）
     * @param delayTime 等待 JS 动态加载视频地址的时间（默认 3000ms，从 onPageFinished 开始计时）
     * @param timeout 抓取超时时间（默认 10000ms，app-stability-round2 P2-2 从 15s 缩短为 10s，BackstageWebView 默认 60s 太长）
     * @return 视频 URL（已匹配 sourceRegex），失败返回 null
     */
    suspend fun extractWithWebView(
        url: String,
        source: BaseSource?,
        delayTime: Long = 3000L,
        timeout: Long = 10000L
    ): String? {
        if (url.isBlank()) return null
        AppLog.putInfo("R5网络抓包: 启动, ${sanitizeUrl(url)}, delayTime=${delayTime}, timeout=${timeout}")
        // 构造 AnalyzeUrl 获取 headerMap（防盗链 Referer/UA/Cookie 等）
        val headerMap = try {
            val analyzeUrl = AnalyzeUrl(url, source = source, ruleData = null)
            HashMap(analyzeUrl.headerMap).apply {
                if (!keys.any { it.equals("Referer", ignoreCase = true) }) {
                    put("Referer", url)
                }
            }
        } catch (e: Exception) {
            AppLog.putWarn("R5网络抓包: 构造 headerMap 失败, 使用空 headerMap", e)
            hashMapOf("Referer" to url)
        }
        // app-stability-round2 P2-2: 不用 runCatching（会捕获 CancellationException 导致协程取消被误记为失败）
        // 改为 try-catch + CancellationException 守卫，协程取消必须重新抛出（Kotlin 协程规范）
        // 根因：60 次 JobCancellationException——退出播放器时 stopLoading() cancelChildren 触发协程取消，
        //   runCatching 捕获 CancellationException → onFailure 记录为"抓包失败"（误报）
        return try {
            BackstageWebView(
                url = url,
                headerMap = headerMap,
                tag = source?.getKey(),
                sourceRegex = VIDEO_SOURCE_REGEX,
                delayTime = delayTime,
                timeout = timeout,
                interceptAllRequests = true,   // 新增：启用 shouldInterceptRequest 拦截 fetch/XHR
                videoSniffJs = VIDEO_SNIFF_JS   // 新增：注入 JS 覆写 fetch/XHR
            ).getStrResponse().body
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消（退出播放器/超时）是正常行为，不记录为失败
            throw e
        } catch (e: Exception) {
            AppLog.putWarn("R5网络抓包失败: ${sanitizeUrl(url)}", e)
            null
        }
    }

    /**
     * URL 脱敏：用于日志输出，只保留 path 前40字符，不输出域名和 query 参数（含 token/鉴权）
     * 符合 P0 安全规范：绝不输出完整 URL/视频域名/敏感字段
     */
    fun sanitizeUrl(url: String?): String {
        if (url.isNullOrBlank()) return "empty"
        return try {
            val u = java.net.URL(url)
            val path = u.path?.take(40) ?: ""
            "path=${path}"
        } catch (e: Exception) {
            "raw=${url.take(30)}"
        }
    }

    /**
     * 方法① video/source 标签提取（jsoup，最精确）
     * 适用：标准 HTML5 视频页面
     */
    private fun extractFromVideoTags(html: String, baseUrl: String): List<String> {
        return try {
            val doc = Jsoup.parse(html)
            val urls = mutableListOf<String>()
            // video 标签的 src 和 data-src
            doc.select("video[src]").forEach { urls.add(it.attr("src")) }
            doc.select("video[data-src]").forEach { urls.add(it.attr("data-src")) }
            // source 标签的 src 和 data-src
            doc.select("source[src]").forEach { urls.add(it.attr("src")) }
            doc.select("source[data-src]").forEach { urls.add(it.attr("data-src")) }
            // [data-src] 通用属性（部分站点用自定义属性存储视频地址）
            doc.select("[data-video-src]").forEach { urls.add(it.attr("data-video-src")) }
            urls.filter { it.isNotBlank() }
                .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                .filter { isVideoUrl(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 方法② OG/Meta 提取（jsoup，开放图谱协议）
     * 适用：支持 og:video 的站点
     */
    private fun extractFromMeta(html: String, baseUrl: String): List<String> {
        return try {
            val doc = Jsoup.parse(html)
            val urls = mutableListOf<String>()
            doc.select("meta[property=og:video]").forEach { urls.add(it.attr("content")) }
            doc.select("meta[property=og:video:url]").forEach { urls.add(it.attr("content")) }
            doc.select("meta[property=og:video:secure_url]").forEach { urls.add(it.attr("content")) }
            urls.filter { it.isNotBlank() }
                .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                .filter { isVideoUrl(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 方法③ script JSON 提取（jsoup+Regex，R5 适配性增强）
     * 适用：视频信息在 <script> 标签内 JSON 数据中的站点
     */
    private fun extractFromScriptJson(html: String, baseUrl: String): List<String> {
        return try {
            val doc = Jsoup.parse(html)
            val urls = mutableListOf<String>()
            doc.select("script").forEach { script ->
                val data = script.data()
                if (data.isNotBlank()) {
                    SCRIPT_JSON_REGEX.findAll(data).forEach { m ->
                        urls.add(m.groupValues[1])
                    }
                }
            }
            urls.map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                .filter { isVideoUrl(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 方法④ JS 变量提取（Regex）
     * 适用：视频地址在 JS 变量赋值中的场景
     */
    private fun extractFromJsVars(html: String, baseUrl: String): List<String> {
        return try {
            JS_VAR_REGEX.findAll(html).map { it.groupValues[1] }
                .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                .filter { isVideoUrl(it) }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 方法⑤ 正则提取（通用兜底）
     * 适用：通用场景，覆盖大多数直接暴露视频 URL 的页面
     * app-stability-round2 P1-4：改为 public，供 VideoPlay 嗅探失败后作为兜底的兜底调用
     */
    fun extractByRegex(html: String, baseUrl: String): List<String> {
        return try {
            VIDEO_URL_REGEX.findAll(html).map { it.value }
                .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                .filter { isStrictVideoUrl(it) }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 视频URL过滤：仅保留含 .m3u8/.mp4/format=m3u8/type=m3u8 的 URL
     * 或包含 ?url=/&url=/?playUrl=/&playUrl= 参数的播放器页面 URL
     * （3003 Bug 修复：播放器页面 URL 后续由 extractPlayerPageUrl 解析）
     * 注意：精确方法（标签/Meta/JSON/JS变量）用此方法，放行播放器页面 URL 后续解析
     */
    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains("format=m3u8") || lower.contains("type=m3u8") ||
            lower.contains("?url=") || lower.contains("&url=") ||
            lower.contains("?playurl=") || lower.contains("&playurl=")
    }

    /**
     * 严格视频URL过滤：仅保留真实视频流特征（.m3u8/.mp4/format=m3u8/type=m3u8）
     * app-stability-round2 P1-4 修复：正则兜底用此方法，避免 ?url= 等参数页面被误判为视频链接
     * 根因：原 extractByRegex 用 isVideoUrl（含 ?url= 条件），正则抓到非视频页面链接直接播放
     */
    private fun isStrictVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains("format=m3u8") || lower.contains("type=m3u8")
    }

    /**
     * 3003 Bug 修复：识别播放器页面 URL 并提取实际视频流 URL
     *
     * 场景1：/player/?url=https%3A%2F%2Fv.example.com%2Fvideo%2F...%2Findex.m3u8
     * 场景2：/player/?playUrl=https%3A%2F%2Fv.example.com%2Fvideo%2F...%2Findex.m3u8
     * 播放器页面 URL 包含 url/playUrl 参数，参数值是实际视频流 URL（URL 编码）
     *
     * @return 实际视频流 URL（已解码），若不是播放器页面 URL 则返回 null
     */
    private fun extractPlayerPageUrl(url: String): String? {
        // 检测 ?url= / &url= / ?playUrl= / &playUrl= 参数
        val urlPattern = Regex("""[?&](?:url|playUrl)=([^&]+)""", RegexOption.IGNORE_CASE)
        val match = urlPattern.find(url) ?: return null
        val encodedUrl = match.groupValues[1] ?: return null
        return try {
            val decodedUrl = URLDecoder.decode(encodedUrl, "UTF-8")
            // 验证解码后的 URL 是否是合法视频流 URL
            // Bug8 修复：移除 lower.startsWith("http") 过宽条件，要求必须包含视频扩展名
            // 避免将非视频流的 HTML 页面 URL 误判为有效视频流
            val lower = decodedUrl.lowercase()
            if (lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains("format=m3u8") || lower.contains("type=m3u8")) {
                decodedUrl
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Bug8 修复：公共方法，在 VideoPlay 的所有 URL 传入 player.setUp() 之前统一调用
     *
     * 确保播放器页面 URL（如 /player/?url=...m3u8 或 /player/?playUrl=...m3u8）
     * 在所有代码路径（ruleContent 非空分支、playRssEpisode、书源分支）中都被正确解析，
     * 避免播放器页面 URL 直接传给 ExoPlayer 触发 UnrecognizedInputFormatException (3003)。
     *
     * @param url 待检查的 URL
     * @return 如果是播放器页面 URL 则返回解析后的实际视频流 URL，否则原样返回
     */
    fun resolvePlayerPageUrl(url: String): String {
        return extractPlayerPageUrl(url) ?: url
    }

    /**
     * MacCMS 播放页检测：判断 URL 是否是 MacCMS 播放页（如 /vodplay/{id}-{r}-{e}.html）
     *
     * 场景：RSS 视频源 ruleContent 返回播放页 URL（非直接 m3u8），需在播放时按需解析
     * 特征：路径含 vodplay/vod_play/play 等，且以 .html 结尾
     *
     * @param url 待检测的 URL
     * @return true 表示是 MacCMS 播放页 URL
     */
    fun isMacCmsPlayPage(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("vodplay") || lower.contains("vod_play") || lower.contains("/play/")) &&
                (lower.endsWith(".html") || lower.endsWith(".htm"))
    }

    /**
     * 从 HTML 中提取 player_aaaa 变量的 url 字段（MacCMS 播放页视频流地址）
     *
     * MacCMS 播放页包含 JS 变量：var player_aaaa = {"flag":"play","encrypt":0,"url":"https://xxx.m3u8",...}
     * 本方法用正则提取 url 字段值，处理转义斜杠 \\/
     *
     * @param html 播放页 HTML 内容
     * @return 提取到的视频流 URL，提取失败返回 null
     */
    fun extractPlayerAaaaUrl(html: String): String? {
        if (html.isBlank()) return null
        // 匹配 player_aaaa = {..."url":"value"...}，容忍空格和单双引号
        val pattern = Regex("""player_aaaa\s*=\s*\{[\s\S]*?"url"\s*:\s*"([^"]+)"""")
        val match = pattern.find(html) ?: return null
        val rawUrl = match.groupValues[1] ?: return null
        // 处理 JSON 转义的斜杠 \/ → /
        val url = rawUrl.replace("\\/", "/")
        // 验证是 http(s) 开头的有效 URL
        return if (url.startsWith("http")) url else null
    }

    /**
     * 多线路多集按需采集统一入口：整合三层降级采集视频流 URL
     *
     * 三层降级链路：
     * 1. MacCMS 播放页解析：检测播放页 URL → 请求 HTML → 提取 player_aaaa
     * 2. DOM 解析（复用第一层 HTML）：用 extract() 从 HTML 中提取视频链接
     * 3. 网络抓包拦截：用 extractWithWebView() 启动 WebView 拦截动态请求
     *
     * @param url 播放页 URL 或视频流 URL
     * @param source 订阅源（用于构造 AnalyzeUrl 获取 headerMap）
     * @param rssArticle 文章（用于 Referer 注入）
     * @return 解析后的视频流 URL，三层均失败返回原 URL
     */
    suspend fun extractVideoUrlForEpisode(
        url: String,
        source: BaseSource?,
        rssArticle: RssArticle?
    ): String {
        if (url.isBlank()) return url
        // 构造 AnalyzeUrl（参考 VideoPlay.playRssEpisode 第1037-1041行）
        val analyzeUrl = AnalyzeUrl(url, source = source, ruleData = rssArticle)
        // 注入 Referer（参考 VideoPlay 第1043-1045行，模拟 WebView 行为解决 CDN 防盗链 404）
        if (!analyzeUrl.headerMap.any { it.key.equals("Referer", ignoreCase = true) }) {
            analyzeUrl.headerMap["Referer"] = rssArticle?.link ?: url
        }
        var resolvedUrl = resolvePlayerPageUrl(analyzeUrl.url)
        val isMacCms = isMacCmsPlayPage(resolvedUrl)
        AppLog.put("extractVideoUrlForEpisode: resolvedUrlEq=${resolvedUrl == analyzeUrl.url}, isMacCms=$isMacCms, urlEndsWithHtml=${resolvedUrl.endsWith(".html")}")
        // 第一层 MacCMS 播放页解析
        if (resolvedUrl == analyzeUrl.url && isMacCms) {
            try {
                val playPageHtml = analyzeUrl.getStrResponseAwait().body ?: ""
                AppLog.put("extractVideoUrlForEpisode: playPageHtmlLen=${playPageHtml.length}, containsPlayerAaaa=${playPageHtml.contains("player_aaaa")}")
                val m3u8Url = extractPlayerAaaaUrl(playPageHtml)
                if (!m3u8Url.isNullOrBlank()) {
                    AppLog.put("extractVideoUrlForEpisode: 第一层MacCMS解析成功, m3u8UrlLen=${m3u8Url.length}")
                    return m3u8Url
                }
                // 第二层 DOM 解析（复用第一层 HTML，避免重复请求）
                if (playPageHtml.isNotBlank()) {
                    val domUrls = extract(playPageHtml, resolvedUrl)
                    if (domUrls.isNotEmpty()) {
                        AppLog.put("extractVideoUrlForEpisode: 第二层DOM解析成功, urlCount=${domUrls.size}")
                        return domUrls[0]
                    }
                }
            } catch (e: Exception) {
                AppLog.put("extractVideoUrlForEpisode: 第一层MacCMS解析失败", e)
            }
        }
        // 第三层 网络抓包拦截
        return try {
            val webViewUrl = extractWithWebView(url, source, delayTime = 3000L, timeout = 10000L)
            if (!webViewUrl.isNullOrBlank() && webViewUrl != url) {
                AppLog.put("extractVideoUrlForEpisode: 第三层网络抓包成功, urlLen=${webViewUrl.length}")
                webViewUrl
            } else {
                resolvedUrl
            }
        } catch (e: Exception) {
            AppLog.put("extractVideoUrlForEpisode: 第三层网络抓包失败", e)
            resolvedUrl
        }
    }
}
