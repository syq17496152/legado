package io.legado.app.help.exoplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import io.legado.app.constant.AppLog
import io.legado.app.model.VideoPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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

    /** 预加载缓存：URL → 预加载时间戳（用于 LRU 淘汰） */
    private val preloadCache = ConcurrentHashMap<String, Long>()

    /**
     * R3: 动态计算预加载字节数
     *
     * - 用户配置 >0 时优先使用用户配置（MB 转 bytes）
     * - 用户配置 =0 时按设备档位自动：HIGH=10MB / MID=5MB
     * - 上限 20MB（防止 OOM）
     */
    private fun getPreloadBytes(): Int {
        val userConfig = VideoPlay.videoPreloadBytesMB
        val bytes = if (userConfig > 0) {
            userConfig * 1024 * 1024
        } else {
            when (DeviceInfoHelper.getDeviceTier()) {
                DeviceInfoHelper.DeviceTier.HIGH -> 10 * 1024 * 1024  // 10MB
                DeviceInfoHelper.DeviceTier.MID -> 5 * 1024 * 1024    // 5MB
            }
        }
        return bytes.coerceAtMost(20 * 1024 * 1024)
    }

    /**
     * R3: 动态计算最大预加载数量
     *
     * - 用户配置 >0 时优先使用用户配置
     * - 用户配置 =0 时按设备档位自动：HIGH=10 / MID=7
     * - 上限 20
     * - R3 移除 WiFi/4G 区分（用户要求激进策略，用户可手动调低 videoPreloadCount 控制流量）
     */
    private fun getPreloadCount(): Int {
        val userConfig = VideoPlay.videoPreloadCount
        val count = if (userConfig > 0) {
            userConfig
        } else {
            when (DeviceInfoHelper.getDeviceTier()) {
                DeviceInfoHelper.DeviceTier.HIGH -> 10
                DeviceInfoHelper.DeviceTier.MID -> 7
            }
        }
        return count.coerceAtMost(20)
    }

    /** 协程作用域：预加载任务在 IO 线程执行 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * R3: 预加载下一个视频（写入 SimpleCache，播放时命中缓存）
     *
     * 改造点：
     * - 移除 WiFi/4G 网络感知区分（用户要求激进策略，用户可手动调低 videoPreloadCount 控制流量）
     * - 使用 CacheDataSink 写入 SimpleCache（原实现只读取后丢弃，浪费带宽）
     * - 用 DataSpec 限制读取字节数（getPreloadBytes() 动态计算），防止 OOM
     * - cacheKey 为纯 URL（与播放器 resolvingDataSource 解析后一致）
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

        // R3 URL 去重：已预加载过则跳过（LRU 缓存命中）
        if (preloadCache.containsKey(nextUrl)) {
            AppLog.putDebug("VideoPreloader: cache hit, skip preload, urlPath=${ExoPlayerHelper.sanitizeUrl(nextUrl)}")
            return
        }

        scope.launch {
            try {
                preloadUrl(nextUrl, headers)
                // 记录预加载时间戳（用于 LRU 淘汰）
                preloadCache[nextUrl] = System.currentTimeMillis()
                // LRU 淘汰：超过最大缓存数量时删除最旧条目
                evictOldestIfNeeded()
                AppLog.put(
                    "VideoPreloader: preload next video success, " +
                        "maxCacheSize=${getPreloadCount()}, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(nextUrl)}"
                )
            } catch (e: Exception) {
                AppLog.put("VideoPreloader: preload failed, urlPath=${ExoPlayerHelper.sanitizeUrl(nextUrl)}, error=${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * R3: 预加载单个 URL 数据（写入 SimpleCache，播放时命中缓存）
     *
     * 改造点：
     * - 从 OkHttp Request + readBytes 改为 ExoPlayer DataSource + CacheDataSink
     * - 数据写入 SimpleCache，播放时通过 CacheDataSource 命中缓存
     * - 用 DataSpec 限制读取字节数（getPreloadBytes() 动态计算），防止 OOM
     * - cacheKey 为纯 URL（与播放器 resolvingDataSource 解析后一致）
     *
     * @param url 视频 URL
     * @param headers 请求头
     */
    private fun preloadUrl(url: String, headers: Map<String, String>) {
        val preloadBytes = getPreloadBytes()
        val dataSpec = DataSpec(Uri.parse(url), 0, preloadBytes.toLong(), null)
        val upstream = ExoPlayerHelper.createPreloadDataSource(headers)
        val cacheSink = CacheDataSink(ExoPlayerHelper.cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE)

        var totalRead = 0
        try {
            upstream.open(dataSpec)
            cacheSink.open(dataSpec)
            val buffer = ByteArray(8 * 1024)  // 8KB buffer
            while (totalRead < preloadBytes) {
                val toRead = minOf(buffer.size, preloadBytes - totalRead)
                val read = upstream.read(buffer, 0, toRead)
                if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                cacheSink.write(buffer, 0, read)
                totalRead += read
            }
        } finally {
            try { cacheSink.close() } catch (e: Exception) {
                AppLog.put("VideoPreloader: cacheSink close failed, error=${e.javaClass.simpleName}")
            }
            try { upstream.close() } catch (e: Exception) {
                AppLog.put("VideoPreloader: upstream close failed, error=${e.javaClass.simpleName}")
            }
        }
        AppLog.put(
            "VideoPreloader: preload success, size=${totalRead}bytes, " +
                "urlPath=${ExoPlayerHelper.sanitizeUrl(url)}"
        )
    }

    /**
     * LRU 淘汰：超过最大缓存数量时删除最旧条目（R3: 动态数量）
     */
    private fun evictOldestIfNeeded() {
        val maxSize = getPreloadCount()
        if (preloadCache.size <= maxSize) return

        // 按时间戳排序，删除最旧的条目
        val sortedEntries = preloadCache.entries.sortedBy { it.value }
        val entriesToRemove = sortedEntries.take(preloadCache.size - maxSize)
        entriesToRemove.forEach { entry ->
            preloadCache.remove(entry.key)
            AppLog.putDebug("VideoPreloader: LRU evict, urlPath=${ExoPlayerHelper.sanitizeUrl(entry.key)}")
        }
    }

    /**
     * 清除预加载缓存（释放资源时调用）
     */
    fun clearCache() {
        preloadCache.clear()
        AppLog.putDebug("VideoPreloader: cache cleared")
    }
}
