package io.legado.app.model.localBook.epubcore

/**
 * 安全正则编译：EPUB 规则里的正则来自出版物内容，非法模式会抛异常。
 * 这里把编译失败（含 LinkageError）降级为"永不匹配"的正则，
 * 避免单个坏规则在类初始化期毒化整条解析链。
 *
 * 迁移自 archive v15 `epubcore/EpubRegex.kt`（能力迁移，算法未改）。
 */
internal object EpubRegex {

    fun compile(pattern: String): Regex {
        return compile(pattern, emptySet())
    }

    fun compile(pattern: String, option: RegexOption): Regex {
        return compile(pattern, setOf(option))
    }

    fun compile(pattern: String, options: Set<RegexOption>): Regex {
        return try {
            Regex(pattern, options)
        } catch (_: LinkageError) {
            neverMatch()
        } catch (_: RuntimeException) {
            neverMatch()
        }
    }

    private fun neverMatch(): Regex = Regex("a\\A")
}