package io.legado.app.ui.book.source.edit

/**
 * 书源编辑页的入口路由判定（纯函数，零 Android 依赖）。
 *
 * 背景：新建规则书源不带 `sourceUrl`。若把「无需重定向」和「不执行任何动作」混为一谈，
 * 规则编辑页就永远不会被构建 —— 这正是 2026-10-23 `af3dac84` 引入的「新建规则书源空白页」回归。
 * 因此把判定与副作用分离：任何输入都必须落到两个显式动作之一。
 */
object JsSourceEditRoute {

    enum class Target {
        /** 重定向到 JS 单文件书源编排页（规则页对 JS 源无意义） */
        RedirectJs,

        /** 进入声明式规则编辑页（含新建书源） */
        RuleEditor
    }

    /**
     * @param sourceUrl intent 中的 `sourceUrl`（新建书源为 null / 空白）
     * @param isJsSource 该 url 在库中是否为 JS 单文件书源；仅在 [sourceUrl] 非空时才有意义
     */
    fun decide(sourceUrl: String?, isJsSource: Boolean): Target {
        if (sourceUrl.isNullOrBlank()) return Target.RuleEditor
        return if (isJsSource) Target.RedirectJs else Target.RuleEditor
    }
}
