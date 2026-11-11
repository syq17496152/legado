package io.legado.app.help.exoplayer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.legado.app.constant.AppLog
import io.legado.app.help.http.videoStreamClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 预取生产端口实现（从 VideoSegmentPrefetcher 拆出，守 G-38 单文件行数）。
 *
 * 设计依据：video-buffer-prefetch-v2 audit B7（端口可注入）；
 * 本期 AD-35：写入器限速由调用方显式传入。
 */
internal const val MAX_SEGMENT_WRITE_BYTES = 32L * 1024 * 1024
internal const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

internal object DefaultPrefetchCache : PrefetchCache {
    override fun isCached(key: String, position: Long, length: Long): Boolean =
        kotlin.runCatching { ExoPlayerHelper.cache.isCached(key, position, length) }.getOrDefault(false)

    override fun contentLength(key: String): Long = kotlin.runCatching {
        ContentMetadata.getContentLength(ExoPlayerHelper.cache.getContentMetadata(key))
    }.getOrDefault(0L)

    override fun removeResource(key: String) {
        kotlin.runCatching { ExoPlayerHelper.cache.removeResource(key) }
    }

    override fun removeSpan(key: String, position: Long, length: Long) {
        kotlin.runCatching {
            val spans = ExoPlayerHelper.cache.getCachedSpans(key)
            val target = spans.firstOrNull { it.position == position && it.length == length }
                ?: spans.firstOrNull {
                    it.length > 0 && it.position <= position && it.position + it.length >= position + length
                }
            if (target != null) ExoPlayerHelper.cache.removeSpan(target)
        }
    }

    override fun cachedSpans(key: String): List<CachedSpan> = kotlin.runCatching {
        ExoPlayerHelper.cache.getCachedSpans(key)
            .filter { it.length > 0 }
            .map { CachedSpan(position = it.position, length = it.length) }
    }.getOrDefault(emptyList())
}

internal object DefaultSegmentWriter : SegmentWriter {
    override suspend fun write(
        uri: String,
        headers: Map<String, String>,
        position: Long,
        maxBytes: Long,
    ): Long = withContext(Dispatchers.IO) {
        val upstream = OkHttpDataSource.Factory(videoStreamClient)
            .setUserAgent(ExoPlayerHelper.BROWSER_UA)
            .setDefaultRequestProperties(headers)
        val factory = CacheDataSource.Factory()
            .setCache(ExoPlayerHelper.cache)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheReadDataSourceFactory(FileDataSource.Factory())
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory()
                    .setCache(ExoPlayerHelper.cache)
                    .setFragmentSize(CacheDataSink.DEFAULT_FRAGMENT_SIZE)
            )
        val length = if (maxBytes <= 0L || maxBytes >= Long.MAX_VALUE / 2) {
            C.LENGTH_UNSET.toLong()
        } else {
            maxBytes
        }
        val dataSpec = DataSpec(Uri.parse(uri), position, length, null)
        val dataSource = factory.createDataSource()
        var total = 0L
        try {
            dataSource.open(dataSpec)
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = dataSource.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT || read <= 0) break
                total += read
                if (length != C.LENGTH_UNSET.toLong() && total >= length) break
            }
        } finally {
            kotlin.runCatching { dataSource.close() }
        }
        total
    }
}

internal object DefaultManifestFetcher : ManifestFetcher {
    override suspend fun fetch(uri: String, headers: Map<String, String>): String? =
        withContext(Dispatchers.IO) {
            try {
                val builder = okhttp3.Request.Builder()
                    .url(uri)
                    .header("User-Agent", ExoPlayerHelper.BROWSER_UA)
                headers.forEach { (k, v) -> builder.header(k, v) }
                videoStreamClient.newCall(builder.build()).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body ?: return@withContext null
                    val source = body.source()
                    source.request(MAX_MANIFEST_BYTES.toLong())
                    val buffer = source.buffer
                    val size = minOf(buffer.size, MAX_MANIFEST_BYTES.toLong()).toInt()
                    if (size <= 0) null else buffer.clone().readUtf8(size.toLong())
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.putDebug("[Prefetch] manifest fetch fail error=${e.javaClass.simpleName}")
                null
            }
        }
}
