package io.legado.app.help.config

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 1.2 端侧小模型常量与档位目录：默认值、合法性收敛、随包 catalog 完整性。
 */
class LocalLlmPrefsTest {

    @Test
    fun `策略只接受三值 未知一律回落本地`() {
        assertEquals(LocalLlmPrefs.POLICY_LOCAL, LocalLlmPrefs.policyOf(null))
        assertEquals(LocalLlmPrefs.POLICY_LOCAL, LocalLlmPrefs.policyOf(""))
        assertEquals(LocalLlmPrefs.POLICY_LOCAL, LocalLlmPrefs.policyOf("unknown"))
        assertEquals(LocalLlmPrefs.POLICY_LOCAL, LocalLlmPrefs.policyOf("local"))
        assertEquals(LocalLlmPrefs.POLICY_ONLINE, LocalLlmPrefs.policyOf("ONLINE"))
        assertEquals(LocalLlmPrefs.POLICY_OFF, LocalLlmPrefs.policyOf(" off "))
    }

    @Test
    fun `启用必须有档位 防止假就绪`() {
        assertFalse(LocalLlmPrefs.enabledOrDefault(true, null))
        assertFalse(LocalLlmPrefs.enabledOrDefault(true, ""))
        assertFalse(LocalLlmPrefs.enabledOrDefault(false, "qwen3-0.6b-q4"))
        assertTrue(LocalLlmPrefs.enabledOrDefault(true, "qwen3-0.6b-q4"))
    }

    @Test
    fun `自检速度文案`() {
        assertEquals("未测", LocalLlmPrefs.speedText(0f))
        assertEquals("未测", LocalLlmPrefs.speedText(-1f))
        assertEquals("12.5 tok/s", LocalLlmPrefs.speedText(12.5f))
    }

    /**
     * 随包档位目录（assets/llm/llm_models.json）：字段齐全、id 唯一、恰好一个推荐档，
     * 且 distribution 合法 —— 2.x 的导入/下载都按这份目录执行。
     */
    @Test
    fun `随包 LLM 档位目录完整`() {
        val file = File("src/main/assets/llm/llm_models.json")
        assertTrue("缺少随包 LLM 档位目录", file.isFile)
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val profiles = root.getAsJsonArray("profiles")
        assertTrue("至少 4 个候选档位（POC 四选一）", profiles.size() >= 4)

        val ids = mutableSetOf<String>()
        var recommended = 0
        profiles.forEach { element ->
            val profile = element.asJsonObject
            val id = profile.get("id").asString
            assertTrue("id 不得为空", id.isNotBlank())
            assertTrue("id 必须唯一：$id", ids.add(id))
            assertTrue("title 不得为空", profile.get("title").asString.isNotBlank())
            assertTrue("sizeBytes 必须为正", profile.get("sizeBytes").asLong > 0)
            assertTrue("contextTokens 必须为正", profile.get("contextTokens").asInt > 0)
            assertTrue("chatTemplate 不得为空", profile.get("chatTemplate").asString.isNotBlank())
            assertTrue("stopTokens 至少一个", profile.getAsJsonArray("stopTokens").size() > 0)
            val distribution = profile.get("distribution").asString
            assertTrue(
                "distribution 只允许 download/import：$distribution",
                distribution == "download" || distribution == "import"
            )
            if (profile.get("recommended")?.asBoolean == true) recommended++
        }
        assertEquals("推荐档必须唯一", 1, recommended)
    }
}
