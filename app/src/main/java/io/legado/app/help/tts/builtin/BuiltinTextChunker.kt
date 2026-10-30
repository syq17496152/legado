package io.legado.app.help.tts.builtin

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

    const val MIN_CHUNK_CHARS = 10
    const val MAX_CHARS_WITHOUT_QUOTE = 50
    const val MAX_CHARS_WITH_QUOTE = 100

    data class Chunk(
        val text: String,
        /** 本块播完后的停顿（最后一块由调用方忽略） */
        val pauseMs: Int
    )

    private val lineBreakChars = setOf('\n')
    private val sentenceEndChars = setOf('。', '！', '？', '!', '?', '；', ';')
    private val commaChars = setOf('，', ',', '、', '：', ':')
    private val quoteChars = setOf('“', '”', '"', '「', '」', '『', '』', '\'')
    private val innerBreakChars = setOf('，', ',', '、', '；', ';', '：', ':', '。', '！', '？', '!', '?')

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

    /** 单块停顿（按块末字符判定） */
    fun pauseOf(chunk: String): Int {
        if (chunk.endsWith("\n")) return PAUSE_NEWLINE_MS
        val last = chunk.trimEnd().lastOrNull() ?: return PAUSE_OTHER_MS
        return when {
            last in sentenceEndChars -> PAUSE_SENTENCE_MS
            last in commaChars -> PAUSE_COMMA_MS
            else -> PAUSE_OTHER_MS
        }
    }

    private fun innerPauseOf(chunk: String): Int {
        val last = chunk.trimEnd().lastOrNull() ?: return PAUSE_OTHER_MS
        return when {
            last in sentenceEndChars -> PAUSE_SENTENCE_MS
            last in commaChars -> PAUSE_COMMA_MS
            else -> PAUSE_OTHER_MS
        }
    }

    private fun rawSplit(text: String): List<String> {
        val result = ArrayList<String>()
        val buffer = StringBuilder()
        text.forEachIndexed { index, c ->
            buffer.append(c)
            val breakHere = when {
                c in lineBreakChars -> true
                c in sentenceEndChars -> true
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
