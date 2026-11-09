package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首屏 4 段与更多设置 6 行（AD-02 / AD-11 / R4 / R14 / R17）。 */
class CastSceneHomeSectionsTest {

    @Test
    fun `首屏固定四段且顺序不变`() {
        assertEquals(
            listOf(
                CastSceneState.SECTION_STATUS,
                CastSceneState.SECTION_CARDS,
                CastSceneState.SECTION_PRIMARY,
                CastSceneState.SECTION_FOLD
            ),
            CastSceneState.homeSections()
        )
        assertTrue(CastSceneState.homeSections().size <= 6)
    }

    @Test
    fun `有书且引擎就绪时六行齐全`() {
        val ids = CastSceneState.foldGroupsOf(
            hasBook = true,
            lowEndDevice = false,
            localAiVisible = true,
            engineReady = true
        ).map { it.id }
        assertEquals(TtsSettingsOwnership.allIds, ids.toSet())
        assertEquals(6, ids.size)
    }

    @Test
    fun `无书时不出现角色与分段`() {
        val ids = CastSceneState.foldGroupsOf(
            hasBook = false,
            lowEndDevice = false,
            localAiVisible = true,
            engineReady = true
        ).map { it.id }
        assertEquals(
            setOf(
                TtsSettingsOwnership.ID_VOICE,
                TtsSettingsOwnership.ID_BEHAVIOR,
                TtsSettingsOwnership.ID_AI,
                TtsSettingsOwnership.ID_DIAGNOSTICS
            ),
            ids.toSet()
        )
    }

    @Test
    fun `低端机隐藏本地 AI`() {
        val ids = CastSceneState.foldGroupsOf(
            hasBook = true,
            lowEndDevice = true,
            localAiVisible = false,
            engineReady = true
        ).map { it.id }
        assertTrue(TtsSettingsOwnership.ID_AI !in ids)
    }

    @Test
    fun `引擎未就绪时诊断置顶`() {
        val first = CastSceneState.foldGroupsOf(
            hasBook = true,
            lowEndDevice = false,
            localAiVisible = true,
            engineReady = false
        ).first()
        assertEquals(TtsSettingsOwnership.ID_DIAGNOSTICS, first.id)
    }

    @Test
    fun `状态条五态文案与去向正确`() {
        assertEquals(
            CastSceneState.StatusTarget.BOOKSHELF,
            CastSceneState.statusActionOf(false, false, false, CastSceneState.CARD_MONO, 0, "").target
        )
        assertEquals(
            CastSceneState.StatusTarget.ONBOARDING,
            CastSceneState.statusActionOf(true, false, true, CastSceneState.CARD_MONO, 0, "").target
        )
        assertEquals(
            CastSceneState.StatusTarget.ONBOARDING,
            CastSceneState.statusActionOf(true, false, false, CastSceneState.CARD_MONO, 0, "").target
        )
        assertEquals(
            CastSceneState.StatusTarget.ONE_TAP,
            CastSceneState.statusActionOf(
                true, true, false, CastSceneState.CARD_MULTI, 0, "高清多音色"
            ).target
        )
        val ready = CastSceneState.statusActionOf(
            true, true, false, CastSceneState.CARD_MULTI, 3, "高清多音色", "边听边看"
        )
        assertEquals(CastSceneState.StatusTarget.VOICES, ready.target)
        assertTrue(ready.text.contains("高清多音色"))
        assertTrue(ready.text.contains("边听边看"))
    }
}
