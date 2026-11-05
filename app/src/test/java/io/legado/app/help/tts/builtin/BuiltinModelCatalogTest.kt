package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模型档案解析与校验（T1.1 DoD）：合法/缺 profiles/未知 engine/空 id 四例。
 */
class BuiltinModelCatalogTest {

    @Test
    fun `合法档案解析出两个档位`() {
        val json = """
            {"version":2,"profiles":[
              {"id":"fast","title":"极速","engine":"vits","sampleRate":22050,
               "files":["tokens.txt"],"sources":["https://example.invalid/a"]},
              {"id":"clone","title":"克隆","engine":"zipvoice","sampleRate":24000,
               "files":["encoder.int8.onnx"],"numSteps":4}
            ]}
        """.trimIndent()
        val profiles = BuiltinModelCatalog.parse(json)
        assertEquals(2, profiles.size)
        assertEquals(BuiltinModelProfile.ENGINE_ZIPVOICE, profiles[1].engine)
        assertTrue(profiles[1].requiresReference)
        assertTrue(!profiles[0].requiresReference)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `空 profiles 显式失败`() {
        BuiltinModelCatalog.parse("{\"version\":2,\"profiles\":[]}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `未知引擎显式失败`() {
        BuiltinModelCatalog.parse(
            """{"version":2,"profiles":[{"id":"x","title":"x","engine":"unknown",
               "sampleRate":24000,"files":["a.onnx"]}]}"""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `缺少 id 显式失败`() {
        BuiltinModelCatalog.parse(
            """{"version":2,"profiles":[{"title":"x","engine":"vits",
               "sampleRate":22050,"files":["a.onnx"]}]}"""
        )
    }

    @Test
    fun `随包档案（assets）可解析且含 clone 主档`() {
        val file = java.io.File("src/main/assets/builtin_models.json")
        assertTrue("缺少随包模型档案", file.isFile)
        val profiles = BuiltinModelCatalog.parse(file.readText())
        assertTrue(profiles.any { it.id == "clone" && it.engine == "zipvoice" })
        assertTrue(profiles.any { it.id == "fast" && it.engine == "vits" })
    }

    /** 2026-11-05 POC 定档：multi(aishell3 8kHz/174) 与 hq(Kokoro 24kHz/100 中文) 必须可解析 */
    @Test
    fun `随包档案含多音色两档且 Kokoro 引擎被支持`() {
        val profiles = BuiltinModelCatalog.parse(
            java.io.File("src/main/assets/builtin_models.json").readText())
        val multi = profiles.first { it.id == "multi" }
        assertEquals(BuiltinModelProfile.ENGINE_VITS, multi.engine)
        assertEquals(8000, multi.sampleRate)
        assertEquals(174, multi.speakerCount)
        assertTrue(multi.multiSpeaker)
        assertEquals(1, multi.speakerSidStart)

        val hq = profiles.first { it.id == "hq" }
        assertEquals(BuiltinModelProfile.ENGINE_KOKORO, hq.engine)
        assertEquals(24000, hq.sampleRate)
        assertEquals(100, hq.speakerCount)
        // 中文音色 sid 从 3 起（0/1/2 为其它语种），区间端点必须是 3..102
        assertEquals(3, hq.speakerSidStart)
        assertEquals("女", hq.genderOfSid(3))
        assertEquals("女", hq.genderOfSid(57))
        assertEquals("男", hq.genderOfSid(58))
        assertEquals("男", hq.genderOfSid(102))
        assertEquals("", hq.genderOfSid(2))
        assertEquals("", hq.genderOfSid(103))
    }

    /** 性别区间写反必须显式失败（否则配声会拿到空性别还以为"没登记"） */
    @Test(expected = IllegalArgumentException::class)
    fun `性别区间倒置显式失败`() {
        BuiltinModelCatalog.parse(
            """{"version":2,"profiles":[{"id":"x","title":"x","engine":"kokoro",
               "sampleRate":24000,"files":["a.onnx"],"speakerCount":10,
               "speakerGenderRanges":[{"gender":"女","fromSid":8,"toSid":3}]}]}"""
        )
    }

    /** Kokoro 缺 voices.bin 必须在档案层就被识别为"文件不齐"，不能在引擎层静默降级 */
    @Test
    fun `Kokoro 档必需文件含 voices_bin`() {
        val profiles = BuiltinModelCatalog.parse(
            java.io.File("src/main/assets/builtin_models.json").readText())
        val hq = profiles.first { it.id == "hq" }
        assertTrue("hq 档缺少 voices.bin 声明", "voices.bin" in hq.files)
        assertTrue("hq 档缺少 model.onnx 声明", "model.onnx" in hq.files)
    }
}
