package io.legado.app.help.readaloud

import io.legado.app.help.readaloud.speech.SpeechRoute
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.6 未安装引擎闸门：系统路由 + 目标包缺失 ⇒ 必须回退并给出可读原因。
 */
class TtsEngineGuardTest {

    @Test
    fun `系统路由指向已卸载包时必须回退`() {
        assertTrue(
            TtsEngineGuard.needsFallback(
                SpeechRoute.ENGINE_SYSTEM, "com.example.tts.gone", packageInstalled = false
            )
        )
        assertFalse(
            TtsEngineGuard.needsFallback(
                SpeechRoute.ENGINE_SYSTEM, "com.example.tts.alive", packageInstalled = true
            )
        )
    }

    @Test
    fun `默认与 HTTP 路由不探测包存在性`() {
        assertFalse(TtsEngineGuard.needsFallback(SpeechRoute.ENGINE_DEFAULT, "", false))
        assertFalse(TtsEngineGuard.needsFallback(SpeechRoute.ENGINE_HTTP, "123", false))
        assertFalse(TtsEngineGuard.needsFallback(SpeechRoute.ENGINE_SYSTEM, "", false))
    }

    @Test
    fun `回退提示含引擎名且空名有兜底`() {
        val message = TtsEngineGuard.fallbackMessage("某引擎")
        assertTrue(message.contains("某引擎"))
        assertTrue(message.contains("未安装"))
        assertTrue(TtsEngineGuard.fallbackMessage("").contains("目标引擎"))
    }
}
