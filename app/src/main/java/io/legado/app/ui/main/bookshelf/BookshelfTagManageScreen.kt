package io.legado.app.ui.main.bookshelf

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.legado.app.R
import io.legado.app.data.dao.BookTagInfo
import io.legado.app.help.book.BookTagHelper
import io.legado.app.help.book.BookTagManagement
import io.legado.app.ui.widget.compose.AppListSpacing
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementCard
import io.legado.app.ui.widget.compose.AppManagementPalette
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.LegadoMiuixCard
import io.legado.app.ui.widget.compose.LegadoMiuixChoiceRow
import io.legado.app.ui.widget.compose.LegadoMiuixSwitch
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.toMiuixPalette

internal data class BookshelfTagItemUi(
    val name: String,
    val assignedCount: Int,
    val visible: Boolean
)

internal data class BookshelfTagGroupUi(
    val groupId: Long,
    val groupName: String,
    val books: List<BookTagInfo>,
    val tags: List<BookshelfTagItemUi>
)

internal data class BookTagAssignmentUi(
    val groupId: Long,
    val groupName: String,
    val tag: String,
    val books: List<BookTagInfo>,
    val initiallySelectedUrls: Set<String>
)

private enum class BookSelectionFilter {
    All,
    Selected,
    Unselected
}

@Composable
internal fun BookshelfTagManageScreen(
    groups: List<BookshelfTagGroupUi>,
    focusGroupId: Long,
    loading: Boolean,
    assignment: BookTagAssignmentUi?,
    onBack: () -> Unit,
    onAddTags: (Long, List<String>) -> Unit,
    onTagVisibilityChange: (Long, String, Boolean) -> Unit,
    onManageBooks: (BookshelfTagGroupUi, String) -> Unit,
    onDeleteTag: (BookshelfTagGroupUi, String) -> Unit,
    onDismissAssignment: () -> Unit,
    onSaveAssignment: (BookTagAssignmentUi, Set<String>) -> Unit
) {
    val palette = rememberAppManagementPalette()
    var selectedGroupId by rememberSaveable { mutableLongStateOf(focusGroupId) }
    var addTagsGroupId by rememberSaveable { mutableStateOf<Long?>(null) }
    LaunchedEffect(groups, focusGroupId) {
        if (groups.none { it.groupId == selectedGroupId }) {
            selectedGroupId = groups.firstOrNull { it.groupId == focusGroupId }?.groupId
                ?: groups.firstOrNull()?.groupId
                ?: focusGroupId
        }
    }
    val selectedGroup = groups.firstOrNull { it.groupId == selectedGroupId }
    AppManagementScaffold(
        title = stringResource(R.string.bookshelf_tag_manage),
        selectedCount = 0,
        totalCount = selectedGroup?.tags?.size ?: 0,
        palette = palette,
        onBack = onBack,
        topActions = selectedGroup?.let { group ->
            listOf(
                AppManagementAction(
                    text = stringResource(R.string.add),
                    iconRes = R.drawable.ic_add,
                    onClick = { addTagsGroupId = group.groupId }
                )
            )
        }.orEmpty()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(palette.settings.page)
        ) {
            if (groups.isNotEmpty()) {
                GroupSelector(
                    groups = groups,
                    selectedGroupId = selectedGroupId,
                    palette = palette,
                    onSelect = { selectedGroupId = it }
                )
            }
            when {
                loading -> LoadingContent(palette)
                selectedGroup == null -> EmptyContent(
                    text = stringResource(R.string.bookshelf_tag_none),
                    palette = palette
                )
                else -> TagGroupContent(
                    group = selectedGroup,
                    palette = palette,
                    onAddTags = { addTagsGroupId = selectedGroup.groupId },
                    onTagVisibilityChange = { tag, visible ->
                        onTagVisibilityChange(selectedGroup.groupId, tag, visible)
                    },
                    onManageBooks = { onManageBooks(selectedGroup, it) },
                    onDeleteTag = { onDeleteTag(selectedGroup, it) }
                )
            }
        }
    }
    assignment?.let {
        BookTagAssignmentDialog(
            assignment = it,
            onDismiss = onDismissAssignment,
            onSave = { selected -> onSaveAssignment(it, selected) }
        )
    }
    addTagsGroupId?.let { groupId ->
        groups.firstOrNull { it.groupId == groupId }?.let { group ->
            val allTags = remember(groups) {
                groups.flatMap { item -> item.tags.map { it.name } }
            }
            val reusableTags = remember(group.tags, allTags) {
                BookTagManagement.reusableTags(
                    current = group.tags.map { it.name },
                    all = allTags
                )
            }
            BookTagAddDialog(
                group = group,
                reusableTags = reusableTags,
                onDismiss = { addTagsGroupId = null },
                onAdd = { tags ->
                    addTagsGroupId = null
                    onAddTags(group.groupId, tags)
                }
            )
        }
    }
}

@Composable
private fun GroupSelector(
    groups: List<BookshelfTagGroupUi>,
    selectedGroupId: Long,
    palette: AppManagementPalette,
    onSelect: (Long) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(AppListSpacing.Compact)
    ) {
        items(groups, key = { it.groupId }) { group ->
            val selected = group.groupId == selectedGroupId
            Surface(
                modifier = Modifier.clickable { onSelect(group.groupId) },
                shape = RoundedCornerShape(palette.miuix.actionRadius ?: 12.dp),
                color = if (selected) {
                    palette.settings.accent.copy(alpha = 0.16f)
                } else {
                    Color(palette.settings.row)
                },
                contentColor = if (selected) palette.settings.accent else palette.settings.primaryText
            ) {
                Text(
                    text = "${group.groupName} · ${group.tags.size}",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun TagGroupContent(
    group: BookshelfTagGroupUi,
    palette: AppManagementPalette,
    onAddTags: () -> Unit,
    onTagVisibilityChange: (String, Boolean) -> Unit,
    onManageBooks: (String) -> Unit,
    onDeleteTag: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(AppListSpacing.Normal)
    ) {
        item(key = "summary:${group.groupId}") {
            AppManagementCard(palette = palette) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = group.groupName,
                            color = palette.settings.primaryText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                R.string.bookshelf_tag_group_summary,
                                group.books.size,
                                group.tags.size
                            ),
                            color = palette.settings.secondaryText,
                            fontSize = 13.sp
                        )
                    }
                    LegadoMiuixActionButton(
                        text = stringResource(R.string.add),
                        palette = palette.miuix,
                        onClick = onAddTags,
                        primary = true,
                        minWidth = 68.dp
                    )
                }
            }
        }
        if (group.tags.isEmpty()) {
            item(key = "empty:${group.groupId}") {
                AppManagementCard(palette = palette) {
                    Text(
                        text = stringResource(R.string.bookshelf_tag_empty_summary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp),
                        color = palette.settings.secondaryText,
                        fontSize = 14.sp
                    )
                }
            }
        } else {
            items(group.tags, key = { it.name.lowercase() }) { tag ->
                TagCard(
                    tag = tag,
                    palette = palette,
                    onVisibilityChange = { onTagVisibilityChange(tag.name, it) },
                    onManageBooks = { onManageBooks(tag.name) },
                    onDelete = { onDeleteTag(tag.name) }
                )
            }
        }
    }
}

@Composable
private fun TagCard(
    tag: BookshelfTagItemUi,
    palette: AppManagementPalette,
    onVisibilityChange: (Boolean) -> Unit,
    onManageBooks: () -> Unit,
    onDelete: () -> Unit
) {
    AppManagementCard(palette = palette) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tag.name,
                    color = palette.settings.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = stringResource(R.string.bookshelf_tag_book_count, tag.assignedCount),
                    color = palette.settings.secondaryText,
                    fontSize = 12.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = stringResource(R.string.bookshelf_tag_visible),
                    color = palette.settings.secondaryText,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                LegadoMiuixSwitch(
                    checked = tag.visible,
                    onCheckedChange = onVisibilityChange,
                    palette = palette.miuix
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LegadoMiuixActionButton(
                text = stringResource(R.string.bookshelf_tag_manage_books),
                palette = palette.miuix,
                onClick = onManageBooks,
                modifier = Modifier.weight(1f),
                primary = true
            )
            LegadoMiuixActionButton(
                text = stringResource(R.string.delete),
                palette = palette.miuix,
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                danger = true
            )
        }
    }
}

@Composable
private fun LoadingContent(palette: AppManagementPalette) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = palette.settings.accent)
    }
}

@Composable
private fun EmptyContent(text: String, palette: AppManagementPalette) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = palette.settings.secondaryText, fontSize = 14.sp)
    }
}

@Composable
private fun BookTagAddDialog(
    group: BookshelfTagGroupUi,
    reusableTags: List<String>,
    onDismiss: () -> Unit,
    onAdd: (List<String>) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var query by rememberSaveable(group.groupId) { mutableStateOf("") }
    var newTagInput by rememberSaveable(group.groupId) { mutableStateOf("") }
    var selectedTags by remember(group.groupId, reusableTags) {
        mutableStateOf(emptySet<String>())
    }
    val visibleTags = remember(reusableTags, query) {
        val normalizedQuery = query.trim()
        reusableTags.filter {
            normalizedQuery.isEmpty() || it.contains(normalizedQuery, ignoreCase = true)
        }
    }
    val newTags = remember(newTagInput) { BookTagHelper.parse(newTagInput) }
    val tagsToAdd = remember(reusableTags, selectedTags, newTags) {
        BookTagManagement.mergeTags(
            configured = reusableTags.filter { it in selectedTags },
            existing = newTags
        )
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.82f)
                .widthIn(max = 620.dp)
                .navigationBarsPadding(),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(16.dp)
        ) {
            Text(
                text = stringResource(R.string.bookshelf_tag_add_title),
                color = style.primaryText,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = stringResource(R.string.bookshelf_tag_add_group, group.groupName),
                color = style.secondaryText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = newTagInput,
                onValueChange = { newTagInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.bookshelf_tag_new_label)) },
                placeholder = { Text(stringResource(R.string.bookshelf_tag_new_hint)) },
                shape = RoundedCornerShape(style.actionRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = style.primaryText,
                    unfocusedTextColor = style.primaryText,
                    focusedContainerColor = style.fieldSurface,
                    unfocusedContainerColor = style.fieldSurface,
                    focusedBorderColor = style.accent,
                    unfocusedBorderColor = style.stroke,
                    cursorColor = style.accent,
                    focusedLabelColor = style.accent,
                    unfocusedLabelColor = style.secondaryText,
                    focusedPlaceholderColor = style.secondaryText,
                    unfocusedPlaceholderColor = style.secondaryText
                )
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.bookshelf_tag_reusable_summary,
                    reusableTags.size,
                    selectedTags.size
                ),
                color = style.secondaryText,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (reusableTags.isNotEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.bookshelf_tag_search_existing)) },
                    shape = RoundedCornerShape(style.actionRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = style.primaryText,
                        unfocusedTextColor = style.primaryText,
                        focusedContainerColor = style.fieldSurface,
                        unfocusedContainerColor = style.fieldSurface,
                        focusedBorderColor = style.accent,
                        unfocusedBorderColor = style.stroke,
                        cursorColor = style.accent,
                        focusedPlaceholderColor = style.secondaryText,
                        unfocusedPlaceholderColor = style.secondaryText
                    )
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            when {
                reusableTags.isEmpty() -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.bookshelf_tag_no_reusable),
                            color = style.secondaryText,
                            fontSize = 14.sp
                        )
                    }
                }
                visibleTags.isEmpty() -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(R.string.bookshelf_tag_no_matching_existing),
                            color = style.secondaryText,
                            fontSize = 14.sp
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(AppListSpacing.Compact)
                    ) {
                        items(visibleTags, key = { it.lowercase() }) { tag ->
                            val selected = tag in selectedTags
                            LegadoMiuixChoiceRow(
                                text = tag,
                                selected = selected,
                                palette = palette,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                                onClick = {
                                    selectedTags = if (selected) {
                                        selectedTags - tag
                                    } else {
                                        selectedTags + tag
                                    }
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                LegadoMiuixActionButton(
                    text = stringResource(R.string.add),
                    palette = palette,
                    onClick = { if (tagsToAdd.isNotEmpty()) onAdd(tagsToAdd) },
                    modifier = Modifier
                        .weight(1f)
                        .alpha(if (tagsToAdd.isNotEmpty()) 1f else 0.45f),
                    primary = true
                )
            }
        }
    }
}

@Composable
private fun BookTagAssignmentDialog(
    assignment: BookTagAssignmentUi,
    onDismiss: () -> Unit,
    onSave: (Set<String>) -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    var query by rememberSaveable(assignment.groupId, assignment.tag) { mutableStateOf("") }
    var filter by rememberSaveable(assignment.groupId, assignment.tag) {
        mutableStateOf(BookSelectionFilter.All)
    }
    var selectedUrls by remember(assignment.groupId, assignment.tag, assignment.books) {
        mutableStateOf(assignment.initiallySelectedUrls)
    }
    val visibleBooks = remember(assignment.books, selectedUrls, query, filter) {
        assignment.books.asSequence()
            .filter { book ->
                query.isBlank() ||
                    book.name.contains(query, ignoreCase = true) ||
                    book.author.contains(query, ignoreCase = true)
            }
            .filter { book ->
                when (filter) {
                    BookSelectionFilter.All -> true
                    BookSelectionFilter.Selected -> book.bookUrl in selectedUrls
                    BookSelectionFilter.Unselected -> book.bookUrl !in selectedUrls
                }
            }
            .sortedWith(
                compareByDescending<BookTagInfo> { it.bookUrl in selectedUrls }
                    .thenBy { it.name.lowercase() }
            )
            .toList()
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        LegadoMiuixCard(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f)
                .widthIn(max = 700.dp)
                .navigationBarsPadding(),
            color = style.surface,
            contentColor = style.primaryText,
            cornerRadius = style.panelRadius,
            insidePadding = PaddingValues(16.dp)
        ) {
            Text(
                text = "${assignment.groupName} · ${assignment.tag}",
                color = style.primaryText,
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text(stringResource(R.string.bookshelf_tag_search_book)) },
                shape = RoundedCornerShape(style.actionRadius),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = style.primaryText,
                    unfocusedTextColor = style.primaryText,
                    focusedContainerColor = style.fieldSurface,
                    unfocusedContainerColor = style.fieldSurface,
                    focusedBorderColor = style.accent,
                    unfocusedBorderColor = style.stroke,
                    cursorColor = style.accent,
                    focusedPlaceholderColor = style.secondaryText,
                    unfocusedPlaceholderColor = style.secondaryText
                )
            )
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SelectionFilterChip(
                    text = stringResource(R.string.bookshelf_tag_filter_all),
                    selected = filter == BookSelectionFilter.All,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = BookSelectionFilter.All }
                )
                SelectionFilterChip(
                    text = stringResource(R.string.bookshelf_tag_filter_selected),
                    selected = filter == BookSelectionFilter.Selected,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = BookSelectionFilter.Selected }
                )
                SelectionFilterChip(
                    text = stringResource(R.string.bookshelf_tag_filter_unselected),
                    selected = filter == BookSelectionFilter.Unselected,
                    palette = palette,
                    modifier = Modifier.weight(1f),
                    onClick = { filter = BookSelectionFilter.Unselected }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(
                        R.string.bookshelf_tag_selected_count,
                        selectedUrls.size,
                        assignment.books.size
                    ),
                    modifier = Modifier.weight(1f),
                    color = style.secondaryText,
                    fontSize = 12.sp
                )
                LegadoMiuixActionButton(
                    text = stringResource(R.string.bookshelf_tag_select_results),
                    palette = palette,
                    onClick = {
                        selectedUrls = selectedUrls + visibleBooks.map { it.bookUrl }
                    },
                    minWidth = 0.dp,
                    minHeight = 34.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                LegadoMiuixActionButton(
                    text = stringResource(R.string.bookshelf_tag_clear_results),
                    palette = palette,
                    onClick = {
                        selectedUrls = selectedUrls - visibleBooks.map { it.bookUrl }.toSet()
                    },
                    minWidth = 0.dp,
                    minHeight = 34.dp,
                    insidePadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (visibleBooks.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.bookshelf_tag_no_matching_books),
                        color = style.secondaryText,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppListSpacing.Compact)
                ) {
                    items(visibleBooks, key = { it.bookUrl }) { book ->
                        val selected = book.bookUrl in selectedUrls
                        val tags = BookTagHelper.parse(book.customTag)
                        val description = listOfNotNull(
                            book.author.takeIf { it.isNotBlank() },
                            tags.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                        ).joinToString("  ·  ")
                        LegadoMiuixChoiceRow(
                            text = book.name,
                            description = description,
                            selected = selected,
                            palette = palette,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                            onClick = {
                                selectedUrls = if (selected) {
                                    selectedUrls - book.bookUrl
                                } else {
                                    selectedUrls + book.bookUrl
                                }
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LegadoMiuixActionButton(
                    text = stringResource(R.string.cancel),
                    palette = palette,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                LegadoMiuixActionButton(
                    text = stringResource(R.string.bookshelf_tag_save),
                    palette = palette,
                    onClick = { onSave(selectedUrls) },
                    modifier = Modifier.weight(1f),
                    primary = true
                )
            }
        }
    }
}

@Composable
private fun SelectionFilterChip(
    text: String,
    selected: Boolean,
    palette: io.legado.app.ui.widget.compose.LegadoMiuixPalette,
    modifier: Modifier,
    onClick: () -> Unit
) {
    LegadoMiuixChoiceRow(
        text = text,
        selected = selected,
        palette = palette,
        onClick = onClick,
        modifier = modifier,
        minHeight = 36.dp,
        compact = true,
        showSelectedMark = false
    )
}
