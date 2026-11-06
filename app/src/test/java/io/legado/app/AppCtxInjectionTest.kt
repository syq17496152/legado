package io.legado.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `appCtx` 兜底注入判定（E14：端侧 LLM 推理进程启动即 FATAL）。
 *
 * 回归背景（2026-11-06 模拟器实测）：`am startservice .../.help.llm.LlmService` 拉起
 * `android:process=":llm"` 的推理进程时报
 * `IllegalStateException: appCtx has not been initialized!`，进程起来即崩 ⇒ 本地 AI 全链路不可用。
 * 修复方式在 [App.onCreate] 中探测后幂等注入；本类覆盖该判定的两条分支：
 * - 已注入（探测不抛）⇒ **不得重复注入**（主进程不能被干扰）；
 * - 未注入（探测抛）⇒ 必须注入。
 */
class AppCtxInjectionTest {

    @Test
    fun injectsWhenAppCtxNotInitialized() {
        var probeCalled = false
        val needInject = shouldInjectAppCtx {
            probeCalled = true
            throw IllegalStateException("appCtx has not been initialized!")
        }
        assertTrue("探测必须真的执行过（不能靠短路蒙对）", probeCalled)
        assertTrue("appCtx 未初始化时必须注入", needInject)
    }

    @Test
    fun skipsWhenAppCtxAlreadyInitialized() {
        var probeCalled = false
        val needInject = shouldInjectAppCtx { probeCalled = true }
        assertTrue("探测必须真的执行过", probeCalled)
        assertFalse("appCtx 已初始化时不得重复注入", needInject)
    }
}
