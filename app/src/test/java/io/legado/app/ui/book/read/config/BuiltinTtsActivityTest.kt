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

    @Test
    fun `一键开书结果文案区分模板是否启用`() {
        assertEquals(
            "角色 2（新增 2）· 配声 2 · 已启用多角色",
            BuiltinTtsUiSupport.multiRoleSummary(characters = 2, discovered = 2, assigned = 2, activated = true)
        )
        assertEquals(
            "角色 0（新增 0）· 配声 0 · 多角色模板启用失败",
            BuiltinTtsUiSupport.multiRoleSummary(characters = 0, discovered = 0, assigned = 0, activated = false)
        )
    }

    @Test
    fun `存储总览文案含三类与合计`() {
        assertEquals(
            "存储：模型 20 MB · 音色 1 KB · 缓存 1.5 GB · 合计 1.5 GB",
            BuiltinTtsUiSupport.storageSummary(
                modelBytes = 20L * 1024 * 1024,
                voiceBytes = 1024L,
                cacheBytes = 1536L * 1024 * 1024
            )
        )
    }
}
