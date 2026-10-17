package io.legado.app.ui.book.read.epub.loading

import io.legado.app.help.config.EpubLoadingTemplate

/** 首屏绘制模式：模板优先，任一条件不满足 ⇒ 回落既有遮罩（见 [LoadingTemplateOverlayPolicy]）。 */
enum class LoadingOverlayMode { TEMPLATE, LEGACY }

/**
 * 首屏绘制的**回落契约**（reader-theme-family 3.12 的核心不变量）。
 *
 * 为什么把"选哪种模式"抽成纯函数：`EpubReadView.drawLoadingOverlay` 的两条路径**必须互斥且可证**
 * —— 模板路径一旦"看起来画了但实际是空的"，用户看到的就是白屏/黑屏（比没有模板更糟）。
 * 判据只有三条，且都能在 JVM 上断言：
 * ①没有模板（用户未选/读取失败）⇒ LEGACY；②插画不可用（SVG 解析失败/资源缺失）⇒ LEGACY；
 * ③视图尺寸非法（0/负）⇒ LEGACY。三者齐备才走模板。
 *
 * 注：**never** 返回"什么都不画"——LEGACY 分支就是既有的"全屏黑遮罩 + 居中文字"或"底部徽标"，
 * 即"最差也不比改造前差"。
 */
object LoadingTemplateOverlayPolicy {

    fun resolve(
        template: EpubLoadingTemplate?,
        artworkReady: Boolean,
        viewWidth: Int,
        viewHeight: Int
    ): LoadingOverlayMode =
        if (template != null && artworkReady && viewWidth > 0 && viewHeight > 0) {
            LoadingOverlayMode.TEMPLATE
        } else {
            LoadingOverlayMode.LEGACY
        }
}
