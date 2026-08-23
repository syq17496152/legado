package io.legado.app.ui.book.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.ui.widget.compose.AppDialogFrame
import io.legado.app.ui.widget.compose.AppDialogStyle
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.LegadoMiuixActionButton
import io.legado.app.ui.widget.compose.rememberAppDialogStyle
import io.legado.app.ui.widget.compose.toMiuixPalette
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

class SearchScopeDialog : ComposeDialogFragment() {

    val callback: Callback get() = parentFragment as? Callback ?: activity as Callback

    override val dialogSize: AppDialogSize = AppDialogSize.Form

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    var mode by remember { mutableStateOf(ScopeMode.GROUP) }
                    var query by remember { mutableStateOf("") }
                    val selectedGroups = remember { mutableStateListOf<String>() }
                    var selectedSource by remember { mutableStateOf<BookSourcePart?>(null) }

                    // 分组列表（一次加载）
                    var groups by remember { mutableStateOf<List<String>>(emptyList()) }
                    LaunchedEffect(Unit) {
                        groups = appDb.bookSourceDao.allEnabledGroups()
                    }

                    // 书源 Flow（仅 SOURCE 模式时收集）
                    val sourceFlow = remember(mode, query) {
                        if (mode != ScopeMode.SOURCE) {
                            flowOf(emptyList())
                        } else {
                            when {
                                query.isEmpty() -> appDb.bookSourceDao.flowAll()
                                else -> appDb.bookSourceDao.flowSearch(query)
                            }.flowWithLifecycleAndDatabaseChange(
                                lifecycle,
                                table = AppDatabase.BOOK_SOURCE_TABLE_NAME
                            ).catch {
                                AppLog.put("搜索范围-多分组/书源界面更新出错", it)
                            }.flowOn(IO).conflate()
                        }
                    }
                    val sources by sourceFlow.collectAsStateWithLifecycle(initialValue = emptyList())

                    SearchScopeContent(
                        mode = mode,
                        onModeChange = {
                            mode = it
                            if (it != ScopeMode.SOURCE) query = ""
                        },
                        query = query,
                        onQueryChange = { query = it },
                        groups = groups,
                        selectedGroups = selectedGroups,
                        sources = sources,
                        selectedSource = selectedSource,
                        onSelectSource = { selectedSource = it },
                        onAllSources = {
                            callback.onSearchScopeOk(SearchScope(""))
                            dismiss()
                        },
                        onConfirm = {
                            val scope = if (mode == ScopeMode.GROUP) {
                                SearchScope(selectedGroups.toList())
                            } else {
                                selectedSource?.let { SearchScope(it) } ?: SearchScope("")
                            }
                            callback.onSearchScopeOk(scope)
                            dismiss()
                        },
                        onCancel = { dismiss() }
                    )
                }
            }
        }
    }

    enum class ScopeMode { GROUP, SOURCE }

    interface Callback {
        fun onSearchScopeOk(searchScope: SearchScope)
    }
}

@Composable
private fun SearchScopeContent(
    mode: SearchScopeDialog.ScopeMode,
    onModeChange: (SearchScopeDialog.ScopeMode) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    groups: List<String>,
    selectedGroups: MutableList<String>,
    sources: List<BookSourcePart>,
    selectedSource: BookSourcePart?,
    onSelectSource: (BookSourcePart) -> Unit,
    onAllSources: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val style = rememberAppDialogStyle()
    val palette = style.toMiuixPalette()
    AppDialogFrame(
        title = stringResource(R.string.search_scope),
        scrollContent = false,
        content = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // 模式切换：分组 / 书源
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModeChip(
                        label = stringResource(R.string.group),
                        selected = mode == SearchScopeDialog.ScopeMode.GROUP,
                        onClick = { onModeChange(SearchScopeDialog.ScopeMode.GROUP) },
                        style = style
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ModeChip(
                        label = "书源",
                        selected = mode == SearchScopeDialog.ScopeMode.SOURCE,
                        onClick = { onModeChange(SearchScopeDialog.ScopeMode.SOURCE) },
                        style = style
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))

                // 书源模式下显示搜索框
                if (mode == SearchScopeDialog.ScopeMode.SOURCE) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(stringResource(R.string.search_book_source)) },
                        singleLine = true,
                        shape = RoundedCornerShape(style.actionRadius),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = style.primaryText,
                            unfocusedTextColor = style.primaryText,
                            focusedContainerColor = style.fieldSurface,
                            unfocusedContainerColor = style.fieldSurface,
                            cursorColor = style.accent,
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedLabelColor = style.accent,
                            unfocusedLabelColor = style.secondaryText
                        ),
                        textStyle = LocalTextStyle.current.copy(
                            color = style.primaryText,
                            fontFamily = style.bodyFontFamily
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // 列表
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                ) {
                    if (mode == SearchScopeDialog.ScopeMode.GROUP) {
                        items(groups, key = { it }) { group ->
                            ScopeCheckRow(
                                label = group,
                                checked = selectedGroups.contains(group),
                                onToggle = {
                                    if (selectedGroups.contains(group)) {
                                        selectedGroups.remove(group)
                                    } else {
                                        selectedGroups.add(group)
                                    }
                                },
                                style = style
                            )
                        }
                    } else {
                        items(sources, key = { it.bookSourceUrl }) { source ->
                            ScopeRadioRow(
                                label = source.bookSourceName,
                                selected = selectedSource == source,
                                onSelect = { onSelectSource(source) },
                                style = style
                            )
                        }
                    }
                }
            }
        },
        actions = {
            LegadoMiuixActionButton(
                text = stringResource(R.string.all_source),
                palette = palette,
                onClick = onAllSources,
                cornerRadius = style.actionRadius
            )
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
private fun ModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    style: AppDialogStyle
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(style.actionRadius))
            .background(if (selected) style.accent else style.fieldSurface)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    }
}

@Composable
private fun ScopeCheckRow(
    label: String,
    checked: Boolean,
    onToggle: () -> Unit,
    style: AppDialogStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = CheckboxDefaults.colors(
                checkedColor = style.accent,
                uncheckedColor = style.secondaryText
            )
        )
        Text(
            text = label,
            color = style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    }
}

@Composable
private fun ScopeRadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    style: AppDialogStyle
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = style.accent,
                unselectedColor = style.secondaryText
            )
        )
        Text(
            text = label,
            color = style.primaryText,
            fontFamily = style.bodyFontFamily
        )
    }
}