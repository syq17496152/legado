package io.legado.app.ui.rss.source.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivityRssSourceDebugBinding
import io.legado.app.help.source.sortUrls
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import splitties.views.onClick
import splitties.views.onLongClick


class RssSourceDebugActivity : VMBaseActivity<ActivityRssSourceDebugBinding, RssSourceDebugModel>() {

    override val binding by viewBinding(ActivityRssSourceDebugBinding::inflate)
    override val viewModel by viewModels<RssSourceDebugModel>()

    private val adapter by lazy { RssSourceDebugAdapter(this) }
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeTopBar()
        initRecyclerView()
        openOrCloseHelp(true)
        viewModel.initData(intent.getStringExtra("key")) {
            initHelpView()
        }
        viewModel.observe { state, msg ->
            lifecycleScope.launch {
                adapter.addItem(msg)
                if (state == -1 || state == 1000) {
                    binding.rotateLoading.gone()
                }
            }
        }
    }

    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Column {
                    GlassTopAppBar(
                        title = getString(R.string.debug_source),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = {
                            // 更多菜单
                            Box {
                                IconButton(onClick = { menuExpanded = true }) {
                                    Icon(Icons.Default.MoreVert, contentDescription = null)
                                }
                                AppDropdownMenu(
                                    expanded = menuExpanded,
                                    onDismiss = { menuExpanded = false },
                                    actions = buildMenuActions()
                                )
                            }
                        }
                    )
                    SettingsSearchBar(
                        query = composeSearchQuery,
                        onQueryChange = { composeSearchQuery = it },
                        placeholder = getString(R.string.rss_debug_search_hint),
                        onSearch = { startSearch(composeSearchQuery) }
                    )
                }
            }
        }
    }

    private fun buildMenuActions(): List<MenuAction> {
        return listOf(
            MenuAction(
                Icons.Default.Code,
                getString(R.string.list_src),
                onClick = { showDialogFragment(TextDialog("Html", viewModel.listSrc)) }
            ),
            MenuAction(
                Icons.Default.Code,
                getString(R.string.content_src),
                onClick = { showDialogFragment(TextDialog("Html", viewModel.contentSrc)) }
            )
        )
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        binding.rotateLoading.loadingColor = accentColor
    }

    @SuppressLint("SetTextI18n")
    private fun initHelpView() {
        binding.textMy.onClick {
            composeSearchQuery = binding.textMy.text.toString()
            startSearch(composeSearchQuery)
        }
        binding.textXt.onClick {
            composeSearchQuery = binding.textXt.text.toString()
            startSearch(composeSearchQuery)
        }
        binding.textFl.onClick {
            if (!binding.textFl.text.startsWith("ERROR:")) {
                composeSearchQuery = binding.textFl.text.toString()
                startSearch(composeSearchQuery)
            }
        }
        binding.textContent.onClick {
            if (!composeSearchQuery.isNullOrBlank()) {
                startSearch(composeSearchQuery)
            }
        }
        initSortKinds()
    }

    private fun initSortKinds() {
        lifecycleScope.launch {
            val sortKinds = viewModel.rssSource?.sortUrls()?.filter {
                it.second.isNotBlank()
            }
            sortKinds?.firstOrNull()?.let {
                binding.textFl.text = "${it.first}::${it.second}"
                if (it.first.startsWith("ERROR:")) {
                    adapter.addItem("${getString(R.string.get_explore_error)}\n${it.second}")
                    openOrCloseHelp(false)
                    return@launch
                }
            }
            @Suppress("USELESS_ELVIS")
            sortKinds?.map { it.first ?: "" }?.let { sortKindTitles ->
                binding.textFl.onLongClick {
                    this@RssSourceDebugActivity.showComposeChoiceListDialog(
                        title = getString(R.string.select_kind),
                        labels = sortKindTitles
                    ) { index ->
                        val sort = sortKinds[index]
                        binding.textFl.text = "${sort.first}::${sort.second}"
                        composeSearchQuery = binding.textFl.text.toString()
                        startSearch(composeSearchQuery)
                    }
                }
            }
        }
    }

    /**
     * 打开关闭辅助面板
     */
    private fun openOrCloseHelp(open: Boolean) {
        binding.help.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun startSearch(key: String) {
        openOrCloseHelp(false)
        adapter.clearItems()
        val searchKey = key.ifBlank { getString(R.string.rss_debug_my) }
        viewModel.startDebug(searchKey, {
            binding.rotateLoading.visible()
        }, {
            toastOnUi(getString(R.string.no_rss_source))
        })
    }
}
