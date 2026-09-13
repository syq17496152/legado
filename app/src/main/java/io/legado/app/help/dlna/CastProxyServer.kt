package io.legado.app.help.dlna

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import io.legado.app.constant.AppLog
import io.legado.app.utils.LogUtils
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * add-dlna-cast：本地拉流代理（design AD-02 / AD-04 / AD-08 + 协议铁律表）。
 *
 * 存在的唯一理由：**渲染端带不了防盗链头**。代理在本机带头上游取流，
 * 再以标准 HTTP（Range / m3u8 重写）喂给电视。
 *
 * 实现要点（每条都对应一次踩坑或红队发现，勿随手改）：
 *  1. `NanoHTTPD(0)` 让系统分配端口，启动后回读 `listeningPort`（避免与 WebService 的 1122 冲突）
 *  2. **`start(timeout, false)` 显式覆盖 5s 默认读超时** —— 默认值对长视频过于激进
 *  3. 自定义 `AsyncRunner` 把并发钉在 16：`DefaultAsyncRunner` 每请求一线程且**无上限**，
 *     HLS 分片并发拉取 + 渲染端重试可以打爆线程
 *  4. 覆写 `useGzipWhenAccepted = false`，避免 NanoHTTPD 自行压包把视频体搅坏
 *  5. 上游请求**剥除 `Accept-Encoding`**：不剥的话 OkHttp 不会做透明解压，
 *     压缩体直接喂给电视必然花屏
 *  6. 上游响应头**白名单**：不回传 `Set-Cookie` / `Authorization` / `Location` / `Content-Encoding`
 *  7. 上游忽略 Range 回 200 时，**本地切片**并改回 206，否则电视 seek 数据错位
 *  8. 任何路径都关闭上游 `ResponseBody`，客户端中途断开也不泄漏连接
 *  9. 不缓存、不复用上游连接、不自动重试（AD-02：重试风暴会被源站风控）
 */
class CastProxyServer : NanoHTTPD(DlnaConstants.PROXY_PORT_AUTO) {

    private val running = AtomicBoolean(false)
    private val activeRequests = AtomicInteger(0)

    /** 实际监听端口；未启动时为 -1 */
    val port: Int get() = if (running.get()) listeningPort else -1

    override fun useGzipWhenAccepted(response: Response): Boolean = false

    /**
     * 启动并返回端口。
     *
     * 整体包 runCatching 且失败即确保已释放：一次启动失败若残留占位，
     * 下次投屏会莫名其妙绑不上端口（红队第 5 轮 P1）。
     */
    fun startServer(): Int? {
        if (running.get()) return port
        setAsyncRunner(BoundedAsyncRunner(DlnaConstants.PROXY_MAX_CONCURRENT_REQUESTS))
        return kotlin.runCatching {
            super.start(DlnaConstants.PROXY_SOCKET_READ_TIMEOUT_MS, false)
            running.set(true)
            val actual = listeningPort
            LogUtils.d(DlnaConstants.TAG) { "代理已启动，端口 $actual" }
            actual
        }.getOrElse { error ->
            AppLog.put("DlnaCast 代理启动失败: ${error.message}", error)
            kotlin.runCatching { stopServer() }
            null
        }
    }

    /** 幂等停止：teardown 与 Service.onDestroy 都会调，重复调用必须安全 */
    fun stopServer() {
        running.set(false)
        kotlin.runCatching {
            stop()
            closeAllConnections()
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri.orEmpty()
        val method = session.method
        if (method != Method.GET && method != Method.HEAD) {
            return plain(Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        val resolved = resolveSource(uri)
            ?: return plain(Status.NOT_FOUND, "not found")
        activeRequests.incrementAndGet()
        return try {
            serveSource(resolved, session, method == Method.HEAD)
        } catch (e: Exception) {
            AppLog.put("DlnaCast 代理处理失败: ${e.message}", e)
            plain(Status.INTERNAL_ERROR, "internal error")
        } finally {
            activeRequests.decrementAndGet()
        }
    }

    // ==================== 路由 ====================

    /** 命中的会话与其条目 */
    private data class Routed(val session: CastProxySession, val source: CastSource)

    /**
     * 解析 `/cast/{token}/{key}` 或 `/cast/{token}/s/{id}`。
     *
     * token 不在注册表 → 返回 null（调用方回 404），**且不会发起任何上游请求**（AD-08）。
     */
    private fun resolveSource(uri: String): Routed? {
        val segments = uri.trim('/').split('/')
        if (segments.size < 3) return null
        if (segments[0] != DlnaConstants.PROXY_PATH_PREFIX.trim('/')) return null
        val session = CastProxyRegistry.find(segments[1]) ?: return null
        val key = if (segments.size >= 4 &&
            segments[2] == DlnaConstants.PROXY_SHORT_ID_SEGMENT
        ) {
            segments[3]
        } else {
            segments[2]
        }
        val source = session.lookup(key) ?: return null
        return Routed(session, source)
    }

    // ==================== 分发 ====================

    private fun serveSource(routed: Routed, session: IHTTPSession, headOnly: Boolean): Response {
        val rangeHeader = session.headers["range"]?.trim()
        return when (val source = routed.source) {
            is CastSource.LocalFile -> serveLocalFile(source, rangeHeader, headOnly)
            is CastSource.Http -> serveHttp(routed.session, source, rangeHeader, headOnly)
        }
    }

    // ---- 本地文件 ----

    private fun serveLocalFile(
        source: CastSource.LocalFile,
        rangeHeader: String?,
        headOnly: Boolean
    ): Response {
        val file = File(source.path)
        if (!file.exists() || file.isDirectory) return plain(Status.NOT_FOUND, "file not found")
        if (!file.canRead()) return plain(Status.FORBIDDEN, "file not readable")
        val total = file.length()
        if (total <= 0L) return plain(Status.NOT_FOUND, "empty file")

        val range = parseRange(rangeHeader, total)
            ?: return plain(Status.RANGE_NOT_SATISFIABLE, "range not satisfiable", total)
        val length = range.end - range.start + 1
        if (headOnly) {
            return buildResponse(Status.OK, source.mime, emptyStream(), total)
                .also { it.addHeader("Accept-Ranges", "bytes") }
        }
        val stream = kotlin.runCatching {
            FileInputStream(file).apply { skip(range.start) }
        }.getOrNull() ?: return plain(Status.INTERNAL_ERROR, "open failed")

        val status = if (range.partial) Status.PARTIAL_CONTENT else Status.OK
        val body = BoundedInputStream(stream, length)
        return buildResponse(status, source.mime, body, length).apply {
            addHeader("Accept-Ranges", "bytes")
            if (range.partial) {
                addHeader("Content-Range", "bytes ${range.start}-${range.end}/$total")
            }
        }
    }

    // ---- HTTP 上游 ----

    private fun serveHttp(
        castSession: CastProxySession,
        source: CastSource.Http,
        rangeHeader: String?,
        headOnly: Boolean
    ): Response {
        val upstreamMethod = if (headOnly) "HEAD" else "GET"
        val requestBuilder = Request.Builder().url(source.url)
        // 会话 headers（防盗链关键）——但绝不透传客户端的 Accept-Encoding
        source.headers.forEach { (k, v) ->
            if (k.equals("Accept-Encoding", true)) return@forEach
            requestBuilder.header(k, v)
        }
        if (!headOnly && !rangeHeader.isNullOrBlank()) {
            // 原样透传 Range：后缀区间（bytes=-n）需要上游自己算总长，我们算不了
            requestBuilder.header("Range", rangeHeader)
        }
        requestBuilder.method(upstreamMethod, null)

        val call = DlnaHttp.streamClient.newCall(requestBuilder.build())
        val response = try {
            call.execute()
        } catch (e: IOException) {
            AppLog.put("DlnaCast 上游取流失败: ${e.message}", e)
            return plain(Status.lookup(504), "upstream timeout")
        }

        response.use { upstream ->
            val body: ResponseBody? = upstream.body
            val upstreamLength = body?.contentLength() ?: -1L
            val upstreamMime = upstream.header("Content-Type")
            val mime = source.mime.ifBlank { upstreamMime ?: DlnaConstants.MIME_FALLBACK }

            if (headOnly) {
                val total = parseTotalFromContentRange(upstream.header("Content-Range"))
                    ?: upstreamLength.takeIf { it >= 0 } ?: 0L
                body?.close()
                return buildResponse(Status.OK, mime, emptyStream(), total)
                    .also { it.addHeader("Accept-Ranges", "bytes") }
            }

            // 上游错误原样透传（不吞、不重试），便于定位
            if (!upstream.isSuccessful) {
                body?.close()
                return plain(Status.lookup(upstream.code), "upstream ${upstream.code}")
            }

            // m3u8：重写清单后整体回传
            if (HlsPlaylistRewriter.isPlaylist(upstreamMime, source.url)) {
                val text = runCatching { body?.string() }.getOrNull()
                body?.close()
                if (text == null) return plain(Status.lookup(504), "playlist empty")
                val finalUrl = upstream.request.url.toString()
                val rewritten = HlsPlaylistRewriter.rewrite(text, finalUrl) { absolute ->
                    // 惰性登记分片（直播清单每次刷新都会出现新分片，预登记不可能）
                    val key = castSession.registerShort(
                        CastSource.Http(source.mime, absolute, source.headers)
                    )
                    proxyPath(castSession, key)
                }
                val bytes = rewritten.toByteArray(Charsets.UTF_8)
                return buildResponse(
                    Status.OK,
                    "application/vnd.apple.mpegurl",
                    bytes.inputStream(),
                    bytes.size.toLong()
                )
            }

            val total = parseTotalFromContentRange(upstream.header("Content-Range"))
                ?: upstreamLength.takeIf { it >= 0 }

            if (upstream.code == 206 && total != null) {
                // 上游已按 Range 切片，直接转发。
                // Content-Length 必须是**区间长度**（= 上游给的 Content-Length），
                // 上游未给（chunked）时传 -1 走 chunked，绝不能猜成全文件长度
                val contentRange = upstream.header("Content-Range")
                return buildResponse(
                    Status.PARTIAL_CONTENT,
                    mime,
                    body?.byteStream() ?: emptyStream(),
                    upstreamLength
                ).apply {
                    addHeader("Accept-Ranges", "bytes")
                    contentRange?.let { addHeader("Content-Range", it) }
                }
            }

            // 客户端要了 Range 但上游忽略了（回了 200 全量）→ 本地切片并改回 206
            if (!rangeHeader.isNullOrBlank() && total != null && total > 0L) {
                val asked = parseRange(rangeHeader, total)
                if (asked != null && asked.partial) {
                    val stream = body?.byteStream() ?: return plain(Status.lookup(504), "no body")
                    val skip = stream.skip(asked.start)
                    if (skip < asked.start) {
                        stream.close()
                        return plain(Status.lookup(504), "skip failed")
                    }
                    val length = asked.end - asked.start + 1
                    return buildResponse(
                        Status.PARTIAL_CONTENT,
                        mime,
                        BoundedInputStream(stream, length),
                        length
                    ).apply {
                        addHeader("Accept-Ranges", "bytes")
                        addHeader("Content-Range", "bytes ${asked.start}-${asked.end}/$total")
                    }
                }
            }

            // 普通整段传输
            val stream = body?.byteStream()
            return if (total != null) {
                buildResponse(Status.OK, mime, stream ?: emptyStream(), total)
                    .also { it.addHeader("Accept-Ranges", "bytes") }
            } else {
                // 未知长度（直播）：必须走 chunked，不能猜 Content-Length
                buildResponse(Status.OK, mime, stream ?: emptyStream(), -1L)
                    .also { it.addHeader("Accept-Ranges", "bytes") }
            }
        }
    }

    // ==================== 工具 ====================

    /** 当前会话（用于 HLS 重写时惰性登记分片） */
    private fun proxyPath(session: CastProxySession, key: String): String =
        "${DlnaConstants.PROXY_PATH_PREFIX}/${session.token}" +
            "/${DlnaConstants.PROXY_SHORT_ID_SEGMENT}/$key"

    /**
     * 构造响应。
     *
     * `length < 0` 走 chunked（未知长度）；否则固定长度。
     * 统一不设 `Content-Encoding`（输出恒为 identity，见铁律表）。
     */
    private fun buildResponse(
        status: Response.IStatus,
        mime: String,
        stream: InputStream,
        length: Long
    ): Response {
        val response = if (length >= 0) {
            newFixedLengthResponse(status, mime, stream, length)
        } else {
            newChunkedResponse(status, mime, stream)
        }
        response.setGzipEncoding(false)
        response.setChunkedTransfer(length < 0)
        return response
    }

    private fun plain(status: Response.IStatus, text: String, total: Long? = null): Response {
        val body = text.toByteArray(Charsets.UTF_8)
        return newFixedLengthResponse(status, "text/plain", body.inputStream(), body.size.toLong())
            .apply {
                setGzipEncoding(false)
                if (status == Status.RANGE_NOT_SATISFIABLE && total != null) {
                    addHeader("Content-Range", "bytes */$total")
                }
            }
    }

    private fun emptyStream(): InputStream = ByteArray(0).inputStream()

    /** 解析出的区间（[partial] 为 false 表示客户端没要区间，回整段） */
    private data class ResolvedRange(val start: Long, val end: Long, val partial: Boolean)

    /**
     * 解析 `Range` 头。
     *
     * 只支持**单区间**（`bytes=a-b` / `bytes=a-` / `bytes=-n`）：
     * 多区间按 RFC 7233 允许的方式**忽略**（回 200 全量），不做 `multipart/byteranges`
     * —— 电视播放器基本只用单区间，实现 multipart 收益为零、出错面大。
     *
     * @return null 表示区间越界（应回 416）；[ResolvedRange.partial] 为 false 表示整段
     */
    private fun parseRange(header: String?, total: Long): ResolvedRange? {
        if (header.isNullOrBlank() || total <= 0L) return ResolvedRange(0, total - 1, false)
        val spec = header.trim()
        if (!spec.startsWith("bytes=", true)) return ResolvedRange(0, total - 1, false)
        val value = spec.substringAfter('=').trim()
        if (value.contains(',')) return ResolvedRange(0, total - 1, false) // 多区间 → 忽略
        val dash = value.indexOf('-')
        if (dash < 0) return ResolvedRange(0, total - 1, false)
        val startText = value.substring(0, dash).trim()
        val endText = value.substring(dash + 1).trim()
        return when {
            startText.isEmpty() && endText.isEmpty() -> ResolvedRange(0, total - 1, false)
            startText.isEmpty() -> {
                // 后缀区间 bytes=-n：取最后 n 字节
                val suffix = endText.toLongOrNull() ?: return ResolvedRange(0, total - 1, false)
                if (suffix <= 0L) return null
                val start = (total - suffix).coerceAtLeast(0L)
                ResolvedRange(start, total - 1, true)
            }
            else -> {
                val start = startText.toLongOrNull() ?: return ResolvedRange(0, total - 1, false)
                if (start >= total) return null // 越界 → 416
                val end = if (endText.isEmpty()) {
                    total - 1
                } else {
                    (endText.toLongOrNull() ?: (total - 1)).coerceAtMost(total - 1)
                }
                if (end < start) return null
                ResolvedRange(start, end, true)
            }
        }
    }

    /** 从 `Content-Range: bytes a-b/total` 里取 total */
    private fun parseTotalFromContentRange(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        val afterSlash = value.substringAfter('/', "").trim()
        return afterSlash.toLongOrNull()
    }

    /**
     * 限量输入流：只允许读出 [limit] 字节。
     *
     * 用途：本地文件按区间切片、上游忽略 Range 时本地补偿切片。
     * 读满即返回 -1，NanoHTTPD 会据此正常结束响应。
     */
    private class BoundedInputStream(
        private val source: InputStream,
        private val limit: Long
    ) : InputStream() {
        private var remaining = limit

        override fun read(): Int {
            if (remaining <= 0) return -1
            val b = source.read()
            if (b >= 0) remaining--
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(len.toLong(), remaining).toInt()
            val read = source.read(b, off, toRead)
            if (read > 0) remaining -= read
            return read
        }

        override fun available(): Int = source.available()

        override fun close() {
            kotlin.runCatching { source.close() }
        }
    }

    /**
     * 有并发上限的 AsyncRunner。
     *
     * `DefaultAsyncRunner` 每请求开一条线程且不设上限；HLS 并发分片 + 渲染端重试
     * 足以把线程数推到危险区。超限时直接拒绝连接（等价于回 503 的效果），
     * 而不是继续堆线程（红队第 2 轮 P1）。
     */
    private class BoundedAsyncRunner(maxThreads: Int) : AsyncRunner {
        private val executor = ThreadPoolExecutor(
            1,
            maxThreads,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            { runnable -> Thread(runnable, "dlna-cast").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )

        override fun exec(code: ClientHandler) {
            kotlin.runCatching { executor.execute(code) }
                .onFailure {
                    // 队列满/线程满：立即关闭该连接，服务端不崩
                    LogUtils.d(DlnaConstants.TAG) { "代理并发已满，拒绝连接" }
                    kotlin.runCatching { code.close() }
                }
        }

        override fun closed(code: ClientHandler) {
            // 线程由线程池回收，无需处理
        }

        override fun closeAll() {
            kotlin.runCatching { executor.shutdownNow() }
        }
    }
}
