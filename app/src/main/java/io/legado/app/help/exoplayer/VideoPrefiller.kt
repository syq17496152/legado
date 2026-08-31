package io.legado.app.help.exoplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.legado.app.constant.AppLog
import io.legado.app.help.http.videoStreamClient
import io.legado.app.model.VideoPlay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 轻量预填器（video-sniff-403-and-rss-classic-fix 4.8a/4.8d / AD-12 / R-P3-7/R-P3-8）
 *
 * 预加载语义重写为"预嗅探下一集 + finalUrl 键预填首分片"后，替代禁用的
 * FirstFramePreloader/VideoPreloader（NPE 未修，禁止带病复活）承担 SimpleCache 预填职责：
 *
 * - **键统一（根治 Z4）**：以嗅探命中后的 finalUrl（最终视频地址）为 DataSpec URI，
 *   CacheDataSource 默认 cache key = uri 字符串，与播放链路
 *   （resolvingDataSource → cacheDataSourceFactory，同一 URI 进缓存）写入键完全一致，
 *   预载数据可被播放真实命中；旧预加载器用嗅探前原始页 URL 作键，与播放键永不命中。
 * - **预填头污染防线（4.8d / F-04 / Z2）**：每次预填 new 独立 OkHttpDataSource.Factory 实例，
 *   请求头仅通过**局部工厂**的 setDefaultRequestProperties 注入，
 *   **禁止**复用全局 lazy 单例工厂（ExoPlayerHelper.cronetDataFactory/okhttpDataFactory）
 *   的 setDefaultRequestProperties 写入下一集头快照——否则会覆盖当前播放中视频的分片请求头。
 * - **配置项复用**：videoPreloadBytesMB（预填字节数，0=按设备档位自动）、
 *   videoPreloadCount（预填记录 LRU 容量）、playerFirstFramePreload（预加载总开关）。
 * - 上游 client 复用 videoStreamClient（强制 HTTP/1.1，与嗅探链路一致，规避 HTTP/2 协议错误）。
 *
 * 已知上限：预填 .m3u8 时仅预填清单本体首段（分片级预填属 Phase 4 M3u8Parser 消费范畴）。
 */
object VideoPrefiller {

    /** 预填记录：finalUrl → 预填时间戳（去重 + LRU 淘汰） */
    private val prefillRecords = ConcurrentHashMap<String, Long>()

    /** 协程作用域：预填任务在 IO 线程执行，失败静默（不影响播放主链路） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 预填首分片（异步）：对 finalUrl 发 Range 请求读取前 N 字节，经 CacheDataSource 写入 SimpleCache
     *
     * @param finalUrl 嗅探命中后的最终视频地址（SimpleCache 缓存键，与播放写入键一致）
     * @param headers 请求头（HeaderResolver.merge 输出，Referer/Cookie/UA 防盗链）
     */
    fun prefill(finalUrl: String, headers: Map<String, String>) {
        // 复用 AD-01 预加载总开关（关闭时不预填）
        if (!VideoPlay.playerFirstFramePreload) return
        if (finalUrl.isBlank()) return
        // 去重：同一 finalUrl 已预填则跳过（换集快速滑动防重复下载）
        if (prefillRecords.containsKey(finalUrl)) {
            AppLog.putDebug("VideoPrefiller: cache hit, skip prefill, urlPath=${ExoPlayerHelper.sanitizeUrl(finalUrl)}")
            return
        }
        scope.launch {
            try {
                val bytes = prefillSync(finalUrl, headers)
                prefillRecords[finalUrl] = System.currentTimeMillis()
                evictOldestIfNeeded()
                AppLog.put(
                    "VideoPrefiller: prefill success, bytes=$bytes, " +
                        "urlPath=${ExoPlayerHelper.sanitizeUrl(finalUrl)}"
                )
            } catch (e: Exception) {
                AppLog.put(
                    "VideoPrefiller: prefill failed, urlPath=${ExoPlayerHelper.sanitizeUrl(finalUrl)}, " +
                        "error=${e.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * 预填单个 URL 的首分片（同步）
     *
     * 头污染防线（4.8d/F-04/Z2）：独立 DataSource 工厂实例，禁止改全局单例工厂头（F-04/Z2）——
     * 工厂与 DataSource 均为本次预填局部新建，setDefaultRequestProperties 仅作用于局部实例，
     * 全局 cronetDataFactory/okhttpDataFactory 的默认头不受影响，当前播放分片请求头零污染。
     */
    private fun prefillSync(finalUrl: String, headers: Map<String, String>): Int {
        val prefillBytes = getPrefillBytes()
        // 独立 DataSource 工厂实例（per-prefill 局部），per-request 头注入不触碰全局工厂（F-04/Z2）
        val localUpstreamFactory = OkHttpDataSource.Factory(videoStreamClient)
            .setUserAgent(ExoPlayerHelper.BROWSER_UA)
            .setDefaultRequestProperties(headers)
        val cacheFactory = CacheDataSource.Factory()
            .setCache(ExoPlayerHelper.cache)
            .setUpstreamDataSourceFactory(localUpstreamFactory)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(ExoPlayerHelper.cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
        // key=null → CacheDataSource 以 uri 字符串为缓存键（= finalUrl），对齐播放写入键（Z4）
        val dataSpec = DataSpec(Uri.parse(finalUrl), 0, prefillBytes.toLong(), null)
        val dataSource = cacheFactory.createDataSource()
        var totalRead = 0
        try {
            dataSource.open(dataSpec)
            val buffer = ByteArray(8 * 1024)
            while (totalRead < prefillBytes) {
                val toRead = minOf(buffer.size, prefillBytes - totalRead)
                val read = dataSource.read(buffer, 0, toRead)
                if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                totalRead += read
            }
        } finally {
            try {
                dataSource.close()
            } catch (e: Exception) {
                AppLog.put("VideoPrefiller: datasource close failed, error=${e.javaClass.simpleName}")
            }
        }
        return totalRead
    }

    /**
     * 预填字节数（复用 videoPreloadBytesMB 语义）：
     * 用户配置 >0 时优先（MB 转 bytes）；=0 按设备档位自动（HIGH=10MB/MID=5MB）；上限 20MB 防 OOM
     */
    private fun getPrefillBytes(): Int {
        val userConfig = VideoPlay.videoPreloadBytesMB
        val bytes = if (userConfig > 0) {
            userConfig * 1024 * 1024
        } else {
            when (DeviceInfoHelper.getDeviceTier()) {
                DeviceInfoHelper.DeviceTier.HIGH -> 10 * 1024 * 1024
                DeviceInfoHelper.DeviceTier.MID -> 5 * 1024 * 1024
            }
        }
        return bytes.coerceAtMost(20 * 1024 * 1024)
    }

    /**
     * 预填记录容量（复用 videoPreloadCount 语义）：
     * 用户配置 >0 时优先；=0 按设备档位自动（HIGH=10/MID=7）；上限 20
     */
    private fun getMaxCount(): Int {
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

    /** LRU 淘汰：预填记录超容量时删除最旧条目 */
    private fun evictOldestIfNeeded() {
        val maxSize = getMaxCount()
        if (prefillRecords.size <= maxSize) return
        val sortedEntries = prefillRecords.entries.sortedBy { it.value }
        sortedEntries.take(prefillRecords.size - maxSize).forEach { entry ->
            prefillRecords.remove(entry.key)
            AppLog.putDebug("VideoPrefiller: LRU evict, urlPath=${ExoPlayerHelper.sanitizeUrl(entry.key)}")
        }
    }

    /** 清除预填记录（退出播放器释放时调用） */
    fun clearCache() {
        prefillRecords.clear()
        AppLog.putDebug("VideoPrefiller: prefill records cleared")
    }
}
