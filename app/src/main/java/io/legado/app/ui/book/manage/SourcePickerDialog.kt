package io.legado.app.ui.book.manage

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.setPadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.DialogSourcePickerBinding
import io.legado.app.databinding.Item1lineTextBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.views.onClick

/**
 * 书源选择
 */
class SourcePickerDialog : BaseDialogFragment(R.layout.dialog_source_picker) {

    private val binding by viewBinding(DialogSourcePickerBinding::bind)
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private val adapter by lazy {
        SourceAdapter(requireContext())
    }
    private var sourceFlowJob: Job? = null

    override fun onStart() {
        super.onStart()
        setLayout(1f, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        initView()
        initData()
        initComposeTopBar()
    }

    private fun initView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
    }

    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Column {
                    GlassTopAppBar(
                        title = getString(R.string.select_book_source),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { dismissAllowingStateLoss() },
                        actions = {
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(
                                        imageVector = Icons.Filled.MoreVert,
                                        contentDescription = getString(R.string.more)
                                    )
                                }
                                AppDropdownMenu(
                                    expanded = menuExpanded,
                                    onDismiss = { menuExpanded = false },
                                    actions = listOf(
                                        MenuAction(
                                            icon = Icons.Default.Settings,
                                            title = getString(R.string.change_source_delay),
                                            onClick = {
                                                NumberPickerDialog(requireContext())
                                                    .setTitle(getString(R.string.change_source_delay))
                                                    .setMaxValue(9999)
                                                    .setMinValue(0)
                                                    .setValue(AppConfig.batchChangeSourceDelay)
                                                    .show { AppConfig.batchChangeSourceDelay = it }
                                            }
                                        )
                                    )
                                )
                            }
                        }
                    )
                    SettingsSearchBar(
                        query = composeSearchQuery,
                        onQueryChange = { query ->
                            composeSearchQuery = query
                            initData(query)
                        },
                        placeholder = getString(R.string.search_book_source)
                    )
                }
            }
        }
    }

    private fun initData(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> appDb.bookSourceDao.flowEnabled()
                else -> appDb.bookSourceDao.flowSearchEnabled(searchKey)
            }.catch {
                AppLog.put("书源选择界面获取书源数据失败\n${it.localizedMessage}", it)
            }.flowOn(IO).collect {
                adapter.setItems(it)
            }
        }
    }

    inner class SourceAdapter(context: Context) :
        RecyclerAdapter<BookSourcePart, Item1lineTextBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): Item1lineTextBinding {
            return Item1lineTextBinding.inflate(inflater, parent, false).apply {
                root.setPadding(16.dpToPx())
            }
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: Item1lineTextBinding,
            item: BookSourcePart,
            payloads: MutableList<Any>
        ) {
            binding.textView.text = item.getDisPlayNameGroup()
        }

        override fun registerListener(holder: ItemViewHolder, binding: Item1lineTextBinding) {
            binding.root.onClick {
                getItemByLayoutPosition(holder.layoutPosition)?.let {
                    lifecycleScope.launch(IO) {
                        val source = it.getBookSource()
                        withContext(Main) {
                            source?.let { s ->
                                callback?.sourceOnClick(s)
                            }
                            dismissAllowingStateLoss()
                        }
                    }
                }
            }
        }

    }

    private val callback: Callback?
        get() {
            return (parentFragment as? Callback) ?: activity as? Callback
        }

    interface Callback {
        fun sourceOnClick(source: BookSource)
    }

}