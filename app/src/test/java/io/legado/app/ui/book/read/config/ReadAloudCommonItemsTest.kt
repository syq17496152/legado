package io.legado.app.ui.book.read.config

import io.legado.app.constant.PreferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 朗读设置「常用」组（AD-06 / R8）：顺序固定、不新增偏好 key。 */
class ReadAloudCommonItemsTest {

    @Test
    fun `常用组顺序固定为四项`() {
        assertEquals(
            listOf(
                ReadAloudCommonItems.KEY_RATE,
                ReadAloudCommonItems.KEY_VOICE,
                ReadAloudCommonItems.KEY_CASTING,
                ReadAloudCommonItems.KEY_TIMER
            ),
            ReadAloudCommonItems.orderedKeys
        )
    }

    @Test
    fun `语速复用既有偏好 key 且归属朗读组`() {
        assertEquals(PreferKey.ttsSpeechRate, ReadAloudCommonItems.KEY_RATE)
        assertTrue(
            ReadAloudCommonItems.KEY_RATE in ReadAloudConfigGroup.Reading.preferenceKeys
        )
    }

    @Test
    fun `动作 key 不是既有偏好 key 避免重复归属`() {
        val allSettings = ReadAloudConfigGroup.allPreferenceKeys
        ReadAloudCommonItems.actionKeys.forEach {
            assertFalse("动作 key 不应命中设置 key：$it", it in allSettings)
        }
    }

    @Test
    fun `isCommonKey 只认四项`() {
        assertTrue(ReadAloudCommonItems.isCommonKey(ReadAloudCommonItems.KEY_VOICE))
        assertFalse(ReadAloudCommonItems.isCommonKey("readAloudByPage"))
        assertFalse(ReadAloudCommonItems.isCommonKey(""))
    }
}
