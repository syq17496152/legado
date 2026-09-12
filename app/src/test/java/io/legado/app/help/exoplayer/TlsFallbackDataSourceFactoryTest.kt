package io.legado.app.help.exoplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * video-proxy-m3u8-403 AD-04 单元测试：isTlsMismatch 判定逻辑（JVM 纯逻辑）
 *
 * 覆盖：
 * - Cronet 形态：cause 链含 net::ERR_SSL_VERSION_OR_CIPHER_MISMATCH
 * - OkHttp 形态：SSLException / SSLHandshakeException
 * - 非 TLS 失败不回退：SocketTimeout / UnknownHost / 403 InvalidResponseCode
 * - cause 链深度限制内命中
 */
class TlsFallbackDataSourceFactoryTest {

    @Test
    fun cronetSslMismatch_detected() {
        val cause = RuntimeException("Exception in CronetUrlRequest: net::ERR_SSL_VERSION_OR_CIPHER_MISMATCH, ErrorCode=11")
        val e = java.io.IOException("Open failed", cause)
        assertTrue(isTlsMismatch(e))
    }

    @Test
    fun sslHandshakeException_detected() {
        assertTrue(isTlsMismatch(javax.net.ssl.SSLHandshakeException("handshake failed")))
    }

    @Test
    fun genericSslException_detected() {
        assertTrue(isTlsMismatch(java.io.IOException(javax.net.ssl.SSLException("Connection reset by peer"))))
    }

    @Test
    fun http403_notFallback() {
        val e = java.io.IOException("Invalid response code: 403")
        assertFalse(isTlsMismatch(e))
    }

    @Test
    fun socketTimeout_notFallback() {
        val e = java.net.SocketTimeoutException("timeout")
        assertFalse(isTlsMismatch(e))
    }

    @Test
    fun unknownHost_notFallback() {
        val e = java.io.IOException(java.net.UnknownHostException("Unable to resolve host"))
        assertFalse(isTlsMismatch(e))
    }

    @Test
    fun noMessage_noThrow() {
        assertFalse(isTlsMismatch(java.io.IOException()))
    }
}
