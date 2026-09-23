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

    /** 占位符标准写法（配置页点选插入用的就是它；`{{name}}` 写法由渲染引擎兼容） */
    fun placeholderToken(name: String): String = "\${$name}"

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
     * 构造阅读期变量值。
     *
     * `battery` 取裸数值（`BatteryView` 的电量图标按「文本尾部数字」定位画框 ⇒ 必须裸数值）；
     * `batteryPercentage` 带 `%`（纯文本表达）。
     */
    fun values(
        chapterTitle: String,
        bookName: String,
        author: String,
        time: String,
        battery: Int,
        page: String,
        totalProgress: String,
        totalProgress1: String
    ): Map<String, String> = mapOf(
        "chapterTitle" to chapterTitle,
        "bookName" to bookName,
        "author" to author,
        "time" to time,
        "battery" to battery.toString(),
        "batteryPercentage" to "$battery%",
        "page" to page,
        "totalProgress" to totalProgress,
        "totalProgress1" to totalProgress1
    )

    /**
     * 配置页预览值（固定样本）—— 供「可视化插入占位符」点击后立刻看到效果，
     * 不必等回到阅读页。**不参与持久化**，只影响预览文本。
     */
    fun previewValues(): Map<String, String> = values(
        chapterTitle = "第一章 示例章节",
        bookName = "示例书名",
        author = "示例作者",
        time = "08:30",
        battery = 85,
        page = "3/20",
        totalProgress = "12.5%",
        totalProgress1 = "3/120"
    )

    /**
     * 该模板是否引用了电量类变量（`battery` / `batteryPercentage`）。
     *
     * 供渲染侧决定是否走「电量视图」分支（`PageView.getTipView(...).isBattery`）——
     * 否则自定义模板槽位会被误当普通文本处理，导致电量图标类渲染异常。
     */
    fun usesBatteryVariable(template: String): Boolean =
        containsVariable(template, "battery") || containsVariable(template, "batteryPercentage")

    /**
     * 电量占位符是否**位于模板末尾**（允许尾部空白）。
     *
     * `BatteryView` 画电量框时按「渲染文本结尾的数字」定位（见 `BatteryView.drawBattery`），
     * 因此只有末尾的电量占位符才能安全走图标分支；出现在中间时降级为纯文本（`battery` → 裸数值），
     * 避免电量框画到无关字符上（视觉错位 > 少个图标）。
     */
    fun batteryIconTrailing(template: String): Boolean {
        val trimmed = template.trimEnd()
        return trimmed.endsWith(placeholderToken("battery")) || trimmed.endsWith("{{battery}}")
    }

    /**
     * 在选区（光标）处插入占位符，返回「新文本 + 新光标位置」。
     *
     * 纯函数：配置页编辑器只负责把结果写回 `TextFieldValue`，插入语义本身可单测。
     * 边界：越界下标按文本边界截断（负值落到 0、超长落到文本长度）；反向选区（end < start）
     * 归一化为 `[min, max)` 区间 ⇒ 返回值恒为同一区间的替换结果。
     */
    fun insertPlaceholder(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        name: String
    ): Pair<String, Int> {
        val safeStart = selectionStart.coerceIn(0, text.length)
        val safeEnd = selectionEnd.coerceIn(0, text.length)
        val from = minOf(safeStart, safeEnd)
        val to = maxOf(safeStart, safeEnd)
        val token = placeholderToken(name)
        val newText = text.substring(0, from) + token + text.substring(to)
        return newText to (from + token.length)
    }

    private fun containsVariable(template: String, name: String): Boolean =
        template.contains(placeholderToken(name)) || template.contains("{{$name}}")
}