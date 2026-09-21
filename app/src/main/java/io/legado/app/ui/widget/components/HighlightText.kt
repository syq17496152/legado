package io.legado.app.ui.widget.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/**
 * 检索命中高亮（单源收口，F30/F31 与 rss/search 结果高亮同源实现）。
 *
 * 语义：把 [query] 在 [text] 中的命中片段标为 [highlight] 色 + 指定字重；[query] 为空
 * （未检索）时**原样返回**，调用方渲染结果与未检索时逐字一致。
 *
 * 实现要点：用 `indexOf(..., ignoreCase = true)` 而非「先 lowercase 再取下标」——
 * 大小写转换在部分语言下会改变字符数，按下标套 span 会越界。
 *
 * 使用约定：新页面一律复用本函数，禁止页内再写一份私有高亮实现（同视觉口径必须单源）。
 */
fun highlightMatches(
    text: CharSequence,
    query: String,
    highlight: Color,
    fontWeight: FontWeight = FontWeight.SemiBold
): AnnotatedString {
    if (query.isBlank() || text.isEmpty()) return AnnotatedString(text.toString())
    val source = text.toString()
    return buildAnnotatedString {
        var cursor = 0
        while (cursor < source.length) {
            val hit = source.indexOf(query, cursor, ignoreCase = true)
            if (hit < 0) {
                append(source.substring(cursor))
                break
            }
            append(source.substring(cursor, hit))
            withStyle(SpanStyle(color = highlight, fontWeight = fontWeight)) {
                append(source.substring(hit, hit + query.length))
            }
            cursor = hit + query.length
        }
    }
}
