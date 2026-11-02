package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沉浸舞台视觉策略配对用例（immersive-reader-and-tts-ecosystem）：
 * 动效闸门 / 降级数量 / 尺寸分档 / 粒子相位可复现，全部断言具体期望值。
 */
class ImmersiveStagePolicyTest {

    @Test
    fun `播放中前台且非墨水屏才允许跑帧`() {
        assertTrue(ImmersiveStagePolicy.shouldAnimate(true, true, false))
    }

    @Test
    fun `暂停时禁止跑帧`() {
        assertFalse(ImmersiveStagePolicy.shouldAnimate(false, true, false))
    }

    @Test
    fun `退到后台时禁止跑帧`() {
        assertFalse(ImmersiveStagePolicy.shouldAnimate(true, false, false))
    }

    @Test
    fun `电子墨水模式禁止跑帧`() {
        assertFalse(ImmersiveStagePolicy.shouldAnimate(true, true, true))
    }

    @Test
    fun `停帧时粒子数量为零`() {
        assertEquals(0, ImmersiveStagePolicy.particleCount(lowRam = false, animate = false))
    }

    @Test
    fun `低内存设备关闭粒子`() {
        assertEquals(0, ImmersiveStagePolicy.particleCount(lowRam = true, animate = true))
    }

    @Test
    fun `正常设备粒子数量为二十六`() {
        assertEquals(26, ImmersiveStagePolicy.particleCount(lowRam = false, animate = true))
    }

    @Test
    fun `低内存设备纹理环减半`() {
        assertEquals(7, ImmersiveStagePolicy.vinylGrooveCount(lowRam = true))
        assertEquals(14, ImmersiveStagePolicy.vinylGrooveCount(lowRam = false))
    }

    @Test
    fun `竖屏唱片尺寸按屏高分三档`() {
        assertEquals(140f, ImmersiveStagePolicy.discDiameter(true, true).value, 0.01f)
        assertEquals(172f, ImmersiveStagePolicy.discDiameter(false, true).value, 0.01f)
        assertEquals(208f, ImmersiveStagePolicy.discDiameter(false, false).value, 0.01f)
    }

    @Test
    fun `横屏唱片尺寸按紧凑档区分`() {
        assertEquals(136f, ImmersiveStagePolicy.landscapeDiscDiameter(true).value, 0.01f)
        assertEquals(168f, ImmersiveStagePolicy.landscapeDiscDiameter(false).value, 0.01f)
    }

    @Test
    fun `粒子相位同输入恒等且落在零到一之间`() {
        val first = ImmersiveStagePolicy.particlePhase(seed = 20261102, index = 5)
        val second = ImmersiveStagePolicy.particlePhase(seed = 20261102, index = 5)
        assertEquals(first, second, 0.0f)
        assertTrue(first >= 0f && first < 1f)
    }

    @Test
    fun `不同索引相位不同`() {
        val a = ImmersiveStagePolicy.particlePhase(seed = 7, index = 0)
        val b = ImmersiveStagePolicy.particlePhase(seed = 7, index = 1)
        val c = ImmersiveStagePolicy.particlePhase(seed = 7, index = 2)
        assertFalse(a == b && b == c)
    }

    @Test
    fun `不同种子相位不同`() {
        val a = ImmersiveStagePolicy.particlePhase(seed = 11, index = 3)
        val b = ImmersiveStagePolicy.particlePhase(seed = 12, index = 3)
        assertFalse(a == b)
    }
}
