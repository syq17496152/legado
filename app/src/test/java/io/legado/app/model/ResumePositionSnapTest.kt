package io.legado.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * IF-05：单 URL 播完后的进度落库口径。
 *
 * 真实回归：短样片播完后 `video_pos_<url>` 被写成片尾位置，二次进入直接 seek 到片尾 →
 * 起播 1.3s 内 ENDED（黑屏）。修复：距片尾 <2s 一律按 0 落库。
 */
class ResumePositionSnapTest {

    @Test
    fun `片尾位置归零`() {
        assertEquals(0, resumePositionForSave(positionMs = 10_026, durationMs = 10_026L))
    }

    @Test
    fun `距片尾小于阈值归零`() {
        // 10s 片，位置 9.0s（距片尾 1.0s < 2s）
        assertEquals(0, resumePositionForSave(positionMs = 9_000, durationMs = 10_026L))
    }

    @Test
    fun `距片尾大于阈值保留原位置`() {
        assertEquals(5_000, resumePositionForSave(positionMs = 5_000, durationMs = 10_026L))
    }

    @Test
    fun `阈值边界按已看完处理`() {
        assertEquals(0, resumePositionForSave(positionMs = 8_026, durationMs = 10_026L))
        assertEquals(8_025, resumePositionForSave(positionMs = 8_025, durationMs = 10_026L))
    }

    @Test
    fun `时长未知时保留原位置`() {
        assertEquals(5_000, resumePositionForSave(positionMs = 5_000, durationMs = 0L))
    }

    /**
     * 读取侧兜底：设备上已存在的旧片尾记录（保存侧修复前写入）也会被归零。
     *
     * 真机铁证（2026-11-07）：收藏直入 `READY posMs=2724599 durMs=2724600` → 16ms 后 ENDED，
     * 且封面层（clickable）覆盖导致上滑手势层收不到事件 ⇒ 黑屏且无法切集。
     */
    @Test
    fun `恢复时旧片尾记录归零`() {
        assertEquals(0, resumePositionForRestore(positionMs = 2_724_599, durationMs = 2_724_600L))
        assertEquals(0, resumePositionForRestore(positionMs = 2_722_600, durationMs = 2_724_600L))
    }

    @Test
    fun `恢复时正常进度与未知时长保留`() {
        assertEquals(600_000, resumePositionForRestore(positionMs = 600_000, durationMs = 2_724_600L))
        assertEquals(600_000, resumePositionForRestore(positionMs = 600_000, durationMs = 0L))
        assertEquals(0, resumePositionForRestore(positionMs = 0, durationMs = 2_724_600L))
    }
}
