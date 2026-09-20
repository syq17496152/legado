package io.legado.app.ui.book.read

import android.content.Context
import androidx.annotation.Keep
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

    // @Keep 铁律（Gson 反射模型，proguard-rules.pro:10 铁证）：R8 收窄后泛型签名丢失会使
    // List<ButtonRef> 反序列化成 List<LinkedTreeMap> ⇒ sanitizeRow 取 .type 时抛
    // ClassCastException ⇒ **打开本页即崩**（真机复现：拖动排序落盘后下次进入必崩）。
    // 项目内所有 Gson 持久化模型一律 @Keep（同 DiscoverySuiteConfig 口径）。
    @Keep
    data class ButtonRef(
        val type: String = TYPE_BUILTIN,
        val id: String = "",
        val titleOverride: String = "",
        val iconPath: String = "",
        val nightIconPath: String = ""
    )

    @Keep
    data class ButtonLayout(
        val firstRow: List<ButtonRef> = emptyList(),
        val secondRow: List<ButtonRef> = emptyList()
    )

    fun load(context: Context): ButtonLayout {
        val raw = context.getPrefString(PreferKey.readMenuButtonLayout).orEmpty()
        val parsed = if (raw.isBlank()) null else runCatching {
            GSON.fromJson(raw, ButtonLayout::class.java)
        }.getOrNull()
        val sanitized = sanitize(parsed ?: defaultLayout())
        // 兜底：解析出的元素被**整体丢弃**（R8 泛型退化/脏数据，见类上 @Keep 注释）⇒ 回落默认布局。
        // 不这么做会得到「两排皆空」的静默数据丢失（菜单按钮区整个消失，比崩页更难排查）。
        val parsedCount = (parsed?.firstRow?.size ?: 0) + (parsed?.secondRow?.size ?: 0)
        val keptCount = sanitized.firstRow.size + sanitized.secondRow.size
        val layout = if (parsedCount > 0 && keptCount == 0) defaultLayout() else sanitized
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

    /**
     * 行清洗。
     *
     * `filterIsInstance` 是**崩溃兜底**（同 ExploreFragment 读旧缓存的口径）：即便拿到的是
     * R8 退化后的 `LinkedTreeMap` 元素（见类上 @Keep 注释），`is` 判断不触发 checkcast ⇒
     * 脏元素被直接丢弃、回落默认布局，**不会让整页崩在 onActivityCreated**。
     */
    private fun sanitizeRow(row: List<ButtonRef>): List<ButtonRef> {
        return row.filterIsInstance<ButtonRef>().filter { ref ->
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
