package io.legado.app.ui.book.read.config

import io.legado.app.help.readaloud.prebuild.TtsPrebuildManager
import io.legado.app.help.readaloud.prebuild.TtsPrebuildManager.PrebuildGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 预合成发起门接线（T3.2/T3.3）：弹窗必须走统一纯函数门控。
 * 回归背景：弹窗原先"先查网络 → 再要求 httpTts 非空"，内置引擎用户永远点不出预合成。
 */
class TtsPrebuildGateWiringTest {

    @Test
    fun `内置引擎离线也可发起预合成`() {
        // 行为断言（带期望值）：内置就绪 + 无 HTTP + 无网络 ⇒ 放行
        assertEquals(
            PrebuildGate.OK,
            TtsPrebuildManager.prebuildGate(
                builtinReady = true, hasHttpTts = false, networkAvailable = false
            )
        )
        // 既无内置也无 HTTP ⇒ 必须提示先选引擎（不能假装开始）
        assertEquals(
            PrebuildGate.NEED_ENGINE,
            TtsPrebuildManager.prebuildGate(
                builtinReady = false, hasHttpTts = false, networkAvailable = true
            )
        )
    }

    @Test
    fun `弹窗接线到统一门控`() {
        val rel = "src/main/java/io/legado/app/ui/book/read/config/TtsPrebuildDialog.kt"
        val src = listOf(File(rel), File("../app/$rel"), File("app/$rel"))
            .firstOrNull { it.isFile }?.readText()
            ?: throw AssertionError("未找到源文件：$rel")
        assertTrue("弹窗必须调用 TtsPrebuildManager.prebuildGate", src.contains("prebuildGate"))
        assertTrue("弹窗必须判定内置引擎可用", src.contains("BuiltinTtsRuntime.isActiveEngine"))
    }
}
