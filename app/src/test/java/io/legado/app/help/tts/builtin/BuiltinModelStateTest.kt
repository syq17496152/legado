package io.legado.app.help.tts.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 模型目录状态机（T1.2）：NONE / 空目录 / 部分文件 / 空文件 / 齐备 / 子目录文件。
 */
class BuiltinModelStateTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val profile = BuiltinModelProfile(
        id = "fast",
        title = "极速档",
        engine = BuiltinModelProfile.ENGINE_VITS,
        sampleRate = 16000,
        files = listOf("model.onnx", "tokens.txt")
    )

    @Test
    fun `目录不存在为NONE`() {
        val dir = File(temp.root, "missing")
        assertEquals(BuiltinEngineState.NONE, BuiltinModelState.evaluate(dir, profile))
    }

    @Test
    fun `空目录为NONE`() {
        val dir = temp.newFolder("empty")
        assertEquals(BuiltinEngineState.NONE, BuiltinModelState.evaluate(dir, profile))
    }

    @Test
    fun `缺必需文件为CORRUPT`() {
        val dir = temp.newFolder("partial")
        File(dir, "model.onnx").writeBytes(ByteArray(16) { 1 })
        assertEquals(BuiltinEngineState.CORRUPT, BuiltinModelState.evaluate(dir, profile))
        assertTrue(BuiltinModelState.missingFiles(dir, profile).contains("tokens.txt"))
    }

    @Test
    fun `空文件视为缺失`() {
        val dir = temp.newFolder("emptyfile")
        File(dir, "model.onnx").writeBytes(ByteArray(0))
        File(dir, "tokens.txt").writeText("a")
        assertEquals(BuiltinEngineState.CORRUPT, BuiltinModelState.evaluate(dir, profile))
    }

    @Test
    fun `文件齐备为READY`() {
        val dir = temp.newFolder("ready")
        File(dir, "model.onnx").writeBytes(ByteArray(64) { 2 })
        File(dir, "tokens.txt").writeText("a\nb\n")
        assertEquals(BuiltinEngineState.READY, BuiltinModelState.evaluate(dir, profile))
    }

    @Test
    fun `文件在子目录也能识别`() {
        val dir = temp.newFolder("nested")
        val sub = File(dir, "model").apply { mkdirs() }
        File(sub, "model.onnx").writeBytes(ByteArray(64) { 3 })
        File(sub, "tokens.txt").writeText("a")
        assertEquals(BuiltinEngineState.READY, BuiltinModelState.evaluate(dir, profile))
    }

    /**
     * 2026-11-05 模拟器铁证：导入被中断，Kokoro model.onnx 只有 5.5MB 残片，
     * 但文件"存在且非空" ⇒ 旧口径判 READY ⇒ native 加载半截模型直接 SIGSEGV。
     * 导入必须打标记，标记存在时无论文件多齐都只能是 NONE。
     */
    @Test
    fun `导入中标记存在时绝不为READY`() {
        val dir = temp.newFolder("importing")
        File(dir, "model.onnx").writeBytes(ByteArray(64) { 4 })
        File(dir, "tokens.txt").writeText("a")
        assertEquals(BuiltinEngineState.READY, BuiltinModelState.evaluate(dir, profile))

        File(dir, BuiltinModelState.IMPORT_MARKER).writeText("")
        assertEquals(BuiltinEngineState.NONE, BuiltinModelState.evaluate(dir, profile))

        File(dir, BuiltinModelState.IMPORT_MARKER).delete()
        assertEquals(BuiltinEngineState.READY, BuiltinModelState.evaluate(dir, profile))
    }

    /** 目录里只有标记文件 ⇒ 视为空目录（NONE），不能当 CORRUPT 去提示"重新导入" */
    @Test
    fun `只有导入标记的目录为NONE`() {
        val dir = temp.newFolder("marker-only")
        File(dir, BuiltinModelState.IMPORT_MARKER).writeText("")
        assertEquals(BuiltinEngineState.NONE, BuiltinModelState.evaluate(dir, profile))
    }
}
