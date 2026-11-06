package io.legado.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:llm` 推理进程判定（E18：主进程被推理进程抢 WebView 锁而 FATAL）。
 *
 * 回归背景（2026-11-06 模拟器实测）：修复 `appCtx` 注入后，`:llm` 会继续跑 [App.onCreate] 的
 * 整套主进程初始化（DB / Cronet / DoH / WebView），其中某处加载了 WebView provider，
 * 于是主进程随后创建 WebView 直接崩：
 * `Using WebView from more than one process at once ... lock owner <pkg>:llm`。
 * 因此必须在 `onCreate` / `attachBaseContext` 里把 `:llm` 当作「只跑推理」的进程短路掉。
 *
 * 判定必须：①对 `:llm` 返回 true；②对主进程与沙箱进程返回 false（否则主进程功能全丢）。
 */
class LlmProcessDetectionTest {

    @Test
    fun llmProcessIsDetected() {
        assertTrue(isLlmInferenceProcess("io.legado.miss.app.debug:llm"))
        assertTrue(isLlmInferenceProcess("io.legado.miss.app.release:llm"))
    }

    @Test
    fun otherProcessesAreNotLlm() {
        assertFalse("主进程不得短路", isLlmInferenceProcess("io.legado.miss.app.debug"))
        assertFalse("沙箱进程有自己的短路分支", isLlmInferenceProcess("io.legado.miss.app.debug:quickjs_sandbox"))
        assertFalse("前缀相同但不是推理进程", isLlmInferenceProcess("io.legado.miss.app.debug:llmX"))
        assertFalse(isLlmInferenceProcess(null))
    }
}
