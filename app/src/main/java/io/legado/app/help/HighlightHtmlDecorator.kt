package io.legado.app.help

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.util.Locale

/**
 * 模板模式高亮渲染（R7 接线）：把**启用中的高亮规则**应用到交给模板沙箱的正文 HTML。
 *
 * ## 为什么模板模式必须另起一套
 *
 * canvas 路径按**字符偏移**在绘制期逐列着色（`TextChapterLayout` + `HighlightRuleMatcher.RuleMatch`），
 * 偏移量在模板模式下**无处可用** —— 正文是交给沙箱（跨源 iframe）的 HTML 字符串，宿主既读不到
 * 它的 DOM、也无法在绘制期介入 ⇒ 必须在**生成 HTML 的那一刻**就把命中包裹成带样式的 `<span>`。
 *
 * archive 的同位实现是 `HighlightDocument`（epub 主题下用 `<style id="legado-reeden-highlight-style">`
 * + 规则类名）。我方不用类名 + `<style>`：沙箱文档由 `ReaderTemplateSandboxDocument` 拼装，
 * 注入的样式表（`md-reader.css` 等）**在作者 CSS 之前** ⇒ 类规则会被作者同特异度规则盖掉；
 * 而**内联样式**是最高优先级（仅让位于作者 `!important`），且不引入类名冲突与额外注入通道。
 * 两者外观等价，故取内联。
 *
 * ## 与 archive 对齐的两处口径
 *
 * - **"高亮加入正文后再测量布局"**（archive 官方作者文档原话）：本装饰发生在宿主侧、
 *   **早于** `TemplateRenderBackend.render()` 把 body 交给沙箱 ⇒ 分页测量看到的就是最终版式；
 * - **正文文本口径**：逐块收集"可见文本"（`br` 记为换行、`img` 等不计字符、`script/style/svg/rt…`
 *   与 `[data-reader-text-ignore]` 一律排除），与 archive `HighlightDocument.collect` 同构 ——
 *   否则命中偏移与块内偏移会错位。
 *
 * ## 边界（`sanitized()` 兜底、预算降级）
 *
 * - 规则集为空 / 无有效规则 / 无命中 ⇒ **原样返回**（零解析成本，不发 jsoup）；
 * - 匹配走 [HighlightRuleMatcher.matchWithBudget]（与 canvas 路径**同一预算**），超预算按
 *   `truncated` 语义**放弃本次装饰**（不阻断渲染：模板正文照常显示，只是这次没高亮）；
 * - 字符上界 2 Mi 与 [io.legado.app.help.md.MdDocumentBuilder.MaxChapterChars] 同口径。
 *
 * 纯函数（jsoup + 纯 JVM）⇒ 全部分支可单测。
 */
object HighlightHtmlDecorator {

    /** 装饰结果：`html` 为改写后的正文（无命中即原样）；规则/手动命中数供日志与自证。 */
    data class Result(val html: String, val matchedRules: Int, val matchedManual: Int = 0)

    /**
     * 手动划线（`BookHighlight`）→ 模板面输入。
     *
     * ⚠️ `start`/`end` 是 **canvas 章内字符口径**，只作**位置提示**（多命中择近用），
     * **不作为最终锚**：模板面的正文文本口径与 canvas 不同（块间补 `\n`、去标签等），
     * 直接用 canvas 偏移会落到错位置 ⇒ 真正的定位靠 [locateByText]（按 [text] 做空白无关匹配）。
     */
    data class ManualHighlight(
        val text: String,
        val start: Int,
        val end: Int,
        val style: HighlightStyle
    )

    /** 手动划线在命中表里的保留 id（不计入 `matchedRules`）。 */
    const val ManualRuleId = "manual-highlight"

    /** 整章文本预算（与 `MdDocumentBuilder.MaxChapterChars` 同口径，此处避免跨包常量耦合）。 */
    private const val MaxTextChars = 2 * 1024 * 1024

    /** 默认匹配预算（毫秒）。生产路径由调用方传 `AppConfig.highlightMatchBudgetMs` 覆盖。 */
    const val DefaultMatchBudgetMs = 300L

    /** 递归深度上限（防作者畸形 DOM 拖死解析）。 */
    private const val MaxDepth = 128

    /**
     * 不参与正文文本的元素（与 archive `HighlightDocument.excluded` 同集）：
     * 这些元素的内容既不算正文、也不该被高亮包裹。
     */
    private val Excluded = setOf(
        "script", "style", "noscript", "template", "svg", "math",
        "rt", "rp", "textarea", "iframe", "object", "audio", "video"
    )

    /** archive 契约：作者自标"此元素文本不属于正文"（如装饰题签），采集文本时排除。 */
    private const val TextIgnoreAttr = "data-reader-text-ignore"

    /**
     * 把命中规则装饰进正文 HTML。
     *
     * @param rules 匹配层纯规则（调用方从 `ReadBook.highlightMatcherRules()` 取，与 canvas 同源）
     * @param tone 当前阅读器态（日/夜/水墨屏）——色板派生样式按它解析（见 [HighlightPalette.resolve]）
     * @param budgetMs 整章匹配预算（调用方从 `AppConfig.highlightMatchBudgetMs` 取，与 canvas 路径同源）。
     *
     * ⚠️ `tone` / `budgetMs` **一律由调用方显式传入**（不给默认值）：本类因此保持**纯函数**
     * （不触碰 `AppConfig` 等 Android 单例，JVM 可测）——默认值里藏 Android 依赖会让单测
     * 在 `<clinit>` 阶段就炸（`NoClassDefFoundError`），且是"看起来能跑"的隐性耦合。
     */
    fun decorate(
        html: String,
        rules: List<HighlightRuleMatcher.Rule>,
        tone: HighlightPalette.Tone,
        budgetMs: Long = DefaultMatchBudgetMs,
        manual: List<ManualHighlight> = emptyList()
    ): Result {
        if (html.isBlank()) return Result(html, 0)
        // 有效规则：有 pattern 且样式非空（空样式命中等于"无高亮"，白白包一层 span）
        val effective = rules.filter { it.pattern.isNotEmpty() && !it.style.sanitized().isEmpty }
        if (effective.isEmpty() && manual.isEmpty()) return Result(html, 0)
        val document = runCatching { Jsoup.parseBodyFragment(html) }.getOrNull()
            ?: return Result(html, 0)
        document.outputSettings().prettyPrint(false)
        val blocks = ArrayList<Block>()
        val chapterText = StringBuilder()
        collect(document.body(), 0, blocks, chapterText)
        if (blocks.isEmpty() || chapterText.length > MaxTextChars) return Result(html, 0)

        // 标题行标记（R12.1 作用域过滤的输入）：整块标 true 即可 —— 模板块的粒度就是段落/标题
        val titleFlags = BooleanArray(chapterText.length)
        blocks.forEach { block ->
            if (!block.title) return@forEach
            val from = block.start
            val to = (block.start + block.text.length).coerceAtMost(titleFlags.size)
            for (i in from until to) titleFlags[i] = true
        }

        val textSnapshot = chapterText.toString()
        val matches = ArrayList<HighlightRuleMatcher.RuleMatch>(
            // 手动划线命中与规则命中同表：后续"逐块裁切 + span 切分"因此只有一套实现
            manual.size + effective.size * 2
        )
        // 手动划线：按**文本**定位到模板面的章内偏移（canvas 偏移只作择近提示）
        manual.forEachIndexed { order, item ->
            if (item.style.sanitized().isEmpty) return@forEachIndexed
            val range = locateByText(textSnapshot, item.text, item.start) ?: return@forEachIndexed
            matches.add(
                HighlightRuleMatcher.RuleMatch(
                    // 带上序号：跨块被切开时同一划线会产生多个段，靠 id 去重才能按「条数」计数
                    range.first, range.last + 1, "$ManualRuleId#$order", item.style
                )
            )
        }
        if (effective.isNotEmpty()) {
            val outcome = HighlightRuleMatcher.matchWithBudget(
                textSnapshot,
                effective,
                titleFlags,
                // 与 canvas 路径**同一**整章预算；超预算按 truncated 语义放弃装饰（不阻断渲染）
                deadlineMs = System.currentTimeMillis() + budgetMs,
                withTemplate = true
            )
            // B15 捕获组子样式：展开为额外命中段（后写覆盖前写 ⇒ 组内样式在段内生效）
            outcome.matches.forEach { match ->
                matches.add(match)
                match.subSpans.forEach { span ->
                    matches.add(
                        HighlightRuleMatcher.RuleMatch(span.start, span.end, match.ruleId, span.style)
                    )
                }
            }
        }
        if (matches.isEmpty()) return Result(html, 0)

        val usedRules = HashSet<String>()
        // 按 id 去重：一条划线跨块被切开会产出多个段，计数要按**条数**而非段数
        val usedManual = HashSet<String>()
        for (block in blocks) {
            val length = block.text.length
            // 与本块相交的命中，裁到块内（跨块命中被块边界切开，与 archive 同处理）
            val local = matches.mapNotNull { match ->
                val from = maxOf(0, match.start - block.start)
                val to = minOf(length, match.end - block.start)
                if (from >= to) null else match.copy(start = from, end = to)
            }
            if (local.isEmpty()) continue

            // 整段命中（且块内联节点就是父元素的全部子节点）⇒ 样式直接落段元素：
            // 背景因此覆盖整行宽、也没有逐字符包裹产生的缝隙（archive 同款优化）
            val entireParent = block.nodes.size == block.parent.childNodes().size
            val wholeBlock = local.all { it.start == 0 && it.end == length }
            if (entireParent && wholeBlock && block.parent.normalName() != "body") {
                local.forEach { if (it.ruleId.startsWith(ManualRuleId)) usedManual.add(it.ruleId) else usedRules.add(it.ruleId) }
                block.parent.attr("style", mergeDeclarations(local.map { it.style }, tone, block.parent.attr("style")))
                continue
            }

            val cuts = (listOf(0, length) + local.flatMap { listOf(it.start, it.end) })
                .distinct().sorted()
            val originals = block.nodes.map { it.clone() }
            val replacement = ArrayList<Node>(cuts.size)
            for (index in 0 until cuts.lastIndex) {
                val from = cuts[index]
                val to = cuts[index + 1]
                val active = local.filter { it.start <= from && it.end >= to }
                val nodes = slice(originals, from, to, length)
                if (active.isEmpty()) {
                    replacement.addAll(nodes)
                    continue
                }
                active.forEach { if (it.ruleId.startsWith(ManualRuleId)) usedManual.add(it.ruleId) else usedRules.add(it.ruleId) }
                val span = Element("span")
                span.attr("style", mergeDeclarations(active.map { it.style }, tone, ""))
                nodes.forEach(span::appendChild)
                replacement.add(span)
            }
            val first = block.nodes.firstOrNull() ?: continue
            replacement.forEach { first.before(it) }
            block.nodes.forEach(Node::remove)
        }
        if (usedRules.isEmpty() && usedManual.isEmpty()) return Result(html, 0)
        return Result(document.body().html(), usedRules.size, usedManual.size)
    }

    /**
     * 在模板面正文文本里按**文本内容**定位手动划线（空白无关匹配）。
     *
     * 为什么必须按文本：canvas 与本面的正文口径不同（后者块间补 `\n`、去标签、`br` 记换行），
     * 直接套 canvas 的 `chapterPos` 会把划线画到别的句子上去。
     *
     * @param hintStart canvas 章内起点（多个命中时择近；仅作提示，不决定正确性）
     * @return 原文本（**含空白**）中的半开区间 `[start, end)`；零命中/文本为空 ⇒ null（**跳过不误锚**）
     */
    fun locateByText(chapterText: String, needle: String, hintStart: Int): IntRange? {
        if (chapterText.isEmpty() || needle.isBlank()) return null
        // ① 归一化章节文本（去空白），并记录「归一化下标 → 原下标」映射
        val map = ArrayList<Int>(chapterText.length)
        val flat = StringBuilder(chapterText.length)
        chapterText.forEachIndexed { index, ch ->
            if (!ch.isWhitespace()) {
                map.add(index)
                flat.append(ch)
            }
        }
        val target = needle.filterNot { it.isWhitespace() }
        if (target.isEmpty() || target.length > flat.length) return null
        val flatText = flat.toString()
        // ② 扫描全部命中，择「原起点离 hintStart 最近」的一处
        var bestFlat = -1
        var bestDistance = Int.MAX_VALUE
        var cursor = flatText.indexOf(target)
        while (cursor >= 0) {
            val distance = kotlin.math.abs(map[cursor] - hintStart)
            if (distance < bestDistance) {
                bestDistance = distance
                bestFlat = cursor
            }
            cursor = flatText.indexOf(target, cursor + 1)
        }
        if (bestFlat < 0) return null
        // ③ 归一化区间 → 原文本区间（含两端之间的空白）
        val start = map[bestFlat]
        val end = map[bestFlat + target.length - 1] + 1
        return start until end
    }

    // === 块采集（与 archive HighlightDocument.collect 同构） ===

    private data class Block(
        val parent: Element,
        val nodes: List<Node>,
        val text: String,
        val start: Int,
        val title: Boolean
    )

    private fun collect(
        parent: Element,
        depth: Int,
        blocks: MutableList<Block>,
        chapterText: StringBuilder
    ) {
        if (depth > MaxDepth || chapterText.length > MaxTextChars) return
        if (parent.normalName() in Excluded) return
        val inline = ArrayList<Node>()
        fun flush() {
            if (inline.isEmpty()) return
            val text = inline.joinToString("") { visibleText(it) }
            if (text.isNotBlank()) {
                if (chapterText.isNotEmpty()) chapterText.append('\n')
                blocks.add(Block(parent, inline.toList(), text, chapterText.length, isTitle(parent)))
                chapterText.append(text)
            }
            inline.clear()
        }
        for (node in parent.childNodes()) {
            if (node is Element && node.hasAttr(TextIgnoreAttr)) continue
            if (node is Element && node.isBlock && node.normalName() !in Excluded) {
                flush()
                collect(node, depth + 1, blocks, chapterText)
            } else {
                inline.add(node)
            }
        }
        flush()
    }

    /** 标题判定：h1..h6 / `data-reader-kind="title"` / `reader-chapter-title` 类。 */
    private fun isTitle(element: Element): Boolean =
        element.normalName() in TitleTags ||
                element.attr("data-reader-kind") == "title" ||
                element.hasClass("reader-chapter-title")

    /** 元素的"可见文本"：`br` 记换行，排除集元素与 text-ignore 元素不计（与 archive 同口径）。 */
    private fun visibleText(node: Node, depth: Int = 0): String {
        if (depth > MaxDepth) return ""
        return when (node) {
            is TextNode -> node.wholeText
            is Element -> when {
                node.normalName() in Excluded -> ""
                node.hasAttr(TextIgnoreAttr) -> ""
                node.normalName() == "br" -> "\n"
                else -> node.childNodes().joinToString("") { visibleText(it, depth + 1) }
            }
            else -> ""
        }
    }

    /**
     * 按字符区间切出节点片段（保留原内联标记与图片）。
     *
     * 与 archive `HighlightDocument.slice` 同构：遇到元素时递归下切，`br` 不递归（空元素），
     * 重复的 `id` 只保留首个（避免克隆出重复 id）。
     */
    private fun slice(
        nodes: List<Node>,
        start: Int,
        end: Int,
        total: Int,
        seenIds: MutableSet<String> = HashSet()
    ): List<Node> {
        var offset = 0
        val output = ArrayList<Node>()
        for (node in nodes) {
            val length = visibleText(node).length
            val left = maxOf(start, offset)
            val right = minOf(end, offset + length)
            if (length == 0) {
                if (offset >= start && (offset < end || (offset == total && end == total))) {
                    output.add(node.clone())
                }
            } else if (left < right) {
                val copy = when (node) {
                    is TextNode -> TextNode(node.wholeText.substring(left - offset, right - offset))
                    is Element -> node.clone().also { element ->
                        if (element.normalName() != "br") {
                            element.empty()
                            slice(node.childNodes(), left - offset, right - offset, length, seenIds)
                                .forEach(element::appendChild)
                        }
                        if (element.id().isNotEmpty() && !seenIds.add(element.id())) element.removeAttr("id")
                    }
                    else -> node.clone()
                }
                output.add(copy)
            }
            offset += length
        }
        return output
    }

    // === 样式 → 内联声明 ===

    /**
     * 合并多条规则的样式为**一段内联声明**（后写覆盖前写 ⇒ 规则顺序即优先级）。
     *
     * @param existing 元素已有 style（整段命中时追加，不能把作者/管线写的样式清掉）
     */
    private fun mergeDeclarations(
        styles: List<HighlightStyle>,
        tone: HighlightPalette.Tone,
        existing: String
    ): String {
        val declarations = LinkedHashMap<String, String>()
        styles.forEach { style -> declarations.putAll(declarationsOf(style, tone)) }
        val body = declarations.entries.joinToString("") { (property, value) -> "$property:$value;" }
        return when {
            existing.isBlank() -> body
            body.isBlank() -> existing
            else -> existing.trimEnd().let { if (it.endsWith(";")) it else "$it;" } + body
        }
    }

    /** 单条样式的声明表（按 [HighlightPalette.resolve] 解析后的具体色值生成）。 */
    private fun declarationsOf(style: HighlightStyle, tone: HighlightPalette.Tone): Map<String, String> {
        // 色板派生样式按当前阅读器态解析；用户手选色原样保留（见 HighlightPalette.resolve）
        val s = HighlightPalette.resolve(style, tone).sanitized()
        val out = LinkedHashMap<String, String>()

        if (s.fill != 0) {
            val color = cssColor(s.fill)
            when (s.resolvedFillShape) {
                HighlightStyle.FillShape.ROUNDED -> {
                    out["background-color"] = color
                    out["border-radius"] = ".25em"
                }
                HighlightStyle.FillShape.PILL -> {
                    out["background-color"] = color
                    out["border-radius"] = "1em"
                    out["padding"] = "0 .35em"
                }
                HighlightStyle.FillShape.MARKER,
                HighlightStyle.FillShape.BASELINE,
                HighlightStyle.FillShape.HALF ->
                    // 半高/底色条：用渐变只画下缘一段（等价 canvas 的 MARKER/BASELINE/HALF 观感）
                    out["background-image"] = "linear-gradient(to top, $color 55%, transparent 55%)"
                else -> out["background-color"] = color
            }
        }
        if (s.textColor != 0) out["color"] = cssColor(s.textColor)
        if (s.bold) out["font-weight"] = "700"
        if (s.italic) out["font-style"] = "italic"

        val lines = ArrayList<String>(2)
        if (s.underline != null) lines.add("underline")
        if (s.strike != null) lines.add("line-through")
        if (lines.isNotEmpty()) {
            out["text-decoration-line"] = lines.joinToString(" ")
            val decoColor = when {
                s.underline?.color?.let { it != 0 } == true -> s.underline!!.color
                s.strike?.color?.let { it != 0 } == true -> s.strike!!.color
                else -> 0
            }
            out["text-decoration-color"] = if (decoColor != 0) cssColor(decoColor) else "currentColor"
            s.underline?.let { underline ->
                out["text-decoration-style"] = when (underline.kind) {
                    HighlightStyle.Kind.SOLID -> "solid"
                    HighlightStyle.Kind.DASHED -> "dashed"
                    HighlightStyle.Kind.WAVY -> "wavy"
                    HighlightStyle.Kind.DOTTED -> "dotted"
                    HighlightStyle.Kind.DOUBLE -> "double"
                }
                underline.width?.let { out["text-decoration-thickness"] = "${it}px" }
            }
        }
        s.box?.let { box ->
            val color = if (box.color != 0) cssColor(box.color) else "currentColor"
            out["border"] = "1px solid $color"
            out["border-radius"] = ".2em"
            out["padding"] = "0 .12em"
        }
        s.emphasis?.let { emphasis ->
            val color = if (emphasis.color != 0) cssColor(emphasis.color) else "currentColor"
            // 着重号：`text-emphasis`（新内核）与 `-webkit-text-emphasis`（旧内核）双写
            out["-webkit-text-emphasis"] = "filled dot"
            out["text-emphasis"] = "filled dot"
            out["text-emphasis-color"] = color
        }
        s.shadow?.normalized()?.let { shadow ->
            val color = if (shadow.color != 0) cssColor(shadow.color) else "currentColor"
            out["text-shadow"] = "${shadow.dx}px ${shadow.dy}px ${shadow.radius}px $color"
        }
        if (s.hasFontOverrides) {
            if (s.fontScale != null && s.resolvedFontScale != 1f) {
                out["font-size"] = "${s.resolvedFontScale}em"
            }
            if (s.letterSpacingEm != null && s.resolvedLetterSpacingEm != 0f) {
                out["letter-spacing"] = "${s.resolvedLetterSpacingEm}em"
            }
        }
        return out
    }

    /** ARGB → CSS 8 位十六进制（`#RRGGBBAA`，Chromium/WebView 支持）。 */
    private fun cssColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        return String.format(Locale.US, "#%02X%02X%02X%02X", r, g, b, a)
    }

    private val TitleTags = setOf("h1", "h2", "h3", "h4", "h5", "h6")
}
