package io.legado.app.help.gsyVideo

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HTTP 补头重试策略（design §1.14 #7 / tasks §13.9）
 */
class PlaybackHttpRetryPolicyTest {

    @Test
    fun `确定性拒绝码在预算内补头重试`() {
        for (code in intArrayOf(403, 410, 451)) {
            assertEquals(
                "code=$code",
                HttpRetryAction.REFRESH_HEADERS,
                PlaybackHttpRetryPolicy.decide(code, retryCount = 0, maxRetry = 2)
            )
        }
    }

    @Test
    fun `其它状态码不补头`() {
        for (code in intArrayOf(400, 401, 404, 500, 502, 503, 416)) {
            assertEquals(
                "code=$code",
                HttpRetryAction.NONE,
                PlaybackHttpRetryPolicy.decide(code, retryCount = 0, maxRetry = 2)
            )
        }
    }

    @Test
    fun `重试预算耗尽后不再补头`() {
        assertEquals(
            HttpRetryAction.NONE,
            PlaybackHttpRetryPolicy.decide(403, retryCount = 2, maxRetry = 2)
        )
        assertEquals(
            HttpRetryAction.NONE,
            PlaybackHttpRetryPolicy.decide(403, retryCount = 3, maxRetry = 2)
        )
    }

    @Test
    fun `预算边界内仍可补头`() {
        assertEquals(
            HttpRetryAction.REFRESH_HEADERS,
            PlaybackHttpRetryPolicy.decide(451, retryCount = 1, maxRetry = 2)
        )
    }
}
