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
}
