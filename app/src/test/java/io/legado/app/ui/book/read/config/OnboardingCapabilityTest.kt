package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 档位能力矩阵（AD-12）：四档分支必须与 builtin_models.json 一致。 */
class OnboardingCapabilityTest {

    @Test
    fun `极速档是单声且没有音色列表`() {
        val cap = OnboardingCapability.of("fast", "vits", 0, 22050, 20L * 1024 * 1024)
        assertFalse(cap.multiSpeaker)
        assertFalse(cap.needsVoicePack)
        assertEquals(OnboardingStep2Mode.SKIPPED_SINGLE, OnboardingCapability.step2Mode(cap))
        assertTrue(OnboardingCapability.cardAvailable(cap, CastSceneState.CARD_MONO))
        assertFalse(OnboardingCapability.cardAvailable(cap, CastSceneState.CARD_MULTI))
        assertTrue(OnboardingCapability.disabledReason(cap, CastSceneState.CARD_DUAL)!!.contains("单声档"))
        assertNull(OnboardingCapability.disabledReason(cap, CastSceneState.CARD_MONO))
    }

    @Test
    fun `轻量多音色档有174音色但采样率8k`() {
        val cap = OnboardingCapability.of("multi", "vits", 174, 8000, 32L * 1024 * 1024)
        assertTrue(cap.multiSpeaker)
        assertEquals(174, cap.speakerCount)
        assertEquals(8000, cap.sampleRate)
        assertEquals(OnboardingStep2Mode.INLINE_VOICES, OnboardingCapability.step2Mode(cap))
        assertTrue(OnboardingCapability.cardAvailable(cap, CastSceneState.CARD_MULTI))
    }

    @Test
    fun `高清档为推荐且全流程可用`() {
        val cap = OnboardingCapability.of("hq", "kokoro", 100, 24000, 348L * 1024 * 1024)
        assertTrue(cap.recommended)
        assertTrue(cap.multiSpeaker)
        assertEquals(OnboardingStep2Mode.INLINE_VOICES, OnboardingCapability.step2Mode(cap))
    }

    @Test
    fun `克隆档必须先导入声音包`() {
        val cap = OnboardingCapability.of("clone", "zipvoice", 0, 24000, 190L * 1024 * 1024)
        assertTrue(cap.needsVoicePack)
        assertEquals(
            OnboardingStep2Mode.IMPORT_VOICE_PACK,
            OnboardingCapability.step2Mode(cap)
        )
    }

    @Test
    fun `未知档回落为单声形态`() {
        val cap = OnboardingCapability.of("unknown", "vits", 0, 22050, 0)
        assertFalse(cap.multiSpeaker)
        assertEquals("unknown", cap.label)
        assertEquals(OnboardingStep2Mode.SKIPPED_SINGLE, OnboardingCapability.step2Mode(cap))
    }

    @Test
    fun `低端机默认极速档 否则高清档`() {
        assertEquals(OnboardingCapability.PROFILE_FAST, OnboardingCapability.defaultProfileId(true))
        assertEquals(OnboardingCapability.PROFILE_HQ, OnboardingCapability.defaultProfileId(false))
    }

    @Test
    fun `展示顺序为推荐置顶且其余按体积升序`() {
        val caps = listOf(
            OnboardingCapability.of("fast", "vits", 0, 22050, 21L * 1024 * 1024),
            OnboardingCapability.of("hq", "kokoro", 100, 24000, 348L * 1024 * 1024),
            OnboardingCapability.of("multi", "vits", 174, 8000, 32L * 1024 * 1024)
        )
        val ordered = OnboardingCapability.orderForDisplay(caps).map { it.profileId }
        assertEquals("hq", ordered.first())
        assertEquals(listOf("hq", "fast", "multi"), ordered)
    }
}
