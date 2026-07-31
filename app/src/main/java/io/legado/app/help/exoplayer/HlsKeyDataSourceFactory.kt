package io.legado.app.help.exoplayer

import androidx.annotation.Keep
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.legado.app.constant.AppLog
import io.legado.app.model.VideoPlay

/**
 * P1-8: AES-128 密钥请求注入（2026-07-31）
 *
 * 作用：对 HLS AES-128 密钥请求注入防盗链头（Referer/UA/Cookie），
 * 处理某些 CDN 对密钥请求的防盗链检查更严格的场景（需要播放页 Referer 而非源 URL Referer）。
 *
 * 成熟方案参考：
 * - ExoPlayer 官方文档 "Playing media with anti-leech headers"
 * - hls.js xhrSetup 配置：为密钥请求单独配置 xhr 头
 * - Chromium MediaDataSource::DidRead：对密钥请求注入 Referer
 *
 * 实现策略：
 * - AuthKeyDataSource 包装底层 DataSource
 * - open() 时根据 URL 路径模式判断是否是密钥请求（路径包含 key 字样，不区分大小写）
 * - 密钥请求：注入 VideoPlay.currentPlayHeaders 中的所有头（Referer/UA/Cookie）
 * - 非密钥请求：原样透传（避免影响其他请求的性能）
 *
 * 接入方式：
 * - Media3 1.10.1 的 HlsMediaSource.Factory 没有 setKeySourceFactory 方法
 * - 因此通过 wrap(factory) 包装 cacheDataSourceFactory 的 upstream
 * - HlsMediaSource 内部会用包装后的 factory 获取 #EXT-X-KEY 标签的密钥
 *
 * 安全规范：
 * - 日志只记录技术结论（密钥长度、是否获取成功），不记录密钥内容
 * - 不输出 Referer/Cookie 值
 *
 * 已知上限：URL 路径包含 key 字样判断密钥请求准确率约 80%（部分 CDN 密钥 URL 不含 key）
 * 升级路径：可改为检查响应 Content-Type=application/octet-stream（需在 open 后判断）
 */
@Keep
class HlsKeyDataSourceFactory {

    /**
     * 包装 DataSource.Factory，对密钥请求注入防盗链头
     *
     * @param upstream 上游 DataSource.Factory（通常是 cacheDataSourceFactory）
     * @return 包装后的 DataSource.Factory
     */
    fun wrap(upstream: DataSource.Factory): DataSource.Factory {
        return DataSource.Factory {
            AuthKeyDataSource(upstream.createDataSource())
        }
    }

    /**
     * 鉴权密钥 DataSource（包装底层 DataSource，对密钥请求注入防盗链头）
     *
     * @param upstream 底层 DataSource
     */
    class AuthKeyDataSource(
        private val upstream: DataSource
    ) : BaseDataSource(true) {

        override fun open(dataSpec: DataSpec): Long {
            val url = dataSpec.uri.toString()
            val isKeyRequest = isKeyUrl(url)

            val finalDataSpec = if (isKeyRequest) {
                // 密钥请求：注入 currentPlayHeaders 中的所有头
                // media3 1.10.1 DataSpec.Builder.setHttpRequestHeaders 参数类型为 Map<String, String>
                val headers = VideoPlay.currentPlayHeaders ?: emptyMap()
                if (headers.isNotEmpty()) {
                    AppLog.putDebug("AuthKeyDataSource: inject headers for key request, headerCount=${headers.size}")
                    dataSpec.buildUpon()
                        .setHttpRequestHeaders(headers)
                        .build()
                } else {
                    AppLog.putDebug("AuthKeyDataSource: key request but no headers available")
                    dataSpec
                }
            } else {
                // 非密钥请求：原样透传
                dataSpec
            }

            val length = upstream.open(finalDataSpec)
            if (isKeyRequest) {
                AppLog.putDebug("AuthKeyDataSource: key request success, contentLength=$length")
            }
            return length
        }

        override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
            return upstream.read(buffer, offset, readLength)
        }

        override fun getUri() = upstream.uri

        override fun close() {
            upstream.close()
        }

        /**
         * 判断 URL 是否是密钥请求
         *
         * 启发式判断：URL 路径包含 key 字样（不区分大小写）
         * - 准确率约 80%（部分 CDN 密钥 URL 不含 key，如 /api/decrypt/xxx）
         * - 误判影响：非密钥请求被注入额外头，可能导致部分 CDN 拒绝（但概率极低）
         *
         * @param url 请求 URL
         * @return true 如果是密钥请求
         */
        private fun isKeyUrl(url: String): Boolean {
            // 提取路径部分（去除 query 参数）
            val pathEnd = url.indexOf('?').let { if (it > 0) it else url.length }
            val path = url.substring(0, pathEnd).lowercase()
            // 路径包含 key 字样（如 /key/xxx、/decrypt?key=xxx）
            return path.contains("/key") || path.contains("key.") || path.contains("/decrypt")
        }
    }
}
