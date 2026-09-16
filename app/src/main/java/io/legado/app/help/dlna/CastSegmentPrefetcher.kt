package io.legado.app.help.dlna

import io.legado.app.constant.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * add-dlna-cast AD-16：**分片滚动预取器**（会话级）。
 *
 * 职责：把"渲染端要一片才去源站取一片"变成"手机提前把窗口内的分片备进内存"。
 * 触发点两处：① 清单重写完成（拿到有序分片表）② 每次命中/转发之后（推进窗口）。
 *
 * 铁律（对应 AD-16 Decision 第 2/7/8/9 条）：
 *  1. **在途去重**：同一 URL 只允许一次在途预取（否则同片双倍流量，反而加剧 WiFi 争用）
 *  2. **并发受限**：`Semaphore(concurrency)`，默认 3，用户可配；避免打爆源站触发风控
 *  3. **失败静默、绝不重试**（AD-02 禁重试铁律）：预取失败就当没发生，渲染端请求时走正常回源
 *  4. **scope 归会话**：`teardown` 调 [cancel]，杜绝跨会话残留
 *  5. 预取请求语义与正常转发一致：带同一份会话 headers、**剥除 `Accept-Encoding`**
 *
 * 注意：本类中 `response.use {}` 是**正确**的——它把响应体读进内存，不把流交出去，
 * 与 IF-8（把流交给 NanoHTTPD 时禁止 use）不是同一场景。
 */
class CastSegmentPrefetcher(
    private val cache: CastSegmentCache,
    concurrency: Int,
    private val window: Int
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(concurrency.coerceAtLeast(1))
    private val inflight = ConcurrentHashMap.newKeySet<String>()

    /** 最近一次清单的分片顺序（滚动推进基准） */
    @Volatile
    private var ordered: List<CastSource.Http> = emptyList()

    /** 推进游标：指向"下一个待预取分片"在 [ordered] 中的下标 */
    private val cursor = AtomicInteger(0)

    /**
     * 清单重写完成后调用：重置窗口基准到清单开头。
     *
     * 直播清单每次刷新都会带新分片，因此**每次重写都重置游标**——否则游标会漂移到旧清单的位置。
     */
    fun onPlaylist(orderedSources: List<CastSource.Http>) {
        if (!cache.enabled) return
        ordered = orderedSources
        cursor.set(0)
        kick()
    }

    /** 命中或转发完成之后调用：推进游标并补齐窗口 */
    fun onServed(url: String?) {
        if (!cache.enabled) return
        if (url.isNullOrBlank()) return
        val index = ordered.indexOfFirst { it.url == url }
        if (index >= 0) {
            cursor.updateAndGet { current -> maxOf(current, index + 1) }
        }
        kick()
    }

    /** 会话结束：取消在途预取（内存由缓存自行清空） */
    fun cancel() {
        kotlin.runCatching { scope.cancel() }
        inflight.clear()
        ordered = emptyList()
        cursor.set(0)
    }

    /** 把窗口内尚未缓存、且不在途的分片排入预取 */
    private fun kick() {
        val snapshot = ordered
        if (snapshot.isEmpty()) return
        val start = cursor.get().coerceIn(0, snapshot.size)
        val end = (start + window).coerceAtMost(snapshot.size)
        for (index in start until end) {
            val source = snapshot[index]
            if (cache.contains(source.url)) continue
            startPrefetch(source)
        }
    }

    private fun startPrefetch(source: CastSource.Http) {
        val url = source.url
        if (!inflight.add(url)) return // 已在途 → 不重复下载
        scope.launch {
            try {
                gate.withPermit {
                    if (cache.contains(url)) return@withPermit
                    val builder = Request.Builder().url(url).get()
                    // 会话 headers 是防盗链关键；Accept-Encoding 必须剥除（否则拿到压缩体）
                    source.headers.forEach { (k, v) ->
                        if (!k.equals("Accept-Encoding", true)) builder.header(k, v)
                    }
                    DlnaHttp.streamClient.newCall(builder.build()).execute().use { response ->
                        if (!response.isSuccessful) return@use
                        val body = response.body ?: return@use
                        val bytes = body.bytes()
                        if (bytes.isEmpty()) return@use
                        val mime = response.header("Content-Type").orEmpty()
                        if (cache.put(url, bytes, mime)) {
                            cache.addPrefetchedBytes(bytes.size.toLong())
                        }
                    }
                }
            } catch (e: Exception) {
                // 预取失败静默丢弃：不重试、不影响播放（AD-02）
                AppLog.putDebugWithTag(
                    DlnaConstants.TAG,
                    "预取失败（忽略）: ${e.message}",
                    level = AppLog.Level.WARN
                )
            } finally {
                inflight.remove(url)
            }
        }
    }
}