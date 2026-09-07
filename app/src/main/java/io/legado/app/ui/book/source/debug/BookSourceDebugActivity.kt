package io.legado.app.ui.book.source.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivitySourceDebugBinding
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.dialogs.selector
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.applyNavigationBarPadding
import io.legado.app.utils.launch
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.launch
import splitties.views.onClick
import splitties.views.onLongClick

class BookSourceDebugActivity : VMBaseActivity<ActivitySourceDebugBinding, BookSourceDebugModel>() {

    override val binding by viewBinding(ActivitySourceDebugBinding::inflate)
    override val viewModel by viewModels<BookSourceDebugModel>()

    private val adapter by lazy { BookSourceDebugAdapter(this) }
    private val searchView: SearchView by lazy {
        binding.searchBar.searchView
    }
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it?.let {
            startSearch(it)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        initRecyclerView()
        initSearchView()
        viewModel.init(intent.getStringExtra("key")) {
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

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.applyNavigationBarPadding()
        binding.rotateLoading.loadingColor = accentColor
    }

    private fun initSearchView() {
        searchView.onActionViewExpanded()
        searchView.isSubmitButtonEnabled = true
        searchView.queryHint = getString(R.string.search_book_key)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchView.clearFocus()
                openOrCloseHelp(false)
                startSearch(query ?: "我的")
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                return false
            }
        })
        searchView.setOnQueryTextFocusChangeListener { _, hasFocus ->
            openOrCloseHelp(hasFocus)
        }
        openOrCloseHelp(true)
    }

    @SuppressLint("SetTextI18n")
    private fun initHelpView() {
        viewModel.bookSource?.ruleSearch?.checkKeyWord?.let {
            if (it.isNotBlank()) {
                binding.textMy.text = it
            }
        }
        binding.textMy.onClick {
            searchView.setQuery(binding.textMy.text, true)
        }
        binding.textXt.onClick {
            searchView.setQuery(binding.textXt.text, true)
        }
        binding.textFx.onClick {
            if (!binding.textFx.text.startsWith("ERROR:")) {
                searchView.setQuery(binding.textFx.text, true)
            }
        }
        binding.textInfo.onClick {
            if (!searchView.query.isNullOrBlank()) {
                searchView.setQuery(searchView.query, true)
            }
        }
        binding.textToc.onClick {
            prefixAutoComplete("++")
        }
        binding.textContent.onClick {
            prefixAutoComplete("--")
        }
        initExploreKinds()
    }

    @SuppressLint("SetTextI18n")
    private fun initExploreKinds() {
        lifecycleScope.launch {
            val exploreKinds = viewModel.bookSource?.exploreKinds()?.filter {
                !it.url.isNullOrBlank()
            }
            exploreKinds?.firstOrNull()?.let {
                binding.textFx.text = "${it.title}::${it.url}"
                if (it.title.startsWith("ERROR:")) {
                    adapter.addItem("获取发现出错\n${it.url}")
                    openOrCloseHelp(false)
                    searchView.clearFocus()
                    return@launch
                }
            }
            @Suppress("USELESS_ELVIS")
            exploreKinds?.map { it.title ?: "" }?.let { exploreKindTitles ->
                binding.textFx.onLongClick {
                    this@BookSourceDebugActivity.showComposeChoiceListDialog(
                        title = "选择发现",
                        labels = exploreKindTitles
                    ) { index ->
                        val explore = exploreKinds[index]
                        binding.textFx.text = "${explore.title}::${explore.url}"
                        searchView.setQuery(binding.textFx.text, true)
                    }
                }
            }
        }
    }

    private fun prefixAutoComplete(prefix: String) {
        val query = searchView.query
        if (query.isNullOrBlank() || query.length <= 2) {
            searchView.setQuery(prefix, false)
        } else {
            if (!query.startsWith(prefix)) {
                searchView.setQuery("$prefix$query", true)
            } else {
                searchView.setQuery(query, true)
            }
        }
    }

    /**
     * 打开关闭历史界面
     */
    private fun openOrCloseHelp(open: Boolean) {
        if (open) {
            binding.help.visibility = View.VISIBLE
        } else {
            binding.help.visibility = View.GONE
        }
    }

    private fun startSearch(key: String) {
        adapter.clearItems()
        viewModel.startDebug(key, {
            binding.rotateLoading.visible()
        }, {
            toastOnUi("未获取到书源")
        })
    }

    // W7.2（Delta 3→1）：顶栏归一 GlassTopAppBar（布局 compose_top_bar 直挂，ConstraintLayout 页不用运行时替换）
    private fun initTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                GlassTopAppBar(
                    title = getString(R.string.debug_source),
                    navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                    onNavClick = { finish() },
                    actions = {
                        TopBarActionRow(
                            listOf(
                                MenuAction(title = getString(R.string.scan_qr_code)) { handleDebugMenuAction(R.id.menu_scan) },
                                MenuAction(title = getString(R.string.search_src)) { handleDebugMenuAction(R.id.menu_search_src) },
                                MenuAction(title = getString(R.string.boo_src)) { handleDebugMenuAction(R.id.menu_book_src) },
                                MenuAction(title = getString(R.string.toc_src)) { handleDebugMenuAction(R.id.menu_toc_src) },
                                MenuAction(title = getString(R.string.content_src)) { handleDebugMenuAction(R.id.menu_content_src) },
                                MenuAction(title = getString(R.string.refresh_explore)) { handleDebugMenuAction(R.id.menu_refresh_explore) },
                                MenuAction(title = getString(R.string.help)) { handleDebugMenuAction(R.id.menu_help) }
                            )
                        )
                    }
                )
            }
        }
    }

    // W7.2：原 onCompatOptionsItemSelected 改私有分发（溢出菜单 MenuAction 直调，itemId 语义不变）
    private fun handleDebugMenuAction(itemId: Int) {
        when (itemId) {
            R.id.menu_scan -> qrCodeResult.launch()
            R.id.menu_search_src -> showDialogFragment(TextDialog("html", viewModel.searchSrc))
            R.id.menu_book_src -> showDialogFragment(TextDialog("html", viewModel.bookSrc))
            R.id.menu_toc_src -> showDialogFragment(TextDialog("html", viewModel.tocSrc))
            R.id.menu_content_src -> showDialogFragment(TextDialog("html", viewModel.contentSrc))
            R.id.menu_refresh_explore -> lifecycleScope.launch {
                viewModel.bookSource?.clearExploreKindsCache()
                adapter.clearItems()
                openOrCloseHelp(true)
                initExploreKinds()
            }

            R.id.menu_help -> showHelp("debugHelp")
        }
    }

}
