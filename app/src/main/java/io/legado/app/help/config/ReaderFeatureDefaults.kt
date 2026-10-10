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

    /**
     * 阅读页面模板**整体开关**默认值（epub-md-rich-rendering 阶段 4.9）。
     *
     * **默认 false（AOAdapt，理由与翻转条件）**：
     * 1. 开启意味着**文本类内容（在线正文 / txt / md）从 canvas 自绘切到模板渲染面**
     *    —— 这是**默认渲染路径的切换**，属大改，必须先有实测（4.19 防卡顿 / XB.2 性能预算）
     *    与兼容验证（4.20），再谈默认；
     * 2. 4.9 的既有语义是「整体**可关闭** + 关闭即等同纯文本渲染、无残留」
     *    （`ReaderTemplateAvailabilityPolicy.DISABLED`）⇒ 默认关与该语义天然一致，
     *    且**不给用户造成"看书方式被悄悄换掉"的意外**；
     * 3. 与 S2「默认模板随主题（浅/暗各一）」不冲突：那条讲的是**模板被使用时**默认选哪套
     *    （由 [ReaderTemplateSelection] 承担），不是"默认是否使用模板"。
     * 翻转条件：4.19/4.20 通过且性能无劣化（同页同路径帧耗时劣化 ≤10%）后，可把本常量改 true
     * 并在 updateLog 作为**用户可感知变化**登记。
     */
    const val READER_TEMPLATE_ENABLED = false

    /**
     * 模板**装饰强度**默认值（epub-md-rich-rendering 阶段 4.8d / TPL-17②）。
     *
     * 取 `2`（中）而非更强：AD-33「默认克制」是硬约束——装饰是氛围层，
     * 默认档位必须保证正文是画面主体（正文区 ≥80% 页高）。
     * 与 `ReaderTemplateDecorationPolicy.DefaultIntensity` **同值双写**（两者皆为"中"），
     * 由同包测试钉住（避免默认值分叉）。
     */
    const val READER_TEMPLATE_DECORATION = 2

    /**
     * 模板**专注模式**默认值（阶段 4.8d / TPL-17③）。
     *
     * 默认 false：专注模式是"我此刻只想看正文"的显式选择，不应替用户决定；
     * 默认关也与"默认档位=中"自洽（若默认专注，装饰强度设置将形同虚设）。
     */
    const val READER_TEMPLATE_FOCUS_MODE = false
}