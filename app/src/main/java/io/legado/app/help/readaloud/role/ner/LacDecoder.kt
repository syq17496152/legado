package io.legado.app.help.readaloud.role.ner

/**
 * LAC 标签解码（纯逻辑，JVM 可直接单测）。
 *
 * ONNX 模型输出的是每个字的发射分数（emissions），CRF 转移矩阵决定相邻标签的合法性；
 * 先用 [viterbi] 解出最优标签序列，再按 BIO 约定合并成词元（词 + 词性/NER 标签 + 字符区间）。
 *
 * 口径与 LAC 官方 Python 实现（lac-onnx/lac.py）保持一致：
 * - `-I` 标签并入当前词，其它标签开新词；
 * - 词元标签取 `-B`/`-I` 前缀之前的部分（PER/LOC/ORG/TIME/n/v…）。
 */
object LacDecoder {

    data class Token(
        val word: String,
        val label: String,
        val start: Int,
        val end: Int
    )

    /**
     * Viterbi 解码：返回每个字符位置的最优标签 id。
     *
     * @param emissions [seqLen][tagCount] 发射分数
     * @param transitions [tagCount][tagCount] CRF 转移分数
     */
    fun viterbi(
        emissions: Array<FloatArray>,
        transitions: Array<FloatArray>,
        seqLen: Int
    ): IntArray {
        if (seqLen <= 0 || emissions.isEmpty() || emissions[0].isEmpty()) return IntArray(0)
        val tagCount = emissions[0].size
        var dp = emissions[0].copyOf(tagCount)
        val back = Array(seqLen) { IntArray(tagCount) }
        for (t in 1 until seqLen) {
            val next = FloatArray(tagCount)
            val emit = emissions[t]
            for (j in 0 until tagCount) {
                var best = Float.NEGATIVE_INFINITY
                var bestTag = 0
                for (i in 0 until tagCount) {
                    val score = dp[i] + transitions[i][j]
                    if (score > best) {
                        best = score
                        bestTag = i
                    }
                }
                next[j] = best + emit[j]
                back[t][j] = bestTag
            }
            dp = next
        }
        var best = Float.NEGATIVE_INFINITY
        var bestTag = 0
        for (j in 0 until tagCount) {
            if (dp[j] > best) {
                best = dp[j]
                bestTag = j
            }
        }
        val path = IntArray(seqLen)
        path[seqLen - 1] = bestTag
        for (t in seqLen - 1 downTo 1) {
            path[t - 1] = back[t][path[t]]
        }
        return path
    }

    /** BIO 合并：`-I` 并入当前词，否则开新词；未知标签按单字词元处理 */
    fun decodeTokens(
        text: String,
        tagIds: IntArray,
        idToTag: Map<Int, String>
    ): List<Token> {
        if (text.isEmpty()) return emptyList()
        val tokens = ArrayList<Token>()
        var start = -1
        var end = -1
        var label = ""
        val word = StringBuilder()

        fun flush() {
            if (start >= 0) tokens.add(Token(word.toString(), label, start, end))
            start = -1
            end = -1
            label = ""
            word.setLength(0)
        }

        for (i in text.indices) {
            val raw = idToTag[tagIds.getOrElse(i) { -1 }].orEmpty()
            if (raw.endsWith("-I") && start >= 0) {
                word.append(text[i])
                end = i + 1
            } else {
                flush()
                start = i
                end = i + 1
                label = if (raw.endsWith("-B")) raw.dropLast(2) else ""
                word.append(text[i])
            }
        }
        flush()
        return tokens
    }
}
