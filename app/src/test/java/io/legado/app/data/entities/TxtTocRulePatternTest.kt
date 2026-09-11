package io.legado.app.data.entities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7/5.2：TXT 目录新增内置规则（-26~-29）正则匹配样例单测。
 * assets JSON 需 Android 运行时，此处内联与 json 相同的正则验证匹配语义。
 */
class TxtTocRulePatternTest {

    @Test
    fun `规则26_中文顶格标题`() {
        val p = Regex("^[一-龥]{1,20}$")
        assertTrue(p.matches("母亲"))
        assertTrue(p.matches("楔子"))
        assertFalse(p.matches("第三章 离别")) // 带编号不命中（由基线规则处理）
        assertFalse(p.matches("Chapter 1"))
    }

    @Test
    fun `规则27_数字可选分隔符`() {
        val p = Regex("^[ 　\\t]{0,4}\\d{1,5}[:：,.， 、_—\\-]?.{1,30}$")
        assertTrue(p.containsMatchIn("02美好的明天"))
        assertTrue(p.containsMatchIn("02 美好的明天"))
        assertTrue(p.containsMatchIn("12：新的开始"))
        assertFalse(p.containsMatchIn("普通的正文内容一行没有数字开头"))
    }

    @Test
    fun `规则28_双编号轻小说`() {
        val p = Regex("^[ 　\\t]{0,4}\\d{1,3}-\\d{1,3}[ 　\\t].{0,30}$")
        assertTrue(p.containsMatchIn("1-1 序章的开始"))
        assertTrue(p.containsMatchIn("12-3 日常篇"))
        assertFalse(p.containsMatchIn("1885-1930 历史年代"))
    }

    @Test
    fun `规则29_英文序词扩展`() {
        val p = Regex("^[ 　\\t]{0,4}(?:Prologue|Interlude|Epilogue|Afterword)\\b.{0,30}$")
        assertTrue(p.containsMatchIn("Prologue The Promise"))
        assertTrue(p.containsMatchIn("Epilogue 十年之后"))
        assertFalse(p.containsMatchIn("PrologueX 无效前缀"))
    }
}
