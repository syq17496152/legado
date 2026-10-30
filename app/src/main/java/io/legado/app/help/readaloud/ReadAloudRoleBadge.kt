package io.legado.app.help.readaloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 当前段角色徽章状态（T8.7）：朗读服务逐段写入角色名（旁白/张三…），阅读页浮层订阅显示。
 * 放在 help 层（服务可写、UI 可读）；只做"展示态"，不参与归因决策；空串=隐藏。
 */
object ReadAloudRoleBadge {

    private val mutable = MutableStateFlow("")
    val state: StateFlow<String> = mutable.asStateFlow()

    /** 写入角色名（空白视为清除，避免浮层残留上一句） */
    fun update(label: String) {
        mutable.value = label.trim()
    }

    fun clear() = update("")

    /** 浮层文案（纯函数可单测）：空标签 → 空串（隐藏） */
    fun text(label: String): String = if (label.isBlank()) "" else "当前：${label.trim()}"
}
