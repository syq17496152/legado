package io.legado.app.ui.widget.components

import io.legado.app.utils.splitNotBlank

/**
 * 搜索 / 发现共用的结果过滤规则（数据契约，task 12.3B，from huajideshutiao）。
 *
 * 规格：ui-standards §3.4 `SourceFilterRule`（task 12.3B）
 * - Field enum 5 种：NAME / AUTHOR / INTRO / KIND / WORD_COUNT
 * - Scope sealed 4 态：All（全部）/ None（无效）/ Source（单源）/ Groups（分组）
 * - 规则字段 OR + 多规则黑名单（任一字段命中即丢弃）
 * - scope 范围协议：空=全部 / 含 `::` = 单源 / CSV = 分组
 *
 * 纯数据契约 + 解析逻辑，**不含 Room 注解**（避免数据库变更）；
 * 接线落库时需转为 @Entity 并走数据库迁移（当前 app v89，见 database-migration-safety.md）。
 */
data class SourceFilterRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val pattern: String = "",
    val fields: String = "",
    val scope: String = "",
    val order: Int = 0,
    val createTime: Long = System.currentTimeMillis(),
) {

    enum class Field { NAME, AUTHOR, INTRO, KIND, WORD_COUNT }

    /** 解析后的作用范围。`None` 表示原串非空但无效，规则不生效。 */
    sealed interface Scope {
        data object All : Scope
        data object None : Scope
        data class Source(val url: String) : Scope
        data class Groups(val names: Set<String>) : Scope
    }

    companion object {

        /** 逗号分隔字段串 → 有效 Field 集合（无效 token 忽略）。 */
        fun parseFields(raw: String): Set<Field> {
            if (raw.isBlank()) return emptySet()
            return raw.splitNotBlank(",").mapNotNullTo(HashSet()) { token ->
                runCatching { Field.valueOf(token.trim()) }.getOrNull()
            }
        }

        fun formatFields(fields: Collection<Field>): String =
            fields.joinToString(",") { it.name }

        /** 解析 scope 字符串：空=全部；含 `::`=单源；CSV=分组。 */
        fun parseScope(raw: String): Scope {
            if (raw.isBlank()) return Scope.All
            if (raw.contains("::")) {
                val url = raw.substringAfter("::", "")
                return if (url.isEmpty()) Scope.None else Scope.Source(url)
            }
            val groups = raw.splitNotBlank(",")
                .mapNotNullTo(HashSet()) { it.trim().takeIf(String::isNotEmpty) }
            return if (groups.isEmpty()) Scope.None else Scope.Groups(groups)
        }
    }
}
