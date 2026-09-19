package io.legado.app.ui.config.compose

import androidx.annotation.StringRes

data class SettingPageSpec(
    @param:StringRes val titleRes: Int,
    val sections: List<SettingSectionSpec>
)

data class SettingSectionSpec(
    val title: CharSequence? = null,
    val items: List<SettingItemSpec>
)

sealed interface SettingItemSpec {
    val key: String
    val title: CharSequence
    val summary: CharSequence?
    val visible: Boolean
    val enabled: Boolean
    val searchKeys: List<String>
}

data class SettingSwitchSpec(
    override val key: String,
    override val title: CharSequence,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
    override val summary: CharSequence? = null,
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val searchKeys: List<String> = emptyList()
) : SettingItemSpec

data class SettingChoiceOption(
    val value: String,
    val label: CharSequence,
    val description: CharSequence? = null,
    val iconName: String? = null
)

data class SettingChoiceSpec(
    override val key: String,
    override val title: CharSequence,
    val options: List<SettingChoiceOption>,
    val selectedValue: String,
    val onSelected: (String) -> Unit,
    override val summary: CharSequence? = null,
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val searchKeys: List<String> = emptyList()
) : SettingItemSpec {
    val selectedOption: SettingChoiceOption?
        get() = options.firstOrNull { it.value == selectedValue }

    val selectedLabel: CharSequence
        get() = selectedOption?.label ?: selectedValue
}

data class SettingSliderSpec(
    override val key: String,
    override val title: CharSequence,
    val value: Int,
    val valueRange: IntRange,
    val onValueChange: (Int) -> Unit,
    val step: Int = 1,
    val valueFormatter: (Int) -> String = { it.toString() },
    val onValueChangeFinished: (() -> Unit)? = null,
    override val summary: CharSequence? = null,
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val searchKeys: List<String> = emptyList()
) : SettingItemSpec

data class SettingActionSpec(
    override val key: String,
    override val title: CharSequence,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    override val summary: CharSequence? = null,
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
    override val searchKeys: List<String> = emptyList()
) : SettingItemSpec

/**
 * 页内检索匹配判据（B1.2 优化 1）：key / searchKeys / 标题 / 摘要，忽略大小写。
 *
 * 放在 spec 模型层（而非渲染层）以便 JVM 单测直接覆盖；空查询恒命中（不构成过滤）。
 */
internal fun SettingItemSpec.matchesQuery(query: String): Boolean {
    if (query.isEmpty()) return true
    return title.contains(query, ignoreCase = true) ||
        summary?.contains(query, ignoreCase = true) == true ||
        key.contains(query, ignoreCase = true) ||
        searchKeys.any { it.contains(query, ignoreCase = true) }
}
