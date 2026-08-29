package io.legado.app.ui.book.manage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.compose.AppDialogSize
import io.legado.app.ui.widget.compose.ComposeDialogFragment
import io.legado.app.ui.widget.number.NumberPickerDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 书源选择（D1 P0 迁移：身体 RecyclerView → ComposeDialogFragment + LazyColumn，随主题全量纳管）
 */
class SourcePickerDialog : ComposeDialogFragment() {

    override val dialogSize: AppDialogSize = AppDialogSize.Wide

    override val dialogWindowAnimations: Int = R.style.AnimDialogFade

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return ComposeView(requireContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoTheme {
                    var searchQuery by rememberSaveable { mutableStateOf("") }
                    var menuExpanded by remember { mutableStateOf(false) }
                    val bookSources by produceState<List<BookSourcePart>>(
                        initialValue = emptyList(),
                        searchQuery
                    ) {
                        val flow = if (searchQuery.isBlank()) {
                            appDb.bookSourceDao.flowEnabled()
                        } else {
                            appDb.bookSourceDao.flowSearchEnabled(searchQuery)
                        }
                        flow.catch {
                            AppLog.put("书源选择界面获取书源数据失败\n${it.localizedMessage}", it)
                        }.flowOn(Dispatchers.IO).collect { value = it }
                    }
                    SourcePickerPanel(
                        sources = bookSources,
                        searchQuery = searchQuery,
                        onSearchChange = { searchQuery = it },
                        menuExpanded = menuExpanded,
                        onMenuExpandedChange = { menuExpanded = it },
                        onBack = { dismissAllowingStateLoss() },
                        onSourceClick = { source ->
                            callback?.sourceOnClick(source)
                            dismissAllowingStateLoss()
                        },
                        onSetDelay = {
                            NumberPickerDialog(requireContext())
                                .setTitle(getString(R.string.change_source_delay))
                                .setMaxValue(9999)
                                .setMinValue(0)
                                .setValue(AppConfig.batchChangeSourceDelay)
                                .show { AppConfig.batchChangeSourceDelay = it }
                        }
                    )
                }
            }
        }
    }

    private val callback: Callback?
        get() = (parentFragment as? Callback) ?: activity as? Callback

    interface Callback {

        fun sourceOnClick(source: BookSource)

    }

}

@Composable
private fun SourcePickerPanel(
    sources: List<BookSourcePart>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onSourceClick: (BookSource) -> Unit,
    onSetDelay: () -> Unit
) {
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxWidth()) {
        GlassTopAppBar(
            title = stringResource(R.string.select_book_source),
            navIcon = Icons.AutoMirrored.Filled.ArrowBack,
            onNavClick = onBack,
            actions = {
                Box {
                    IconButton(onClick = { onMenuExpandedChange(true) }) {
                        Icon(
                            imageVector = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.more)
                        )
                    }
                    AppDropdownMenu(
                        expanded = menuExpanded,
                        onDismiss = { onMenuExpandedChange(false) },
                        actions = listOf(
                            MenuAction(
                                icon = Icons.Default.Settings,
                                title = stringResource(R.string.change_source_delay),
                                onClick = {
                                    onMenuExpandedChange(false)
                                    onSetDelay()
                                }
                            )
                        )
                    )
                }
            }
        )
        SettingsSearchBar(
            query = searchQuery,
            onQueryChange = onSearchChange,
            placeholder = stringResource(R.string.search_book_source)
        )
        if (sources.isEmpty()) {
            Text(
                text = stringResource(R.string.chapter_list_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .padding(24.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
            ) {
                items(items = sources) { item ->
                    Text(
                        text = item.getDisPlayNameGroup(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                loadSource(scope, item, onSourceClick)
                            }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun loadSource(
    scope: CoroutineScope,
    item: BookSourcePart,
    onSourceClick: (BookSource) -> Unit
) {
    scope.launch {
        val source = withContext(Dispatchers.IO) { item.getBookSource() }
        source?.let(onSourceClick)
    }
}