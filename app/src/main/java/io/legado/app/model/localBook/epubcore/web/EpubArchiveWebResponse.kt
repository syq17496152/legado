package io.legado.app.model.localBook.epubcore.web

import android.webkit.WebResourceResponse
import io.legado.app.model.localBook.epubcore.archive.EpubArchive
import io.legado.app.model.localBook.epubcore.archive.EpubPath
import splitties.init.appCtx
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URLConnection
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * EPUB 压缩包 → WebView 资源响应（阶段 2.3 抽出的**共用单源**）。
 *
 * 抽因：布局会话（离屏测量 WebView）、选区会话、可见 WebView 后端需要**完全一致**的回源语义
 * （虚拟主机、路径归一、大小写不敏感、MIME、阅读器字体特例）。三份实现必然漂移，
 * 真机表现为「测量与显示字体/图片不一致」。
 *
 * 线程约束：`shouldInterceptRequest` 在工作线程回调 ⇒ 本类**无 View/主线程状态**，
 * 仅做 archive 只读访问（项目 landmine：WebView 操作须在 UI 线程）。
 *
 * 可测性：**解析与编码全部为纯 JVM**（`java.net` + [EpubPath]，不使用 `android.net.Uri`），
 * 使 [archivePath]/[baseUrl]/[mimeTypeFor] 可在单测中直接断言（`Uri` 在 JVM stub 下恒返回 null）。
 */
class EpubArchiveWebResponse(
    private val archive: EpubArchive
) {

    /** 该 URL 是否属于本压缩包的虚拟主机（供 shouldOverrideUrlLoading 判定外链）。 */
    fun isLocalUrl(url: String?): Boolean = archivePath(url) != null

    /** 章节 href → 虚拟主机 baseUrl（WebView 的相对路径解析基准）。 */
    fun baseUrl(chapterHref: String): String {
        val encoded = EpubPath.normalize(chapterHref)
            .split('/')
            .joinToString("/") { EpubPath.encodePathSegment(it) }
        return "$SCHEME://$Host/$encoded"
    }

    /**
     * 构造响应；未命中/读取失败一律返回空响应（不抛异常）。
     *
     * @param url 请求 URL。
     * @param readerFont 本次请求上下文的阅读器自定义字体绑定；null ⇒ 未配置自定义字体。
     */
    fun responseFor(url: String?, readerFont: ReaderFontBinding? = null): WebResourceResponse {
        val path = archivePath(url) ?: return emptyResponse()
        if (ReaderFontPath == path) {
            return readerFontResponse(readerFont)
        }
        return runCatching {
            if (!archive.exists(path)) return emptyResponse()
            val bytes = archive.readBytes(path)
            WebResourceResponse(mimeTypeFor(path), textEncodingFor(path), ByteArrayInputStream(bytes))
        }.getOrElse {
            emptyResponse()
        }
    }

    /**
     * 阅读器自定义字体绑定：字体**实体**路径（`content://` 或 `file://`）+ 流读取器。
     *
     * @param entityPath 字体实体路径（宿主 `readerFontPath`）。
     * @param streamProvider 按实体路径打开流；默认按 content/file 语义。
     */
    data class ReaderFontBinding(
        val entityPath: String,
        val streamProvider: (String) -> InputStream? = defaultStreamProvider()
    )

    private fun readerFontResponse(readerFont: ReaderFontBinding?): WebResourceResponse {
        val binding = readerFont ?: return emptyResponse()
        if (binding.entityPath.isBlank()) return emptyResponse()
        return runCatching {
            val stream = binding.streamProvider(binding.entityPath) ?: return emptyResponse()
            WebResourceResponse(mimeTypeFor(binding.entityPath), null, stream)
        }.getOrElse {
            emptyResponse()
        }
    }

    /** 把 URL 映射为压缩包内路径（非本主机/空路径 ⇒ null）。纯 JVM 实现（不用 `Uri`）。 */
    fun archivePath(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val prefix = "$SCHEME://$Host/"
        val lowerUrl = url.lowercase()
        val rawPath = when {
            lowerUrl.startsWith(prefix) -> url.substring(prefix.length)
            // 协议相对（`//epub.local/...`）与大小写变体（主机名不区分大小写）。
            lowerUrl.startsWith("//$Host/") -> url.substring(2 + Host.length + 1)
            else -> return null
        }
        val decoded = runCatching {
            URLDecoder.decode(rawPath.replace("+", "%2B"), StandardCharsets.UTF_8.name())
        }.getOrDefault(rawPath)
        return EpubPath.normalize(decoded).takeIf { it.isNotBlank() }
    }

    /** 内容类型判定（纯函数，供单测）。 */
    fun mimeTypeFor(path: String?): String {
        return when (path?.substringAfterLast('.', "")?.lowercase()) {
            "css" -> "text/css"
            "html", "htm" -> "text/html"
            "xhtml", "xml" -> "application/xhtml+xml"
            "svg" -> "image/svg+xml"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> URLConnection.guessContentTypeFromName(path.orEmpty()) ?: "application/octet-stream"
        }
    }

    /** 文本类资源显式声明 UTF-8，避免 Chromium 按本地代码页误判（真机实证：中文页面乱码）。 */
    private fun textEncodingFor(path: String): String? {
        return if (path.endsWith(".css", true) || path.endsWith(".html", true) || path.endsWith(".xhtml", true)) {
            "UTF-8"
        } else {
            null
        }
    }

    private fun emptyResponse(): WebResourceResponse {
        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    companion object {
        const val SCHEME = "https"

        /** 虚拟主机：与 archive v15 一致（`epub.local`），便于复用同一套回源规则。 */
        const val Host = "epub.local"

        /** 阅读器自定义字体的虚拟路径（宿主 `readerFontUrl` 即 `https://epub.local/<本值>`）。 */
        const val ReaderFontPath = "__legado_reader_font__"

        /** 按宿主 URI 语义打开字体实体（`content://` 或 `file://`）。 */
        fun defaultStreamProvider(): (String) -> InputStream? = { path ->
            runCatching {
                if (path.startsWith("content://", ignoreCase = true)) {
                    appCtx.contentResolver.openInputStream(android.net.Uri.parse(path))
                } else {
                    java.io.File(path.removePrefix("file://")).inputStream()
                }
            }.getOrNull()
        }
    }
}