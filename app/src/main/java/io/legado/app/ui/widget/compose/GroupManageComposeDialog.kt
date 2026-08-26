package io.legado.app.ui.widget.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.R
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.MenuAction

@Composable
fun ComposeGroupManageDialogContent(
    groups: List<String>,
    onAddGroup: (String) -> Unit,
    onRenameGroup: (oldGroup: String, newGroup: String) -> Unit,
    onDeleteGroup: (String) -> Unit,
    onDismiss: () -> Unit,
    message: String? = null,
    groupCountText: ((String) -> String?)? = null,
    onSelectGroup: ((String) -> Unit)? = null,
    onViewAll: (() -> Unit)? = null,
    viewAllLabel: String = "",
    moreActions: (String) -> List<MenuAction> = { emptyList() },
    externalEdit: Pair<String?, Long>? = null
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var editingGroup by remember { mutableStateOf<String?>(null) }
    var input by rememberSaveable { mutableStateOf("") }
    val isEditing = editingGroup != null

    // 允许外部（如薄壳的「更多」菜单）请求触发内联编辑：second 为递增 key 保证可重复请求
    LaunchedEffect(externalEdit?.second) {
        val request = externalEdit ?: return@LaunchedEffect
        editingGroup = request.first
        input = request.first ?: ""
    }

    fun startAdd() {
        editingGroup = null
        input = ""
    }

    fun startEdit(group: String) {
        editingGroup = group
        input = group
    }

    fun submit() {
        val value = input.trim()
        if (value.isBlank()) return
        val old = editingGroup
        if (old == null) {
            onAddGroup(value)
        } else {
            onRenameGroup(old, value)
        }
        input = ""
        editingGroup = null
    }

    AppDialogFrame(
        title = stringResource(R.string.group_manage),
        scrollContent = false,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                LegadoMiuixCard(
                    modifier = Modifier.fillMaxWidth(),
                    color = style.fieldSurface,
                    contentColor = style.primaryText,
                    cornerRadius = style.panelRadius,
                    insidePadding = PaddingValues(12.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (isEditing) R.string.group_edit else R.string.add_group
                        ),
                        color = style.primaryText,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    GroupManageTextField(
                        value = input,
                        onValueChange = { input = it },
                        style = style
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isEditing || input.isNotEmpty()) {
                            LegadoMiuixActionButton(
                                text = stringResource(R.string.cancel),
                                palette = palette,
                                onClick = ::startAdd,
                                minHeight = 36.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        LegadoMiuixActionButton(
                            text = stringResource(R.string.ok),
                            palette = palette,
                            onClick = ::submit,
                            primary = true,
                            minHeight = 36.dp
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 240.dp, max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(
                        items = groups,
                        key = { it }
                    ) { group ->
                        GroupManageRow(
                            group = group,
                            style = style,
                            palette = palette,
                            onEdit = { startEdit(group) },
                            onDelete = { onDeleteGroup(group) }
                        )
                    }
                }
            }
        },
        actions = {
            if (onViewAll != null && viewAllLabel.isNotBlank()) {
                LegadoMiuixActionButton(
                    text = viewAllLabel,
                    palette = palette,
                    onClick = { onViewAll() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            LegadoMiuixActionButton(
                text = stringResource(R.string.close),
                palette = palette,
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun GroupManageTextField(
    value: String,
    onValueChange: (String) -> Unit,
    style: AppDialogStyle
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = {
            Text(
                text = stringResource(R.string.group_name),
                fontFamily = style.bodyFontFamily
            )
        },
        textStyle = androidx.compose.ui.text.TextStyle(
            color = style.primaryText,
            fontFamily = style.bodyFontFamily,
            fontSize = 14.sp
        ),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(style.actionRadius),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = style.primaryText,
            unfocusedTextColor = style.primaryText,
            focusedContainerColor = style.fieldSurface,
            unfocusedContainerColor = style.fieldSurface,
            focusedBorderColor = style.accent,
            unfocusedBorderColor = style.stroke,
            focusedLabelColor = style.accent,
            unfocusedLabelColor = style.secondaryText,
            cursorColor = style.accent
        )
    )
}

@Composable
private fun GroupManageRow(
    group: String,
    style: AppDialogStyle,
    palette: LegadoMiuixPalette,
    onSelect: (() -> Unit)? = null,
    secondaryText: String? = null,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    moreActions: List<MenuAction> = emptyList()
) {
    var moreExpanded by remember { mutableStateOf(false) }
    LegadoMiuixCard(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onSelect != null) Modifier.clickable(onClick = onSelect) else Modifier),
        color = style.fieldSurface,
        contentColor = style.primaryText,
        cornerRadius = style.actionRadius,
        insidePadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group,
                    color = style.primaryText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (secondaryText != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = secondaryText,
                        color = style.secondaryText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            LegadoMiuixActionButton(
                text = stringResource(R.string.edit),
                palette = palette,
                onClick = onEdit,
                minHeight = 34.dp,
                insidePadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            LegadoMiuixActionButton(
                text = stringResource(R.string.delete),
                palette = palette,
                onClick = onDelete,
                danger = true,
                minHeight = 34.dp,
                insidePadding = PaddingValues(horizontal = 12.dp, vertical = 7.dp)
            )
            if (moreActions.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                Box {
                    IconButton(
                        onClick = { moreExpanded = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.more),
                            tint = style.secondaryText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    AppDropdownMenu(
                        expanded = moreExpanded,
                        onDismiss = { moreExpanded = false },
                        actions = moreActions
                    )
                }
            }
        }
    }
}
