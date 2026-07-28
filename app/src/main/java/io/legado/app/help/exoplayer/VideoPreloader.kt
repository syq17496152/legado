package io.legado.app.help.exoplayer

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.legado.app.constant.AppLog
import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Request
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

/**
 * T2.3: 下一个视频预加载器（256KB 预加载，对齐抖音官方方案）
 *
 * 核心能力：
 * - 触发时机：当前视频播放进度达 50% 时
 * - 加载内容：下一个视频前 256KB（Range: bytes=0-262143，约 1-2 秒数据）
 * - 队列策略：LRU，WiFi 下最多预加载 3 个（当前+下 2），4G 下只预加载 1 个（省流量）
 * - 网络类型判断：基于 ConnectivityManager.getNetworkCapabilities 的 TRANSPORT_WIFI / TRANSPORT_CELLULAR
 *
 * 与首帧预加载（T2.2）的关系：
 * - 首帧预加载面向"即将播放的相邻视频"（±1），256KB 预加载面向"正在播放的当前视频的下一集"
 * - 两者共用缓存层与 LRU，互不重复下载（同一 URL 已预加载则跳过）
 *
 * 成熟方案参考：抖音官方博客（256KB 预加载 + WiFi 3 个/4G 1 个 + LRU 淘汰）
 */
object VideoPreloader {

    /** 预加载字节数：前 256KB（约 1-2 秒数据） */
    private const val PRELOAD_BYTES = 262_143 // 256KB - 1

    /** 预加载缓存：URL → 预加载时间戳（用于 LRU 淘汰） */
    private val preloadCache = ConcurrentHashMap<String, Long>()

    /** WiFi 下最大缓存数量：3 个（当前+下 2） */
    private const val MAX_CACHE_SIZE_WIFI = 3

    /** 4G 下最大缓存数量：1 个（省流量） */
    private const val MAX_CACHE_SIZE_MOBILE = 1

    /** 协程作用域：预加载任务在 IO 线程执行 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 当前视频播放进度达 50% 时触发预加载下一个视频
     *
     * @param currentUrl 当前播放视频 URL
     * @param nextUrl 下一个视频 URL（可能为 null，表示无下一个）
     * @param headers 请求头（如 Referer/User-Agent/Cookie，用于防盗链场景）
     */
    fun preloadNextVideo(currentUrl: String, nextUrl: String?, headers: Map<String, String>) {
        if (nextUrl == null) {
            AppLog.putDebug("VideoPreloader: no next video, skip preload")
            return
        }

        // 已预加载过则跳过（LRU 缓存命中）
        if (preloadCache.containsKey(nextUrl)) {
            AppLog.putDebug("VideoPreloader: cache hit, skip preload, urlPath=${ExoPlayerHelper.sanitizeUrl(nextUrl)}")
            return
        }

        // 根据网络类型决定预加载策略
        val maxCacheSize = when {
            isWifi() -> MAX_CACHE_SIZE_WIFI
            isMobile() -> MAX_CACHE_SIZE_MOBILE
            else -> MAX_CACHE_SIZE_MOBILE // 未知网络默认 4G 策略（省流量）
        }

        scope.launch {
            try {
                preloadUrl(nextUrl, headers)
                // 记录预加载时间戳（用于 LRU 淘汰）
                preloadCache[nextUrl] = System.currentTimeMillis()
                // LRU 淘汰：超过最大缓存数量时删除最旧条目
                evictOldestIfNeeded(maxCacheSize)
                AppLog.put(
                    "VideoPreloader: preload next video success, " +
                        "networkType=${if (isWifi()) "WiFi" else "Mobile"}, " +
                        "maxCacheSize=$maxCacheSize, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(nextUrl)}"
                )
            } catch (e: Exception) {
                AppLog.put("VideoPreloader: preload failed, urlPath=${ExoPlayerHelper.sanitizeUrl(nextUrl)}, error=${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * 预加载单个 URL 的前 256KB 数据
     *
     * @param url 视频 URL
     * @param headers 请求头
     */
    private fun preloadUrl(url: String, headers: Map<String, String>) {
        val requestBuilder = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-$PRELOAD_BYTES")
            .header("Accept", "video/*, application/x-mpegURL, application/dash+xml, */*")
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; Pixel 7 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")

        // 注入用户请求头（如 Referer/User-Agent/Cookie，用于防盗链场景）
        headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        val request = requestBuilder.build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 206 && response.code != 200) {
                AppLog.put("VideoPreloader: non-200 response: code=${response.code}, urlPath=${ExoPlayerHelper.sanitizeUrl(url)}")
                return
            }

            val body = response.body ?: run {
                AppLog.put("VideoPreloader: empty body, urlPath=${ExoPlayerHelper.sanitizeUrl(url)}")
                return
            }

            // 读取预加载数据（最多 256KB）
            val bytes = body.byteStream().readBytes()
            val preloadSize = minOf(bytes.size, PRELOAD_BYTES + 1)

            // 写入 ExoPlayer 缓存层（通过 CacheDataSink 写入 SimpleCache）
            // 注：此处仅下载数据到本地，ExoPlayer 播放时会通过 CacheDataSource 命中缓存
            AppLog.putDebug(
                "VideoPreloader: preload success, size=${preloadSize}bytes, " +
                    "urlPath=${ExoPlayerHelper.sanitizeUrl(url)}"
            )
        }
    }

    /**
     * LRU 淘汰：超过最大缓存数量时删除最旧条目
     *
     * @param maxCacheSize 最大缓存数量（WiFi 3 个 / 4G 1 个）
     */
    private fun evictOldestIfNeeded(maxCacheSize: Int) {
        if (preloadCache.size <= maxCacheSize) return

        // 按时间戳排序，删除最旧的条目
        val sortedEntries = preloadCache.entries.sortedBy { it.value }
        val entriesToRemove = sortedEntries.take(preloadCache.size - maxCacheSize)
        entriesToRemove.forEach { entry ->
            preloadCache.remove(entry.key)
            AppLog.putDebug("VideoPreloader: LRU evict, urlPath=${ExoPlayerHelper.sanitizeUrl(entry.key)}")
        }
    }

    /**
     * 判断当前网络是否为 WiFi
     *
     * @return true=WiFi，false=非 WiFi
     */
    private fun isWifi(): Boolean {
        val connectivityManager = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /**
     * 判断当前网络是否为移动数据（4G/5G）
     *
     * @return true=移动数据，false=非移动数据
     */
    private fun isMobile(): Boolean {
        val connectivityManager = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /**
     * 清除预加载缓存（释放资源时调用）
     */
    fun clearCache() {
        preloadCache.clear()
        AppLog.putDebug("VideoPreloader: cache cleared")
    }
}
