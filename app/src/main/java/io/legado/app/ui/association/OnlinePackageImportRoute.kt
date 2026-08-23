package io.legado.app.ui.association

import java.util.Locale

sealed interface OnlinePackageImportRoute {

    data class ParagraphRule(val sourceUrl: String) : OnlinePackageImportRoute

    data class Bubble(val sourceUrl: String) : OnlinePackageImportRoute

    data class Invalid(val reason: String) : OnlinePackageImportRoute

    data object Other : OnlinePackageImportRoute

    companion object {
        private val supportedSchemes = setOf("legado", "yuedu")
        private val paragraphPaths = setOf("/paragraphrule", "/paragraphrules")

        fun parse(
            scheme: String?,
            host: String?,
            path: String?,
            sourceUrl: String?
        ): OnlinePackageImportRoute {
            if (scheme?.lowercase(Locale.ROOT) !in supportedSchemes) return Other
            val normalizedPath = path?.lowercase(Locale.ROOT) ?: return Other
            val target = when (normalizedPath) {
                in paragraphPaths -> Target.PARAGRAPH_RULE
                "/bubble" -> Target.LEGACY_BUBBLE
                "/bubblepackage" -> Target.BUBBLE
                else -> return Other
            }
            if (target != Target.LEGACY_BUBBLE && !host.equals("import", ignoreCase = true)) {
                return Invalid("Import link host must be import")
            }
            val source = sourceUrl.orEmpty()
            if (source.isBlank()) {
                return Invalid("Import link is missing src")
            }
            return when (target) {
                Target.PARAGRAPH_RULE -> ParagraphRule(source)
                Target.BUBBLE,
                Target.LEGACY_BUBBLE -> Bubble(source)
            }
        }

        private enum class Target {
            PARAGRAPH_RULE,
            BUBBLE,
            LEGACY_BUBBLE
        }
    }
}
