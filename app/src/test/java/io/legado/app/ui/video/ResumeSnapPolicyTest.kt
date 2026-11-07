package io.legado.app.ui.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IF-05 起播回卷判定（对应 `VideoFragment` onPrepared 后 400ms 的兜底检查）。
 *
 * 真机铁证（2026-11-07 21:41）：订阅源续播把 `seekOnStart` 打到片尾，
 * `onPrepared` 时 `currentPosition` 仍为 0 ⇒ 只能在延迟一拍后按「位置≈片尾」判定回卷。
 */
class ResumeSnapPolicyTest {

    @Test
    fun `起播位置落在片尾阈值内应回卷`() {
        assertTrue(shouldSnapToStart(positionMs = 2_724_674, durationMs = 2_724_600))
        assertTrue(shouldSnapToStart(positionMs = 2_722_601, durationMs = 2_724_600))
    }

    @Test
    fun `正常续播位置不回卷`() {
        assertFalse(shouldSnapToStart(positionMs = 600_000, durationMs = 2_724_600))
        assertFalse(shouldSnapToStart(positionMs = 2_722_599, durationMs = 2_724_600))
    }

    @Test
    fun `短视频与时长未知不判定`() {
        // 3s 样片：时长 ≤ 2×阈值，避免起播即判播完导致无限回卷
        assertFalse(shouldSnapToStart(positionMs = 3_000, durationMs = 3_000))
        assertFalse(shouldSnapToStart(positionMs = 2_724_674, durationMs = 0))
    }
}
