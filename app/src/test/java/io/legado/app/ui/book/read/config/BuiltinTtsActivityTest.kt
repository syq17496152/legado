package io.legado.app.ui.book.read.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `一键开书文案必须回报分镜产出（IF-09 回归）`() {
        // 有分镜：显示段数，用户能据此判断"识别真的跑了"
        assertEquals(
            "角色 2（新增 0）· 配声 0 · 已启用多角色 · 分镜 9 段",
            BuiltinTtsUiSupport.multiRoleSummary(
                characters = 2, discovered = 0, assigned = 0, activated = true, storyboardSegments = 9
            )
        )
        // 无分镜：显式提示未生成，而不是静默成功
        assertEquals(
            "角色 2（新增 0）· 配声 0 · 已启用多角色 · 分镜未生成（可点“识别角色”重试）",
            BuiltinTtsUiSupport.multiRoleSummary(
                characters = 2, discovered = 0, assigned = 0, activated = true, storyboardSegments = 0
            )
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

    @Test
    fun `一键启用文案包含引擎 多角色 自检三段`() {
        assertEquals(
            "已切换到内置离线引擎 · 多角色已启用 · 自检通过",
            BuiltinTtsUiSupport.quickEnableSummary(
                engineMessage = "已切换到内置离线引擎",
                castingActivated = true,
                checkMessage = "自检通过"
            )
        )
        assertEquals(
            "切换失败：原因 · 多角色未启用（可稍后在开书向导重试） · 自检失败",
            BuiltinTtsUiSupport.quickEnableSummary(
                engineMessage = "切换失败：原因",
                castingActivated = false,
                checkMessage = "自检失败"
            )
        )
    }

    @Test
    fun `批量试听优先非内置音色且不超过上限`() {
        val preset = (1..6).map {
            io.legado.app.help.tts.builtin.BuiltinVoice(
                id = "p$it", name = "内置$it", origin = io.legado.app.help.tts.builtin.BuiltinVoice.ORIGIN_PRESET
            )
        }
        val imported = (1..3).map {
            io.legado.app.help.tts.builtin.BuiltinVoice(
                id = "i$it", name = "导入$it", origin = io.legado.app.help.tts.builtin.BuiltinVoice.ORIGIN_ZIP
            )
        }
        val batch = BuiltinTtsUiSupport.previewBatch(preset + imported, limit = 5)
        assertEquals(3, batch.size)
        assertTrue(batch.all { it.id.startsWith("i") })
        // 全是内置时回落全量取样（受 limit 约束，全新用户也能"连续试听"）
        assertEquals(5, BuiltinTtsUiSupport.previewBatch(preset, limit = 5).size)
    }

    @Test
    fun `打标文案含处理数与池数 T6-3`() {
        assertEquals(
            "打标完成：处理 6 · 男 3 / 女 3 / 未定 0 · 分组 4",
            BuiltinTtsUiSupport.autoTagMessage(processed = 6, male = 3, female = 3, unknown = 0, pooled = 4)
        )
    }

    @Test
    fun `池 chips 只显示去重后的前四个池`() {
        fun voice(id: String, pool: String) = io.legado.app.help.tts.builtin.BuiltinVoice(
            id = id, name = id, groupId = pool
        )
        val voices = listOf(
            voice("1", "旁白"), voice("2", "旁白"), voice("3", "male"),
            voice("4", "female"), voice("5", "youth"), voice("6", "aged"), voice("7", "")
        )
        assertEquals(listOf("旁白", "male", "female", "youth"), BuiltinTtsUiSupport.visiblePoolNames(voices))
    }

    @Test
    fun `分镜改绑结果文案 IF-10 回归 失败与旁白与角色`() {
        assertEquals(
            "改绑失败（本章还没有识别缓存，请先点“识别角色”）",
            BuiltinTtsUiSupport.segmentAssignMessage(ok = false, narrator = false, characterName = "张三")
        )
        assertEquals(
            "已改为旁白（返回阅读页重开朗读生效）",
            BuiltinTtsUiSupport.segmentAssignMessage(ok = true, narrator = true, characterName = "")
        )
        assertEquals(
            "已改为张三（可在分镜页继续校正）",
            BuiltinTtsUiSupport.segmentAssignMessage(ok = true, narrator = false, characterName = "张三")
        )
    }

    @Test
    fun `分镜标签 未识别不静默归旁白`() {
        assertEquals("旁白", BuiltinTtsUiSupport.storyboardLabel("narrator", false, ""))
        assertEquals("张三", BuiltinTtsUiSupport.storyboardLabel("character", true, "张三"))
        assertEquals("未识别", BuiltinTtsUiSupport.storyboardLabel("character", false, ""))
        assertEquals("未识别", BuiltinTtsUiSupport.storyboardLabel("other", true, ""))
    }

    @Test
    fun `分镜片段按区间裁剪且越界安全`() {
        val paragraph = "张三说：今天天气不错，我们去公园吧。"
        assertEquals("天气不错", BuiltinTtsUiSupport.storyboardSnippet(paragraph, 6, 10))
        // 越界：start > length / end > length 均被夹紧，不抛异常
        assertEquals("", BuiltinTtsUiSupport.storyboardSnippet(paragraph, 200, 300))
        assertEquals(paragraph, BuiltinTtsUiSupport.storyboardSnippet(paragraph, -5, 300, 200))
        // 超长截断
        assertEquals(60, BuiltinTtsUiSupport.storyboardSnippet("字".repeat(100), 0, 100).length)
    }

    @Test
    fun `音色包导出边界说明写明内置只读 T6-5`() {
        val hint = BuiltinTtsUiSupport.voiceExportHint()
        assertTrue(hint.contains("内置 6 个只读"))
        assertTrue(hint.contains("不随包分发"))
    }
}
