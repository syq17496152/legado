package io.legado.app.help.dlna

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import io.legado.app.constant.AppLog
import io.legado.app.model.VideoPlay
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
            AppLog.putDebugWithTag(
                DlnaConstants.TAG,
                "代理已启动，端口 $actual",
                level = AppLog.Level.INFO
            )
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
            AppLog.putDebugWithTag(DlnaConstants.TAG, "代理拒绝方法: $method $uri", level = AppLog.Level.WARN)
            return plain(Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        val resolved = resolveSource(uri)
        if (resolved == null) {
            // token 失效/路径错误 → 设备必然取不到流（首帧 STOPPED 的常见根因），必须留痕
            AppLog.putDebugWithTag(DlnaConstants.TAG, "代理 404: $method $uri", level = AppLog.Level.WARN)
            return plain(Status.NOT_FOUND, "not found")
        }
        // 设备取流确认（60s 节流防 HLS 分片刷屏）：完全无此日志=电视根本没来取流（网络不通）
        AppLog.putThrottled(
            "DlnaCast.proxyReq",
            "代理收到设备请求: $method $uri",
            level = AppLog.Level.INFO,
            tag = DlnaConstants.TAG
        )
        activeRequests.incrementAndGet()
        return try {
            // AD-16：命中会话内存缓存 → **不回源**直接返回（响应时间由「源站 RTT + 下载」降为「局域网传输」）。
            // 仅限 GET 且非 Range、非清单：Range 的区间语义与整片不同（避免错位），清单必须实时重写。
            val source = resolved.source
            val rangeHeader = session.headers["range"]
            if (method == Method.GET &&
                source is CastSource.Http &&
                rangeHeader.isNullOrBlank() &&
                !MimeSniffer.isHlsUrl(source.url)
            ) {
                val cached = resolved.session.cache.get(source.url)
                if (cached != null) {
                    AppLog.putThrottled(
                        "DlnaCast.cache",
                        "缓存命中(不回源): $uri | ${resolved.session.cache.stats()}",
                        level = AppLog.Level.INFO,
                        tag = DlnaConstants.TAG
                    )
                    return buildResponse(
                        Status.OK,
                        cached.mime.ifBlank { DlnaConstants.MIME_FALLBACK },
                        cached.bytes.inputStream(),
                        cached.bytes.size.toLong()
                    ).also { it.addHeader("Accept-Ranges", "bytes") }
                }
            }
            val response = serveSource(resolved, session, method == Method.HEAD)
            // AD-16：一次请求完成 → 推进预取窗口（滚动预取）
            (source as? CastSource.Http)?.let { resolved.session.prefetcher.onServed(it.url) }
            response
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
        // 清单请求不透传 Range：清单是文本、体量极小，永远整段取。
        // 透传会让上游回 206（真机日志 `HLS 清单重写: code=206` 实证），而重写分支按整段文本处理，
        // 若上游按区间只回了前半段，就会产出**被截断的清单**（分片列表不完整 → 渲染端起播失败）。
        val isPlaylistUrl = MimeSniffer.isHlsUrl(source.url)
        if (!headOnly && !rangeHeader.isNullOrBlank() && !isPlaylistUrl) {
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

        // ⚠️ 这里必须是 `also`，**绝不能用 `use`**：`use` 是 inline 且会在 lambda 结束时关闭上游响应，
        // 而 NanoHTTPD 是 `serve()` 返回之后才写响应体 → 框架读到已关闭的流，抛
        // `Could not send response to the client: java.io.IOException: closed`
        // （`okio.RealBufferedSource$inputStream$1.read`）。
        // 2026-09-16 真机铁证：三批日志共 748 处该错误；清单路径因 `body.string()` 先读入内存而
        // 正常，所有分片响应全部写失败 → 电视永远取不到分片，停在「正在获取投屏内容信息」。
        // 流式分支的关闭责任随 `upstreamStream` 交给 NanoHTTPD（其 `Response.send()` 发完后
        // 会 `safeClose(data)`，字节码实证）：NanoHTTPD 关流 → 我们关上游，形成闭环。
        response.also { upstream ->
            val body: ResponseBody? = upstream.body
            val upstreamLength = body?.contentLength() ?: -1L
            val upstreamMime = upstream.header("Content-Type")
            val mime = source.mime.ifBlank { upstreamMime ?: DlnaConstants.MIME_FALLBACK }

            if (headOnly) {
                val total = parseTotalFromContentRange(upstream.header("Content-Range"))
                    ?: upstreamLength.takeIf { it >= 0 } ?: 0L
                upstream.close()
                return buildResponse(Status.OK, mime, emptyStream(), total)
                    .also { it.addHeader("Accept-Ranges", "bytes") }
            }

            // 上游错误原样透传（不吞、不重试），便于定位
            if (!upstream.isSuccessful) {
                // 防盗链 403/404 是"设备拉流失败"的高频根因，必须留痕（会话头缺失/过期时典型）
                AppLog.putDebugWithTag(
                    DlnaConstants.TAG,
                    "上游取流返回 ${upstream.code}: url=${upstream.request.url}",
                    level = AppLog.Level.WARN
                )
                upstream.close()
                return plain(Status.lookup(upstream.code), "upstream ${upstream.code}")
            }

            // m3u8：重写清单后整体回传
            if (HlsPlaylistRewriter.isPlaylist(upstreamMime, source.url)) {
                AppLog.putDebugWithTag(
                    DlnaConstants.TAG,
                    "HLS 清单重写: code=${upstream.code} mime=$upstreamMime",
                    level = AppLog.Level.INFO
                )
                val rawText = runCatching { body?.string() }.getOrNull()
                // 清单已完整读入内存，此处可安全关闭上游（与流式分支相反）
                upstream.close()
                if (rawText == null) return plain(Status.lookup(504), "playlist empty")
                // AD-16「流畅优先」：master 清单只保留最低带宽变体（用户可在投屏设置切"画质优先"）
                val text = if (VideoPlay.dlnaPreferSmooth) {
                    HlsPlaylistRewriter.keepLowestBandwidth(rawText)
                } else {
                    rawText
                }
                val finalUrl = upstream.request.url.toString()
                // AD-16：按清单顺序收集分片源，供滚动预取使用
                val orderedSegments = ArrayList<CastSource.Http>()
                val rewritten = HlsPlaylistRewriter.rewrite(text, finalUrl) { absolute ->
                    // 惰性登记分片（直播清单每次刷新都会出现新分片，预登记不可能）
                    // mime 必须按**分片自身**推断，绝不能复用清单的 mime（`source.mime`）：
                    //    复用会把每个 TS/m4s 分片的 Content-Type 标成 application/vnd.apple.mpegurl，
                    //    渲染端按清单解析二进制分片必然失败——真机表现为长期停在「正在获取投屏内容信息」
                    //    却持续拉分片（2026-09-16 铁证：launch 后 30s+ 不分片解码，请求累计 139→248 条）。
                    //    推断不出则留空 → `serveHttp` 透传上游真实 Content-Type（CDN 对分片会返回
                    //    video/mp2t 等正确类型）。
                    val segmentSource = CastSource.Http(
                        MimeSniffer.mimeFromUrl(absolute).orEmpty(),
                        absolute,
                        source.headers
                    )
                    orderedSegments.add(segmentSource)
                    val key = castSession.registerShort(segmentSource)
                    proxyPath(castSession, key)
                }
                // AD-16：清单驱动滚动预取（窗口/并发来自用户偏好；缓存禁用时为空操作）
                castSession.prefetcher.onPlaylist(orderedSegments)
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
                    upstreamStream(upstream, body),
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
                    val stream = upstreamStream(upstream, body)
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
            val stream = upstreamStream(upstream, body)
            return if (total != null) {
                buildResponse(Status.OK, mime, stream, total)
                    .also { it.addHeader("Accept-Ranges", "bytes") }
            } else {
                // 未知长度（直播）：必须走 chunked，不能猜 Content-Length
                buildResponse(Status.OK, mime, stream, -1L)
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

    /**
     * 取得「随响应体关闭而关闭上游」的输入流（配合 [serveHttp] 中的 `also` 说明一起读）。
     *
     * 为什么需要它：`serveHttp` 不能在返回前关闭上游（NanoHTTPD 是 `serve()` 返回**之后**
     * 才写响应体），而 `Response.send()` 在正常发完后会 `safeClose(data)`
     * （字节码 `NanoHTTPD$Response.send` 实证：`getfield data` → `access$000`）。
     * 因此把上游 [okhttp3.Response] 的关闭动作绑定到这条流上，即完成闭环：
     * 框架关流 → 我们关上游；客户端中途断开时框架走 catch 分支不关流，
     * 由 OkHttp 的连接泄漏检测回收（不影响正确性）。
     *
     * body 为 null（极少见）时立即关闭上游并返回空流。
     */
    private fun upstreamStream(upstream: okhttp3.Response, body: ResponseBody?): InputStream =
        if (body == null) {
            kotlin.runCatching { upstream.close() }
            emptyStream()
        } else {
            UpstreamStream(body.byteStream(), upstream)
        }

    /**
     * 把上游响应的关闭责任绑定到底层流上，见 [upstreamStream]。
     *
     * `skip` 必须委托：本地切片分支（上游忽略 Range 时）靠 `skip` 定位区间起点，
     * 若走 `InputStream` 的默认实现会逐字节读取丢弃，大文件下等于白搬一遍数据。
     */
    private class UpstreamStream(
        private val stream: InputStream,
        private val upstream: okhttp3.Response
    ) : InputStream() {

        override fun read(): Int = stream.read()

        override fun read(b: ByteArray, off: Int, len: Int): Int = stream.read(b, off, len)

        override fun available(): Int = stream.available()

        override fun skip(n: Long): Long = stream.skip(n)

        override fun close() {
            kotlin.runCatching { stream.close() }
            kotlin.runCatching { upstream.close() }
        }
    }

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
                    AppLog.putDebugWithTag(
                        DlnaConstants.TAG,
                        "代理并发已满，拒绝连接",
                        level = AppLog.Level.WARN
                    )
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
