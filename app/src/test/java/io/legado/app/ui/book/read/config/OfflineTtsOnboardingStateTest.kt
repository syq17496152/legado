package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 引导判定内核（AD-04）：老用户不打扰、跳过有限次、半就绪不放行。 */
class OfflineTtsOnboardingStateTest {

    @Test
    fun `已引导用户直接进首页`() {
        assertEquals(
            OfflineTtsOnboardingState.Decision.ShowHome,
            OfflineTtsOnboardingState.decide(onboarded = true, engineReady = false, skipCount = 0)
        )
    }

    @Test
    fun `引擎已就绪的老用户即使没写过标记也不弹`() {
        assertEquals(
            OfflineTtsOnboardingState.Decision.ShowHome,
            OfflineTtsOnboardingState.decide(onboarded = false, engineReady = true, skipCount = 0)
        )
    }

    @Test
    fun `未引导且未就绪时进入引导`() {
        assertEquals(
            OfflineTtsOnboardingState.Decision.EnterOnboarding,
            OfflineTtsOnboardingState.decide(onboarded = false, engineReady = false, skipCount = 0)
        )
    }

    @Test
    fun `跳过达到上限后静默`() {
        assertEquals(
            OfflineTtsOnboardingState.Decision.Skipped,
            OfflineTtsOnboardingState.decide(
                onboarded = false,
                engineReady = false,
                skipCount = OfflineTtsOnboardingState.MAX_SKIP
            )
        )
    }

    @Test
    fun `残标模型不能进入第二步`() {
        assertFalse(OfflineTtsOnboardingState.canEnterStep2(modelReady = false, voiceCount = 3))
        assertFalse(OfflineTtsOnboardingState.canEnterStep2(modelReady = true, voiceCount = 0))
        assertTrue(OfflineTtsOnboardingState.canEnterStep2(modelReady = true, voiceCount = 1))
    }

    @Test
    fun `跳过计数递增并在上限时标记静默`() {
        val first = OfflineTtsOnboardingState.skipResult(0)
        assertEquals(1, first.newCount)
        assertFalse(first.silentFromNow)

        val last = OfflineTtsOnboardingState.skipResult(OfflineTtsOnboardingState.MAX_SKIP - 1)
        assertEquals(OfflineTtsOnboardingState.MAX_SKIP, last.newCount)
        assertTrue(last.silentFromNow)
    }

    @Test
    fun `负数跳过计数按零处理`() {
        assertEquals(1, OfflineTtsOnboardingState.skipResult(-5).newCount)
    }

    @Test
    fun `完成引导写入标记并清零跳过计数`() {
        val mark = OfflineTtsOnboardingState.onCompleted()
        assertTrue(mark.onboarded)
        assertEquals(0, mark.skipCount)
    }
}
