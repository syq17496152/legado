package io.legado.app.ui.book.read.config

import io.legado.app.testkit.SourceFileProbe
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「继续上次」提示（R23 / tasks 10.6）：
 * 朗读面板顶部在「当前章节 ≠ 上次收听章节」时给一条可点提示，点击回到上次章节。
 *
 * 为什么用源码不变量：该提示直接读 `ReadBook` 全局单例与 `curTextChapter`（依赖 appDb/appCtx），
 * 纯 JVM 无法构造；判定内核已由 `CastSceneHomeSectionsTest` 等状态机测试覆盖，这里只锁接线不回退。
 */
class ReadAloudDialogResumeHintTest {

    private fun source(): String =
        SourceFileProbe.sourceText("ui/book/read/config/ReadAloudDialog.kt")

    @Test
    fun `当前章节不同时给出上次听到提示`() {
        val text = source()
        assertTrue("必须出现「上次听到」提示文案", text.contains("上次听到"))
        assertTrue("必须比较上次章节索引", text.contains("durChapterIndex"))
        assertTrue("必须比较当前章节索引", text.contains("curTextChapter"))
    }

    @Test
    fun `点击提示回到上次章节`() {
        val text = source()
        assertTrue("必须调用 ReadBook.openChapter(", text.contains("ReadBook.openChapter("))
        assertTrue("必须带上上次位置", text.contains("ReadBook.durChapterPos"))
    }
}
