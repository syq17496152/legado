package io.legado.app.ui.code

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器**载荷通道**契约（epub-md-rich-rendering 阶段 4.8c 配对）。
 *
 * 为什么值得钉住：通道选择是"极端体量才暴露"的一类缺陷 —— 阈值被改坏 / 两侧 extra 键不同源 /
 * 文件读完不删，日常（书源规则几 KB）**完全看不出来**，只有用户导入大模板时才表现为
 * "点编辑卡住/直接崩"或"缓存被数百 KB 正文拷贝撑大"。
 */
class CodeEditPayloadPolicyTest {

    @Test
    fun `阈值口径：小载荷内联 大载荷走文件`() {
        assertEquals(64 * 1024, CodeEditPayloadPolicy.MaxInlineChars)
        assertFalse("空载荷内联", CodeEditPayloadPolicy.useFileChannel(0))
        assertFalse("边界正好等于阈值 ⇒ 内联（含等号）", CodeEditPayloadPolicy.useFileChannel(64 * 1024))
        assertTrue("超一个字符即走文件", CodeEditPayloadPolicy.useFileChannel(64 * 1024 + 1))
        assertTrue("模板 CSS 实测体量必须走文件", CodeEditPayloadPolicy.useFileChannel(320 * 1024))
    }

    @Test
    fun `阈值必须远低于 binder 事务上限`() {
        // 依据：Intent extras 走 Binder（事务上限≈1MB）⇒ 阈值若逼近上限，大载荷仍会
        // TransactionTooLargeException（症状是"点编辑直接崩"，且与设备/系统版本相关）
        assertTrue(
            "内联上限必须留足余量（当前 ${CodeEditPayloadPolicy.MaxInlineChars}）",
            CodeEditPayloadPolicy.MaxInlineChars <= 128 * 1024
        )
    }

    @Test
    fun `两侧 extra 键同源且读写各就位`() {
        // 写侧（管理页）与读侧（编辑器 VM）必须引用**同一常量**：各写各的字面量 ⇒ 键不一致 ⇒
        // 编辑器读不到文本，走 `未获取到待编辑文本` 分支（用户看到 toast，而不是编辑器）
        val writer = SourceFileProbe.sourceText("ui/config/ReaderTemplateManageActivity.kt")
        val readerVm = SourceFileProbe.sourceText("ui/code/CodeEditViewModel.kt")
        assertTrue("写侧必须用策略常量", writer.contains("CodeEditPayloadPolicy.ExtraTextFile"))
        assertTrue("读侧必须用同一常量", readerVm.contains("CodeEditPayloadPolicy.ExtraTextFile"))
        assertFalse("任一侧都不得写死字面量键名（会与常量漂移）", readerVm.contains("\"textFile\""))
    }

    @Test
    fun `出方向也走文件通道且由调用方显式开启`() {
        // 真机铁证（2026-10-10）：入方向修好后，**出方向**仍把 320KB 经 result Intent 回传 ⇒
        // TransactionTooLargeException（parcel 662368 bytes）⇒ 进程被杀、编辑丢失。
        // 且必须 opt-in：编辑器被书源/订阅源等多处复用，自动改通道会让它们把"路径"当正文保存。
        val editor = SourceFileProbe.sourceText("ui/code/CodeEditActivity.kt")
        val writer = SourceFileProbe.sourceText("ui/config/ReaderTemplateManageActivity.kt")
        assertTrue("编辑器必须按策略构造回传结果", editor.contains("private fun buildResult("))
        assertTrue("大载荷回传必须写文件并只回路径", editor.contains("CodeEditPayloadPolicy.ExtraTextFile, payloadFile.absolutePath"))
        assertTrue("必须读调用方的开关", editor.contains("ExtraFileChannelOptIn"))
        assertTrue("调用方必须显式开启", writer.contains("putExtra(CodeEditPayloadPolicy.ExtraFileChannelOptIn, true)"))
        assertTrue("写侧必须能读文件通道的回传结果", writer.contains("CodeEditPayloadStore(this@ReaderTemplateManageActivity).read(payloadFile)"))
        assertTrue("未修改（两通道皆空）不得当成错误", writer.contains("val hasPayload = payloadFile != null || inlineText != null"))
    }

    @Test
    fun `载荷仓库三不变量齐备`() {
        val store = SourceFileProbe.sourceText("ui/code/CodeEditPayloadStore.kt")
        assertTrue("只落私有缓存目录", store.contains("File(context.cacheDir, CodeEditPayloadPolicy.TempDirName)"))
        assertTrue("写前清历史残留", store.contains("dir.listFiles()?.forEach { it.delete() }"))
        assertTrue("读完即删", store.contains("runCatching { file.delete() }"))
        assertTrue("读失败可辨别（返回 null 而非空串）", store.contains("fun read(path: String): String?"))
    }

    @Test
    fun `搜索面板按职责拆分且宿主顶层有主题作用域`() {
        // 4.8c：编辑器 821 行触顶 800 行门禁 ⇒ 把约 190 行「程序化 View 构造」下沉到独立类；
        // 同时 G-37 要求宿主入口**顶层**有主题作用域（原形态只把顶栏包在 LegadoTheme 里）。
        val activity = SourceFileProbe.sourceText("ui/code/CodeEditActivity.kt")
        val panel = SourceFileProbe.sourceText("ui/code/CodeEditSearchPanelViews.kt")
        assertTrue("面板视图构造必须已下沉", activity.contains("CodeEditSearchPanelViews(this)"))
        assertTrue("宿主只留行为（搜索/替换流程）", activity.contains("private fun search()"))
        assertTrue("面板取色仍走运行时面 token（不得硬编码）", panel.contains("context.themeCardColorOrDefault()"))
        assertTrue("面板必须懒构造（与旧实现同为首次访问才建）", panel.contains("val searchPanel: LinearLayout by lazy"))
        val scopeIndex = activity.indexOf("attachComposeContent {")
        val themeIndex = activity.indexOf("LegadoTheme {", scopeIndex)
        assertTrue("宿主入口顶层必须紧跟主题作用域", themeIndex in (scopeIndex + 1)..(scopeIndex + 200))
    }

    @Test
    fun `写侧回落与清理齐备`() {
        val writer = SourceFileProbe.sourceText("ui/config/ReaderTemplateManageActivity.kt")
        assertTrue("写失败必须回落内联（不得静默传路径）", writer.contains("putExtra(\"text\", text)"))
        assertTrue(
            "临时件必须交给仓库单源（写前清残留/读完即删由 CodeEditPayloadStore 保证）",
            writer.contains("CodeEditPayloadStore(this@ReaderTemplateManageActivity).write(text)")
        )
    }

    @Test
    fun `读侧读完即删且失败显式抛出`() {
        val vm = SourceFileProbe.sourceText("ui/code/CodeEditViewModel.kt")
        assertTrue("必须走仓库读取（IO 与删除单源）", vm.contains("CodeEditPayloadStore(context).read(textFile)"))
        assertTrue(
            "读取失败必须显式抛出（静默空文本会被后续保存覆盖掉用户模板）",
            vm.contains("未获取到待编辑文本（临时载荷读取失败）")
        )
        assertTrue("载荷诊断必须留下字符数与通道（真机 L2 证据）", vm.contains("payload loaded: chars="))
    }
}