package io.legado.app.ui.book.source.debug

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.databinding.ActivitySourceDebugBinding
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppDropdownMenu
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.SettingsSearchBar
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
    private var composeSearchQuery by mutableStateOf("")
    private var menuExpanded by mutableStateOf(false)
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it?.let {
            composeSearchQuery = it
            startSearch(it)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeTopBar()
        initRecyclerView()
        openOrCloseHelp(true)
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

    private fun initComposeTopBar() {
        binding.composeTopBar.setContent {
            LegadoTheme {
                Column {
                    GlassTopAppBar(
                        title = getString(R.string.debug_source),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { finish() },
                        actions = {
                            // 扫码
                            IconButton(onClick = { qrCodeResult.launch() }) {
                                Icon(
                                    Icons.Default.QrCodeScanner,
                                    contentDescription = getString(R.string.scan_qr_code)
                                )
                            }
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
                        placeholder = getString(R.string.search_book_key),
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
                getString(R.string.search_src),
                onClick = { showDialogFragment(TextDialog("html", viewModel.searchSrc)) }
            ),
            MenuAction(
                Icons.Default.Code,
                getString(R.string.boo_src),
                onClick = { showDialogFragment(TextDialog("html", viewModel.bookSrc)) }
            ),
            MenuAction(
                Icons.Default.Code,
                getString(R.string.toc_src),
                onClick = { showDialogFragment(TextDialog("html", viewModel.tocSrc)) }
            ),
            MenuAction(
                Icons.Default.Code,
                getString(R.string.content_src),
                onClick = { showDialogFragment(TextDialog("html", viewModel.contentSrc)) }
            ),
            MenuAction(
                Icons.Default.Refresh,
                getString(R.string.refresh_explore),
                onClick = {
                    lifecycleScope.launch {
                        viewModel.bookSource?.clearExploreKindsCache()
                        adapter.clearItems()
                        openOrCloseHelp(true)
                        initExploreKinds()
                    }
                }
            ),
            MenuAction(
                Icons.Default.Help,
                getString(R.string.help),
                onClick = { showHelp("debugHelp") }
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
        viewModel.bookSource?.ruleSearch?.checkKeyWord?.let {
            if (it.isNotBlank()) {
                binding.textMy.text = it
            }
        }
        binding.textMy.onClick {
            composeSearchQuery = binding.textMy.text.toString()
            startSearch(composeSearchQuery)
        }
        binding.textXt.onClick {
            composeSearchQuery = binding.textXt.text.toString()
            startSearch(composeSearchQuery)
        }
        binding.textFx.onClick {
            if (!binding.textFx.text.startsWith("ERROR:")) {
                composeSearchQuery = binding.textFx.text.toString()
                startSearch(composeSearchQuery)
            }
        }
        binding.textInfo.onClick {
            if (!composeSearchQuery.isNullOrBlank()) {
                startSearch(composeSearchQuery)
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
                    composeSearchQuery = ""
                    return@launch
                }
            }
            @Suppress("USELESS_ELVIS")
            exploreKinds?.map { it.title ?: "" }?.let { exploreKindTitles ->
                binding.textFx.onLongClick {
                    selector("选择发现", exploreKindTitles) { _, index ->
                        val explore = exploreKinds[index]
                        binding.textFx.text = "${explore.title}::${explore.url}"
                        composeSearchQuery = binding.textFx.text.toString()
                        startSearch(composeSearchQuery)
                    }
                }
            }
        }
    }

    private fun prefixAutoComplete(prefix: String) {
        if (composeSearchQuery.isNullOrBlank() || composeSearchQuery.length <= 2) {
            composeSearchQuery = prefix
        } else {
            if (!composeSearchQuery.startsWith(prefix)) {
                composeSearchQuery = "$prefix$composeSearchQuery"
                startSearch(composeSearchQuery)
            } else {
                startSearch(composeSearchQuery)
            }
        }
    }

    private fun openOrCloseHelp(open: Boolean) {
        binding.help.visibility = if (open) View.VISIBLE else View.GONE
    }

    private fun startSearch(key: String) {
        openOrCloseHelp(false)
        adapter.clearItems()
        viewModel.startDebug(key, {
            binding.rotateLoading.visible()
        }, {
            toastOnUi("未获取到书源")
        })
    }

}
