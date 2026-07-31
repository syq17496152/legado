package io.legado.app.help.exoplayer

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import io.legado.app.constant.AppLog
import io.legado.app.model.VideoPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import splitties.init.appCtx
import java.util.concurrent.ConcurrentHashMap

/**
 * T2.2: 首帧预加载器（I-frame 预加载，对齐快手官方方案）
 *
 * 核心能力：
 * - 触发时机：视频列表/切换场景中，对当前位置 ±1 的视频启动预加载
 * - 加载内容：Range 请求拉取视频前 ~1MB（含 MP4 moov box + 第一个 I-frame，或 m3u8 清单 + 首个 ts 分片头部）
 * - 写入 ExoPlayer 缓存层：播放时命中缓存直接渲染首帧，不走网络
 * - 埋点：首帧命中/未命中写入埋点字段，验收首帧命中率≥80%（快手官方数据 90%+，考虑本项目源异构性下调至 80%）
 *
 * 复用现有基础设施：
 * - 嗅探链路已支持 Range 请求与 moov 位置检测（SniffResult.moovPosition）
 * - 预加载器直接复用 ExoPlayerHelper 的请求头注入（Referer/Cookie/UA 防盗链）
 *
 * 成熟方案参考：快手官方博客（I-frame 预加载，首帧命中率 90%+）
 */
object FirstFramePreloader {

    /** A5 预热字节数：前 64KB（首个视频点击瞬间预热，建立 TCP+DNS，加速后续 ExoPlayer 请求） */
    private const val PREWARM_BYTES = 65_535 // 64KB - 1

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
        return bytes.coerceAtMost(20 * 1024 * 1024)  // 上限 20MB 防 OOM
    }

    /** 预加载缓存：URL → 预加载时间戳（用于 LRU 淘汰） */
    private val preloadCache = ConcurrentHashMap<String, Long>()

    /**
     * R3: 动态计算最大预加载数量
     *
     * - 用户配置 >0 时优先使用用户配置
     * - 用户配置 =0 时按设备档位自动：HIGH=10 / MID=7
     * - 上限 20（防止过多预加载消耗带宽和磁盘）
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
     * A5：首个视频预热机制（用户点击视频列表项时调用）
     *
     * 场景：用户点击视频列表项 → 立即异步预加载前 64KB → VideoPlayerActivity 启动 + initSource → ExoPlayer 播放
     * 价值：
     * - 提前建立 TCP 连接 + DNS 解析（CDN 预热），ExoPlayer 请求时复用连接加速首帧
     * - 提前下载前 64KB 数据（含 m3u8 清单头部或 MP4 moov box 前段），TCP 窗口扩大
     * - 记录到 preloadCache，避免 FirstFramePreloader 重复预加载
     *
     * 与 preloadFirstFrame 的区别：
     * - preloadFirstFrame 预加载 ±1 的相邻视频（1MB），播放时触发
     * - prewarmCurrentVideo 预加载当前视频（64KB），点击时触发
     *
     * @param url 当前视频 URL（点击进入播放页的视频）
     * @param headers 请求头（如 Referer/User-Agent/Cookie，用于防盗链场景）
     */
    fun prewarmCurrentVideo(url: String, headers: Map<String, String>) {
        // AD-01: 首帧预加载开关检查，关闭时不预热（WEAK 档行为）
        if (!VideoPlay.playerFirstFramePreload) {
            AppLog.putDebug("FirstFramePreloader: prewarm disabled by playerFirstFramePreload=false")
            return
        }
        if (preloadCache.containsKey(url)) {
            AppLog.putDebug("FirstFramePreloader: prewarm cache hit, skip, urlPath=${ExoPlayerHelper.sanitizeUrl(url)}")
            return
        }

        scope.launch {
            try {
                prewarmUrl(url, headers)
                // 记录预加载时间戳（复用 preloadCache，避免 FirstFramePreloader 重复预加载）
                preloadCache[url] = System.currentTimeMillis()
                evictOldestIfNeeded()
            } catch (e: Exception) {
                AppLog.put("FirstFramePreloader: prewarm failed, urlPath=${ExoPlayerHelper.sanitizeUrl(url)}, error=${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * A5：预加载单个 URL 的前 64KB 数据（预热专用，写入 SimpleCache 复用缓存）
     *
     * R3 改造：从 OkHttp Request + readBytes 改为 ExoPlayer DataSource + CacheDataSink
     * - 数据写入 SimpleCache，播放时命中缓存（原实现只读取后丢弃，浪费带宽）
     * - 用 DataSpec 限制读取字节数，防止 OOM
     * - cacheKey 为纯 URL（与播放器 resolvingDataSource 解析后一致）
     *
     * @param url 视频 URL
     * @param headers 请求头
     */
    private fun prewarmUrl(url: String, headers: Map<String, String>) {
        val dataSpec = DataSpec(Uri.parse(url), 0, (PREWARM_BYTES + 1).toLong(), null)
        val upstream = ExoPlayerHelper.createPreloadDataSource(headers)
        val cacheSink = CacheDataSink(ExoPlayerHelper.cache, CacheDataSink.DEFAULT_FRAGMENT_SIZE)

        var totalRead = 0
        try {
            upstream.open(dataSpec)
            cacheSink.open(dataSpec)
            val buffer = ByteArray(8 * 1024)  // 8KB buffer
            while (totalRead <= PREWARM_BYTES) {
                val toRead = minOf(buffer.size, PREWARM_BYTES + 1 - totalRead)
                val read = upstream.read(buffer, 0, toRead)
                if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                cacheSink.write(buffer, 0, read)
                totalRead += read
            }
        } finally {
            try { cacheSink.close() } catch (e: Exception) {
                AppLog.put("FirstFramePreloader: prewarm cacheSink close failed, error=${e.javaClass.simpleName}")
            }
            try { upstream.close() } catch (e: Exception) {
                AppLog.put("FirstFramePreloader: prewarm upstream close failed, error=${e.javaClass.simpleName}")
            }
        }
        AppLog.put(
            "FirstFramePreloader: prewarm success, size=${totalRead}bytes, " +
                "urlPath=${ExoPlayerHelper.sanitizeUrl(url)}"
        )
    }

    /**
     * 预加载首帧（当前位置 ±1 的视频）
     *
     * @param urls 视频 URL 列表（当前播放列表）
     * @param currentIndex 当前播放位置
     * @param headers 请求头（如 Referer/User-Agent/Cookie，用于防盗链场景）
     */
    fun preloadFirstFrame(urls: List<String>, currentIndex: Int, headers: Map<String, String>) {
        if (urls.isEmpty()) return

        // AD-01: 分档位预加载深度（WEAK=0/MEDIUM=1/GOOD=3）
        val precacheDepth = getPrecacheDepth()
        if (precacheDepth <= 0) {
            AppLog.putDebug("FirstFramePreloader: preload disabled by precacheDepth=0")
            return
        }

        // 预加载当前位置 ±precacheDepth 的视频
        val indicesToPreload = mutableListOf<Int>()
        for (offset in 1..precacheDepth) {
            listOf(currentIndex - offset, currentIndex + offset).forEach { index ->
                if (index in urls.indices && index !in indicesToPreload) {
                    indicesToPreload.add(index)
                }
            }
        }

        indicesToPreload.forEach { index ->
            val url = urls[index]
            // 已预加载过则跳过（LRU 缓存命中）
            if (preloadCache.containsKey(url)) {
                AppLog.putDebug("FirstFramePreloader: cache hit, skip preload, urlPath=${ExoPlayerHelper.sanitizeUrl(url)}")
                return@forEach
            }

            scope.launch {
                try {
                    preloadUrl(url, headers)
                    // 记录预加载时间戳（用于 LRU 淘汰）
                    preloadCache[url] = System.currentTimeMillis()
                    // LRU 淘汰：超过最大缓存数量时删除最旧条目
                    evictOldestIfNeeded()
                } catch (e: Exception) {
                    AppLog.put("FirstFramePreloader: preload failed, urlPath=${ExoPlayerHelper.sanitizeUrl(url)}, error=${e.javaClass.simpleName}")
                }
            }
        }
    }

    /**
     * AD-01: 分档位预加载深度
     * - playerFirstFramePreload=false → 0（WEAK 档，不预加载）
     * - playerPrecacheRange > 0 → 用户配置值（1/2/3，上限5防过多消耗带宽）
     * - playerPrecacheRange = 0 → 按设备档位自动（HIGH=3 GOOD档/MID=1 MEDIUM档）
     */
    private fun getPrecacheDepth(): Int {
        if (!VideoPlay.playerFirstFramePreload) return 0
        val userConfig = VideoPlay.playerPrecacheRange
        if (userConfig > 0) return userConfig.coerceAtMost(5)
        return when (DeviceInfoHelper.getDeviceTier()) {
            DeviceInfoHelper.DeviceTier.HIGH -> 3  // GOOD 档：预加载 ±3
            DeviceInfoHelper.DeviceTier.MID -> 1   // MEDIUM 档：预加载 ±1
        }
    }

    /**
     * R3: 预加载单个 URL 的首帧数据（写入 SimpleCache，播放时命中缓存）
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
                AppLog.put("FirstFramePreloader: cacheSink close failed, error=${e.javaClass.simpleName}")
            }
            try { upstream.close() } catch (e: Exception) {
                AppLog.put("FirstFramePreloader: upstream close failed, error=${e.javaClass.simpleName}")
            }
        }
        AppLog.put(
            "FirstFramePreloader: preload success, size=${totalRead}bytes, " +
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
            AppLog.putDebug("FirstFramePreloader: LRU evict, urlPath=${ExoPlayerHelper.sanitizeUrl(entry.key)}")
        }
    }

    /** A3 修复：延迟清理 Handler（主线程，避免 onPause 立即清理导致快速切回时缓存失效） */
    private val clearCacheHandler = Handler(Looper.getMainLooper())
    private val clearCacheRunnable = Runnable {
        preloadCache.clear()
        AppLog.putDebug("FirstFramePreloader: cache cleared (delayed)")
    }

    /**
     * 清除预加载缓存（释放资源时调用，立即清理）
     */
    fun clearCache() {
        clearCacheHandler.removeCallbacks(clearCacheRunnable)
        preloadCache.clear()
        AppLog.putDebug("FirstFramePreloader: cache cleared (immediate)")
    }

    /**
     * A3 修复：延迟清除预加载缓存（onPause 时调用，30s 后清理）
     *
     * 场景：用户 onPause 后可能快速 onResume（如切换到其他 App 再切回），
     * 延迟清理避免缓存失效导致重新预热，提升首帧命中率。
     *
     * @param delayMs 延迟时间（默认 30s）
     */
    fun delayedClearCache(delayMs: Long = 30_000) {
        clearCacheHandler.removeCallbacks(clearCacheRunnable)  // 防重复
        clearCacheHandler.postDelayed(clearCacheRunnable, delayMs)
        AppLog.putDebug("FirstFramePreloader: delayed clear scheduled, delayMs=$delayMs")
    }

    /**
     * A3 修复：取消延迟清理（Activity onResume 时调用）
     *
     * 场景：用户快速切回时取消延迟清理，保留缓存。
     */
    fun cancelDelayedClear() {
        clearCacheHandler.removeCallbacks(clearCacheRunnable)
        AppLog.putDebug("FirstFramePreloader: delayed clear cancelled")
    }
}
