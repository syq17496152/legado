package io.legado.app.ui.config

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 模板编辑器载荷与回写契约（epub-md-rich-rendering 阶段 4.8c 配对）。
 *
 * 覆盖三件在 JVM 无法实例化、却决定"能不能编辑"的事：
 * ① 大载荷通道（模板 CSS ≈320KB 不走 Intent/Binder）；
 * ② 编辑器可用性（保存键有可读文案 ⇒ 无障碍与真机可定位）；
 * ③ 回写闭环（编辑器返回的文本必须经 `TemplateField.write` 落到用户库，否则"改了不生效"）。
 */
class ReaderTemplateEditorPayloadTest {

    private val manage = "ui/config/ReaderTemplateManageActivity.kt"
    private val editor = "ui/code/CodeEditActivity.kt"

    private fun manageText(): String = SourceFileProbe.sourceText(manage)

    @Test
    fun `大载荷走文件通道且小载荷内联`() {
        val s = manageText()
        assertTrue("必须按策略判定通道", s.contains("CodeEditPayloadPolicy.useFileChannel(text.length)"))
        assertTrue("大载荷传路径", s.contains("putExtra(CodeEditPayloadPolicy.ExtraTextFile, payloadFile.absolutePath)"))
        assertTrue("小载荷仍内联（行为零变化）", s.contains("putExtra(\"text\", text)"))
        assertTrue("回写前必须读取当前字段值（不能把整模板塞进编辑器）", s.contains("val text = field.read(entry.template)"))
    }

    @Test
    fun `编辑器返回的文本必须落回用户库`() {
        val s = manageText()
        assertTrue("必须取回内联结果", s.contains("data?.getStringExtra(\"text\")"))
        assertTrue("必须能取回文件通道结果（4.8c 出方向）", s.contains("CodeEditPayloadPolicy.ExtraTextFile"))
        assertTrue("文件通道结果须在 IO 线程读取", s.contains("CodeEditPayloadStore(this@ReaderTemplateManageActivity).read(payloadFile)"))
        assertTrue("读取失败必须明说而非当空文本保存", s.contains("编辑器结果读取失败，已放弃本次修改"))
        assertTrue("必须按字段写回", s.contains("ReaderTemplateManager.saveUserTemplate(field.write(base, text))"))
        assertTrue("写回失败必须给出原因而非静默", s.contains("ReaderTemplateManager.lastLibraryError()"))
    }

    @Test
    fun `双向文件通道与仓库单源`() {
        val s = manageText()
        assertTrue("入方向写临时件走仓库单源", s.contains("CodeEditPayloadStore(this@ReaderTemplateManageActivity).write(text)"))
        assertTrue("必须向编辑器显式开启文件通道（其它调用方不受影响）", s.contains("ExtraFileChannelOptIn, true"))
        val store = SourceFileProbe.sourceText("ui/code/CodeEditPayloadStore.kt")
        assertTrue("仓库只落私有缓存目录", store.contains("context.cacheDir"))
        // 真机铁证：出方向漏接 ⇒ TransactionTooLargeException(662368B) ⇒ 进程被杀、编辑丢失
        assertTrue("编辑器出方向必须走文件通道", SourceFileProbe.sourceText("ui/code/CodeEditActivity.kt").contains("buildResult("))
    }

    @Test
    fun `多字段模板必须先选择字段再进编辑器`() {
        val s = manageText()
        assertTrue("必须有字段集判定", s.contains("TemplateField.of(entry.template)"))
        assertTrue("单字段直达编辑器", s.contains("if (fields.size == 1)"))
        assertTrue("多字段先弹选择", s.contains("ComposeActionListDialog.create(") && s.contains("fields.map { it.label }"))
    }

    @Test
    fun `编辑入口与保存键对无障碍与真机可定位`() {
        val s = SourceFileProbe.sourceText(editor)
        assertTrue(
            "保存键必须有可读文案（原为 null：TalkBack 读不出，真机 L2 也定位不到）",
            s.contains("contentDescription = \"保存\"")
        )
        assertTrue("编辑器必须有语言选择（html/js/markdown 语法高亮）", manageText().contains("putExtra(\"languageName\", field.language)"))
        assertTrue("模板字段的语言取自本仓既有语法集", manageText().contains("text.html.basic"))
        assertFalse("不得引入不存在的 source.css 单语法（本仓 textmate 只有 js/html/markdown）", manageText().contains("source.css"))
    }
}