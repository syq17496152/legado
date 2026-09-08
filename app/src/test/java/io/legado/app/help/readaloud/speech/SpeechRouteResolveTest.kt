package io.legado.app.help.readaloud.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * resolveSpeechRoute 四态解析单测（optimize-tts-engine tasks 2.1）
 */
class SpeechRouteResolveTest {

    @Test
    fun blankReturnsDefault() {
        assertEquals(SpeechRoute.ENGINE_DEFAULT, SpeechRoute.resolveSpeechRoute("").engineType)
        assertEquals(SpeechRoute.ENGINE_DEFAULT, SpeechRoute.resolveSpeechRoute(null).engineType)
    }

    @Test
    fun numericMapsToHttp() {
        val route = SpeechRoute.resolveSpeechRoute("123456789")
        assertEquals(SpeechRoute.ENGINE_HTTP, route.engineType)
        assertEquals("123456789", route.engineValue)
    }

    @Test
    fun legacySelectItemTakesInnerValue() {
        val raw = """{"title":"讯飞","value":"com.iflytek.speech"}"""
        val route = SpeechRoute.resolveSpeechRoute(raw)
        assertEquals(SpeechRoute.ENGINE_SYSTEM, route.engineType)
        assertEquals("com.iflytek.speech", route.engineValue)
    }

    @Test
    fun newSpeechRouteJsonDirectRead() {
        val raw = """{"engineType":"http","engineValue":"42","source":"manual"}"""
        val route = SpeechRoute.resolveSpeechRoute(raw)
        assertEquals(SpeechRoute.ENGINE_HTTP, route.engineType)
        assertEquals("42", route.engineValue)
    }

    @Test
    fun doubleNestedSystemUnwrapsInnerValue() {
        val inner = java.net.URLEncoder.encode(
            """{"title":"讯飞","value":"com.iflytek.speech"}""", "UTF-8"
        )
        // 双嵌套：engineValue 本身是 SelectItem JSON（未编码直嵌）
        val raw = """{"engineType":"system","engineValue":"{\"title\":\"讯飞\",\"value\":\"com.iflytek.speech\"}"}"""
        val route = SpeechRoute.resolveSpeechRoute(raw)
        assertEquals(SpeechRoute.ENGINE_SYSTEM, route.engineType)
        assertEquals("com.iflytek.speech", route.engineValue)
    }

    @Test
    fun barePackageNameMapsToSystem() {
        val route = SpeechRoute.resolveSpeechRoute("com.xiaomi.mibrain.speech")
        assertEquals(SpeechRoute.ENGINE_SYSTEM, route.engineType)
        assertEquals("com.xiaomi.mibrain.speech", route.engineValue)
    }

    @Test
    fun brokenJsonFallsBackToDefault() {
        assertEquals(SpeechRoute.ENGINE_DEFAULT, SpeechRoute.resolveSpeechRoute("{broken").engineType)
    }

    @Test
    fun unknownEngineTypeFallsBackToDefault() {
        val raw = """{"engineType":"quantum","engineValue":"x"}"""
        assertEquals(SpeechRoute.ENGINE_DEFAULT, SpeechRoute.resolveSpeechRoute(raw).engineType)
    }
}
