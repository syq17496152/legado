package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 管理页纯逻辑（D2 UI 收敛）：导入档案推断 + 体积格式化（G-01 配对配置页目录）。
 */
class BuiltinTtsActivityTest {

    @Test
    fun `按文件名推断模型档`() {
        assertEquals("fast", BuiltinTtsUiSupport.guessProfile("vits-piper-zh_CN-xiao_ya-medium-int8"))
        assertEquals("fast", BuiltinTtsUiSupport.guessProfile("FAST"))
        assertEquals("clone", BuiltinTtsUiSupport.guessProfile("sherpa-onnx-zipvoice-distill-int8-zh-en-emilia"))
        assertEquals("clone", BuiltinTtsUiSupport.guessProfile("vocos_24khz.onnx"))
    }

    @Test
    fun `体积格式化`() {
        assertEquals("512 B", BuiltinTtsUiSupport.formatBytes(512))
        assertEquals("1 KB", BuiltinTtsUiSupport.formatBytes(1024))
        assertEquals("20 MB", BuiltinTtsUiSupport.formatBytes(20L * 1024 * 1024))
        assertEquals("1.5 GB", BuiltinTtsUiSupport.formatBytes(1536L * 1024 * 1024))
    }
}
