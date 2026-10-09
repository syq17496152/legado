package io.legado.app.help.md

/**
 * Markdown 切章（epub-md-rich-rendering 阶段 3.4；AD-04 v1.1）。
 *
 * **纯函数**：输入 md 全文，输出章节目录（标题 + 字符区间），不做 IO/不做 HTML 转换 ⇒ JVM 可测。
 *
 * 规则（对齐 CommonMark 语义，避免把代码块里的 `#` 误当标题）：
 * 1. **ATX 标题**：`^ {0,3}#{1,6}\s+标题`（可带尾部 `#` 收尾），仅 **层级 ≤ [maxLevel]** 起新章，
 *    更深层级标题仍留在当前章内（章内小节）；
 * 2. **Setext 标题**：非空行 + 紧随的 `===`（1 级）/ `---`（2 级）下划线行；
 *    前一行是块级标记（空行/列表/引用/分隔线）时不算标题；
 * 3. **围栏代码块**：` ``` ` / `~~~` 内的 `#` 与下划线**全部不参与**切章（未闭合围栏延至文末）；
 * 4. **无标题**：整本一章；
 * 5. **前置内容**：首个标题之前若有非空内容，作为独立首章（标题取首个非空行）；
 * 6. **软切**：单章超过 [softLimitChars] 时在**空行边界**就近切分（无空行则硬切），续块标题加
 *    「（续 N）」后缀；软切只改分章粒度，**不丢字符**。
 *
 * 不变量（单测覆盖）：①各章区间首尾相接且拼回 == 原文；②区间均落在 `[0, length]` 内；
 * ③围栏块内不产生章节边界；④软切前后**字符总量不变**。
 */
object MdChapterizer {

    /** 默认切章层级：1~2 级标题（3 级及以下视为章内小节）。 */
    const val DefaultMaxLevel = 2

    /** 默认软切阈值：300KB（P-12：单章上限另由构建器按 2Mi 报错约束）。 */
    const val DefaultSoftLimitChars = 300 * 1024

    /** 章节标题长度上限（超长标题截断，避免目录撑爆）。 */
    const val MaxTitleChars = 120

    /**
     * 一个章节区间（`[startOffset, endOffset)`，字符偏移，基于**原文**）。
     *
     * @param level 标题层级；0 表示无标题章或软切续块。
     * @param continued 是否为软切产生的续块（同一原始章节的后续部分）。
     */
    data class Section(
        val title: String,
        val startOffset: Int,
        val endOffset: Int,
        val level: Int,
        val continued: Boolean = false
    ) {
        val length: Int get() = endOffset - startOffset
    }

    /** 一行（含终止符）的偏移信息。 */
    private data class Line(val start: Int, val contentEnd: Int, val end: Int)

    /** 标题：所在行号 + 层级 + 标题文本 + 标题块结束偏移（Setext 含下划线行）。 */
    private data class Heading(
        val lineIndex: Int,
        val level: Int,
        val title: String,
        val blockEnd: Int
    )

    fun chapterize(
        markdown: String,
        maxLevel: Int = DefaultMaxLevel,
        softLimitChars: Int = DefaultSoftLimitChars
    ): List<Section> {
        if (markdown.isEmpty()) {
            return listOf(Section(UntitledChapter, startOffset = 0, endOffset = 0, level = 0))
        }
        val lines = splitLines(markdown)
        val headings = collectHeadings(markdown, lines, maxLevel)
        val sections = buildSections(markdown, lines, headings)
        return softSplit(markdown, lines, sections, softLimitChars)
    }

    // === 行扫描（含终止符，偏移可直接用于 slice 原文）===

    private fun splitLines(text: String): List<Line> {
        val lines = ArrayList<Line>()
        var index = 0
        while (index < text.length) {
            var cursor = index
            while (cursor < text.length && text[cursor] != '\n') cursor++
            lines += Line(
                start = index,
                contentEnd = cursor,
                end = if (cursor < text.length) cursor + 1 else cursor
            )
            index = if (cursor < text.length) cursor + 1 else text.length
        }
        return lines
    }

    // === 标题收集 ===

    private fun collectHeadings(text: String, lines: List<Line>, maxLevel: Int): List<Heading> {
        val headings = ArrayList<Heading>()
        var fenceChar: Char? = null
        var fenceLength = 0
        lines.forEachIndexed { index, line ->
            val raw = text.substring(line.start, line.contentEnd)
            val fence = fenceTransition(raw, fenceChar, fenceLength)
            if (fence != null) {
                fenceChar = fence.first
                fenceLength = fence.second
                return@forEachIndexed
            }
            if (fenceChar != null) return@forEachIndexed
            val atx = atxHeading(raw)
            if (atx != null) {
                if (atx.first <= maxLevel) {
                    headings += Heading(index, atx.first, atx.second, line.end)
                }
                return@forEachIndexed
            }
            if (raw.isNotBlank() && index + 1 < lines.size) {
                val next = text.substring(lines[index + 1].start, lines[index + 1].contentEnd)
                val level = setextLevel(raw, next)
                if (level != null && level <= maxLevel) {
                    headings += Heading(index, level, raw.trim().boundTitle(), lines[index + 1].end)
                }
            }
        }
        return headings
    }

    /**
     * 围栏状态迁移。
     *
     * @return null ⇒ 非围栏行（状态不变）；(null, 0) ⇒ 关闭；(`char`, 长度) ⇒ 开启。
     */
    private fun fenceTransition(raw: String, openChar: Char?, openLength: Int): Pair<Char?, Int>? {
        val trimmed = raw.trimStart(' ')
        if (raw.length - trimmed.length > 3) return null
        val marker = trimmed.firstOrNull() ?: return null
        if (marker != '`' && marker != '~') return null
        val run = trimmed.takeWhile { it == marker }.length
        if (run < FenceMinLength) return null
        if (openChar == null) return marker to run
        if (marker != openChar || run < openLength) return null
        if (trimmed.drop(run).isNotBlank()) return null
        return null to 0
    }

    private fun atxHeading(raw: String): Pair<Int, String>? {
        val trimmed = raw.trimStart(' ')
        if (raw.length - trimmed.length > 3) return null
        val hashes = trimmed.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return null
        val rest = trimmed.drop(hashes)
        if (rest.isNotEmpty() && !rest.first().isWhitespace()) return null
        return hashes to rest.trim().trimEnd('#').trim().boundTitle()
    }

    /** Setext 下划线判定；返回层级（`=`→1，`-`→2）或 null。 */
    private fun setextLevel(current: String, next: String): Int? {
        val underline = next.trimStart(' ')
        if (next.length - underline.length > 3) return null
        val marker = underline.firstOrNull() ?: return null
        if (marker != '=' && marker != '-') return null
        if (underline.any { it != marker && !it.isWhitespace() }) return null
        if (current.isBlockMarker()) return null
        return if (marker == '=') 1 else 2
    }

    /** 块级标记行（列表/引用/标题/分隔线/空行）：其后跟下划线行不构成 Setext 标题。 */
    private fun String.isBlockMarker(): Boolean {
        val trimmed = trimStart(' ')
        val first = trimmed.firstOrNull() ?: return true
        return when (first) {
            '#', '>', '-', '*', '+' -> true
            else -> first.isDigit() && DIGIT_LIST.containsMatchIn(trimmed)
        }
    }

    private fun String.boundTitle(): String {
        val trimmed = trim()
        if (trimmed.length <= MaxTitleChars) return trimmed
        return trimmed.take(MaxTitleChars).trimEnd() + "…"
    }

    // === 章节构造 ===

    private fun buildSections(text: String, lines: List<Line>, headings: List<Heading>): List<Section> {
        if (headings.isEmpty()) {
            return listOf(
                Section(
                    title = firstNonBlankLine(text) ?: UntitledChapter,
                    startOffset = 0,
                    endOffset = text.length,
                    level = 0
                )
            )
        }
        val sections = ArrayList<Section>(headings.size + 1)
        val firstHeadingStart = lines[headings.first().lineIndex].start
        if (firstHeadingStart > 0 && text.substring(0, firstHeadingStart).isNotBlank()) {
            sections += Section(
                title = firstNonBlankLine(text.substring(0, firstHeadingStart)) ?: UntitledChapter,
                startOffset = 0,
                endOffset = firstHeadingStart,
                level = 0
            )
        }
        headings.forEachIndexed { index, heading ->
            val start = lines[heading.lineIndex].start
            val end = if (index + 1 < headings.size) {
                lines[headings[index + 1].lineIndex].start
            } else {
                text.length
            }
            val safeEnd = maxOf(end, start)
            val title = heading.title.ifBlank {
                firstNonBlankLine(text.substring(minOf(heading.blockEnd, safeEnd), safeEnd)) ?: UntitledChapter
            }
            sections += Section(title = title, startOffset = start, endOffset = safeEnd, level = heading.level)
        }
        return sections
    }

    private fun firstNonBlankLine(text: String): String? {
        return text.lineSequence()
            .map { it.trim().trimStart('#').trim() }
            .firstOrNull { it.isNotBlank() }
            ?.boundTitle()
    }

    // === 软切 ===

    private fun softSplit(
        text: String,
        lines: List<Line>,
        sections: List<Section>,
        softLimitChars: Int
    ): List<Section> {
        if (softLimitChars <= 0) return sections
        val result = ArrayList<Section>(sections.size)
        sections.forEach { section ->
            if (section.length <= softLimitChars) {
                result += section
                return@forEach
            }
            var cursor = section.startOffset
            var part = 0
            while (cursor < section.endOffset) {
                val hardEnd = minOf(section.endOffset, cursor + softLimitChars)
                val end = if (hardEnd >= section.endOffset) {
                    section.endOffset
                } else {
                    findSoftBoundary(text, lines, cursor, hardEnd)
                }
                val safeEnd = if (end <= cursor) hardEnd else end
                result += Section(
                    title = if (part == 0) section.title else "${section.title}${ContinuationSuffix}${part + 1}）",
                    startOffset = cursor,
                    endOffset = safeEnd,
                    level = if (part == 0) section.level else 0,
                    continued = part > 0
                )
                cursor = safeEnd
                part++
            }
        }
        return result
    }

    /** 在 `(start, hardEnd)` 内回退到最近的空行起点；找不到则硬切于 [hardEnd]。 */
    private fun findSoftBoundary(text: String, lines: List<Line>, start: Int, hardEnd: Int): Int {
        var candidate = -1
        for (line in lines) {
            if (line.start <= start) continue
            if (line.start >= hardEnd) break
            if (text.substring(line.start, line.contentEnd).isBlank()) candidate = line.start
        }
        return if (candidate > start) candidate else hardEnd
    }

    private val DIGIT_LIST = Regex("^\\d+[.)]\\s")
    private const val FenceMinLength = 3
    private const val ContinuationSuffix = "（续"
    private const val UntitledChapter = "全文"
}