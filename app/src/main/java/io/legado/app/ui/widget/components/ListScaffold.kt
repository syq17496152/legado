package io.legado.app.ui.widget.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier

/**
 * 泛型列表 UI 状态（S2 列表通用模板，公共组件库三期）。
 *
 * 承载列表页核心状态：items / selectedIds / searchKey / isSearch / isLoading，
 * 由 [ListScaffold] 消费驱动内容渲染与多选模式。
 * 规格：ui-standards §3.4 `ListScaffold<T>`+`ListUiState<T>`（task 12.30，from 325506）。
 */

/** 列表页通用状态（items 泛型化，selectedIds 以字符串标识跨类型选中）。 */
data class ListUiState<T>(
    val items: List<T> = emptyList(),
    val selectedIds: List<String> = emptyList(),
    val searchKey: String = "",
    val isSearch: Boolean = false,
    val isLoading: Boolean = false,
) {
    val isSelectionMode: Boolean get() = selectedIds.isNotEmpty()
}

/**
 * 列表页通用 Scaffold 模板：顶栏 + 内容区 + 可选多选底栏 + FAB。
 * 内容区由调用方提供（项 ≥48dp 规范由调用方遵守）；多选模式时自动滑入 [selectionBottomBar]。
 */
@Composable
fun <T> ListScaffold(
    uiState: ListUiState<T>,
    topBar: @Composable () -> Unit,
    content: @Composable (state: ListUiState<T>) -> Unit,
    modifier: Modifier = Modifier,
    selectionBottomBar: (@Composable (state: ListUiState<T>) -> Unit)? = null,
    fab: (@Composable () -> Unit)? = null,
) {
    Scaffold(
        topBar = topBar,
        bottomBar = {
            key(uiState.isSelectionMode) {
                if (uiState.isSelectionMode) {
                    selectionBottomBar?.invoke(uiState)
                }
            }
        },
        floatingActionButton = { fab?.invoke() },
        modifier = modifier,
    ) { _ ->
        Box(modifier = Modifier) {
            content(uiState)
        }
    }
}
