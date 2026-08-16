package io.legado.app.ui.book.read

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 阅读器 UI 状态单源（S5 骨架，AD-03）
 *
 * 轻量 UI 状态：菜单层可见性 / 弹层单态 / 对话框 / 路由栈 / 搜索状态。
 * 业务数据仍由 ReadBookViewModel 承担，本类只做 UI 展示状态，替换散落 mutableBoolean。
 */
data class ReaderUiState(
    val menuVisible: Boolean = false,
    val activeSheet: ReadBookSheet? = null,
    val activeDialog: ReadBookDialog? = null,
    val routeStack: List<ReaderRoute> = emptyList(),
    val searchVisible: Boolean = false,
)

/** 弹层单态枚举（AD-02：任意时刻最多一个 activeSheet） */
sealed interface ReadBookSheet {
    /** 目录/书签双 Tab Sheet（Phase4 已接线 BookTocBookmarkSheet） */
    object Toc : ReadBookSheet
    /** 阅读设置 Sheet（字号/亮度/夜间/行距/对齐 + 扩展翻页/字体） */
    object ReaderMenu : ReadBookSheet
    /** 更多操作 Sheet */
    object More : ReadBookSheet
}

/** 对话框枚举（L2 Dialog 族） */
sealed interface ReadBookDialog {
    object Search : ReadBookDialog
    object AutoRead : ReadBookDialog
    object ReadAloud : ReadBookDialog
}

/** 路由栈条目（Back 链，AD：弹层→搜索→自动翻页→菜单路由→退出） */
sealed interface ReaderRoute {
    object Menu : ReaderRoute
    object AutoPage : ReaderRoute
    object Search : ReaderRoute
}

/** 阅读器 UI 状态持有者（由 ReadBookActivity 持有，非全局单例，避免跨 Activity 状态泄漏） */
class ReaderUiStateHolder {
    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    fun update(transform: (ReaderUiState) -> ReaderUiState) = _state.update(transform)

    fun showMenu() = update { it.copy(menuVisible = true) }
    fun hideMenu() = update { it.copy(menuVisible = false) }

    fun showSheet(sheet: ReadBookSheet) = update { it.copy(activeSheet = sheet) }
    fun dismissSheet(sheet: ReadBookSheet? = null) = update {
        if (sheet == null || it.activeSheet == sheet) it.copy(activeSheet = null) else it
    }

    fun showDialog(dialog: ReadBookDialog) = update { it.copy(activeDialog = dialog) }
    fun dismissDialog() = update { it.copy(activeDialog = null) }

    fun setSearchVisible(visible: Boolean) = update { it.copy(searchVisible = visible) }
}
