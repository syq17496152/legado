package io.legado.app.ui.rss.source.manage

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.RssSource
import io.legado.app.databinding.ActivityRssSourceBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.ui.association.ImportRssSourceDialog
import io.legado.app.ui.association.showShibbolethDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.rss.source.edit.RssSourceEditActivity
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.compose.AppManagementAction
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppManagementScaffold
import io.legado.app.ui.widget.compose.replaceByIndex
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeSuggestionTextInputDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.ACache
import io.legado.app.utils.ShibbolethCodec
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.sendToClip
import io.legado.app.utils.share
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.startActivity
import io.legado.app.utils.transaction
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 订阅源管理
 */
class RssSourceActivity : VMBaseActivity<ActivityRssSourceBinding, RssSourceViewModel>(),
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack {

    override val binding by viewBinding(ActivityRssSourceBinding::inflate)
    override val viewModel by viewModels<RssSourceViewModel>()
    private val importRecordKey = "rssSourceRecordKey"
    private var sourceFlowJob: Job? = null
    private var groups = arrayListOf<String>()
    private var groupMenu: SubMenu? = null
    private val sourcesState = mutableStateListOf<RssSource>()
    private val selectedUrls = mutableStateOf<Set<String>>(emptySet())
    private val isSelectMode = mutableStateOf(false)
    private val searchQueryState = mutableStateOf("")
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(ImportRssSourceDialog(it))
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            showDialogFragment(ImportRssSourceDialog(uri.toString()))
        }
    }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            val url = uri.toString()
            showComposeConfirmDialog(
                title = getString(R.string.export_success),
                message = if (url.isAbsUrl()) {
                    url + "\n" + DirectLinkUpload.getSummary()
                } else {
                    url
                },
                positiveText = getString(R.string.copy_text),
                negativeText = getString(R.string.cancel),
                neutralText = getString(R.string.shibboleth)
                    .takeIf { ShibbolethCodec.canEncodeUrl(url) },
                onPositive = { sendToClip(url) },
                onNeutral = { showShibbolethDialog(url, ShibbolethCodec.RSS_SOURCE) }
            )
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeContent()
        initGroupFlow()
        upSourceFlow()
    }

    private fun initComposeContent() {
        binding.titleBar.visibility = View.GONE
        binding.selectActionBar.visibility = View.GONE
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        val cv = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                AppManagementScaffold(
                    title = getString(R.string.rss_source_manage),
                    selectedCount = selectedUrls.value.size,
                    totalCount = sourcesState.size,
                    searchQuery = searchQueryState.value,
                    searchHint = getString(R.string.search_rss_source),
                    onSearchChange = ::updateSearchQuery,
                    topActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.menu_action_group),
                            iconRes = R.drawable.ic_groups,
                            onClick = ::showFilterMenu
                        ),
                        AppManagementAction(
                            text = getString(R.string.add),
                            iconRes = R.drawable.ic_add,
                            onClick = { startActivity<RssSourceEditActivity>() }
                        ),
                        AppManagementAction(
                            text = getString(R.string.more_menu),
                            iconRes = R.drawable.ic_more_vert,
                            menuActions = ::pageMenuActions
                        )
                    ),
                    bottomActions = listOf(
                        AppManagementAction(
                            text = getString(R.string.enable_selection),
                            onClick = ::enableSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.disable_selection),
                            onClick = ::disableSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.add_group),
                            onClick = ::selectionAddToGroups
                        ),
                        AppManagementAction(
                            text = getString(R.string.remove_group),
                            onClick = ::selectionRemoveFromGroups
                        ),
                        AppManagementAction(
                            text = getString(R.string.selection_to_top),
                            onClick = ::topSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.selection_to_bottom),
                            onClick = ::bottomSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.export_selection),
                            onClick = ::exportSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.share_selected_source),
                            onClick = ::shareSelected
                        ),
                        AppManagementAction(
                            text = getString(R.string.check_selected_interval),
                            onClick = ::checkSelectedInterval
                        ),
                        AppManagementAction(
                            text = getString(R.string.delete),
                            danger = true,
                            onClick = ::delSourceDialog
                        )
                    ),
                    onBack = { finish() },
                    onSelectAll = { selectAll(true) },
                    onInvertSelection = { revertSelection() }
                ) {
                    RssSourceScreen(
                        sources = sourcesState,
                        selectedUrls = selectedUrls.value,
                        isSelectMode = isSelectMode.value,
                        reorderEnabled = searchQueryState.value.isBlank(),
                        onReorder = { reordered -> viewModel.upOrder(reordered) },
                        onToggleSelect = ::toggleSourceSelection,
                        onToggleEnabled = ::toggleSourceEnabled,
                        onEdit = ::editSource,
                        sourceMenuActions = ::sourceMenuActions
                    )
                }
            }
        }
        container.addView(cv, index)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rss_source, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupMenu()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add -> startActivity<RssSourceEditActivity>()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }

            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_group_manage -> showDialogFragment<GroupManageDialog>()
            R.id.menu_import_default -> viewModel.importDefault()
            R.id.menu_enabled_group -> {
                updateSearchQuery(getString(R.string.enabled))
            }

            R.id.menu_disabled_group -> {
                updateSearchQuery(getString(R.string.disabled))
            }

            R.id.menu_group_login -> {
                updateSearchQuery(getString(R.string.need_login))
            }

            R.id.menu_group_null -> {
                updateSearchQuery(getString(R.string.no_group))
            }

            R.id.menu_help -> showHelp("SourceMRssHelp")
            else -> if (item.groupId == R.id.source_group) {
                updateSearchQuery("group:${item.title}")
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun showFilterMenu() {
        val fixedLabels = listOf(
            getString(R.string.group_manage),
            getString(R.string.enabled),
            getString(R.string.disabled),
            getString(R.string.need_login),
            getString(R.string.no_group)
        )
        val labels = fixedLabels + groups
        showComposeActionListDialog(
            title = getString(R.string.menu_action_group),
            labels = labels
        ) { index ->
            when (index) {
                0 -> showDialogFragment<GroupManageDialog>()
                1 -> updateSearchQuery(getString(R.string.enabled))
                2 -> updateSearchQuery(getString(R.string.disabled))
                3 -> updateSearchQuery(getString(R.string.need_login))
                4 -> updateSearchQuery(getString(R.string.no_group))
                else -> updateSearchQuery("group:${labels[index]}")
            }
        }
    }

    private fun pageMenuActions(): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.import_local)) {
                importDoc.launch {
                    mode = HandleFileContract.FILE
                    allowExtensions = arrayOf("txt", "json")
                }
            },
            AppManagementMenuAction(getString(R.string.import_on_line)) {
                showImportDialog()
            },
            AppManagementMenuAction(getString(R.string.import_by_qr_code)) {
                qrCodeResult.launch()
            },
            AppManagementMenuAction(getString(R.string.import_default_rule)) {
                viewModel.importDefault()
            },
            AppManagementMenuAction(getString(R.string.help)) {
                showHelp("SourceMRssHelp")
            }
        )
    }

    private fun initSelectActionBar() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.rss_source_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun initGroupFlow() {
        lifecycleScope.launch {
            appDb.rssSourceDao.flowGroups().conflate().collect {
                groups.clear()
                groups.addAll(it)
                upGroupMenu()
            }
        }
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_enable_selection -> enableSelected()
            R.id.menu_disable_selection -> disableSelected()
            R.id.menu_add_group -> selectionAddToGroups()
            R.id.menu_remove_group -> selectionRemoveFromGroups()
            R.id.menu_top_sel -> topSelected()
            R.id.menu_bottom_sel -> bottomSelected()
            R.id.menu_export_selection -> exportSelected()
            R.id.menu_share_source -> shareSelected()
            R.id.menu_check_selected_interval -> checkSelectedInterval()
        }
        return true
    }

    private fun enableSelected() {
        val selection = getSelectedSources()
        updateSelectedEnabled(enabled = true)
        viewModel.enableSelection(selection)
    }

    private fun disableSelected() {
        val selection = getSelectedSources()
        updateSelectedEnabled(enabled = false)
        viewModel.disableSelection(selection)
    }

    private fun topSelected() {
        val selection = getSelectedSources()
        viewModel.topSource(*selection.toTypedArray())
    }

    private fun bottomSelected() {
        val selection = getSelectedSources()
        viewModel.bottomSource(*selection.toTypedArray())
    }

    private fun exportSelected() {
        val selection = getSelectedSources()
        viewModel.saveToFile(selection) { file, name ->
            exportResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    name, file, "application/json"
                )
            }
        }
    }

    private fun shareSelected() {
        val selection = getSelectedSources()
        viewModel.saveToFile(selection) { file, _ ->
            share(file)
        }
    }

    private fun selectionAddToGroups() {
        showComposeSuggestionTextInputDialog(
            title = getString(R.string.add_group),
            hint = getString(R.string.group_name),
            suggestions = groups.toList(),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotEmpty()) {
                    val selection = getSelectedSources()
                    viewModel.selectionAddToGroups(selection, text)
                }
            }
        )
    }

    private fun selectionRemoveFromGroups() {
        showComposeSuggestionTextInputDialog(
            title = getString(R.string.remove_group),
            hint = getString(R.string.group_name),
            suggestions = groups.toList(),
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotEmpty()) {
                    val selection = getSelectedSources()
                    viewModel.selectionRemoveFromGroups(selection, text)
                }
            }
        )
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            selectedUrls.value = sourcesState.map { it.sourceUrl }.toSet()
        } else {
            selectedUrls.value = emptySet()
        }
        isSelectMode.value = selectedUrls.value.isNotEmpty()
        upCountView()
    }

    override fun revertSelection() {
        val allUrls = sourcesState.map { it.sourceUrl }.toSet()
        selectedUrls.value = allUrls - selectedUrls.value
        isSelectMode.value = selectedUrls.value.isNotEmpty()
        upCountView()
    }

    override fun onClickSelectBarMainAction() {
        delSourceDialog()
    }

    private fun delSourceDialog() {
        showComposeConfirmDialog(
            title = getString(R.string.draw),
            message = getString(R.string.sure_del),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                val selection = getSelectedSources()
                viewModel.del(*selection.toTypedArray())
                selectedUrls.value = emptySet()
                isSelectMode.value = false
            }
        )
    }

    private fun upGroupMenu() = groupMenu?.transaction { menu ->
        menu.removeGroup(R.id.source_group)
        groups.forEach {
            menu.add(R.id.source_group, Menu.NONE, Menu.NONE, it)
        }
    }

    private fun upSourceFlow(searchKey: String? = null) {
        sourceFlowJob?.cancel()
        sourceFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> {
                    appDb.rssSourceDao.flowAll()
                }

                searchKey == getString(R.string.enabled) -> {
                    appDb.rssSourceDao.flowEnabled()
                }

                searchKey == getString(R.string.disabled) -> {
                    appDb.rssSourceDao.flowDisabled()
                }

                searchKey == getString(R.string.need_login) -> {
                    appDb.rssSourceDao.flowLogin()
                }

                searchKey == getString(R.string.no_group) -> {
                    appDb.rssSourceDao.flowNoGroup()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.rssSourceDao.flowGroupSearch(key)
                }

                else -> {
                    appDb.rssSourceDao.flowSearch(searchKey)
                }
            }.catch {
                AppLog.put("订阅源管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect {
                sourcesState.replaceByIndex(it, ::sameRssSourceListContent)
                val currentUrls = it.mapTo(mutableSetOf()) { source -> source.sourceUrl }
                selectedUrls.value = selectedUrls.value.filter { url -> url in currentUrls }.toSet()
                isSelectMode.value = selectedUrls.value.isNotEmpty()
                upCountView()
                delay(100)
            }
        }
    }

    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        showComposeTextInputDialog(
            title = getString(R.string.import_on_line),
            hint = "url",
            positiveText = getString(android.R.string.ok),
            negativeText = getString(R.string.cancel),
            onPositive = { text ->
                if (text.isNotBlank()) {
                    if (text.isAbsUrl() && !cacheUrls.contains(text)) {
                        cacheUrls.add(0, text)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(ImportRssSourceDialog(text))
                }
            }
        )
    }

    private fun toggleSourceSelection(source: RssSource) {
        val current = selectedUrls.value.toMutableSet()
        if (source.sourceUrl in current) {
            current.remove(source.sourceUrl)
        } else {
            current.add(source.sourceUrl)
        }
        selectedUrls.value = current
        isSelectMode.value = current.isNotEmpty()
        upCountView()
    }

    private fun toggleSourceEnabled(source: RssSource, enabled: Boolean) {
        val updated = source.copy(enabled = enabled)
        val index = sourcesState.indexOfFirst { it.sourceUrl == source.sourceUrl }
        if (index >= 0) {
            sourcesState[index] = updated
        }
        viewModel.update(updated)
    }

    private fun updateSelectedEnabled(enabled: Boolean) {
        val urls = selectedUrls.value
        if (urls.isEmpty()) return
        sourcesState.indices.forEach { index ->
            val source = sourcesState[index]
            if (source.sourceUrl in urls && source.enabled != enabled) {
                sourcesState[index] = source.copy(enabled = enabled)
            }
        }
    }

    private fun sameRssSourceListContent(old: RssSource, new: RssSource): Boolean {
        return old.sourceUrl == new.sourceUrl &&
            old.sourceName == new.sourceName &&
            old.sourceIcon == new.sourceIcon &&
            old.sourceGroup == new.sourceGroup &&
            old.sourceComment == new.sourceComment &&
            old.enabled == new.enabled &&
            old.loginUrl == new.loginUrl &&
            old.lastUpdateTime == new.lastUpdateTime &&
            old.customOrder == new.customOrder &&
            old.type == new.type &&
            old.preload == new.preload &&
            old.cacheFirst == new.cacheFirst
    }

    private fun updateSearchQuery(query: String) {
        if (searchQueryState.value == query) {
            return
        }
        searchQueryState.value = query
        upSourceFlow(query)
    }

    private fun editSource(source: RssSource) {
        startActivity<RssSourceEditActivity> {
            putExtra("sourceUrl", source.sourceUrl)
        }
    }

    private fun sourceMenuActions(source: RssSource): List<AppManagementMenuAction> {
        return listOf(
            AppManagementMenuAction(getString(R.string.selection_to_top)) {
                viewModel.topSource(source)
            },
            AppManagementMenuAction(getString(R.string.selection_to_bottom)) {
                viewModel.bottomSource(source)
            },
            AppManagementMenuAction(
                text = getString(R.string.delete),
                danger = true
            ) {
                showComposeConfirmDialog(
                    title = getString(R.string.draw),
                    message = getString(R.string.sure_del) + "\n" + source.sourceName,
                    positiveText = getString(R.string.yes),
                    negativeText = getString(R.string.no),
                    dangerPositive = true,
                    onPositive = { viewModel.del(source) }
                )
            }
        )
    }

    private fun getSelectedSources(): List<RssSource> {
        val urls = selectedUrls.value
        return sourcesState.filter { it.sourceUrl in urls }
    }

    private fun checkSelectedInterval() {
        val urls = selectedUrls.value
        val positions = mutableListOf<Int>()
        sourcesState.forEachIndexed { index, source ->
            if (source.sourceUrl in urls) {
                positions.add(index)
            }
        }
        if (positions.isEmpty()) return
        val minPos = positions.min()
        val maxPos = positions.max()
        val newSelected = urls.toMutableSet()
        for (i in minPos..maxPos) {
            newSelected.add(sourcesState[i].sourceUrl)
        }
        selectedUrls.value = newSelected
        upCountView()
    }

    private fun upCountView() {
        binding.selectActionBar.upCountView(
            getSelectedSources().size,
            sourcesState.size
        )
    }

}
