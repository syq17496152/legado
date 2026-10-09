package io.legado.app.help.md

/**
 * Markdown 渲染降级判据（epub-md-rich-rendering 阶段 3.10；台账 X7「旧内核兼容」）。
 *
 * 三级降级（与 blueprint「WebView→canvas→text→raw」同构，收敛到 md 场景）：
 * ```
 * RICH       —— WebView + 离线 mermaid/KaTeX 注入（富渲染完整）
 * BASIC_HTML —— HTML 就地渲染但**不注入**富渲染运行时 ⇒ mermaid/公式**保持代码块/原文**（不白屏）
 * PLAIN_TEXT —— 纯文本兜底（连 HTML 渲染都不可用时；md 语法标记保留为可读文本）
 * ```
 *
 * **纯函数**：把"能力探测"（`WebViewFeature.isFeatureSupported` 等，需 Android 运行时）与
 * "降级决策"解耦 ⇒ 决策矩阵可在 JVM 逐格断言，探测只在宿主侧做一次。
 */
internal object MdRenderModePolicy {

    enum class Mode {
        RICH,
        BASIC_HTML,
        PLAIN_TEXT
    }

    /** 宿主探测到的运行环境能力（Android 侧填充，本层只消费）。 */
    data class Capability(
        /** WebView 具备现代特性（宿主用 [ProbeFeatures] 探测）。 */
        val modernWebView: Boolean,
        /** WebView 可用且 JS 可执行（引擎存在、未被系统禁用）。 */
        val javaScriptUsable: Boolean,
        /** 是否允许走 HTML 就地渲染（canvas 的 `<usehtml>` 路径始终可用）。 */
        val htmlRenderAvailable: Boolean = true
    )

    /**
     * 宿主需探测的特性清单（顺序即优先级）。
     *
     * 判据选型说明：富渲染只依赖 **JS 执行 + 现代 DOM/CSS**，不依赖 WebView 的进程隔离/消息通道
     * （消息通道是阶段 4 模板桥的需求）。故此处探测 `WEB_MESSAGE_PORT_POST_MESSAGE` 与
     * `MULTI_PROCESS` 两项：前者代表引擎版本足够新，后者代表不会因内存压力闪退——
     * 任一不支持即保守降级到 [Mode.BASIC_HTML]，避免"半渲染"（图渲染一半后闪退）。
     */
    val ProbeFeatures: List<String> = listOf(
        "WEB_MESSAGE_PORT_POST_MESSAGE",
        "MULTI_PROCESS"
    )

    /**
     * 决策。
     *
     * @param richRenderWanted 构建期标注（含 mermaid/公式）⇒ 才值得走 RICH。
     */
    fun decide(
        capability: Capability,
        richRenderWanted: Boolean,
        /** 用户/设置是否允许富渲染（阶段 3.2 的独立开关，含灰度）。 */
        richRenderEnabled: Boolean = true
    ): Mode {
        if (richRenderWanted && richRenderEnabled &&
            capability.modernWebView && capability.javaScriptUsable
        ) {
            return Mode.RICH
        }
        if (capability.htmlRenderAvailable) return Mode.BASIC_HTML
        return Mode.PLAIN_TEXT
    }

    /**
     * 该模式下 mermaid / 公式是否必须以**代码块/原文**形态呈现。
     *
     * 语义：`true` ⇒ 保留源码文本块（可读、可复制），**不得**留空容器（否则用户看到空白页）。
     */
    fun requiresCodeBlockFallback(mode: Mode): Boolean = mode != Mode.RICH

    /**
     * 降级后的用户可感知提示（空串 ⇒ 无需提示，避免无谓打扰）。
     */
    fun noticeFor(mode: Mode, richRenderWanted: Boolean): String {
        if (!richRenderWanted || mode == Mode.RICH) return ""
        return when (mode) {
            Mode.BASIC_HTML -> "当前环境不支持图表/公式渲染，已按源码显示"
            Mode.PLAIN_TEXT -> "当前环境不支持富文本渲染，已按纯文本显示"
            Mode.RICH -> ""
        }
    }
}