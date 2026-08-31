package io.legado.app.help.video

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookChapter
import io.legado.app.data.entities.RssArticle
import io.legado.app.help.http.BackstageWebView
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.analyzeRule.RuleDataInterface
import io.legado.app.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * T1.10: R5 网络抓包参数常量（V2 修订）
     *
     * - R5_DELAY_TIME: 从 3000ms 降至 1000ms（解决 Bug-1，WebView 加载后 1 秒开始拦截）
     * - R5_TIMEOUT: 从 10000ms 降至 6000ms（配合 delayTime 降低，总耗时控制在 6 秒内）
     *
     * 注意：4处调用方（VideoPlay.kt L425/L520/L547 + VideoUrlExtractor.kt L552）
     * 必须统一引用此常量，禁止硬编码（V1 文档 T1.1/T1.2 关键漏洞：只改默认值无效）
     */
    // video-sniff-403-and-rss-classic-fix 2.3a/R-P1-10：恢复巅峰窗口参数（07-26 af3ba150c 曾砍至 6s/1s 致慢源嗅探成功率下降）
    // 巅峰实证：git show 7e11d7399:VideoUrlExtractor.kt timeout=15000/delay=3000
    // 命中即收口：四路命中点均立即 destroy+resume（BackstageWebView 命中路径），快站点无额外等待
    // 已知上限：慢站点总耗时延长至 15s（快站点不受影响，命中即返回）
    const val R5_DELAY_TIME = 3000L
    const val R5_TIMEOUT = 15000L

    /**
     * FR-1: R5 嗅探去重锁
     *
     * 根因：extractWithWebView 有 4 个调用路径（VideoPlay.kt L425/L520/L547 + VideoUrlExtractor.kt L552），
     * 多路径并发触发同一 URL 的 WebView 嗅探，41% 浪费率（19:00 会话 41 次启动中 17 次重复）。
     *
     * 方案：对同一 URL 的 R5 嗅探请求加内存锁，重复请求复用已有 Deferred 结果。
     * 去重 key 用完整 URL（protocol+host+path+query），比 path 更精确（避免同 path 不同 query 误判）。
     * 用独立 CoroutineScope（SupervisorJob）创建 Deferred，避免调用方取消影响其他复用方。
     *
     * 已知上限：ConcurrentHashMap 常驻内存，每条记录约 200 字节，单次会话 < 50 次
     * 升级路径：如需更精细控制可按 source+url 分组，或引入 LRU 淘汰
     */
    // 2.3/R-P1-1：泛型升级 SniffCandidate?（携带嗅探上下文）
    private val r5InProgress = ConcurrentHashMap<String, Deferred<SniffCandidate?>>()
    private val r5CleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * FR-8: play.php 类 URL 预解析缓存（降低首帧延迟方差）
     *
     * 根因：play.php 类 URL 首帧延迟方差 7.5 倍（701ms~5309ms），慢路径为重定向处理
     * 方案：解析成功后缓存结果 5 分钟，下次同一 URL 直接返回缓存（跳过重定向+解析）
     *
     * 已知上限：ConcurrentHashMap 常驻内存，每条记录约 300 字节，单次会话 < 30 次
     * 升级路径：如需更精细控制可引入 LRU 淘汰
     */
    private data class PlayerPageCacheEntry(val videoUrl: String, val timestamp: Long)
    private val playerPageCache = ConcurrentHashMap<String, PlayerPageCacheEntry>()
    private const val PLAYER_PAGE_CACHE_TTL_MS = 5 * 60 * 1000L  // 5 分钟

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
        // T2.5: extractPrecise 各阶段耗时日志（统一 putInfo 级别，release 包可见）
        val startTime = System.currentTimeMillis()
        AppLog.putInfo("extractPrecise: start, baseUrl=${sanitizeUrl(baseUrl)}")
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
        val elapsed = System.currentTimeMillis() - startTime
        AppLog.putInfo("extractPrecise: end, preciseCount=${resolved.size}, elapsed=${elapsed}ms, baseUrl=${sanitizeUrl(baseUrl)}")
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
     * @param delayTime 等待 JS 动态加载视频地址的时间（默认 R5_DELAY_TIME=1000ms，T1.10 从 3000ms 降至 1000ms 解决 Bug-1）
     * @param timeout 抓取超时时间（默认 R5_TIMEOUT=6000ms，T1.10 从 10000ms 降至 6000ms 配合 delayTime 降低）
     * @return 视频 URL（已匹配 sourceRegex），失败返回 null
     */
    suspend fun extractWithWebView(
        url: String,
        source: BaseSource?,
        delayTime: Long = R5_DELAY_TIME,
        timeout: Long = R5_TIMEOUT
    ): SniffCandidate? {
        if (url.isBlank()) return null

        // FR-1: 提取完整 URL（去掉 fragment）作为去重 key
        // 用完整 URL 而非 path，避免同 path 不同 query 误判为相同请求
        val dedupKey = try {
            val u = java.net.URL(url)
            buildString {
                append(u.protocol).append("://").append(u.host)
                if (u.port != -1) append(":").append(u.port)
                append(u.path ?: "")
                u.query?.let { append("?").append(it) }
            }
        } catch (e: Exception) {
            url  // URL 解析失败，用原始 URL 作为 key
        }

        // FR-1: 检查是否有进行中的 R5 嗅探，有则复用
        r5InProgress[dedupKey]?.let { existing ->
            if (!existing.isCompleted) {
                AppLog.putDebug("FR-1 R5嗅探去重: 复用进行中请求, key=${dedupKey.take(30)}")
                return try {
                    existing.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } finally {
                    r5InProgress.remove(dedupKey, existing)
                }
            }
            // 已完成但未清理，移除后继续创建新的
            r5InProgress.remove(dedupKey, existing)
        }

        // FR-1: 原子性获取或创建 Deferred（computeIfAbsent 处理并发竞争）
        val deferred = r5InProgress.computeIfAbsent(dedupKey) {
            r5CleanupScope.async { extractWithWebViewInternal(url, source, delayTime, timeout) }
        }

        // 等待结果，完成后移除 Entry（用 remove(key, value) 确保不误删其他 Entry）
        return try {
            deferred.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消（退出播放器/超时）是正常行为，重新抛出
            throw e
        } catch (e: Exception) {
            // 嗅探失败（BackstageWebView 异常等），返回 null
            null
        } finally {
            r5InProgress.remove(dedupKey, deferred)
        }
    }

    /**
     * FR-1: R5 嗅探内部实现（原 extractWithWebView 逻辑）
     *
     * 从 extractWithWebView 拆分出来，供去重锁的 Deferred 调用。
     * 保持原有逻辑不变：构造 headerMap + BackstageWebView 嗅探 + CancellationException 守卫。
     */
    private suspend fun extractWithWebViewInternal(
        url: String,
        source: BaseSource?,
        delayTime: Long,
        timeout: Long
    ): SniffCandidate? {
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
            // R-P1-1/AD-01：命中后读取四路命中点写入的嗅探上下文候选
            val backstageWebView = BackstageWebView(
                url = url,
                headerMap = headerMap,
                tag = source?.getKey(),
                sourceRegex = VIDEO_SOURCE_REGEX,
                delayTime = delayTime,
                timeout = timeout,
                interceptAllRequests = true,   // 新增：启用 shouldInterceptRequest 拦截 fetch/XHR
                videoSniffJs = VIDEO_SNIFF_JS   // 新增：注入 JS 覆写 fetch/XHR
            )
            val response = backstageWebView.getStrResponse()
            val hitUrl = response.body
            if (hitUrl.isNullOrBlank()) {
                null
            } else {
                val candidate = backstageWebView.lastSniffCandidate
                if (candidate != null) {
                    AppLog.putInfo("R5嗅探上下文回传: source=${candidate.source}, headers=${candidate.headers.keys}")
                    candidate.copy(url = hitUrl)
                } else {
                    // 兜底：命中但无上下文（理论不可达，四路命中均已写入）
                    SniffCandidate(url = hitUrl, source = SniffCandidate.SOURCE_WEBVIEW_RUNTIME)
                }
            }
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
     * Bug-fix 2026-07-26: 判断 URL 是否已是视频流格式（可直接交给 ExoPlayer 播放）
     *
     * 识别 .m3u8/.mpd/.mp4/.flv/.mkv/.webm 等视频流后缀（忽略 query 参数和锚点）
     * 排除 .ts（HLS 分片，需配合 m3u8 主清单使用，不能单独播放）
     *
     * 使用场景：extractVideoUrlForEpisode 入口快速路径，避免已解析出的视频流 URL 走完整三层解析
     * 铁证：logcat 显示 11 次 .m3u8 URL 因缺少快速路径走 12 秒 WebView 抓包超时返回 null 触发降级
     *
     * @param url 待检测的 URL
     * @return true 表示 URL 后缀是视频流格式，可直接交给 ExoPlayer
     */
    private fun isDirectVideoStreamUrl(url: String): Boolean {
        val lower = url.lowercase().substringBefore("?").substringBefore("#")
        return lower.endsWith(".m3u8") ||
            lower.endsWith(".mpd") ||
            lower.endsWith(".mp4") ||
            lower.endsWith(".flv") ||
            lower.endsWith(".mkv") ||
            lower.endsWith(".webm") ||
            lower.contains("format=m3u8") ||
            lower.contains("type=m3u8")
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
     * 解析 Referer 注入源（RSS 文章 / 书源章节兼容）
     *
     * 能力迁移设计：extractVideoUrlForEpisode 第三参由 RssArticle 泛化为 RuleDataInterface
     * （docs/specs/sniff-migration-booksource/design.md AD-06）
     * - RssArticle 使用 link 属性
     * - BookChapter 使用 url 属性（章节页 URL 作为 Referer）
     * - 其他规则数据源无 URL 语义时回退到请求 URL 本身
     */
    private fun resolveReferer(ruleData: RuleDataInterface?, url: String): String {
        return when (ruleData) {
            is RssArticle -> ruleData.link
            is BookChapter -> ruleData.url
            else -> url
        } ?: url
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
     * @param ruleData 规则数据（RSS 文章 / 书源章节，用于 ruleData 变量与 Referer 注入）
     * @return 解析后的视频流 URL，三层均失败或超时返回 null（T2.9 改造，避免非视频流URL传给 ExoPlayer）
     */
    suspend fun extractVideoUrlForEpisode(
        url: String,
        source: BaseSource?,
        ruleData: RuleDataInterface?
    ): SniffCandidate? {
        if (url.isBlank()) return null
        // Bug-fix 2026-07-26: URL 已是视频流格式时直接返回，跳过三层解析
        // 铁证：logcat 11 次 .m3u8 URL 走完整 12 秒超时返回 null 触发 WebView 降级（WebView 也不支持 HLS）
        // 回归原因：R4 T2.9 改造加入"超时返回 null"逻辑，但未补充"URL已是视频流"快速路径
        // 修复：识别 .m3u8/.mpd/.mp4/.flv/.mkv/.webm/.ts(排除) 等视频流后缀，直接交给 ExoPlayer
        if (isDirectVideoStreamUrl(url)) {
            AppLog.putInfo("extractVideoUrlForEpisode: URL已是视频流, 跳过三层解析直接返回, ${sanitizeUrl(url)}")
            return SniffCandidate(url = url, source = SniffCandidate.SOURCE_FAST)
        }
        // FR-8: play.php 类 URL 预解析缓存检查（5 分钟 TTL，降低首帧延迟方差 7.5 倍）
        playerPageCache[url]?.let { entry ->
            if (System.currentTimeMillis() - entry.timestamp < PLAYER_PAGE_CACHE_TTL_MS) {
                AppLog.putInfo("FR-8 预解析缓存命中, url=${sanitizeUrl(url)}")
                // 静态解析路径无 WebView 上下文，headers 留空由消费端走源配置兜底
                return SniffCandidate(url = entry.videoUrl, source = SniffCandidate.SOURCE_MACCMS)
            }
            playerPageCache.remove(url)
        }
        // sniff-result-pipeline-fix FR-1: 移除外层 withTimeoutOrNull(12000L) 抢占
        // 根因：外层 12s 超时是抢占式取消，会取消整个协程树，包括内层 R5 的 suspendCancellableCoroutine
        // 铁证：R5 命中(17:56:45.907) → 15ms 后外层超时(17:56:45.922) → 返回 null → WebView 降级
        // 方案：移除外层超时，让内层各层超时自然累加（第一层 6s + 第三层 6s = 12s）
        // 已知上限：极端情况下总耗时可达 12s（第一层 6s 超时 + 第三层 6s 超时），与原设计一致
        // 构造 AnalyzeUrl（参考 VideoPlay.playRssEpisode 第1037-1041行）
        val analyzeUrl = AnalyzeUrl(url, source = source, ruleData = ruleData)
        // 注入 Referer（参考 VideoPlay 第1043-1045行，模拟 WebView 行为解决 CDN 防盗链 404）
        if (!analyzeUrl.headerMap.any { it.key.equals("Referer", ignoreCase = true) }) {
            analyzeUrl.headerMap["Referer"] = resolveReferer(ruleData, url)
        }
        var resolvedUrl = resolvePlayerPageUrl(analyzeUrl.url)
        val isMacCms = isMacCmsPlayPage(resolvedUrl)
        AppLog.put("extractVideoUrlForEpisode: resolvedUrlEq=${resolvedUrl == analyzeUrl.url}, isMacCms=$isMacCms, urlEndsWithHtml=${resolvedUrl.endsWith(".html")}")
        // 第一层 MacCMS 播放页解析
        if (resolvedUrl == analyzeUrl.url && isMacCms) {
            try {
                // T1.11: 第一层 MacCMS 解析超时控制 6 秒（解决 Bug-12 + Bug-1 真正主因）
                // 用 withTimeoutOrNull 避免异常处理复杂性，超时返回 null 降级第三层
                val playPageHtmlResult = withTimeoutOrNull(6000L) { analyzeUrl.getStrResponseAwait().body }
                if (playPageHtmlResult == null) {
                    AppLog.put("extractVideoUrlForEpisode: MacCMS parse timeout (6s), 降级第三层网络抓包")
                }
                val playPageHtml = playPageHtmlResult ?: ""
                AppLog.put("extractVideoUrlForEpisode: playPageHtmlLen=${playPageHtml.length}, containsPlayerAaaa=${playPageHtml.contains("player_aaaa")}")
                val m3u8Url = extractPlayerAaaaUrl(playPageHtml)
                if (!m3u8Url.isNullOrBlank()) {
                    AppLog.put("extractVideoUrlForEpisode: 第一层MacCMS解析成功, m3u8UrlLen=${m3u8Url.length}")
                    playerPageCache[url] = PlayerPageCacheEntry(m3u8Url, System.currentTimeMillis())
                    // 静态解析无 WebView 上下文，headers 留空走消费端源配置兜底
                    return SniffCandidate(url = m3u8Url, source = SniffCandidate.SOURCE_MACCMS)
                }
                // 第二层 DOM 解析（复用第一层 HTML，避免重复请求）
                if (playPageHtml.isNotBlank()) {
                    val domUrls = extract(playPageHtml, resolvedUrl)
                    if (domUrls.isNotEmpty()) {
                        AppLog.put("extractVideoUrlForEpisode: 第二层DOM解析成功, urlCount=${domUrls.size}")
                        playerPageCache[url] = PlayerPageCacheEntry(domUrls[0], System.currentTimeMillis())
                        return SniffCandidate(url = domUrls[0], source = SniffCandidate.SOURCE_DOM)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // T2.11: CancellationException 守卫（解决 Bug-17 + Bug-18：协程取消必须传播）
                throw e
            } catch (e: Exception) {
                AppLog.put("extractVideoUrlForEpisode: 第一层MacCMS解析失败", e)
            }
        }
        // 第三层 网络抓包拦截
        return try {
            val webViewCandidate = extractWithWebView(url, source, delayTime = R5_DELAY_TIME, timeout = R5_TIMEOUT)
            val webViewUrl = webViewCandidate?.url
            if (!webViewUrl.isNullOrBlank() && webViewUrl != url) {
                AppLog.put("extractVideoUrlForEpisode: 第三层网络抓包成功, urlLen=${webViewUrl.length}")
                playerPageCache[url] = PlayerPageCacheEntry(webViewUrl, System.currentTimeMillis())
                webViewCandidate
            } else {
                // T2.9: 第三层失败返回 null（解决 Bug-16：不返回非视频流URL给 ExoPlayer）
                // 原 resolvedUrl 可能是文章页 URL（非视频流），传给 ExoPlayer 会触发 UnrecognizedInputFormatException
                AppLog.put("extractVideoUrlForEpisode: 第三层网络抓包返回null或等于原URL, ${sanitizeUrl(url)}")
                null
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // T2.11: CancellationException 守卫（解决 Bug-17 + Bug-18：协程取消必须传播）
            throw e
        } catch (e: Exception) {
            AppLog.put("extractVideoUrlForEpisode: 第三层网络抓包失败", e)
            // T2.9: 第三层失败返回 null（解决 Bug-16：不返回非视频流URL给 ExoPlayer）
            null
        }
    }
}
