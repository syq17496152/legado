package io.legado.app.help.gsyVideo

/**
 * HTTP 失败补头重试策略（纯函数；design §1.14 #7 / tasks §13.9）
 *
 * 只对「确定性拒绝」的响应码做一次补 Cookie 头重试：
 * 403（禁止）/ 410（已删除）/ 451（因法律原因不可用）——这三类常由 Cookie/Referer 失效触发，
 * 补实时 Cookie 后重试有效；404/500 等补头无意义，直接走降级链。
 */
internal enum class HttpRetryAction {
    /** 补实时 Cookie/Referer 头后重试（并 seek 回原进度） */
    REFRESH_HEADERS,

    /** 不做补头重试，交给降级链 */
    NONE
}

internal object PlaybackHttpRetryPolicy {

    private val REFRESH_CODES = setOf(403, 410, 451)

    fun decide(responseCode: Int, retryCount: Int, maxRetry: Int): HttpRetryAction =
        if (responseCode in REFRESH_CODES && retryCount < maxRetry) {
            HttpRetryAction.REFRESH_HEADERS
        } else {
            HttpRetryAction.NONE
        }
}
