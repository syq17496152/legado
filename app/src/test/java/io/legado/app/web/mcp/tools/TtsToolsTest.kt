package io.legado.app.web.mcp.tools

import io.legado.app.web.TokenManager
import io.legado.app.web.mcp.MCP_DOMAIN_TTS
import io.legado.app.web.mcp.McpTool
import io.legado.app.web.mcp.McpToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ⑦ TTS 听书域声明单测（二期 · tasks 2.13 / 2.28）。
 *
 * 断言：元数据（domain / level / readOnlyHint / 危险标记）、schema 必填、AD-10 结构红线，
 * 以及**已注册进目录**（McpToolCatalog）。
 */
class TtsToolsTest {

    private val tools: List<McpTool> = TtsTools.tools

    @Test
    fun ttsEnginesGet_isReadonlyWithoutParams() {
        val tool = tools.single { it.name == "tts_engines_get" }
        assertEquals(MCP_DOMAIN_TTS, tool.domain)
        assertEquals(TokenManager.Level.READONLY, tool.level)
        assertEquals("只读工具须标 readOnlyHint", true, tool.readOnlyHint)
        assertFalse("引擎清单不是危险操作", tool.dangerous)
        assertEquals(0, tool.inputSchema.getAsJsonArray("required").size())
    }

    @Test
    fun metaFields_areCorrectForWriteTools() {
        listOf("tts_config_save", "audio_control", "audiobook_control").forEach { name ->
            val tool = tools.single { it.name == name }
            assertEquals("写工具须为 MANAGE 级：$name", TokenManager.Level.MANAGE, tool.level)
            assertFalse("写工具 readOnlyHint 须为 false：$name", tool.readOnlyHint)
        }
    }

    @Test
    fun declarationsAreMetadataConsistent_andRegisteredInCatalog() {
        DomainDeclarationAssert.assertMetadataConsistent(
            tools, MCP_DOMAIN_TTS,
            "tts_", "http_tts_", "audio_", "audiobook_", "speaker_group", "read_aloud_bgm_",
            "builtin_tts_", "builtin_voice_", "builtin_assign_"
        )
        tools.forEach { tool ->
            assertNotNull("域文件声明的工具须被目录聚合：${tool.name}", McpToolCatalog.find(tool.name))
        }
        assertEquals("TTS 域须 20 个工具（17 + 内置离线 3）", 20, tools.size)
    }

    @Test
    fun builtinTools_haveExpectedLevels() {
        val status = tools.single { it.name == "builtin_tts_status" }
        assertEquals(TokenManager.Level.READONLY, status.level)
        assertEquals(true, status.readOnlyHint)
        val list = tools.single { it.name == "builtin_voice_list" }
        assertEquals(TokenManager.Level.READONLY, list.level)
        val assign = tools.single { it.name == "builtin_assign_voices" }
        assertEquals(TokenManager.Level.MANAGE, assign.level)
        assertEquals(3, assign.inputSchema.getAsJsonArray("required").size())
    }

    @Test
    fun invokeGoesThroughKernel_notDaoNorController() {
        DomainDeclarationAssert.assertKernelOnly("tools/TtsTools.kt")
    }
}