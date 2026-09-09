package io.legado.app.help.readaloud.casting

import io.legado.app.constant.AppLog
import java.util.regex.Pattern

/**
 * 分段结果五元组（§3.5.2② 分段契约）
 * paragraphIndex=所属段落坐标（与朗读段落系对齐）；offset/length=段内偏移与长度（精确字符账）
 */
data class CastingSegment(
    val paragraphIndex: Int,
    val offsetInParagraph: Int,
    val length: Int,
    val tag: String
)

/**
 * 分段规则执行器（§3.5.1/§1.8-D-2）
 * - builtin_quote：内置引号规则（中英文引号集显式枚举；嵌套=外层优先；跨段未闭合=段内闭合）
 * - regex：Pattern 构造时预编译校验（防用户正则卡 IO 主链）
 * - keyword：关键词包含判定
 * - 匹配语义：规则数组顺序首命中生效（order 优先级）+匹配区间互斥先到先得
 * - 输出不变量：所有分段按 offset 排序、互不重叠、并集覆盖 [0,paragraph.length)
 */
object TtsTagSplitter {

    // 中英文引号集显式枚举（§1.8-D-2 任务边界）
    private val QUOTE_PAIRS = arrayOf(
        '\u201C' to '\u201D', // “ ”
        '\u300C' to '\u300D', // 「 」
        '\u2018' to '\u2019', // ‘ ’
        '\uFF02' to '\uFF02', // ＂ ＂（全角引号同形）
        '"' to '"'
    )

    private val compiledPatternCache = HashMap<String, Pattern?>()

    @Synchronized
    private fun compiledPattern(pattern: String): Pattern? {
        if (compiledPatternCache.containsKey(pattern)) return compiledPatternCache[pattern]
        val compiled = runCatching { Pattern.compile(pattern) }.getOrNull()
        compiledPatternCache[pattern] = compiled
        return compiled
    }

    /**
     * 对单段落执行模板规则，产出 (text,tag,paragraphIndex,offsetInParagraph,length) 序列。
     * rules 为空/全部不匹配 → 整段回落 narration（旁白兜底语义，保证并集覆盖）。
     */
    fun splitParagraph(
        paragraphIndex: Int,
        paragraph: String,
        rules: List<CastingRule>
    ): List<CastingSegment> {
        if (paragraph.isEmpty()) return emptyList()
        val bounds = mutableListOf<Triple<Int, Int, String>>() // start,end,tag
        val used = BooleanArray(paragraph.length)
        for (rule in rules) {
            when (rule.matchType) {
                CastingMatchType.BUILTIN_QUOTE -> collectQuotes(paragraph, used, rule.tag, bounds)
                CastingMatchType.REGEX -> collectRegex(paragraph, used, rule, bounds)
                CastingMatchType.KEYWORD -> collectKeyword(paragraph, used, rule, bounds)
            }
        }
        if (bounds.isEmpty()) {
            return listOf(CastingSegment(paragraphIndex, 0, paragraph.length, CastingTag.NARRATION))
        }
        bounds.sortBy { it.first }
        // 未覆盖区间回落 narration（并集覆盖不变量）
        val segments = mutableListOf<CastingSegment>()
        var cursor = 0
        for ((start, end, tag) in bounds) {
            if (start > cursor) {
                segments.add(CastingSegment(paragraphIndex, cursor, start - cursor, CastingTag.NARRATION))
            }
            segments.add(CastingSegment(paragraphIndex, start, end - start, tag))
            cursor = end
        }
        if (cursor < paragraph.length) {
            segments.add(CastingSegment(paragraphIndex, cursor, paragraph.length - cursor, CastingTag.NARRATION))
        }
        return segments
    }

    /** 引号规则：区间互斥（已被高优先级规则占用的字符不再纳入） */
    private fun collectQuotes(
        paragraph: String,
        used: BooleanArray,
        tag: String,
        bounds: MutableList<Triple<Int, Int, String>>
    ) {
        var openChar: Char? = null
        var openIndex = -1
        for (i in paragraph.indices) {
            val c = paragraph[i]
            if (openChar == null) {
                val close = QUOTE_PAIRS.firstOrNull { it.first == c }?.second
                if (close != null && !used[i]) {
                    openChar = close
                    openIndex = i
                }
            } else if (c == openChar && !used[i]) {
                // 跨段未闭合=段内闭合语义：openIndex 有值且找到配对才成段
                for (j in openIndex..i) used[j] = true
                bounds.add(Triple(openIndex, i + 1, tag))
                openChar = null
                openIndex = -1
            }
        }
        // 段内未闭合：不跨段延伸（段内闭合语义），开放引号区间保持未标记→回落旁白
    }

    private fun collectRegex(
        paragraph: String,
        used: BooleanArray,
        rule: CastingRule,
        bounds: MutableList<Triple<Int, Int, String>>
    ) {
        val pattern = compiledPattern(rule.pattern) ?: return
        // ReDoS 读预算熔断：灾难性回溯（如 (a+)+$）在读超预算时抛出，该规则跳过落旁白兜底
        val matcher = pattern.matcher(BoundedCharSequence(paragraph))
        runCatching {
            while (matcher.find()) {
                val start = matcher.start()
                val end = matcher.end()
                if (end <= start) continue
                if ((start until end).any { used[it] }) continue
                for (j in start until end) used[j] = true
                bounds.add(Triple(start, end, rule.tag))
            }
        }.onFailure {
            AppLog.put("TTS 规则正则执行超读预算，跳过该规则（tag=${rule.tag}）：${it.message}")
        }
    }

    /**
     * ReDoS 读预算包装（§3.2-15）：matcher 读取字符计数超阈值即抛出打断回溯
     * （JVM Matcher 不响应线程中断，读预算是确定性熔断手段；阈值覆盖正常段落匹配的数百倍余量）
     */
    private class BoundedCharSequence(
        private val inner: CharSequence,
        private var budget: Int = READ_BUDGET
    ) : CharSequence {

        override val length: Int get() = inner.length

        override fun get(index: Int): Char {
            if (--budget < 0) {
                throw io.legado.app.exception.NoStackTraceException("正则匹配超读预算")
            }
            return inner[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            return BoundedCharSequence(inner.subSequence(startIndex, endIndex), budget)
        }

        override fun toString(): String = inner.toString()

        companion object {
            private const val READ_BUDGET = 200_000
        }
    }

    private fun collectKeyword(
        paragraph: String,
        used: BooleanArray,
        rule: CastingRule,
        bounds: MutableList<Triple<Int, Int, String>>
    ) {
        if (rule.pattern.isBlank()) return
        var index = paragraph.indexOf(rule.pattern)
        while (index >= 0) {
            val start = index
            val end = index + rule.pattern.length
            if ((start until end).none { used[it] }) {
                for (j in start until end) used[j] = true
                bounds.add(Triple(start, end, rule.tag))
            }
            index = paragraph.indexOf(rule.pattern, end)
        }
    }

    /** 对白段剥离引号字符（§1.7 对白引号不读出，正文显示保留） */
    fun stripQuotesForSpeech(text: String): String {
        return text.filter { c -> QUOTE_PAIRS.none { it.first == c || it.second == c } }
    }
}
