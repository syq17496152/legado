package io.legado.app.help.tts.builtin

/**
 * 送端侧模型前的**可朗读字符清洗**（2026-11-10 真机「每句结尾多读一个耶」根因修复）。
 *
 * 根因（本机 1.13.1 同模型 ASR 实测）：Kokoro 中文前端对**词表外符号**（`～` `#` `*` `·` `☆`
 * 零宽字符等）不是静默丢弃，而是映射成未知音素并由模型补一个**音节**（听感为“耶/的/道”）。
 * 网文大量以这类装饰符号结尾 ⇒ 每个 chunk（切分块）末尾都多读一个字。
 *
 * 口径：
 * - 只保留「可朗读字符」：CJK/字母/数字、空白、以及模型词表内或实测不发声的中文句读；
 * - 括号类（`（）【】」` 等）实测同样会补音节，**一律剔除**（语义由标点/停顿承担）；
 * - 纯符号文本清洗后为空 ⇒ 调用方应跳过该块（不产生无意义音频）。
 *
 * 纯函数（JVM 可测），不改正文显示，只作用于送引擎文本。
 */
object BuiltinSpeechTextSanitizer {

    /**
     * 可保留的标点：中文句读 + 英文句读 + 引号。
     * 依据：同模型实测这些标点不产生额外音节；括号/装饰符号会产生。
     */
    private const val SAFE_PUNCT = "。，、；：！？…—,.!?;:\"'“”‘’"

    /** 清洗；返回只含可朗读字符的文本（可能为空串） */
    fun sanitize(text: String): String {
        if (text.isEmpty()) return text
        val out = StringBuilder(text.length)
        text.forEach { c ->
            if (isSpeakable(c)) out.append(c)
        }
        return out.toString()
    }

    /** 被剔除的字符数（供诊断日志计数，不记录内容） */
    fun removedCount(text: String): Int {
        if (text.isEmpty()) return 0
        var removed = 0
        text.forEach { c -> if (!isSpeakable(c)) removed++ }
        return removed
    }

    private fun isSpeakable(c: Char): Boolean =
        c.isLetterOrDigit() || c.isWhitespace() || c in SAFE_PUNCT
}
