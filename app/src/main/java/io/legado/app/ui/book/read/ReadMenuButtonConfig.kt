package io.legado.app.ui.book.read

import android.content.Context
import io.legado.app.constant.PreferKey
import io.legado.app.utils.GSON
import io.legado.app.utils.getPrefString
import io.legado.app.utils.putPrefString

object ReadMenuButtonConfig {

    const val TYPE_BUILTIN = "builtin"
    const val TYPE_CUSTOM = "custom"

    object Builtin {
        const val SEARCH = "search"
        const val AUTO_PAGE = "autoPage"
        const val REPLACE_RULE = "replaceRule"
        const val NIGHT_THEME = "nightTheme"
        const val CATALOG = "catalog"
        const val READ_ALOUD = "readAloud"
        const val READ_STYLE = "readStyle"
        const val SETTING = "setting"
        const val READ_ASSISTANT = "readAssistant"
        const val AI_SUMMARY = "aiSummary"
        const val PARAGRAPH_RULES = "paragraphRules"
        const val CHARACTERS = "characters"
        const val BUBBLE = "bubble"

        val ids = setOf(
            SEARCH,
            AUTO_PAGE,
            REPLACE_RULE,
            NIGHT_THEME,
            CATALOG,
            READ_ALOUD,
            READ_STYLE,
            SETTING,
            READ_ASSISTANT,
            AI_SUMMARY,
            PARAGRAPH_RULES,
            CHARACTERS,
            BUBBLE
        )
    }

    data class ButtonRef(
        val type: String = TYPE_BUILTIN,
        val id: String = "",
        val titleOverride: String = "",
        val iconPath: String = "",
        val nightIconPath: String = ""
    )

    data class ButtonLayout(
        val firstRow: List<ButtonRef> = emptyList(),
        val secondRow: List<ButtonRef> = emptyList()
    )

    fun load(context: Context): ButtonLayout {
        val raw = context.getPrefString(PreferKey.readMenuButtonLayout).orEmpty()
        val parsed = if (raw.isBlank()) null else runCatching {
            GSON.fromJson(raw, ButtonLayout::class.java)
        }.getOrNull()
        val layout = sanitize(parsed ?: defaultLayout())
        return when {
            layout.isMisplacedAiDefault() -> defaultLayout()
            parsed != null && layout.isLegacyDefaultBeforeBubble() -> defaultLayout()
            parsed != null && layout.isDefaultWithBubbleInSecondRow() -> defaultLayout()
            else -> layout
        }
    }

    fun save(context: Context, layout: ButtonLayout) {
        context.putPrefString(PreferKey.readMenuButtonLayout, GSON.toJson(sanitize(layout)))
    }

    fun defaultLayout(): ButtonLayout {
        return ButtonLayout(defaultFirstRow(), defaultSecondRow())
    }

    private fun defaultFirstRow(): List<ButtonRef> {
        return listOf(
            builtin(Builtin.SEARCH),
            builtin(Builtin.AUTO_PAGE),
            builtin(Builtin.REPLACE_RULE),
            builtin(Builtin.NIGHT_THEME),
            builtin(Builtin.CHARACTERS),
            builtin(Builtin.PARAGRAPH_RULES),
            builtin(Builtin.BUBBLE),
            builtin(Builtin.READ_ASSISTANT),
            builtin(Builtin.AI_SUMMARY)
        )
    }

    private fun defaultSecondRow(): List<ButtonRef> {
        return listOf(
            builtin(Builtin.CATALOG),
            builtin(Builtin.READ_ALOUD),
            builtin(Builtin.READ_STYLE),
            builtin(Builtin.SETTING)
        )
    }

    fun builtin(id: String): ButtonRef {
        return ButtonRef(type = TYPE_BUILTIN, id = id)
    }

    private fun sanitize(layout: ButtonLayout): ButtonLayout {
        val first = sanitizeRow(layout.firstRow)
        val second = sanitizeRow(layout.secondRow)
        return ButtonLayout(first, second)
    }

    private fun sanitizeRow(row: List<ButtonRef>): List<ButtonRef> {
        return row.filter { ref ->
            when (ref.type) {
                TYPE_BUILTIN -> ref.id in Builtin.ids
                TYPE_CUSTOM -> ref.id.toLongOrNull() != null
                else -> false
            }
        }
    }

    private fun ButtonLayout.isMisplacedAiDefault(): Boolean {
        return firstRow.map { it.type to it.id } == defaultLegacyFirstRow().map { it.type to it.id } &&
                secondRow.map { it.type to it.id } == defaultAiShortcutRow().map { it.type to it.id }
    }

    private fun defaultLegacyFirstRow(): List<ButtonRef> {
        return listOf(
            builtin(Builtin.SEARCH),
            builtin(Builtin.AUTO_PAGE),
            builtin(Builtin.REPLACE_RULE),
            builtin(Builtin.NIGHT_THEME)
        )
    }

    private fun defaultAiShortcutRow(): List<ButtonRef> {
        return listOf(
            builtin(Builtin.CHARACTERS),
            builtin(Builtin.PARAGRAPH_RULES),
            builtin(Builtin.READ_ASSISTANT),
            builtin(Builtin.AI_SUMMARY)
        )
    }

    private fun ButtonLayout.isLegacyDefaultBeforeBubble(): Boolean {
        return firstRow.map { it.type to it.id } == listOf(
            builtin(Builtin.SEARCH),
            builtin(Builtin.AUTO_PAGE),
            builtin(Builtin.REPLACE_RULE),
            builtin(Builtin.NIGHT_THEME),
            builtin(Builtin.CHARACTERS),
            builtin(Builtin.PARAGRAPH_RULES),
            builtin(Builtin.READ_ASSISTANT),
            builtin(Builtin.AI_SUMMARY)
        ).map { it.type to it.id } &&
                secondRow.map { it.type to it.id } == listOf(
            builtin(Builtin.CATALOG),
            builtin(Builtin.READ_ALOUD),
            builtin(Builtin.READ_STYLE),
            builtin(Builtin.SETTING)
        ).map { it.type to it.id }
    }

    private fun ButtonLayout.isDefaultWithBubbleInSecondRow(): Boolean {
        return firstRow.map { it.type to it.id } == listOf(
            builtin(Builtin.SEARCH),
            builtin(Builtin.AUTO_PAGE),
            builtin(Builtin.REPLACE_RULE),
            builtin(Builtin.NIGHT_THEME),
            builtin(Builtin.CHARACTERS),
            builtin(Builtin.PARAGRAPH_RULES),
            builtin(Builtin.READ_ASSISTANT),
            builtin(Builtin.AI_SUMMARY)
        ).map { it.type to it.id } &&
                secondRow.map { it.type to it.id } == listOf(
            builtin(Builtin.CATALOG),
            builtin(Builtin.READ_ALOUD),
            builtin(Builtin.READ_STYLE),
            builtin(Builtin.BUBBLE),
            builtin(Builtin.SETTING)
        ).map { it.type to it.id }
    }
}
