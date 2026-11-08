package io.legado.app.help.exoplayer

import android.net.Uri
import android.os.SystemClock
import androidx.annotation.Keep
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.legado.app.constant.AppLog
import java.util.concurrent.atomic.AtomicLong

/**
 * 清单请求绕过缓存的数据源工厂（fix-live-hls-manifest-cache，2026-11-08）。
 *
 * 根因：`cacheDataSourceFactory`（CacheDataSource + SimpleCache）经 `resolvingDataSource`
 * 交给 `DefaultMediaSourceFactory` 后，HLS 的 master/媒体清单也被写入 SimpleCache。
 * SimpleCache 不认 HTTP `Cache-Control`，直播清单的滑动窗口被冻结 ⇒
 * `HlsPlaylistTracker$PlaylistStuckException` ⇒ 播放约 6 秒后中断。
 *
 * 方案：`open(dataSpec)` 按 URI 分发 ——
 * - 清单（[isManifestUrl] 为真）⇒ 上游直连，不读也不写缓存；
 * - 分片 / 其他 ⇒ CacheDataSource（读缓存 + 边播边写），与修复前完全一致。
 *
 * 已知上限：只覆盖 `.m3u8/.m3u/.mpd` 与 `format/type=m3u8|mpd` 形态；
 * SmoothStreaming（`.ism`）未覆盖，如后续出现按同一入口扩展。
 *
 * 安全：日志只输出路径代号（经 [ExoPlayerHelper.sanitizeUrl]），不输出完整 URL/凭据。
 */
@Keep
class ManifestBypassDataSourceFactory(
    private val cacheFactory: DataSource.Factory,
    private val directFactory: DataSource.Factory
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return ManifestBypassDataSource(cacheFactory.createDataSource(), directFactory.createDataSource())
    }

    class ManifestBypassDataSource(
        private val cache: DataSource,
        private val direct: DataSource
    ) : BaseDataSource(true) {

        private var active: DataSource? = null

        override fun open(dataSpec: DataSpec): Long {
            val useDirect = isManifestUrl(dataSpec.uri.toString())
            active = if (useDirect) direct else cache
            if (useDirect && shouldLog()) {
                AppLog.putDebug(
                    "ManifestBypass: direct, urlPath=${ExoPlayerHelper.sanitizeUrl(dataSpec.uri.toString())}"
                )
            }
            return requireNotNull(active) { "active not set" }.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
            return requireNotNull(active) { "open() not called" }.read(buffer, offset, readLength)
        }

        override fun getUri(): Uri? = active?.uri

        override fun close() {
            active?.close()
            active = null
        }
    }

    private companion object {
        /** 清单重载约每秒一次；日志限频到 5 秒一条，避免刷屏。 */
        private val lastLogAt = AtomicLong(0L)

        fun shouldLog(): Boolean {
            val now = SystemClock.elapsedRealtime()
            val last = lastLogAt.get()
            if (now - last < 5_000L) return false
            return lastLogAt.compareAndSet(last, now)
        }
    }
}
