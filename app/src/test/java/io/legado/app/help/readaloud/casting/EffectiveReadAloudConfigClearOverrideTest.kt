package io.legado.app.help.readaloud.casting

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 书级覆盖「恢复全局」（offline-tts-ux-consolidation 5.9 / J4）：
 * `clearBookOverride` 必须只清 L2 两处来源（模板覆盖键 + 书级语速），
 * 不动全局模板与全局语速 —— 否则会误伤其它书。
 *
 * 纯逻辑不可 JVM 测（读写 `appCtx` prefs），用源码不变量锁边界（同项目既有接线测试口径）。
 */
class EffectiveReadAloudConfigClearOverrideTest {

    private fun code(): String {
        val rel = "src/main/java/io/legado/app/help/readaloud/casting/EffectiveReadAloudConfig.kt"
        val f = listOf(File(rel), File("../app/$rel"), File("app/$rel")).first { it.isFile }
        return f.readLines()
            .filterNot { it.trimStart().let { t -> t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") } }
            .joinToString("\n")
    }

    @Test
    fun `恢复全局只清书级两层且不动全局`() {
        val text = code()
        val start = text.indexOf("fun clearBookOverride")
        assertTrue("未找到 clearBookOverride", start >= 0)
        val body = text.substring(start, (start + 500).coerceAtMost(text.length))
        assertTrue("必须清书级模板覆盖键", body.contains("PreferKey.ttsCastingBookOverridePrefix"))
        assertTrue("必须清书级语速", body.contains("setBookSpeechRate(bookKey, null)"))
        assertTrue(
            "禁止在恢复全局里改全局模板/全局语速",
            !body.contains("setActiveTemplateId") && !body.contains("ttsSpeechRate =")
        )
        assertTrue("空书键必须直接返回（不发无意义写入）", body.contains("isBlank()"))
    }
}
