package io.legado.app.help.tts.builtin

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下载暂停能力（offline-tts-ux-consolidation 4.3）：
 * `download` 必须把当前 OkHttp Call 交给调用方（`onCallCreated`），
 * 且该回调要在 `execute()` **之前**触发 —— 否则暂停时拿不到句柄，只能等整包下完。
 *
 * 断点续传本身的纯逻辑已由 `BuiltinModelDownloadResumeTest` 覆盖；
 * 本类只锁"暂停句柄"这一条接线不变量（无法 JVM 实例化 Context 依赖的下载器）。
 */
class BuiltinModelDownloadPauseWiringTest {

    private fun code(): String {
        val rel = "src/main/java/io/legado/app/help/tts/builtin/BuiltinModelManager.kt"
        val f = listOf(File(rel), File("../app/$rel"), File("app/$rel")).first { it.isFile }
        return f.readLines()
            .filterNot { it.trimStart().let { t -> t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") } }
            .joinToString("\n")
    }

    @Test
    fun `暂停回调在发起请求前交给调用方`() {
        val text = code()
        val start = text.indexOf("suspend fun download(")
        assertTrue("未找到 download", start >= 0)
        val body = text.substring(start, (start + 2600).coerceAtMost(text.length))
        val hook = body.indexOf("onCallCreated?.invoke(call)")
        val execute = body.indexOf("call.execute()")
        assertTrue("必须把 Call 交给调用方", hook >= 0)
        assertTrue("必须先回调再 execute（否则暂停拿不到句柄）", execute > hook)
        assertTrue("暂停依赖 Call 类型", body.contains("onCallCreated: ((Call) -> Unit)? = null"))
    }

    @Test
    fun `断点续传仍按同源判定`() {
        // 暂停后重下必须能续：同源残片 offset>0，换源一律 0（既有行为不回退）
        assertEquals(
            1024L,
            BuiltinModelManager.resumeOffset(1024L, "https://example.invalid/a.tar.bz2", "https://example.invalid/a.tar.bz2")
        )
        assertEquals(
            0L,
            BuiltinModelManager.resumeOffset(1024L, "https://example.invalid/a.tar.bz2", "https://example.invalid/b.tar.bz2")
        )
    }
}
