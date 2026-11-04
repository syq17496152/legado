package io.legado.app.ui.book.read

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 6.4：章节总结的来源标签必须和模型名拼在一起，且空模型名时也不能出现「 · 」悬空。
 * 这保证"模型选择"与"结果展示"两处文案同源（都走 [summaryModelLabel]）。
 */
class ReadAiSummarySourceLabelTest {

    @Test
    fun `有模型名时标注来源`() {
        assertEquals("qwen-plus - 云端 · 来源：云端", summaryModelLabel("qwen-plus - 云端", "云端"))
        assertEquals("本地模型 · 来源：本地", summaryModelLabel("本地模型", "本地"))
    }

    @Test
    fun `无模型名时不产生悬空分隔符`() {
        assertEquals("来源：未启用", summaryModelLabel("", "未启用"))
        assertEquals("来源：云端", summaryModelLabel("   ", "云端"))
    }
}
