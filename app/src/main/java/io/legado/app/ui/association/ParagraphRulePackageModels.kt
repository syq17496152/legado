package io.legado.app.ui.association

import io.legado.app.data.entities.ParagraphRule

internal const val MAX_PARAGRAPH_RULE_NAME_CHARS = 200

enum class ParagraphRuleConflictStrategy {
    RENAME,
    SKIP,
    OVERWRITE
}

data class ParagraphRuleImportEntry(
    val rule: ParagraphRule,
    val vars: Map<String, String> = emptyMap(),
    val varsIncluded: Boolean = false,
    val exportId: String? = null
)

data class ParagraphRuleImportPackage(
    val entries: List<ParagraphRuleImportEntry>
)

data class ParagraphRuleImportResult(
    val inserted: Int,
    val overwritten: Int,
    val skipped: Int,
    val renamed: Int
)

data class ParagraphRuleImportInspection(
    val packageData: ParagraphRuleImportPackage,
    val conflictCount: Int
)
