package io.legado.app.help.readaloud.speech

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 批 7（D 档单源化收口）：`SpeechVoiceCatalogParser.flattenSpeakers` 曾被两处**逐字手写复刻**
     * （`SpeechVoiceGroupRepository` / `SpeechRouteSanitizer` 的 `parseSpeakerGroups(..).flatMap { it.items }`）
     * ⇒ 零引用分诊判 D（调用点漏用封装，删封装会把「3 处副本」变成「2 处副本 + 无主运算」）。
     * 本断言锁定封装语义（含无效项剔除与分组归属透传），文本探针锁定手写复刻不得回潮。
     */
    @Test
    fun flattenSpeakers_flattensValidItemsAndKeepsGroupInfo() {
        val json = """[{"groupId":"g1","groupName":"分组一","items":[
            {"speakerName":"甲","toneID":"t1"},
            {"speakerName":"乙","toneId":"t2"},
            {"speakerName":"","toneID":"t3"}
        ]}]"""
        val speakers = SpeechVoiceCatalogParser.flattenSpeakers(json)
        assertEquals("须剔除 speakerName 为空的无效项", 2, speakers.size)
        assertEquals(listOf("甲", "乙"), speakers.map { it.speakerName })
        assertEquals(listOf("t1", "t2"), speakers.map { it.toneID })
        assertEquals("分组归属须透传", "g1", speakers.first().groupId)
        assertEquals(
            "空/畸形 JSON 不得抛异常",
            emptyList<SpeechSpeaker>(),
            SpeechVoiceCatalogParser.flattenSpeakers("{"),
        )
    }

    @Test
    fun handWrittenSpeakerFlatteningStaysRetiredAtBothCallSites() {
        val repository = SourceFileProbe.sourceText("help/readaloud/speech/SpeechVoiceGroupRepository.kt")
        assertFalse("调用点手写复刻不得回潮", repository.contains(".flatMap { it.items }"))
        val sanitizer = SourceFileProbe.sourceText("help/readaloud/speech/SpeechRouteSanitizer.kt")
        assertFalse("调用点手写复刻不得回潮", sanitizer.contains(".flatMap { it.items }"))
        assertTrue("须改走单源封装", sanitizer.contains("flattenSpeakers(httpTTS.speakersJson)"))
        assertTrue("须改走单源封装", repository.contains("flattenSpeakers(httpTts.speakersJson)"))
    }
}
