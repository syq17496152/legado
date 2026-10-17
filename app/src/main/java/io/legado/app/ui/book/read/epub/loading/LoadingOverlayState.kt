package io.legado.app.ui.book.read.epub.loading

import io.legado.app.help.config.EpubLoadingTemplate

/**
 * 首屏遮罩的**状态机**（纯值对象，可 JVM 断言）。
 *
 * 为什么不让 `EpubReadView` 直接散落三个字段（template/night/failed）：
 * 「模板不得泄漏到下一轮加载」这条不变量**只能在"一轮结束"这个动作上被验证**，
 * 而散落字段的收尾逻辑埋在 View 的 Android 代码里（JVM 测不到）⇒ 一旦漏清就会表现为
 * "下一次开书顶着上一本的模板"。把状态收成一个纯对象后，`endRound()` 的语义可被逐条断言。
 *
 * 语义（与调用点一一对应）：
 * - [inject]  ← `ReadBookActivity` 整章切换前注入模板（含夜态，重置失败态）
 * - [beginLoading] ← `showLoading()`（正常加载文案）
 * - [markFailed] ← `setError()`（同一个遮罩显示错误，观感只变强调色）
 * - [clearFailure] ← `clearLoading()`（保留模板：同一本书切章还要用）
 * - [endRound] ← `clearBoundaryLoadingTurn()`（一轮加载收尾 ⇒ **模板必须清空**，防泄漏）
 */
internal class LoadingOverlayState {

    var template: EpubLoadingTemplate? = null
        private set
    var night: Boolean = false
        private set
    var failed: Boolean = false
        private set

    fun inject(template: EpubLoadingTemplate?, night: Boolean) {
        this.template = template
        this.night = night
        failed = false
    }

    fun beginLoading() {
        failed = false
    }

    fun markFailed() {
        failed = true
    }

    fun clearFailure() {
        failed = false
    }

    /** 一轮加载结束：模板与失败态一并清空（**防模板泄漏到下一次开书**）。 */
    fun endRound() {
        template = null
        failed = false
    }
}
