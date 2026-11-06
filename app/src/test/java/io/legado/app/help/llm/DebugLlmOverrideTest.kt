package io.legado.app.help.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仅 DEBUG 的本地 AI 注入开关（tasks 7.2/7.6 的验证前置）。
 *
 * 为什么必须有单测锁定：它能在**不改 UI 状态机**的前提下直接打开本地 AI，
 * 一旦误在 release 生效就等于线上后门 ⇒ 必须用用例把「非 debug 恒 null」钉死。
 */
class DebugLlmOverrideTest {

    @Test
    fun debugBuildWithModelIdIsActionable() {
        val request = DebugLlmOverride.request(
            debugBuild = true,
            wantEnable = true,
            rawModelId = "  qwen  "
        )
        assertEquals("模型 id 必须去空白后原样透出", "qwen", request?.modelId)
    }

    @Test
    fun releaseBuildNeverActions() {
        assertNull(
            "release 包必须完全没有这条路径（否则就是线上后门）",
            DebugLlmOverride.request(debugBuild = false, wantEnable = true, rawModelId = "qwen")
        )
    }

    @Test
    fun missingEnableFlagDoesNothing() {
        assertNull(
            DebugLlmOverride.request(debugBuild = true, wantEnable = false, rawModelId = "qwen")
        )
    }

    @Test
    fun blankModelIdDoesNothing() {
        assertNull(
            "没有模型 id 时开了也用不了（AppConfig 要求 profileId 非空）",
            DebugLlmOverride.request(debugBuild = true, wantEnable = true, rawModelId = "   ")
        )
        assertNull(DebugLlmOverride.request(debugBuild = true, wantEnable = true, rawModelId = null))
    }

    /**
     * 自检开关：只有显式请求才带 bench，且 release 包同样恒 null
     * （否则等于给了线上一条"在推理进程里跑重任务"的后门）。
     */
    @Test
    fun benchIsOptInAndDebugOnly() {
        assertFalse(
            "未请求自检时不得偷偷跑（会白占 native 内存与几十秒）",
            DebugLlmOverride.request(
                debugBuild = true, wantEnable = true, rawModelId = "qwen"
            )!!.bench
        )
        val withBench = DebugLlmOverride.request(
            debugBuild = true, wantEnable = true, rawModelId = "  qwen  ", wantBench = true
        )!!
        assertTrue("显式请求自检时必须带上", withBench.bench)
        // 自检与模型 id 互不影响：带 bench 也要照常去空白透出（否则调用方会拿 "  qwen  " 去找不到目录）
        assertEquals("带自检时模型 id 仍须去空白", "qwen", withBench.modelId)
        assertNull(
            DebugLlmOverride.request(
                debugBuild = false, wantEnable = true, rawModelId = "qwen", wantBench = true
            )
        )
    }
}
