package io.legado.app.constant

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读页面模板选定键登记契约（epub-md-rich-rendering 阶段 4.8a）。
 *
 * 为什么固化：该键是"用户当前应用哪套模板"的**唯一落点**，被 `AppConfig.readerTemplate`
 * 与模板管理页双处消费 ⇒ 改名漏改会造成"应用了但下次打开又变回默认"的静默失联（编译期无提示）。
 */
class PreferKeyReaderTemplateTest {

    private fun pairs(): Map<String, String> {
        val rel = "src/main/java/io/legado/app/constant/PreferKey.kt"
        val file = listOf(File(rel), File("../app/$rel"), File("app/$rel")).first { it.isFile }
        return Regex("const val (\\w+)\\s*=\\s*\"([^\"]*)\"")
            .findAll(file.readText())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toMap()
    }

    @Test
    fun `模板选定键与取值按同一口径登记`() {
        assertEquals("readerTemplate", pairs()["readerTemplate"])
    }

    @Test
    fun `不与文本富渲染开关同值`() {
        val all = pairs()
        val template = all["readerTemplate"]
        assertTrue("键与取值都不得为空", !template.isNullOrBlank())
        assertTrue(
            "模板选定值绝不能与 mdRichRender 同值（会让布尔开关读到模板名）",
            template != all["mdRichRender"]
        )
    }
}