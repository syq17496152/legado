package io.legado.app.help.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 档位目录的「可下载 vs 必须导入」判据。
 *
 * 这条判据直接决定用户点的是「下载」还是「导入」：
 * 只要 `download` 缺 sha256 或下载地址，就必须按「导入」处理 ——
 * 否则用户会下载到无法校验的模型，装上后才发现是坏文件。
 */
class LlmModelCatalogTest {

    private fun profile(
        id: String,
        distribution: String,
        sourceUrl: String = "",
        sha256: String = "",
        recommended: Boolean = false
    ) = LlmModelCatalog.Profile(
        id = id,
        title = id,
        distribution = distribution,
        sourceUrl = sourceUrl,
        sha256 = sha256,
        recommended = recommended,
        sizeBytes = 100
    )

    @Test
    fun `download 需要地址与 sha256 同时具备`() {
        val ready = profile("ok", LlmModelCatalog.DISTRIBUTION_DOWNLOAD, "https://x/y.gguf", "a".repeat(64))
        assertTrue(ready.downloadable)

        val noSha = profile("no-sha", LlmModelCatalog.DISTRIBUTION_DOWNLOAD, "https://x/y.gguf", "")
        assertFalse("缺 sha256 的文件装上也校验不了", noSha.downloadable)

        val noUrl = profile("no-url", LlmModelCatalog.DISTRIBUTION_DOWNLOAD, "", "a".repeat(64))
        assertFalse(noUrl.downloadable)

        val importOnly = profile("import", LlmModelCatalog.DISTRIBUTION_IMPORT, "https://x/y.gguf", "a".repeat(64))
        assertFalse("import 档一律由用户自备", importOnly.downloadable)
    }

    @Test
    fun `parse 非法 JSON 返回空而不抛`() {
        assertTrue(LlmModelCatalog.parse(null).isEmpty())
        assertTrue(LlmModelCatalog.parse("").isEmpty())
        assertTrue(LlmModelCatalog.parse("not json").isEmpty())
        assertTrue(LlmModelCatalog.parse("""{"profiles": 1}""").isEmpty())
    }

    @Test
    fun `recommendedDownloadable 只取推荐且可下载的档位`() {
        val list = listOf(
            profile("a", LlmModelCatalog.DISTRIBUTION_IMPORT, "https://x/a", "a".repeat(64)),
            profile("b", LlmModelCatalog.DISTRIBUTION_DOWNLOAD, "https://x/b", ""),
            profile("c", LlmModelCatalog.DISTRIBUTION_DOWNLOAD, "https://x/c", "c".repeat(64), recommended = true)
        )
        assertEquals("c", LlmModelCatalog.recommendedDownloadable(list)?.id)
        assertNull(LlmModelCatalog.recommendedDownloadable(list.take(2)))
    }

    /** 随包目录必须真的锁定了推荐档（否则设置页永远出不来「下载」按钮） */
    @Test
    fun `随包目录的推荐档已锁定下载地址与 sha256`() {
        val file = File("src/main/assets/llm/llm_models.json")
        assertTrue("缺少随包 LLM 档位目录", file.isFile)
        val profiles = LlmModelCatalog.parse(file.readText())

        assertEquals(4, profiles.size)
        val recommended = profiles.first { it.recommended }
        assertEquals("qwen3-0.6b-q4", recommended.id)
        assertTrue("推荐档必须可下载", recommended.downloadable)
        assertEquals(64, recommended.sha256.length)
        assertEquals("Q4_K_M", recommended.quant)
        assertEquals(396705472L, recommended.sizeBytes)
        assertTrue(recommended.sourceUrl.startsWith("https://"))

        assertEquals("推荐档必须唯一", 1, profiles.count { it.recommended })
    }
}
