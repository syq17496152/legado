package io.legado.app.help.dlna

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.legado.app.constant.AppLog
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.model.VideoPlay
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * dlna-cast-cache-unify AD-01：**投屏 L2 = 内置播放器的 media3 `SimpleCache`（同一实例）**。
 *
 * 为什么要共享同一个实例（而不是"同目录再建一个"）：
 * media3 1.10.1 的 `SimpleCache` 用静态 `lockedCacheDirs` 拒绝同目录第二个实例
 * （字节码含字面量 `Another SimpleCache instance uses the folder:`，javap 实证）——
 * 因此"复用播放器已下载的分片"只有一条路：**用它的实例**。
 *
 * 键的对齐：两侧都用 media3 默认 `CacheKeyFactory`（键 = `dataSpec.key ?: uri.toString()`，
 * 全仓无 `setCacheKeyFactory`），所以"同一个分片 URL"即"同一条缓存记录"。
 *
 * 铁律：
 *  1. **不新建实例、不新建目录**（AD-01）；关闭复用开关时本对象完全不介入，既有
 *     OkHttp 磁盘缓存路径原样生效
 *  2. media3 路径的底层客户端**必须是不带磁盘缓存的** [DlnaHttp.streamClientNoDiskCache]，
 *     否则同一分片会被写两份（`cacheDir/dlna-cast` + `externalCache/exoplayer`）
 *  3. 会话 headers 逐条注入且**剥除 `Accept-Encoding`**（拿到压缩体将无法按分片字节落盘）
 *  4. 读取失败一律**退回既有回源路径**，绝不用不完整数据凑响应
 */
object CastL2Store {

    /** 是否复用播放器视频缓存（用户可配，默认开） */
    val reuseEnabled: Boolean
        get() = VideoPlay.dlnaReusePlayerCache

    /** 本会话是否禁止写入共享缓存（用户可配，默认关；开启时只用内存 L1 + 回源） */
    val sessionMemoryOnly: Boolean
        get() = VideoPlay.dlnaSessionMemoryOnly

    /**
     * 取共享缓存实例。
     *
     * @return 未开启复用、或实例初始化异常（首次访问会 new `SimpleCache` + 数据库）
     *         时返回 null → 调用方退回既有 OkHttp 路径
     */
    fun sharedCacheOrNull(): Cache? {
        if (!reuseEnabled) return null
        return kotlin.runCatching { ExoPlayerHelper.cache }.getOrElse { error ->
            AppLog.put("DlnaCast 共享播放器缓存不可用，本次退回投屏独立磁盘缓存: ${error.message}")
            null
        }
    }

    /**
     * 会话建立时**预热**共享缓存实例（AD-01 兜底）。
     *
     * 首次访问 `ExoPlayerHelper.cache` 是懒初始化（构造 `SimpleCache` 会同步建目录/恢复索引），
     * 若等到电视第一个请求才触发，这段耗时落在代理请求线程上 → 首片响应变慢。
     * 因此在会话建立（IO 线程）时先摸一下，把成本挪到起播之前。
     *
     * @return 预热后拿到的实例（不可用时为 null）
     */
    fun warmUp(): Cache? = sharedCacheOrNull()

    /**
     * 分片在共享缓存里的完整长度（字节）；不可知返回 -1。
     *
     * ⚠️ javap 实证：`ContentMetadata.getContentLength(ContentMetadata)` 是**接口静态方法**
     *（不是实例属性），因此必须写成 `ContentMetadata.getContentLength(meta)`。
     */
    fun contentLength(cache: Cache, key: String): Long = kotlin.runCatching {
        ContentMetadata.getContentLength(cache.getContentMetadata(key))
    }.getOrDefault(-1L)

    /**
     * 已缓存字节数。
     *
     * ️ javap 实证：`Cache.getCachedBytes` **未命中返回 0**（不是 -1）→
     * 判定必须用 `> 0` 语义（见 [CastRangeSpec.isFullyCached]）。
     *
     * @param length 期望字节数；`-1` 表示"从 position 读到末尾"
     */
    fun cachedBytes(cache: Cache, key: String, position: Long, length: Long): Long =
        kotlin.runCatching { cache.getCachedBytes(key, position, length) }.getOrDefault(0L)

    /**
     * 从共享缓存读满 [length] 字节。
     *
     * 调用方**必须**先用 [CastRangeSpec.isFullyCached] 确认整段命中；本方法仍会校验读满，
     * 未读满即视为失败（宁要回源重取，也不返回错位/截断字节 —— AD-06）。
     */
    fun readFully(source: CastSource.Http, position: Long, length: Int): ByteArray? {
        var dataSource: DataSource? = null
        return try {
            dataSource = openDataSource(source, withWriteSink = false)
            dataSource.open(DataSpec(Uri.parse(source.url), position, length.toLong(), null))
            val out = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = dataSource.read(out, offset, length - offset)
                if (read <= 0) return null
                offset += read
            }
            out
        } catch (e: Exception) {
            AppLog.putDebugWithTag(
                DlnaConstants.TAG,
                "L2 缓存读取失败（退回回源）: ${e.message}",
                level = AppLog.Level.WARN
            )
            null
        } finally {
            dataSource?.let { kotlin.runCatching { it.close() } }
        }
    }

    /** 预取结果 */
    class PrefetchOutcome(
        /** 读到的整片字节；读取失败为 null */
        val bytes: ByteArray?,
        /** 上游声明的完整长度（`DataSource.open` 的返回值）；未知为 -1 */
        val expectedBytes: Long,
        /** 是否**读满**（= [bytes] 可安全进 L1，见 REQ-8） */
        val complete: Boolean
    )

    /**
     * 预取一片：读完并**经 `CacheDataSink` 落入共享缓存**（`TeeDataSource` 内部完成写盘）。
     *
     * 说明：`CacheDataSink` 本身只有 `DataSink` 的 `open/write/close`（javap 实证没有
     * `write(DataSpec, InputStream)`）→ 预取必须"打开并读完"，写盘由 media3 在流经时完成。
     *
     * @param writeToCache false 表示"本会话不写入共享缓存"（只取字节供 L1 用）
     */
    fun prefetch(source: CastSource.Http, writeToCache: Boolean): PrefetchOutcome {
        var dataSource: DataSource? = null
        return try {
            dataSource = openDataSource(source, withWriteSink = writeToCache)
            // open 的返回值 = 上游解析出的可读长度（HTTP 即 Content-Length；未知为 -1）
            val expected = dataSource.open(DataSpec(Uri.parse(source.url), 0L, -1L, null))
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
            while (true) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read <= 0) break
                out.write(buffer, 0, read)
            }
            val bytes = out.toByteArray()
            PrefetchOutcome(
                bytes = bytes,
                expectedBytes = expected,
                complete = CastRangeSpec.isComplete(bytes.size.toLong(), expected)
            )
        } catch (e: IOException) {
            PrefetchOutcome(null, -1L, false)
        } finally {
            dataSource?.let { kotlin.runCatching { it.close() } }
        }
    }

    /**
     * 装配一个 media3 数据源：缓存优先读取，未命中部分走上游；[withWriteSink] 为真时落盘。
     */
    private fun openDataSource(source: CastSource.Http, withWriteSink: Boolean): DataSource {
        val factory = CacheDataSource.Factory()
            .setCache(ExoPlayerHelper.cache)
            .setUpstreamDataSourceFactory(okHttpFactory(source))
            // 缓存写入失败（磁盘满/权限）不应让播放失败：media3 在此标志下会忽略缓存错误
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        if (withWriteSink) {
            factory.setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(ExoPlayerHelper.cache)
            )
        }
        return factory.createDataSource()
    }

    /**
     * 带会话 headers 的上游工厂（局部实例，禁止改全局单例；剥除 `Accept-Encoding`）。
     *
     * ⚠️ media3 1.10.1 的 `OkHttpDataSource.Factory` **只有** `setDefaultRequestProperties(Map)`
     * （javap 实证：没有逐条的 `setDefaultRequestProperty(String, String)`）→ 必须一次性传 map。
     */
    private fun okHttpFactory(source: CastSource.Http): OkHttpDataSource.Factory {
        val factory = OkHttpDataSource.Factory(DlnaHttp.streamClientNoDiskCache)
        val headers = source.headers.filterKeys { !it.equals("Accept-Encoding", true) }
        if (headers.isNotEmpty()) {
            factory.setDefaultRequestProperties(headers)
        }
        return factory
    }

    private const val DEFAULT_BUFFER_BYTES = 64 * 1024
}