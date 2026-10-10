package io.legado.app.help.config

import io.legado.app.model.localBook.epubcore.template.ReaderTemplateSelection

/**
 * 阅读器功能默认值**单源**（epub-md-rich-rendering 阶段 3.2 配套）。
 *
 * 为什么单独成文件：`AppConfig` 的取值依赖 `appCtx`（Android 运行时），JVM 单测无法断言默认值；
 * 而"新功能的默认开/关"是**产品决策**，必须被测试钉住（否则未来一次改动可能悄悄翻转用户行为）。
 * 把默认值收敛到本对象后，AppConfig 只负责"读偏好，缺省用本常量"，默认值即可被逐项断言。
 */
object ReaderFeatureDefaults {

    /**
     * 文本富渲染默认值（md / 含富渲染元素的文本内容走 WebView 富渲染面）。
     *
     * **默认 true（AOAdapt，理由）**：
     * 1. md 是我方**新增**格式，不存在"旧行为被改变"的回归面（此前 md 根本不被支持）；
     * 2. 分流只在**真含 mermaid/公式**时才切换渲染面，普通 md 章节仍走 canvas 快路径；
     * 3. 重运行时（mermaid ≈2.4MB）只在含 mermaid 时才读取；
     * 4. 关闭后不是"功能消失"而是**可用降级**（回落 canvas 的 `<usehtml>` HTML 渲染）。
     */
    const val MD_RICH_RENDER = true

    /**
     * 阅读页面模板**默认选定值**（epub-md-rich-rendering 阶段 4.8a）。
     *
     * 空串 = 「跟随主题」（按当前日夜取默认款：浅色→素笺 / 暗色→霓虹夜行），
     * 与 [ReaderTemplateSelection.FollowTheme] 同一哨兵口径。
     * 默认跟随主题而非固定某套：默认模板暗色款在浅色主题下会与主题冲撞（SP-06）。
     */
    const val READER_TEMPLATE_ID = ReaderTemplateSelection.FollowTheme
}