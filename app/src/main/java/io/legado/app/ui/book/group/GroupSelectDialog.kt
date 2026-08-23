package io.legado.app.ui.book.group

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ComposeView
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
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.showDialogFragment
import kotlinx.coroutines.flow.conflate

class GroupSelectDialog() : ComposeDialogFragment() {

    constructor(groupId: Long, requestCode: Int = -1) : this() {
        arguments = Bundle().apply {
            putLong("groupId", groupId)
            putInt("requestCode", requestCode)
        }
    }

    private val viewModel: GroupViewModel by viewModels()
    private var requestCode: Int = -1
    private val callBack get() = (activity as? CallBack)

    override val dialogSize: AppDialogSize = AppDialogSize.Form
    override val dialogGravity: Int = Gravity.CENTER
    override val dialogWindowAnimations: Int = R.style.AnimDialogCenter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val initialGroupId = arguments?.getLong("groupId") ?: 0L
        requestCode = arguments?.getInt("requestCode", -1) ?: -1
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    val groups by appDb.bookGroupDao.flowSelect().conflate()
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    var selectedGroupId by remember { mutableLongStateOf(initialGroupId) }
                    GroupSelectContent(
                        groups = groups,
                        selectedGroupId = selectedGroupId,
                        onGroupToggle = { group ->
                            selectedGroupId = if ((selectedGroupId and group.groupId) > 0) {
                                selectedGroupId - group.groupId
                            } else {
                                selectedGroupId + group.groupId
                            }
                        },
                        onAddGroup = {
                            showDialogFragment(GroupEditDialog())
                        },
                        onEditGroup = { group ->
                            showDialogFragment(GroupEditDialog(group))
                        },
                        onConfirm = {
                            callBack?.upGroup(requestCode, selectedGroupId)
                            dismissAllowingStateLoss()
                        },
                        onCancel = { dismiss() }
                    )
                }
            }
        }
    }

    interface CallBack {
        fun upGroup(requestCode: Int, groupId: Long)
    }
}

@Composable
private fun GroupSelectContent(
    groups: List<BookGroup>,
    selectedGroupId: Long,
    onGroupToggle: (BookGroup) -> Unit,
    onAddGroup: () -> Unit,
    onEditGroup: (BookGroup) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        title = stringResource(R.string.group_select),
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
                ) {
                    items(groups, key = { it.groupId }) { group ->
                        GroupSelectRow(
                            group = group,
                            isSelected = (selectedGroupId and group.groupId) > 0,
                            onToggle = { onGroupToggle(group) },
                            onEdit = { onEditGroup(group) },
                            style = style
                        )
                    }
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.cancel),
                palette = palette,
                onClick = onCancel,
                cornerRadius = style.actionRadius
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.ok),
                palette = palette,
                onClick = onConfirm,
                primary = true,
                cornerRadius = style.actionRadius
            )
        }
    )
}

@Composable
private fun GroupSelectRow(
    group: BookGroup,
    isSelected: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    style: io.legado.app.ui.widget.compose.AppDialogStyle
) {
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.actionRadius))
            .clickable { onToggle() },
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
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = isSelected,
                    onCheckedChange = { onToggle() },
                    colors = CheckboxDefaults.colors(
                        checkedColor = style.accent,
                        uncheckedColor = style.secondaryText,
                        checkmarkColor = style.surface
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = group.groupName,
                    color = style.primaryText,
                    fontFamily = style.bodyFontFamily
                )
            }
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
