package io.legado.app.help.llm

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2.3 LlmModelManager：坏文件绝不进清单、半写目录不参与扫描、重复导入与删除。
 */
class LlmModelManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun makeGguf(folder: File, name: String = "m.gguf"): File {
        val file = File(folder, name)
        val bytes = ByteArray(64)
        bytes[0] = 'G'.code.toByte()
        bytes[1] = 'G'.code.toByte()
        bytes[2] = 'U'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `导入有效 GGUF 后出现在清单`() {
        val root = File(temp.root, "models/llm")
        val manager = LlmModelManager(root)
        val src = makeGguf(temp.root)
        val result = manager.importModel(src, "qwen3-0.6b", displayName = "Qwen3 0.6B")
        assertTrue(result is LlmModelManager.ImportResult.Success)
        val model = (result as LlmModelManager.ImportResult.Success).model
        assertEquals("qwen3-0.6b", model.id)
        assertEquals(64L, model.bytes)
        assertEquals(1, manager.scan().size)
        assertEquals(64L, manager.totalBytes())
        assertTrue(File(model.dir, "model.gguf").isFile)
    }

    @Test
    fun `魔数不对拒绝导入`() {
        val manager = LlmModelManager(File(temp.root, "models/llm"))
        val bad = File(temp.root, "bad.gguf").apply { writeText("not a gguf file") }
        val result = manager.importModel(bad, "bad")
        assertEquals(
            LlmModelManager.ErrorCode.BAD_MAGIC,
            (result as LlmModelManager.ImportResult.Failure).code
        )
        assertTrue("坏文件不能留下 .part 残骸", File(temp.root, "models/llm/bad.part").exists().not())
        assertTrue(manager.scan().isEmpty())
    }

    @Test
    fun `重复 id 拒绝导入`() {
        val manager = LlmModelManager(File(temp.root, "models/llm"))
        val src = makeGguf(temp.root)
        assertTrue(manager.importModel(src, "dup") is LlmModelManager.ImportResult.Success)
        val again = manager.importModel(src, "dup")
        assertEquals(
            LlmModelManager.ErrorCode.ALREADY_EXISTS,
            (again as LlmModelManager.ImportResult.Failure).code
        )
        assertEquals(1, manager.scan().size)
    }

    @Test
    fun `sha256 不匹配拒绝导入`() {
        val manager = LlmModelManager(File(temp.root, "models/llm"))
        val src = makeGguf(temp.root)
        val result = manager.importModel(src, "sha", expectedSha256 = "deadbeef")
        assertEquals(
            LlmModelManager.ErrorCode.SHA_MISMATCH,
            (result as LlmModelManager.ImportResult.Failure).code
        )
        assertTrue(manager.scan().isEmpty())
    }

    @Test
    fun `删除后从清单消失`() {
        val manager = LlmModelManager(File(temp.root, "models/llm"))
        val src = makeGguf(temp.root)
        assertTrue(manager.importModel(src, "x") is LlmModelManager.ImportResult.Success)
        assertTrue(manager.remove("x"))
        assertTrue(manager.scan().isEmpty())
        assertFalse(manager.remove("x"))
    }

    @Test
    fun `半写与临时目录不参与扫描`() {
        val root = File(temp.root, "models/llm").apply { mkdirs() }
        val manager = LlmModelManager(root)
        // 目录里有 .gguf 但魔数错误（半写/坏文件）
        File(root, "broken").apply { mkdirs() }.let { dir ->
            File(dir, "model.gguf").writeText("half written")
        }
        // `.part` 目录即使内容有效也不能算已安装
        File(root, "ok.part").apply { mkdirs() }.let { dir ->
            makeGguf(dir)
        }
        assertEquals(0, manager.scan().size)
        assertEquals(0L, manager.totalBytes())
    }
}
