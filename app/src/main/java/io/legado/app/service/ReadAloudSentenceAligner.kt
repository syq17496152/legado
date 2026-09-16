package io.legado.app.service

/**
 * P1/B1-③ 朗读"段中触发 → 对齐句首"工具（纯字符串，无 Android 依赖，可 JVM 单测）。
 *
 * 口径（与设计文档一致，写死避免歧义）：
 * 1. 仅处理 `offset > 0`（段中触发）；
 * 2. 在 `[0, offset)` 内向前查找**最近的句末标点**；
 *    找到 → 起点推进到该标点之后，并跳过紧随的空白与闭引号（属于上一句的收尾符）；
 *    找不到 → **返回原 offset 不变**（长段无标点时不改变既有行为）；
 * 3. 返回值恒满足 `0 <= 返回值 <= offset`（只可能向前对齐，绝不越过原起点，避免"跳过内容"）。
 */
object ReadAloudSentenceAligner {

    /** 句末标点（与项目既有断句口径对齐） */
    private const val SENTENCE_END = "。！？…；!?;"

    /** 闭引号/收尾符（紧跟句末标点，属于上一句） */
    private const val CLOSING_QUOTES = "”’\"』」）】》"

    /**
     * @return 对齐后的段内起点（`<= offset`）；无标点或非法入参时返回原 [offset]
     */
    fun alignToSentenceStart(text: String, offset: Int): Int {
        if (offset <= 0) return 0
        if (text.isEmpty()) return offset
        val limit = offset.coerceAtMost(text.length)
        var i = limit - 1
        while (i >= 0 && text[i] !in SENTENCE_END) i--
        if (i < 0) return offset
        var start = i + 1
        while (start < limit && (text[start].isWhitespace() || text[start] in CLOSING_QUOTES)) start++
        return start.coerceAtMost(limit)
    }
}