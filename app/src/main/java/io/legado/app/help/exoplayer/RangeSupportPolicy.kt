package io.legado.app.help.exoplayer

/**
 * Range 支持判定（AD-45）。
 *
 * 背景：嗅探阶段原本只认 `Accept-Ranges: bytes` 响应头，但大量 CDN/对象存储
 * 在正确响应 `206 Partial Content`（或带 `Content-Range`）时并不回该头。
 * 结果 `supportsRange=false` ⇒ 直链 MP4/FLV 的分块预取被判定「不适用」，整集预取形同关闭。
 *
 * 判定优先级：显式 `Accept-Ranges: bytes` > 206 状态码 > 存在 `Content-Range` > 不支持。
 * 纯函数，便于 JVM 单测覆盖。
 */
internal object RangeSupportPolicy {

    fun resolve(httpCode: Int, acceptRanges: String?, contentRange: String?): Boolean {
        if (acceptRanges?.trim()?.equals("bytes", ignoreCase = true) == true) return true
        if (httpCode == 206) return true
        return !contentRange.isNullOrBlank()
    }
}
