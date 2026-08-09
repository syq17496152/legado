package io.legado.app.help.book

import io.legado.app.constant.AppPattern

/**
 * 特殊内容保护器
 * 在替换净化等可能破坏格式的流程前，将特殊内容（usehtml/img/newpage）用 PUA 占位符保护，
 * 流程结束后还原，保证格式块完整。
 */
object SpecialContentProtector {

    const val MARKER_PREFIX = "\uE000LEGADO_SPECIAL_"
    const val USEHTML_MARKER_PREFIX = "\uE000LEGADO_USEHTML_"

    private val imgRegex = Regex("""<img\b[^>]*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val newPageRegex = Regex("""(?m)^\s*\[newpage]\s*$""")

    fun protect(content: String): ProtectedContent {
        val placeholders = linkedMapOf<String, String>()
        var useHtmlCount = 0
        var imgCount = 0
        var newPageCount = 0
        var protected = content
        fun reserve(value: String): String {
            val key = "$MARKER_PREFIX${placeholders.size}\uE001"
            placeholders[key] = value
            return key
        }
        protected = AppPattern.useHtmlRegex.replace(protected) { matchResult ->
            useHtmlCount++
            reserve(matchResult.value)
        }
        protected = imgRegex.replace(protected) { matchResult ->
            imgCount++
            reserve(matchResult.value)
        }
        protected = newPageRegex.replace(protected) { matchResult ->
            newPageCount++
            reserve(matchResult.value)
        }
        return ProtectedContent(protected, placeholders, useHtmlCount, imgCount, newPageCount)
    }

    data class ProtectedContent(
        val content: String,
        private val placeholders: Map<String, String>,
        val useHtmlCount: Int = 0,
        val imgCount: Int = 0,
        val newPageCount: Int = 0
    ) {
        fun restore(value: String): String {
            var restored = value
            placeholders.forEach { (placeholder, original) ->
                restored = restored.replace(placeholder, original)
            }
            return restored
        }
    }

    private val placeholderRegex = Regex("\uE000LEGADO_SPECIAL_\\d+\uE001")

    fun residualCount(value: String): Int {
        return placeholderRegex.findAll(value).count()
    }

    /**
     * 残留占位符快速检测，用于兜底校验
     */
    fun hasResidual(value: String): Boolean {
        return value.contains(MARKER_PREFIX) || value.contains(USEHTML_MARKER_PREFIX)
    }
}
