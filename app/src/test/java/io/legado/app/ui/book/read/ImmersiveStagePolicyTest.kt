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
    fun `竖屏唱片尺寸按屏高分三档且已整体上调`() {
        assertEquals(156f, ImmersiveStagePolicy.discDiameter(true, true).value, 0.01f)
        assertEquals(196f, ImmersiveStagePolicy.discDiameter(false, true).value, 0.01f)
        assertEquals(264f, ImmersiveStagePolicy.discDiameter(false, false).value, 0.01f)
        // 用户反馈"封面转动区域偏小"：三档都必须大于旧值 140/172/208
        assertTrue(ImmersiveStagePolicy.discDiameter(true, true).value > 140f)
        assertTrue(ImmersiveStagePolicy.discDiameter(false, true).value > 172f)
        assertTrue(ImmersiveStagePolicy.discDiameter(false, false).value > 208f)
    }

    @Test
    fun `横屏唱片尺寸按紧凑档区分且已整体上调`() {
        assertEquals(176f, ImmersiveStagePolicy.landscapeDiscDiameter(true).value, 0.01f)
        assertEquals(216f, ImmersiveStagePolicy.landscapeDiscDiameter(false).value, 0.01f)
        // 横屏高度受限，但直径仍必须大于旧值 136/168
        assertTrue(ImmersiveStagePolicy.landscapeDiscDiameter(true).value > 136f)
        assertTrue(ImmersiveStagePolicy.landscapeDiscDiameter(false).value > 168f)
    }

    @Test
    fun `封面占直径比例大于旧值放大小尺寸感知区域`() {
        assertEquals(0.62f, ImmersiveStagePolicy.COVER_RATIO, 0.001f)
        assertTrue(ImmersiveStagePolicy.COVER_RATIO > 0.52f)
    }

    @Test
    fun `播放中中央键必须是暂停双杠`() {
        assertEquals(
            ImmersiveStagePolicy.TransportIcon.Pause,
            ImmersiveStagePolicy.transportIcon(playing = true, busy = true)
        )
        assertEquals(
            ImmersiveStagePolicy.TransportIcon.Pause,
            ImmersiveStagePolicy.transportIcon(playing = true, busy = false)
        )
    }

    @Test
    fun `未播放且加载中才显示进度环`() {
        assertEquals(
            ImmersiveStagePolicy.TransportIcon.Busy,
            ImmersiveStagePolicy.transportIcon(playing = false, busy = true)
        )
    }

    @Test
    fun `未播放且空闲显示播放三角`() {
        assertEquals(
            ImmersiveStagePolicy.TransportIcon.Play,
            ImmersiveStagePolicy.transportIcon(playing = false, busy = false)
        )
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
    fun `低配闸门覆盖低内存与旧系统`() {
        assertTrue(ImmersiveStagePolicy.shouldReduceMotion(lowRam = true, sdkInt = 33))
        // API ≤28（2018 年及更早机型）也按低配降级：真机 A7x（API 28）播放期 GPU 合成吃紧
        assertTrue(ImmersiveStagePolicy.shouldReduceMotion(lowRam = false, sdkInt = 28))
        assertTrue(ImmersiveStagePolicy.shouldReduceMotion(lowRam = false, sdkInt = 26))
        assertFalse(ImmersiveStagePolicy.shouldReduceMotion(lowRam = false, sdkInt = 29))
    }

    @Test
    fun `封面缺失占位取书名首字`() {
        assertEquals("无", ImmersiveStagePolicy.coverFallbackLabel("无限恐怖"))
        assertEquals("三", ImmersiveStagePolicy.coverFallbackLabel("  三体 "))
    }

    @Test
    fun `书名为空时占位回落为书`() {
        assertEquals("书", ImmersiveStagePolicy.coverFallbackLabel(""))
        assertEquals("书", ImmersiveStagePolicy.coverFallbackLabel("   "))
    }

    @Test
    fun `不同种子相位不同`() {
        val a = ImmersiveStagePolicy.particlePhase(seed = 11, index = 3)
        val b = ImmersiveStagePolicy.particlePhase(seed = 12, index = 3)
        assertFalse(a == b)
    }

    /** 卡顿修复（2026-11-03）：流体层改为「只改 layer alpha」，绝不能再去动全屏几何（GPU 重采样更卡） */
    @Test
    fun `流体层透明度在安全区间内线性呼吸`() {
        assertEquals(0.90f, ImmersiveStagePolicy.fluidLayerAlpha(-0.10f), 0.001f)
        assertEquals(0.95f, ImmersiveStagePolicy.fluidLayerAlpha(0f), 0.001f)
        assertEquals(1.00f, ImmersiveStagePolicy.fluidLayerAlpha(0.10f), 0.001f)
        // 越界的 shift 必须夹紧，避免 alpha 超出 0..1 造成整层不可见
        assertEquals(0.90f, ImmersiveStagePolicy.fluidLayerAlpha(-5f), 0.001f)
        assertEquals(1.00f, ImmersiveStagePolicy.fluidLayerAlpha(5f), 0.001f)
    }
}
