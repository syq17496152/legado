package io.legado.app.help.video

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

    /**
     * 综合提取视频 URL（五种方法去重）
     *
     * @param html 文章页面 HTML
     * @param baseUrl 基础 URL（用于相对路径转绝对路径）
     * @return 去重后的视频 URL 列表（已转绝对路径）
     */
    fun extract(html: String, baseUrl: String): List<String> {
        if (html.isBlank()) return emptyList()
        val result = LinkedHashSet<String>()
        // 按精确度优先级调用：标签 > JSON > JS变量 > 正则兜底
        result.addAll(extractFromVideoTags(html, baseUrl))
        result.addAll(extractFromMeta(html, baseUrl))
        result.addAll(extractFromScriptJson(html, baseUrl))
        result.addAll(extractFromJsVars(html, baseUrl))
        result.addAll(extractByRegex(html, baseUrl))
        // 3003 Bug 修复：播放器页面 URL 解析
        // 候选 URL 可能是播放器页面（如 /player/?url=https%3A%2F%2F...m3u8），需提取 url 参数中的实际视频流
        val resolved = mutableListOf<String>()
        for (url in result) {
            val actualUrl = extractPlayerPageUrl(url) ?: url
            resolved.add(actualUrl)
        }
        return resolved.distinct()
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
     */
    private fun extractByRegex(html: String, baseUrl: String): List<String> {
        return try {
            VIDEO_URL_REGEX.findAll(html).map { it.value }
                .map { NetworkUtils.getAbsoluteURL(baseUrl, it) }
                .filter { isVideoUrl(it) }
                .toList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 视频URL过滤：仅保留含 .m3u8/.mp4/format=m3u8/type=m3u8 的 URL
     * 或包含 ?url=/&url= 参数的播放器页面 URL（3003 Bug 修复：播放器页面 URL 后续由 extractPlayerPageUrl 解析）
     */
    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains(".mp4") ||
            lower.contains("format=m3u8") || lower.contains("type=m3u8") ||
            lower.contains("?url=") || lower.contains("&url=")
    }

    /**
     * 3003 Bug 修复：识别播放器页面 URL 并提取实际视频流 URL
     *
     * 场景：站点A/player/?url=https%3A%2F%2Fv.example.com%2Fvideo%2F...%2Findex.m3u8
     * 播放器页面 URL 包含 url 参数，参数值是实际视频流 URL（URL 编码）
     *
     * @return 实际视频流 URL（已解码），若不是播放器页面 URL 则返回 null
     */
    private fun extractPlayerPageUrl(url: String): String? {
        // 检测 ?url= 或 &url= 参数
        val urlPattern = Regex("""[?&]url=([^&]+)""")
        val match = urlPattern.find(url) ?: return null
        val encodedUrl = match.groupValues[1] ?: return null
        return try {
            val decodedUrl = URLDecoder.decode(encodedUrl, "UTF-8")
            // 验证解码后的 URL 是否是合法视频流 URL（仅检查视频扩展名，不检查 url= 参数避免递归）
            val lower = decodedUrl.lowercase()
            if (lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains("format=m3u8") || lower.contains("type=m3u8") ||
                lower.startsWith("http")) {
                decodedUrl
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
