package io.legado.app.help.exoplayer

import androidx.annotation.Keep
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import io.legado.app.constant.AppLog
import java.io.IOException

/**
 * video-proxy-m3u8-403 AD-04：TLS 握手失败自动回退数据源（2026-09-12）
 *
 * 背景（真机/模拟器实测铁证）：
 * - 站点B CDN 对 App 内置 Cronet 的 TLS ClientHello 返回 ERR_SSL_VERSION_OR_CIPHER_MISMATCH，
 *   清单（壳域名）可正常加载，但 AES-128 密钥请求（站点B 域名）握手失败 → 播放失败
 * - 项目既有铁证（cacheDataSourceFactory 注释）方向相反：部分 CDN 拒 OkHttp(conscrypt)、
 *   仅 Cronet(BoringSSL) 能过 → 两类 CDN 各拒一种 TLS 栈，单栈无法全覆盖
 *
 * 方案：Cronet upstream open() 抛出 TLS 握手类 IOException 时，自动用 OkHttp upstream
 * 重试同一 DataSpec。回退按「请求粒度」生效：每次 open 独立判定，成功后 read/close
 * 委派给活跃的 upstream。
 *
 * 已知上限：
 * - 仅覆盖 open() 阶段握手失败（ERR_SSL_VERSION_OR_CIPHER_MISMATCH / SSLException）；
 *   传输中途被 RST 不回退（重开流语义复杂，交给 ExoPlayer 重试机制）
 * - 回退后同主机后续请求仍先走 Cronet（无主机级记忆）——每次多一次失败握手开销，
 *   换取实现简单与无状态；如实测开销显著可升级为主机级缓存
 *
 * 安全规范：日志只记录技术结论（回退触发、主机路径代号），不输出密钥与完整 URL
 */
@Keep
class TlsFallbackDataSourceFactory(
    private val primaryFactory: DataSource.Factory,
    private val fallbackFactory: DataSource.Factory
) : DataSource.Factory {

    override fun createDataSource(): DataSource {
        return TlsFallbackDataSource(primaryFactory.createDataSource(), fallbackFactory.createDataSource())
    }

    class TlsFallbackDataSource(
        private val primary: DataSource,
        private val fallback: DataSource
    ) : BaseDataSource(true) {

        private var active: DataSource? = null

        override fun open(dataSpec: DataSpec): Long {
            try {
                val length = primary.open(dataSpec)
                active = primary
                return length
            } catch (e: IOException) {
                if (!isTlsMismatch(e)) throw e
                AppLog.put("TlsFallback: primary TLS mismatch, fallback to okhttp, urlPath=${ExoPlayerHelper.sanitizeUrl(dataSpec.uri.toString())}")
                val length = fallback.open(dataSpec)
                active = fallback
                return length
            }
        }

        override fun read(buffer: ByteArray, offset: Int, readLength: Int): Int {
            return requireNotNull(active) { "open() not called" }.read(buffer, offset, readLength)
        }

        override fun getUri(): android.net.Uri? = active?.uri

        override fun close() {
            active?.close()
            active = null
        }
    }
}

/**
 * 判断是否为 TLS 握手类失败（应回退）
 *
 * 覆盖两种形态：
 * - Cronet：cause 链中 CronetException message 含 net::ERR_SSL_*（如 ERR_SSL_VERSION_OR_CIPHER_MISMATCH）
 * - OkHttp/conscrypt：SSLException / javax.net.ssl.SSLHandshakeException
 */
internal fun isTlsMismatch(e: Throwable): Boolean {
    var cur: Throwable? = e
    var depth = 0
    while (cur != null && depth < 8) {
        if (cur is javax.net.ssl.SSLException) return true
        val msg = cur.message
        if (msg != null && msg.contains("ERR_SSL")) return true
        cur = cur.cause
        depth++
    }
    return false
}
