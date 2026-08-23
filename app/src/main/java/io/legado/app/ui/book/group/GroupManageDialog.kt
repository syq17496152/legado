package io.legado.app.ui.book.group

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookGroup
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers.IO
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class GroupManageDialog : ComposeDialogFragment() {

    private val viewModel: GroupViewModel by viewModels()

    override val dialogSize: AppDialogSize = AppDialogSize.Management
    override val dialogWindowAnimations: Int = R.style.AnimDialogFade

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    val groups by appDb.bookGroupDao.flowAll()
                        .catch { }
                        .flowOn(IO)
                        .conflate()
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    GroupManageContent(
                        groups = groups,
                        onAddGroup = {
                            if (appDb.bookGroupDao.canAddGroup) {
                                showDialogFragment(GroupEditDialog())
                            } else {
                                requireContext().toastOnUi("分组已达上限(64个)")
                            }
                        },
                        onEditGroup = { group ->
                            showDialogFragment(GroupEditDialog(group))
                        },
                        onToggleShow = { group ->
                            viewModel.upGroup(group.copy(show = !group.show))
                        },
                        onReorder = viewModel::upOrder,
                        onDismiss = { dismiss() }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupManageContent(
    groups: List<BookGroup>,
    onAddGroup: () -> Unit,
    onEditGroup: (BookGroup) -> Unit,
    onToggleShow: (BookGroup) -> Unit,
    onReorder: (List<BookGroup>) -> Unit,
    onDismiss: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    val context = LocalContext.current
    val lazyListState = rememberLazyListState()
    val groupsSnapshot = groups.toList()
    val groupsSignature = groupsSnapshot.joinToString(separator = "\u001F") { it.toString() }
    var orderedGroups by remember {
        mutableStateOf(groupsSnapshot, referentialEqualityPolicy())
    }
    LaunchedEffect(groupsSignature) {
        orderedGroups = groupsSnapshot
    }
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        orderedGroups = orderedGroups.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
    }
    AppDialogFrame(
        title = stringResource(R.string.group_manage),
        scrollContent = false,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.add_group),
                        palette = palette,
                        onClick = onAddGroup,
                        cornerRadius = style.actionRadius
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                ) {
                    items(orderedGroups, key = { it.groupId }) { group ->
                        ReorderableItem(reorderState, key = group.groupId) {
                            GroupManageRow(
                                group = group,
                                onEdit = { onEditGroup(group) },
                                onToggleShow = { onToggleShow(group) },
                                style = style,
                                displayName = group.getManageName(context),
                                dragHandle = {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_drag_handle),
                                        contentDescription = stringResource(R.string.sort),
                                        tint = style.secondaryText,
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(22.dp)
                                            .draggableHandle(
                                                onDragStopped = { onReorder(orderedGroups) }
                                            )
                                    )
                                }
                            )
                        }
                    }
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = onDismiss,
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun GroupManageRow(
    group: BookGroup,
    onEdit: () -> Unit,
    onToggleShow: () -> Unit,
    style: io.legado.app.ui.widget.compose.AppDialogStyle,
    displayName: String,
    dragHandle: @Composable () -> Unit
) {
    LegadoMiuixCard(
        modifier = Modifier.fillMaxWidth(),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 12.dp,
            vertical = 8.dp
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            dragHandle()
            Text(
                text = displayName,
                color = style.primaryText,
                fontFamily = style.bodyFontFamily,
                modifier = Modifier.weight(1f)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LegadoMiuixSwitch(
                    checked = group.show,
                    onCheckedChange = { onToggleShow() },
                    palette = style.toMiuixPalette()
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(onClick = onEdit) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.edit),
                        tint = style.accent
                    )
                }
            }
        }
    }
}
