package io.legado.app.help.http

import android.util.LruCache
import io.legado.app.constant.AppLog
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import splitties.init.appCtx
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * FR-4: favicon.ico 缓存（内存 LruCache + 磁盘 24h + 并行请求合并）
 *
 * 根因：日志铁证单次会话 137 次 favicon.ico 请求，每次 400-600ms，无缓存机制
 * 方案：内存 LruCache + 磁盘缓存 24h + 并行请求合并（同一 host 并发请求只执行一次）
 *
 * W4 整改：同步方法（非 suspend），避免 runBlocking 阻塞 OkHttp 调度线程
 *
 * 已知上限：内存缓存 50 个 favicon（约 50KB-500KB），磁盘缓存无上限（24h 自动过期）
 * 升级路径：如需更精细控制可引入 LRU 磁盘淘汰
 */
object FaviconCache {
    private const val DISK_CACHE_DURATION_MS = 24 * 60 * 60 * 1000L  // 24h
    private const val MAX_MEMORY_CACHE_SIZE = 50  // 最多 50 个 favicon

    /** 内存缓存（LruCache，按条目数限制，favicon 通常 <10KB） */
    private val memoryCache = LruCache<String, ByteArray>(MAX_MEMORY_CACHE_SIZE)

    /** 磁盘缓存目录 */
    private val diskCacheDir: File by lazy {
        File(appCtx.cacheDir, "favicon_cache").apply { mkdirs() }
    }

    /** 并行请求合并锁（同一 host 的并发请求只执行一次，其他等待复用结果） */
    private val pendingLocks = ConcurrentHashMap<String, Any>()

    /**
     * 获取缓存的 favicon Response（内存优先 → 磁盘 → 返回 null 需放行请求）
     *
     * 同步方法，在 OkHttp 拦截器中调用
     * @param request 原始请求
     * @return 命中缓存返回 Response（200 + 缓存 body），未命中返回 null
     */
    fun getCachedResponse(request: Request): Response? {
        val host = request.url.host
        val cached = get(host) ?: return null
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK (FaviconCache)")
            .body(cached.toResponseBody("image/x-icon".toMediaTypeOrNull()))
            .build()
    }

    /**
     * 获取缓存的 favicon 字节数据（内存优先 → 磁盘）
     * @param host 域名
     * @return 缓存数据，未命中返回 null
     */
    fun get(host: String): ByteArray? {
        // 内存缓存
        memoryCache.get(host)?.let { return it }
        // 磁盘缓存
        val diskFile = File(diskCacheDir, "${host.hashCode()}.bin")
        if (diskFile.exists()) {
            val age = System.currentTimeMillis() - diskFile.lastModified()
            if (age < DISK_CACHE_DURATION_MS) {
                return try {
                    val data = diskFile.readBytes()
                    memoryCache.put(host, data)
                    data
                } catch (e: Exception) {
                    AppLog.putDebug("FaviconCache: disk read failed, host=${host.take(3)}***")
                    null
                }
            }
            diskFile.delete()  // 过期删除
        }
        return null
    }

    /**
     * 写入缓存（内存 + 磁盘）
     * @param host 域名
     * @param data favicon 字节数据
     */
    fun put(host: String, data: ByteArray) {
        memoryCache.put(host, data)
        try {
            val diskFile = File(diskCacheDir, "${host.hashCode()}.bin")
            diskFile.writeBytes(data)
        } catch (e: Exception) {
            AppLog.putDebug("FaviconCache: disk write failed, host=${host.take(3)}***")
        }
    }

    /**
     * 获取并行请求合并锁（同一 host 的并发请求复用同一锁对象）
     * @param host 域名
     * @return 锁对象（用于 synchronized）
     */
    fun getLock(host: String): Any = pendingLocks.computeIfAbsent(host) { Any() }
}
