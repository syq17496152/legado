package io.legado.app.help.tts.builtin

import java.io.File

/**
 * 切分与停顿（AD-19，参数取自 CloneTTS 反编译实测，非拍脑袋）：
 * - 切分点：换行 / 句末标点 / 非英数环境下的英文句点
 * - 停顿四档：换行 420ms / 句末 280ms / 逗号类 120ms / 其他 100ms
 * - 短块 <10 字与下一块合并（防碎句）
 * - 超长再切：无引号 >50 字、含引号 >100 字；或按预估帧数上限（frameCapChars）
 * - 引导语粘合：以「…：」结尾且下一块以引号开头 → 合并（cue 不和对白分离，直接影响归因准确率）
 */
object BuiltinTextChunker {

    const val PAUSE_NEWLINE_MS = 420
    const val PAUSE_SENTENCE_MS = 280
    const val PAUSE_COMMA_MS = 120
    const val PAUSE_OTHER_MS = 100
    // 「好听度」A1（offline-tts-llm-productization 3.8）：语义停顿。
    // 旧实现只按「句末/逗号」二分，省略号与破折号被当成普通停顿 ⇒ 该有余味的地方被念得太赶、
    // 该"打断"的地方没有中断感。三档取值来自朗读节奏经验（非拍脑袋：均与 CloneTTS 实测的
    // 四档基线保持同量级，只在语义更强的位置延长 40~80ms）。
    const val PAUSE_ELLIPSIS_MS = 360
    const val PAUSE_DASH_MS = 340
    const val PAUSE_DIALOGUE_END_MS = 220

    const val MIN_CHUNK_CHARS = 10
    const val MAX_CHARS_WITHOUT_QUOTE = 50
    const val MAX_CHARS_WITH_QUOTE = 100

    /** 自定义规则上限（防用户文件写爆：条数与单条长度都限，避免 ReDoS） */
    const val MAX_RULES = 20
    const val MAX_RULE_LENGTH = 200

    /**
     * 读取自定义切分规则（AD-19⑦，CloneTTS 同口径）：按行解析，空行与 `#` 开头的行忽略，
     * 非法正则/超长正则跳过（不让一条坏规则拖垮整章朗读）。
     */
    fun loadRules(file: File?): List<Regex> {
        if (file == null || !file.isFile) return emptyList()
        return runCatching {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .take(MAX_RULES)
                .mapNotNull { line ->
                    line.takeIf { it.length <= MAX_RULE_LENGTH }
                        ?.let { runCatching { Regex(it) }.getOrNull() }
                }
        }.getOrDefault(emptyList())
    }

    data class Chunk(
        val text: String,
        /** 本块播完后的停顿（最后一块由调用方忽略） */
        val pauseMs: Int
    )

    private val lineBreakChars = setOf('\n')
    private val sentenceEndChars = setOf('。', '！', '？', '!', '?', '；', ';')
    private val commaChars = setOf('，', ',', '、', '：', ':')
    private val quoteChars = setOf('“', '”', '"', '「', '」', '『', '』', '\'')
    /** 对白收束引号（A1：对白结束 = 换人，需要一点间隔） */
    private val closingQuoteChars = setOf('”', '」', '』')
    private val innerBreakChars = setOf('，', ',', '、', '；', ';', '：', ':', '。', '！', '？', '!', '?', '…', '—')

    fun split(
        text: String,
        customRules: List<Regex> = emptyList(),
        frameCapChars: Int = 0
    ): List<Chunk> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (normalized.isEmpty()) return emptyList()
        val raw = if (customRules.isEmpty()) rawSplit(normalized) else rawSplitByRules(normalized, customRules)
        val merged = mergeCueWithQuote(mergeShortChunks(raw))
        val result = ArrayList<Chunk>(merged.size)
        merged.forEach { piece ->
            val pieces = splitOverlong(piece, frameCapChars)
            pieces.forEachIndexed { index, part ->
                val isLastOfPiece = index == pieces.lastIndex
                result.add(
                    Chunk(
                        text = part,
                        pauseMs = if (isLastOfPiece) pauseOf(piece) else innerPauseOf(part)
                    )
                )
            }
        }
        return result.filter { it.text.isNotBlank() }.let { chunks ->
            chunks.mapIndexed { index, chunk ->
                if (index == chunks.lastIndex) chunk.copy(pauseMs = 0) else chunk
            }
        }
    }

    /**
     * 语义停顿（A1）：按块末字符判定。
     * 优先级 = 省略号（余味）> 破折号（打断）> 对白收束引号（换人）> 句末 > 逗号 > 其他。
     * 说明：块末是收束引号时一律取对白档 —— 对白结束的核心语义是「换人」，
     * 引号内的句号/省略号已被切分成内层块，不应再把换人停顿拉长到省略号档。
     */
    fun semanticPause(text: String): Int {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return PAUSE_OTHER_MS
        if (trimmed.endsWith("…") || trimmed.endsWith("...")) return PAUSE_ELLIPSIS_MS
        if (trimmed.endsWith("—")) return PAUSE_DASH_MS
        val last = trimmed.last()
        if (last in closingQuoteChars) return PAUSE_DIALOGUE_END_MS
        return when {
            last in sentenceEndChars -> PAUSE_SENTENCE_MS
            last in commaChars -> PAUSE_COMMA_MS
            else -> PAUSE_OTHER_MS
        }
    }

    /** 单块停顿（按块末字符判定；换行单独一档） */
    fun pauseOf(chunk: String): Int {
        if (chunk.endsWith("\n")) return PAUSE_NEWLINE_MS
        return semanticPause(chunk)
    }

    private fun innerPauseOf(chunk: String): Int = semanticPause(chunk)

    private fun rawSplit(text: String): List<String> {
        val result = ArrayList<String>()
        val buffer = StringBuilder()
        text.forEachIndexed { index, c ->
            buffer.append(c)
            val breakHere = when {
                c in lineBreakChars -> true
                c in sentenceEndChars -> true
                // A1 语义断点：省略号（余味）与破折号（打断）也是真实朗读停顿点
                c == '…' || c == '—' -> true
                c == '.' -> !isAsciiAlnum(text.getOrNull(index - 1)) && !isAsciiAlnum(text.getOrNull(index + 1))
                else -> false
            }
            // 换行后的空白不应成为独立块
            if (breakHere) {
                result.add(buffer.toString())
                buffer.clear()
            }
        }
        if (buffer.isNotBlank()) result.add(buffer.toString())
        return result.filter { it.isNotBlank() }
    }

    private fun rawSplitByRules(text: String, rules: List<Regex>): List<String> {
        var pieces = listOf(text)
        rules.forEach { rule ->
            pieces = pieces.flatMap { piece -> rule.split(piece) }
        }
        return pieces.filter { it.isNotBlank() }
    }

    private fun mergeShortChunks(source: List<String>): List<String> {
        val result = ArrayList<String>()
        var buffer = ""
        source.forEach { piece ->
            val candidate = buffer + piece
            buffer = if (candidate.trim().length < MIN_CHUNK_CHARS) candidate else {
                result.add(candidate)
                ""
            }
        }
        if (buffer.isNotBlank()) {
            if (result.isNotEmpty() && buffer.trim().length < MIN_CHUNK_CHARS) {
                result[result.lastIndex] = result.last() + buffer
            } else {
                result.add(buffer)
            }
        }
        return result
    }

    private fun mergeCueWithQuote(source: List<String>): List<String> {
        val result = ArrayList<String>()
        var pending: String? = null
        source.forEach { piece ->
            val current = pending?.let { it + piece } ?: piece
            val trimmed = current.trimEnd()
            val endsWithCue = trimmed.endsWith('：') || trimmed.endsWith(':')
            val nextStartsQuote = piece.trimStart().firstOrNull() in quoteChars
            pending = if (endsWithCue && !nextStartsQuote) current else null
            if (pending == null) result.add(current)
        }
        pending?.let { result.add(it) }
        return result
    }

    private fun splitOverlong(piece: String, frameCapChars: Int): List<String> {
        val limit = when {
            frameCapChars > 0 -> frameCapChars
            piece.any { it in quoteChars } -> MAX_CHARS_WITH_QUOTE
            else -> MAX_CHARS_WITHOUT_QUOTE
        }
        if (piece.length <= limit) return listOf(piece)
        val result = ArrayList<String>()
        var remaining = piece
        while (remaining.length > limit) {
            val window = remaining.substring(0, limit)
            val cut = window.indexOfLast { it in innerBreakChars }
                .takeIf { it >= MIN_CHUNK_CHARS }
                ?: limit
            result.add(remaining.substring(0, cut))
            remaining = remaining.substring(cut)
            if (remaining.trim().isEmpty()) break
        }
        if (remaining.isNotBlank()) result.add(remaining)
        return result
    }

    private fun isAsciiAlnum(c: Char?): Boolean {
        if (c == null) return false
        return c.isLetter() && c.code < 128 || c.isDigit() && c.code < 128
    }
}
