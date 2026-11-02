package io.legado.app.ui.book.read.config

import io.legado.app.help.tts.builtin.BuiltinEngineState
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

    /** IF-IM-08：即使只清理旧碎片（新增 0），discovery.changed>0 也必须重算分镜 */
    @Test
    fun `仅清理碎片也必须强制重算分镜`() {
        assertTrue(
            BuiltinTtsUiSupport.shouldForceRecomputeStoryboard(
                discoveredCharacters = 1, // changed = added(0) + pruned(1)
                cachedRecognized = 35
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

    @Test
    fun `管理页副标题点明离线与多角色`() {
        val subtitle = BuiltinTtsUiSupport.engineSubtitle()
        assertTrue(subtitle.contains("离线"))
        assertTrue(subtitle.contains("多角色"))
    }

    @Test
    fun `音色行副文案含来源池性别年龄 T6-4`() {
        val imported = io.legado.app.help.tts.builtin.BuiltinVoice(
            id = "v1",
            name = "男青年",
            refAudio = "v1/prompt.wav",
            refText = "稿",
            origin = io.legado.app.help.tts.builtin.BuiltinVoice.ORIGIN_ZIP,
            groupId = "青年",
            gender = "male",
            age = "youth"
        )
        assertEquals("导入 · 池:青年 · 逐字稿 1 字", BuiltinTtsUiSupport.voiceMetaText(imported))
        val preset = io.legado.app.help.tts.builtin.BuiltinVoice(
            id = "b6_m_teen",
            name = "内置·男·少年",
            refAudio = "b6_m_teen/prompt.wav",
            refText = "稿",
            origin = io.legado.app.help.tts.builtin.BuiltinVoice.ORIGIN_PRESET,
            gender = "male",
            age = "teen",
            readOnly = true
        )
        assertTrue(BuiltinTtsUiSupport.voiceMetaText(preset).startsWith("内置"))
    }

    // ===== 第三轮视觉重做（卡片化/状态胶囊）新增纯文案口径 =====

    @Test
    fun `语音包档位标题按 id 区分两档`() {
        assertEquals("极速单声档", BuiltinTtsUiSupport.profileTitle("fast"))
        // 非 fast 一律按高质量克隆档（导入的 ZipVoice/vocos 都落这一档）
        assertEquals("高质量克隆档", BuiltinTtsUiSupport.profileTitle("clone"))
        assertEquals("高质量克隆档", BuiltinTtsUiSupport.profileTitle("unknown"))
    }

    @Test
    fun `语音包状态胶囊为短标签且区分五态`() {
        assertEquals("已就绪", BuiltinTtsUiSupport.profileStateText(BuiltinEngineState.READY))
        assertEquals("下载中", BuiltinTtsUiSupport.profileStateText(BuiltinEngineState.DOWNLOADING))
        assertEquals("已损坏", BuiltinTtsUiSupport.profileStateText(BuiltinEngineState.CORRUPT))
        assertEquals("内存不足", BuiltinTtsUiSupport.profileStateText(BuiltinEngineState.LOW_MEM))
        assertEquals("未下载", BuiltinTtsUiSupport.profileStateText(BuiltinEngineState.NONE))
    }

    @Test
    fun `语音包副文案含采样率且标明是否当前档`() {
        assertEquals("24000 Hz · 朗读走这一档", BuiltinTtsUiSupport.profileSubtitle(24000, active = true))
        assertEquals("22050 Hz · 可切换为当前档", BuiltinTtsUiSupport.profileSubtitle(22050, active = false))
        // 未取到采样率时不显示 "0 Hz" 这类噪声
        assertEquals("可切换为当前档", BuiltinTtsUiSupport.profileSubtitle(0, active = false))
    }

    @Test
    fun `就绪胶囊两态文案`() {
        assertEquals("离线就绪", BuiltinTtsUiSupport.readyPill(true))
        assertEquals("语音包未就绪", BuiltinTtsUiSupport.readyPill(false))
    }

    @Test
    fun `音色头像首字与空白兜底`() {
        assertEquals("张", BuiltinTtsUiSupport.voiceInitial("张三"))
        // 首尾空格不影响取字
        assertEquals("内", BuiltinTtsUiSupport.voiceInitial("  内置·男·青年"))
        assertEquals("?", BuiltinTtsUiSupport.voiceInitial("   "))
        assertEquals("?", BuiltinTtsUiSupport.voiceInitial(""))
    }

    @Test
    fun `RTF 未测过显示破折号`() {
        assertEquals("—", BuiltinTtsUiSupport.rtfText(0f))
        assertEquals("1.22", BuiltinTtsUiSupport.rtfText(1.22f))
        assertEquals("5.68", BuiltinTtsUiSupport.rtfText(5.682f))
    }

    @Test
    fun `音色标题去掉来源前缀避免内置音色全叫内置`() {
        // 6 个内置音色原名都以"内置·"开头，标题去掉后靠"男·少年"这类后缀区分
        assertEquals("男·少年", BuiltinTtsUiSupport.voiceTitle("内置·男·少年"))
        assertEquals("张三音色", BuiltinTtsUiSupport.voiceTitle("导入·张三音色"))
        assertEquals("我的录音", BuiltinTtsUiSupport.voiceTitle("自录·我的录音"))
        // 无前缀 / 只有前缀本身时保持原样，不产出空标题
        assertEquals("张三音色", BuiltinTtsUiSupport.voiceTitle("张三音色"))
        assertEquals("内置", BuiltinTtsUiSupport.voiceTitle("内置"))
        // 头像取"去前缀后的首字"，所以内置音色能一眼看出男女老少
        assertEquals("男", BuiltinTtsUiSupport.voiceInitial(BuiltinTtsUiSupport.voiceTitle("内置·男·少年")))
    }

    @Test
    fun `导出本章音频需要已开书且有分镜 T8-5`() {
        assertTrue(BuiltinTtsUiSupport.canExportChapter(hasBook = true, storyboardSegments = 13))
        // 未开书 / 无分镜都不能导（按钮置灰，避免点了没反应）
        assertEquals(false, BuiltinTtsUiSupport.canExportChapter(hasBook = false, storyboardSegments = 13))
        assertEquals(false, BuiltinTtsUiSupport.canExportChapter(hasBook = true, storyboardSegments = 0))
        // 未开书时 SAF 默认名也必须可给（不能崩在空书名上）
        assertEquals("book-chapter.wav", BuiltinAudioExportOps.chapterFileName())
    }

    @Test
    fun `缓存水位显示用量上限与百分比 T3-4`() {
        // 正常水位：用量/上限/百分比（GB 统一保留一位小数）
        assertEquals(
            "缓存 20 MB / 1.0 GB（1%）",
            BuiltinTtsUiSupport.cacheWatermarkText(20L * 1024 * 1024, 1024L * 1024 * 1024)
        )
        // 超限：显式提示按 LRU 清理（不是静默丢文件）
        assertEquals(
            "缓存 1.5 GB / 1.0 GB（150%） · 已超限，按最近最少使用清理",
            BuiltinTtsUiSupport.cacheWatermarkText(1536L * 1024 * 1024, 1024L * 1024 * 1024)
        )
        // 上限未知（引擎未初始化）时只显示用量，不显示 0%
        assertEquals("缓存 512 B", BuiltinTtsUiSupport.cacheWatermarkText(512, 0))
    }

    /** IF-IM-08 自愈回归：碎片名落库后，一键开书必须补发现并让 0 命中的旧分镜缓存失效 */
    @Test
    fun `一键开书重算分镜的判定`() {
        // 补到了新角色：即使旧缓存有命中也要重算（新角色可能改变段归属）
        assertEquals(true, BuiltinTtsUiSupport.shouldForceRecomputeStoryboard(discoveredCharacters = 3, cachedRecognized = 12))
        // 没有新角色但旧缓存 0 命中（碎片名时代的缓存）：必须重算，否则永远无换声
        assertEquals(true, BuiltinTtsUiSupport.shouldForceRecomputeStoryboard(discoveredCharacters = 0, cachedRecognized = 0))
        // 没有新角色且旧缓存已有命中：沿用缓存，避免每次开书都全章重算
        assertEquals(false, BuiltinTtsUiSupport.shouldForceRecomputeStoryboard(discoveredCharacters = 0, cachedRecognized = 35))
    }
}
