package io.legado.app.help.config

/**
 * R12（B3）页眉页脚「自定义模板」渲染核心（纯函数，可单测）。
 *
 * 设计约束（来自 spec R12 与 design §3.3）：
 * - **复用既有占位符引擎** `AdvancedTitleConfig.replaceTemplateVariables` —— 全项目只保留一套占位符语法
 *   （`${name}` 与 `{{name}}` 两种写法），禁止在本项另造一套模板语法；
 * - **未知占位符原样保留**：`replaceTemplateVariables` 是「逐已知键 replace」语义，未列出的占位符不会被吃掉
 *   ⇒ R12-3 天然满足，但仍以单测固化（防未来改成"先扫描再替换"的实现而回归）；
 * - 变量集为**声明式白名单**，供配置页「可视化插入占位符」按名生成按钮（用户无需背语法）。
 */
internal object ReadTipTemplate {

    /** 阅读期可用变量（顺序即配置页占位符按钮顺序） */
    val variables: List<String> = listOf(
        "chapterTitle",
        "bookName",
        "author",
        "time",
        "battery",
        "batteryPercentage",
        "page",
        "totalProgress",
        "totalProgress1"
    )

    /**
     * 渲染模板：已知变量替换为其值，未知占位符原样保留。
     *
     * @param template 用户模板串（可能含 `${name}` / `{{name}}`）
     * @param values 本轮阅读态变量值（缺失的键不参与替换 ⇒ 对应占位符原样保留）
     */
    fun render(template: String, values: Map<String, String>): String {
        if (template.isEmpty()) return template
        return AdvancedTitleConfig.replaceTemplateVariables(template, values)
    }

    /**
     * 该模板是否引用了电量类变量（`battery` / `batteryPercentage`）。
     *
     * 供渲染侧决定是否走「电量视图」分支（`PageView.getTipView(...).isBattery`）——
     * 否则自定义模板槽位会被误当普通文本处理，导致电量图标类渲染异常。
     */
    fun usesBatteryVariable(template: String): Boolean =
        template.contains("\${battery}") || template.contains("{{battery}}") ||
            template.contains("\${batteryPercentage}") || template.contains("{{batteryPercentage}}")
}