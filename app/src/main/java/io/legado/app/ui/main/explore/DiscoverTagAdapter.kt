package io.legado.app.ui.main.explore

import io.legado.app.data.entities.rule.ExploreKind

data class DiscoverTagItem(
    val kind: ExploreKind,
    val text: String,
    val role: Role,
    val group: String? = null,
) {
    enum class Role {
        UrlTag,
        GlobalSelect,
        Toggle,
        ActionButton,
        ScriptUrl
    }

    val isButton: Boolean
        get() = role == Role.ActionButton || role == Role.ScriptUrl || role == Role.Toggle
}
