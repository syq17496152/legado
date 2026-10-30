package io.legado.app.help.readaloud.speech

import io.legado.app.constant.PreferKey
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R5：朗读参数重置的「白名单 ⊕ 保护集合」契约（AD-08）。
 *
 * 最高风险是**把资产类键混进白名单**：一次重置就丢引擎/音色/密钥或误关定时，
 * 且用户无法从 UI 恢复 ⇒ 用集合断言把两集合互斥与关键项归属固化。
 */
class TtsTuningDefaultsTest {

    @Test
    fun whitelistAndProtectedAreDisjoint() {
        val overlap = TtsTuningDefaults.resetPreferenceKeys
            .intersect(TtsTuningDefaults.protectedPreferenceKeys)
        assertTrue("白名单与保护集合不得有交集: $overlap", overlap.isEmpty())
    }

    @Test
    fun whitelistCoversNumericTuningItems() {
        val keys = TtsTuningDefaults.resetPreferenceKeys
        assertTrue(keys.contains(PreferKey.ttsSpeechRate))
        assertTrue(keys.contains(PreferKey.ttsFollowSys))
        assertTrue(keys.contains(PreferKey.ttsParagraphPauseMs))
        assertTrue(keys.contains(PreferKey.ttsEngineParamsJson))
        assertTrue(keys.contains(PreferKey.readAloudTargetVoiceVolume))
        assertTrue(keys.contains(PreferKey.readAloudMaxSpeakerGain))
        assertTrue(keys.contains(PreferKey.readAloudNarratorBaseGain))
        assertTrue(keys.contains(PreferKey.readAloudSpeakerLoudnessStats))
    }

    @Test
    fun protectedKeepsAssetsAndTimerIntent() {
        val keys = TtsTuningDefaults.protectedPreferenceKeys
        assertTrue("引擎选择属资产，不得重置", keys.contains(PreferKey.ttsEngine))
        assertTrue("选角模板属资产，不得重置", keys.contains(PreferKey.ttsCastingActiveId))
        assertTrue("定时关闭属用户意图，不得重置", keys.contains(PreferKey.ttsTimer))
        assertTrue(keys.contains(PreferKey.ttsTimerMode))
        assertTrue(keys.contains(PreferKey.ttsTimerChapters))
    }
}
