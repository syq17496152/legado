package io.legado.app.model.localBook.epubcore.facade

/**
 * 渲染多级回退判据（纯函数，JVM 可测；阶段 1.6 骨架 / 台账 C1 / 缺点 X7）。
 *
 * 分级（保真度递减、可用性递增）：
 * ```
 * WEB_LAYOUT  —— 出版方排版保真渲染（WebView 引擎）
 * CANVAS_PAGE —— 归一化分页渲染（canvas 阅读器主题）
 * TEXT        —— 纯文本（剥离标签）单页兜底
 * RAW         —— 原始内容/错误说明页（终态，绝不崩溃）
 * ```
 *
 * 设计约束：
 * - **WEB_LAYOUT 只对「出版方控制型」章节是第一选择**；可重排章节本就该走 CANVAS 快路径（AD-15）。
 * - `WEBVIEW_UNAVAILABLE` / 结构损坏 ⇒ **跳过 WEB_LAYOUT**（重试无意义，直接下探）。
 * - 超时/空结果 ⇒ 允许下探，但**不做同级别重试**（同参数重试在保真链路上必失败）。
 * - `RAW` 为终态：`next()` 返回 null ⇒ 调用方必须产出兜底内容（不抛异常）。
 *
 * 纯判定、不改状态 ⇒ 可单测；调用方负责执行与埋点。
 */
internal object EpubRenderFallbackPolicy {

    /** 渲染分级（保真度递减）。 */
    enum class Stage {
        WEB_LAYOUT,
        CANVAS_PAGE,
        TEXT,
        RAW
    }

    /** 失败原因（决定「下探」还是「跳过该级」）。 */
    enum class Failure {
        /** WebView 引擎不可用/旧内核/渲染进程崩溃。 */
        ENGINE_UNAVAILABLE,

        /** 布局执行异常（注入脚本报错、模板异常）。 */
        LAYOUT_ERROR,

        /** 布局成功但结果为空（页面数为 0 / 无 fragment）。 */
        EMPTY_RESULT,

        /** 布局超时。 */
        TIMEOUT,

        /** 压缩包/OPF 结构损坏，内容不可读。 */
        MALFORMED_CONTENT
    }

    /**
     * 由「章节内容态」推导首选渲染分级。
     *
     * @param prefersPublisherLayout 该章是否保留出版方排版（五态非 REFLOWABLE）。
     */
    fun initialStage(prefersPublisherLayout: Boolean): Stage {
        return if (prefersPublisherLayout) Stage.WEB_LAYOUT else Stage.CANVAS_PAGE
    }

    /**
     * 失败后应下探到的下一级；null 表示已到终态（调用方必须产出兜底，禁止再抛）。
     */
    fun nextAfter(current: Stage, failure: Failure): Stage? {
        val skipWeb = failure == Failure.ENGINE_UNAVAILABLE || failure == Failure.MALFORMED_CONTENT
        return when (current) {
            Stage.WEB_LAYOUT -> if (skipWeb || failure == Failure.TIMEOUT) {
                Stage.TEXT
            } else {
                Stage.CANVAS_PAGE
            }
            Stage.CANVAS_PAGE -> Stage.TEXT
            Stage.TEXT -> Stage.RAW
            Stage.RAW -> null
        }
    }

    /** 该失败是否值得在**下一级**重试（RAW 已无下级）。 */
    fun hasFallback(current: Stage, failure: Failure): Boolean = nextAfter(current, failure) != null

    /**
     * 汇总一次降级链的完整路径（诊断用）：从 [from] 起按 [failure] 逐级下探至终态。
     */
    fun fallbackChain(from: Stage, failure: Failure): List<Stage> {
        val chain = arrayListOf<Stage>()
        var cursor: Stage? = nextAfter(from, failure)
        while (cursor != null) {
            chain += cursor
            cursor = nextAfter(cursor, failure)
        }
        return chain
    }
}