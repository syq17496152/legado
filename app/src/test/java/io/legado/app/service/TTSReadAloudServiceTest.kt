package io.legado.app.service

import io.legado.app.help.readaloud.speech.SpeechRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.6 朗读引擎回退决策（纯函数）：包缺失提前回退、强制默认不重复提示、HTTP 路由不探测。
 */
class TTSReadAloudServiceTest {

    @Test
    fun `引擎包缺失时回退默认并给出可读原因`() {
        val (engine, notice) = TTSReadAloudService.resolveInitEngine(
            engineType = SpeechRoute.ENGINE_SYSTEM,
            engineValue = "com.gone.tts",
            packageInstalled = false,
            forceDefault = false,
            fallbackInit = false,
            label = "某引擎"
        )
        assertEquals("", engine)
        assertTrue(notice.orEmpty().contains("某引擎"))
        assertTrue(notice.orEmpty().contains("未安装"))
    }

    @Test
    fun `引擎包存在时按原值创建且不提示`() {
        val (engine, notice) = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_SYSTEM, "com.alive.tts", true, false, false, "某引擎"
        )
        assertEquals("com.alive.tts", engine)
        assertNull(notice)
    }

    @Test
    fun `显示名缺失时提示改用目标包名`() {
        // speakerName 为空时 label 回落「系统默认」；提示若仍用它会出现"「系统默认」未安装"的矛盾文案
        val (_, notice) = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_SYSTEM, "com.gone.tts", false, false, false, "系统默认"
        )
        assertTrue(notice.orEmpty().contains("com.gone.tts"))
        assertTrue(!notice.orEmpty().contains("「系统默认」"))
    }

    @Test
    fun `强制默认或已回退时不再重复提示`() {
        val forced = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_SYSTEM, "com.gone.tts", false, true, false, "某引擎"
        )
        assertEquals("", forced.first)
        assertNull(forced.second)

        val alreadyFellBack = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_SYSTEM, "com.gone.tts", false, false, true, "某引擎"
        )
        assertEquals("", alreadyFellBack.first)
        assertNull(alreadyFellBack.second)
    }

    @Test
    fun `HTTP 路由不探测包存在性`() {
        val (engine, notice) = TTSReadAloudService.resolveInitEngine(
            SpeechRoute.ENGINE_HTTP, "123", false, false, false, "某引擎"
        )
        assertEquals("123", engine)
        assertNull(notice)
    }
}
