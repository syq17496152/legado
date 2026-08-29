package io.legado.app.ui.book.read

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextFormDialog
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.toastOnUi
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 划词搜索引擎管理（View→Compose 迁移）
 * RecyclerView + ItemTouchHelper → LazyColumn + ReorderableItem 拖拽排序；
 * 对外接口不变：构造参数 [onChanged]，增删改/拖拽排序/恢复默认行为语义保持一致。
 */
class SelectionSearchEngineManageDialog(
    private val onChanged: (() -> Unit)? = null
) : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override val dialogWindowAnimations: Int = R.style.AnimDialogFade

    private var engines: List<ContentSelectConfig.SearchEngine> by mutableStateOf(emptyList())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        engines = ContentSelectConfig.searchEngines(requireContext())
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    EngineManageContent()
                }
            }
        }
    }

    private fun resetToDefault() {
        ContentSelectConfig.resetSearchEngines(requireContext())
        engines = ContentSelectConfig.searchEngines(requireContext())
        onChanged?.invoke()
    }

    private fun saveAndRefresh() {
        ContentSelectConfig.saveSearchEngines(requireContext(), engines)
        engines = ContentSelectConfig.searchEngines(requireContext())
        onChanged?.invoke()
    }

    private fun showEditDialog(engine: ContentSelectConfig.SearchEngine?) {
        showComposeTextFormDialog(
            title = getString(if (engine == null) R.string.add else R.string.edit),
            labels = listOf(
                getString(R.string.selection_search_engine_name),
                getString(R.string.selection_search_engine_url_hint),
                getString(R.string.selection_search_engine_hide_css_hint)
            ),
            initialValues = listOf(
                engine?.name.orEmpty(),
                engine?.url.orEmpty(),
                engine?.hideCss.orEmpty()
            ),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            validateInput = { values ->
                val name = values.getOrNull(0)?.trim().orEmpty()
                val url = values.getOrNull(1)?.trim().orEmpty()
                if (name.isBlank() || url.isBlank()) {
                    toastOnUi(R.string.cannot_empty)
                    return@showComposeTextFormDialog false
                }
                if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                    toastOnUi(R.string.selection_search_engine_url_error)
                    return@showComposeTextFormDialog false
                }
                true
            },
            onPositive = { values ->
                val name = values.getOrNull(0)?.trim().orEmpty()
                val url = values.getOrNull(1)?.trim().orEmpty()
                val edited = ContentSelectConfig.SearchEngine(
                    id = engine?.id ?: "engine_${System.currentTimeMillis()}",
                    name = name,
                    url = url,
                    hideCss = values.getOrNull(2)?.trim().orEmpty()
                )
                engines = engines.toMutableList().apply {
                    val index = indexOfFirst { it.id == edited.id }
                    if (index >= 0) {
                        set(index, edited)
                    } else {
                        add(edited)
                    }
                }
                saveAndRefresh()
            }
        )
    }

    private fun confirmDelete(engine: ContentSelectConfig.SearchEngine) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = engine.name,
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                engines = engines.filterNot { it.id == engine.id }
                if (engines.isEmpty()) {
                    engines = ContentSelectConfig.defaultSearchEngines
                }
                saveAndRefresh()
            }
        )
    }

    @Composable
    private fun EngineManageContent() {
        val style = rememberAppDialogStyle()
        val palette = style.toMiuixPalette()
        val lazyListState = rememberLazyListState()
        val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
            engines = engines.toMutableList().apply {
                add(to.index, removeAt(from.index))
            }
        }
        AppDialogFrame(
            title = stringResource(R.string.selection_search_engine_manage),
            scrollContent = false,
            content = {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(items = engines, key = { it.id }) { engine ->
                        ReorderableItem(reorderState, key = engine.id) {
                            EngineRow(
                                engine = engine,
                                style = style,
                                dragHandle = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_drag_handle),
                                        contentDescription = stringResource(R.string.sort),
                                        tint = style.secondaryText,
                                        modifier = Modifier
                                            .size(22.dp)
                                            .draggableHandle(onDragStopped = { saveAndRefresh() })
                                    )
                                },
                                onEdit = { showEditDialog(engine) },
                                onDelete = { confirmDelete(engine) }
                            )
                        }
                    }
                }
            },
            actions = {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.restore_default),
                    palette = palette,
                    onClick = { resetToDefault() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = { dismissAllowingStateLoss() },
                    cornerRadius = style.actionRadius
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.add),
                    palette = palette,
                    onClick = { showEditDialog(null) },
                    primary = true,
                    cornerRadius = style.actionRadius
                )
            }
        )
    }

    @Composable
    private fun EngineRow(
        engine: ContentSelectConfig.SearchEngine,
        style: AppDialogStyle,
        dragHandle: @Composable () -> Unit,
        onEdit: () -> Unit,
        onDelete: () -> Unit
    ) {
        val palette = style.toMiuixPalette()
        LegadoMiuixCard(
            modifier = Modifier.fillMaxWidth(),
            color = style.fieldSurface,
            contentColor = style.primaryText,
            cornerRadius = style.actionRadius,
            insidePadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                dragHandle()
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = engine.name,
                        color = style.primaryText,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = engine.url,
                        color = style.secondaryText,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 5.dp, bottom = 6.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!engine.hideCss.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.selection_search_engine_hide_css_enabled),
                            color = style.accent,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.edit),
                    palette = palette,
                    onClick = onEdit,
                    cornerRadius = style.actionRadius,
                    minWidth = 58.dp,
                    minHeight = 32.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.delete),
                    palette = palette,
                    onClick = onDelete,
                    cornerRadius = style.actionRadius,
                    minWidth = 58.dp,
                    minHeight = 32.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}
