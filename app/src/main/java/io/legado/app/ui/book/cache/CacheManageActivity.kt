package io.legado.app.ui.book.cache

import android.os.Bundle
import android.graphics.Color
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.widget.AppCompatImageButton
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.SimpleItemAnimator
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityCacheManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.lib.dialogs.AndroidAlertBuilder
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.ui.widget.MainTopBarView
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.gone
import io.legado.app.utils.cnCompare
import io.legado.app.utils.applyTint
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.visible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CacheManageActivity :
    VMBaseActivity<ActivityCacheManageBinding, CacheManageActivityViewModel>(),
    CacheManageAdapter.Callback,
    CacheChapterDialog.Callback {

    companion object {
        const val EXTRA_INITIAL_SEARCH_KEY = "initialSearchKey"
    }

    override val binding by viewBinding(ActivityCacheManageBinding::inflate)
    override val viewModel by viewModels<CacheManageActivityViewModel>()

    private val adapter by lazy { CacheManageAdapter(this, this) }
    private var audioTaskReloadJob: Job? = null
    private var lastMissingTaskReloadAt = 0L
    private val handledTerminalTaskReloads = hashSetOf<String>()
    private var cloudContainerId: String? = null
    private var containerActionButton: AppCompatImageButton? = null
    private var rawItems: List<CacheBookItem> = emptyList()
    private var searchKey: String = ""
    private var sortMode: CacheManageSortMode = CacheManageSortMode.RECENT

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initTopBar()
        initView()
        observeData()
        observeTasks()
        val initialSearchKey = intent.getStringExtra(EXTRA_INITIAL_SEARCH_KEY).orEmpty().trim()
        if (initialSearchKey.isNotBlank()) {
            searchKey = initialSearchKey
        }
        viewModel.load(CacheManageMode.BOOK)
    }

    override fun onResume() {
        super.onResume()
        updateContainerMenu()
    }

    private fun initView() = binding.run {
        tabBar.background = UiCorner.opaqueRounded(
            themeMutedColorOrDefault(),
            UiCorner.panelRadius(this@CacheManageActivity)
        )
        listOf(btnBooks, btnAudio, btnManga).forEach {
            it.background = UiCorner.actionSelector(
                Color.TRANSPARENT,
                themeCardColorOrDefault(),
                UiCorner.actionRadius(this@CacheManageActivity)
            )
        }
        recyclerView.layoutManager = LinearLayoutManager(this@CacheManageActivity)
        recyclerView.adapter = adapter
        (recyclerView.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        btnBooks.setOnClickListener { switchMode(CacheManageMode.BOOK) }
        btnAudio.setOnClickListener { switchMode(CacheManageMode.AUDIO) }
        btnManga.setOnClickListener { switchMode(CacheManageMode.MANGA) }
        btnUploadAll.setOnClickListener { uploadAll() }
        btnDeleteAll.setOnClickListener { deleteAll() }
        updateTabs(CacheManageMode.BOOK)
    }

    /** subpage-topbar-unify: 子页头部统一为 MainTopBarView(Mode.SUB)，原工具栏搜索/排序/容器切换改为 action 插槽图标。 */
    private fun initTopBar() = binding.titleBar.run {
        applyStatusBarPadding(withInitialPadding = true)
        setMode(MainTopBarView.Mode.SUB)
        setTitle(getString(R.string.cache_manage_title))
        // Mode.SUB 下 titleSelect 显示「标题+返回箭头」，点击回退
        setSearchEntryVisible(false)
        titleSelect.setOnClickListener { finish() }
        addActionButton(R.drawable.ic_search, R.string.cache_manage_search_book) {
            showSearchDialog()
        }
        addActionButton(R.drawable.ic_baseline_sort_24, R.string.cache_manage_sort_title) {
            showSortSelector()
        }
        containerActionButton = addActionButton(R.drawable.ic_outline_cloud_24, R.string.s3_bucket) {
            showContainerSelector()
        }
        updateContainerMenu()
    }

    /** 搜索：弹出关键词输入框，就地过滤列表（原 ActionBar 折叠式搜索的等价替代）。 */
    private fun showSearchDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.cache_manage_search_book),
            hint = getString(R.string.cache_manage_search_book),
            initialValue = searchKey,
            positiveText = getString(android.R.string.ok),
            negativeText = getString(android.R.string.cancel),
            onPositive = {
                updateSearchKey(it.trim())
            }
        )
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        return super.onCompatOptionsItemSelected(item)
    }

    private fun updateContainerMenu() {
        val containers = AppCloudStorage.listContainers().filter { it.enabled }
        val item = containerActionButton ?: return
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            item.isVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(S3ContainerScope.CACHE)?.id
            ?: containers.firstOrNull()?.id
        item.isVisible = true
    }

    private fun showContainerSelector() {
        lifecycleScope.launch {
            val containers = withContext(Dispatchers.IO) {
                AppCloudStorage.listContainers().filter { it.enabled }
            }
            if (containers.isEmpty()) {
                toastOnUi(R.string.cloud_storage_config_required)
                return@launch
            }
            val selected = cloudContainerId ?: AppCloudStorage.selectedContainer(S3ContainerScope.CACHE)?.id
            showComposeChoiceListDialog(
                title = getString(R.string.s3_bucket),
                labels = containers.map(AppCloudStorage::containerDisplayLabel)
            ) { index ->
                val container = containers.getOrNull(index) ?: return@showComposeChoiceListDialog
                if (container.id == selected) return@showComposeChoiceListDialog
                AppCloudStorage.selectContainer(S3ContainerScope.CACHE, container.id)
                cloudContainerId = container.id
                updateContainerMenu()
                viewModel.clearDisplay()
                viewModel.load()
            }
        }
    }

    private fun observeData() {
        viewModel.itemsLiveData.observe(this) { items ->
            rawItems = items
            applyFilters()
        }
        viewModel.summaryLiveData.observe(this) { summary ->
            binding.tvSummary.text = getString(
                R.string.cache_manage_summary_state,
                summary.bookCount,
                summary.cachedChapterCount
            )
        }
        viewModel.loadingLiveData.observe(this) { loading ->
            if (loading) binding.rotateLoading.visible() else binding.rotateLoading.gone()
        }
    }

    private fun updateSearchKey(key: String) {
        val value = key.trim()
        if (searchKey == value) return
        searchKey = value
        applyFilters()
    }

    private fun showSortSelector() {
        val modes = CacheManageSortMode.entries
        showComposeChoiceListDialog(
            title = getString(R.string.cache_manage_sort_title),
            labels = modes.map { getString(it.titleRes) }
        ) { index ->
            val mode = modes.getOrNull(index) ?: return@showComposeChoiceListDialog
            if (sortMode == mode) return@showComposeChoiceListDialog
            sortMode = mode
            applyFilters()
        }
    }

    private fun applyFilters() {
        val items = rawItems
            .asSequence()
            .filter { it.matchesSearch(searchKey) }
            .sortedWith(sortMode.comparator())
            .toList()
        adapter.setItems(items)
        binding.tvEmpty.run {
            if (items.isEmpty()) {
                text = getString(R.string.cache_manage_empty, getString(viewModel.mode.titleRes))
                visible()
            } else {
                gone()
            }
        }
    }

    private fun observeTasks() {
        lifecycleScope.launch {
            AudioCacheTaskManager.states.collectLatest { states ->
                adapter.updateTaskStates(states)
                if (viewModel.mode == CacheManageMode.AUDIO) {
                    reloadAudioItemsWhenNeeded(states)
                }
            }
        }
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                adapter.updateWebDavTaskStates(states)
                reloadItemsWhenWebDavTaskFinished(states)
            }
        }
    }

    private fun reloadItemsWhenWebDavTaskFinished(states: Map<String, WebDavTaskState>) {
        states.values
            .filter { !it.active && it.status.isTerminalForListRefresh() }
            .forEach { state ->
                val key = "webdav:${state.key}:${state.type}:${state.status}"
                if (handledTerminalTaskReloads.add(key)) {
                    viewModel.load()
                }
            }
    }
    private fun reloadAudioItemsWhenNeeded(states: Map<String, AudioCacheTaskState>) {
        val stateValues = states.values
        val activeTaskBookUrls = stateValues
            .asSequence()
            .filter { it.active }
            .mapTo(hashSetOf<String>()) { it.bookUrl }
        if (activeTaskBookUrls.isNotEmpty()) {
            val visibleBookUrls = hashSetOf<String>()
            adapter.getItems().forEach { item ->
                if (item.sourceVariants.isEmpty()) {
                    visibleBookUrls.add(item.book.bookUrl)
                } else {
                    item.sourceVariants.forEach { visibleBookUrls.add(it.book.bookUrl) }
                }
            }
            val missingActiveTasks = activeTaskBookUrls - visibleBookUrls
            if (missingActiveTasks.isNotEmpty()) {
                val now = System.currentTimeMillis()
                if (now - lastMissingTaskReloadAt > MISSING_TASK_RELOAD_INTERVAL_MS && !viewModel.isLoading()) {
                    lastMissingTaskReloadAt = now
                    scheduleAudioTaskReload(MISSING_TASK_RELOAD_DELAY_MS)
                }
            }
        }
        stateValues
            .filter { !it.active && it.status.isTerminalForListRefresh() }
            .forEach { state ->
                val key = "${state.bookUrl}:${state.status}:${state.completedChapters}:${state.totalChapters}"
                if (handledTerminalTaskReloads.add(key)) {
                    scheduleAudioTaskReload(TERMINAL_TASK_RELOAD_DELAY_MS)
                }
            }
    }

    private fun scheduleAudioTaskReload(delayMs: Long) {
        if (audioTaskReloadJob?.isActive == true) return
        audioTaskReloadJob = lifecycleScope.launch {
            delay(delayMs)
            if (viewModel.mode == CacheManageMode.AUDIO && !viewModel.isLoading()) {
                viewModel.load(CacheManageMode.AUDIO)
            }
        }
    }

    private fun switchMode(mode: CacheManageMode) {
        if (viewModel.mode == mode) return
        updateTabs(mode)
        rawItems = emptyList()
        applyFilters()
        viewModel.load(mode)
    }

    private fun updateTabs(mode: CacheManageMode) = binding.run {
        btnBooks.isSelected = mode == CacheManageMode.BOOK
        btnAudio.isSelected = mode == CacheManageMode.AUDIO
        btnManga.isSelected = mode == CacheManageMode.MANGA
        btnBooks.setTextColor(if (mode == CacheManageMode.BOOK) accentColor else primaryTextColor)
        btnAudio.setTextColor(if (mode == CacheManageMode.AUDIO) accentColor else primaryTextColor)
        btnManga.setTextColor(if (mode == CacheManageMode.MANGA) accentColor else primaryTextColor)
    }

    override fun openChapters(item: CacheBookItem) {
        if (item.localCachedCount <= 0) {
            toastOnUi(R.string.cache_manage_download_first)
            return
        }
        showDialogFragment(CacheChapterDialog.newInstance(item.book))
    }

    override fun upload(item: CacheBookItem) {
        selectSyncStrategy(R.string.cache_manage_upload_strategy_title) { strategy ->
            val queued = WebDavTaskManager.enqueueCacheUpload(item) {
                viewModel.uploadCacheItem(item, strategy)
            }
            toastOnUi(if (queued) R.string.cache_manage_upload_queued else R.string.cache_manage_webdav_task_duplicate)
        }
    }

    override fun download(item: CacheBookItem) {
        selectSyncStrategy(R.string.cache_manage_download_strategy_title) { strategy ->
            val queued = WebDavTaskManager.enqueueCacheDownload(item) {
                viewModel.downloadRemoteCache(item, strategy)
            }
            toastOnUi(if (queued) R.string.cache_manage_download_queued else R.string.cache_manage_webdav_task_duplicate)
        }
    }

    override fun selectSyncAction(item: CacheBookItem) {
        val actions = listOf(
            R.string.cache_manage_upload to { upload(item) },
            R.string.action_download to { download(item) }
        )
        showComposeChoiceListDialog(
            title = getString(R.string.cache_manage_sync_action),
            labels = actions.map { getString(it.first) }
        ) { index ->
            actions.getOrNull(index)?.second?.invoke()
        }
    }

    override fun restoreToBookshelf(item: CacheBookItem) {
        lifecycleScope.launch {
            kotlin.runCatching {
                viewModel.restoreCacheToBookshelf(item)
            }.onSuccess { success ->
                if (success) {
                    toastOnUi(
                        if (item.inBookshelf) R.string.cache_manage_use_cache_success
                        else R.string.cache_manage_add_bookshelf_success
                    )
                    viewModel.load()
                } else {
                    toastOnUi(R.string.cache_manage_no_cache)
                }
            }.onFailure {
                toastOnUi(it.localizedMessage ?: getString(R.string.error))
            }
        }
    }

    override fun deleteBookCache(item: CacheBookItem) {
        selectDeleteTarget(
            item = item,
            localAvailable = item.localCachedCount > 0,
            remoteAvailable = item.hasRemoteCache()
        ) { target ->
            confirmDeleteTarget(target, 1) {
                viewModel.deleteBookCache(item, target) { result ->
                    toastDeleteResult(result)
                }
            }
        }
    }

    override fun stopAudioCache(item: CacheBookItem) {
        AudioCacheTaskManager.togglePause(item.book.bookUrl)
    }

    override fun selectSource(item: CacheBookItem) {
        val variants = item.sourceVariants
        if (variants.size <= 1) return
        val labels: List<CharSequence> = variants.map { variant ->
            buildString {
                append(
                    if (variant.sourceAvailable) {
                        variant.sourceName
                    } else {
                        getString(R.string.cache_manage_source_deleted, variant.sourceName)
                    }
                )
                append(" · ")
                append(variant.cacheCountText(this@CacheManageActivity))
            }
        }
        showComposeChoiceListDialog(
            title = getString(R.string.cache_manage_select_source),
            labels = labels
        ) { index ->
            val variant = variants.getOrNull(index) ?: return@showComposeChoiceListDialog
            viewModel.selectSource(item.groupKey, variant.sourceKey)
        }
    }

    private fun uploadAll() {
        val items = adapter.getItems().filter { it.cachedCount > 0 && !it.hasLockedCacheTask() }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        val queued = items.count { item ->
            WebDavTaskManager.enqueueCacheUpload(item) {
                viewModel.uploadCacheItem(item)
            }
        }
        toastOnUi(getString(R.string.cache_manage_batch_upload_queued, queued))
    }

    private fun deleteAll() {
        val items = adapter.getItems().filter {
            !it.hasLockedCacheTask() && (it.localCachedCount > 0 || it.hasRemoteCache())
        }
        if (items.isEmpty()) {
            toastOnUi(R.string.cache_manage_batch_empty)
            return
        }
        val localAvailable = items.any { it.localCachedCount > 0 }
        val remoteAvailable = items.any { it.hasRemoteCache() }
        selectDeleteTarget(localAvailable = localAvailable, remoteAvailable = remoteAvailable) { target ->
            val targets = items.filter { item -> target.canDelete(item) }
            if (targets.isEmpty()) {
                toastOnUi(R.string.cache_manage_batch_empty)
                return@selectDeleteTarget
            }
            confirmDeleteTarget(target, targets.size) {
                viewModel.deleteBookCaches(targets, target) { result ->
                    toastDeleteResult(result)
                }
            }
        }
    }

    private fun selectDeleteTarget(
        item: CacheBookItem? = null,
        localAvailable: Boolean,
        remoteAvailable: Boolean,
        onSelected: (CacheDeleteTarget) -> Unit
    ) {
        val targets = CacheDeleteTarget.entries.filter { target ->
            when (target) {
                CacheDeleteTarget.LOCAL -> localAvailable
                CacheDeleteTarget.REMOTE -> remoteAvailable
                CacheDeleteTarget.BOTH -> localAvailable && remoteAvailable
            }
        }
        if (targets.isEmpty()) {
            toastOnUi(R.string.cache_manage_no_cache)
            return
        }
        if (targets.size == 1) {
            onSelected(targets.first())
            return
        }
        val title = item?.let { getString(R.string.cache_manage_delete_book_title, it.book.name) }
            ?: getString(R.string.delete)
        showComposeChoiceListDialog(
            title = title,
            labels = targets.map { getString(it.labelRes) }
        ) { index ->
            targets.getOrNull(index)?.let(onSelected)
        }
    }

    private fun confirmDeleteTarget(
        target: CacheDeleteTarget,
        count: Int,
        onConfirmed: () -> Unit
    ) {
        val message = when (target) {
            CacheDeleteTarget.LOCAL -> getString(R.string.cache_manage_delete_local_confirm, count)
            CacheDeleteTarget.REMOTE -> getString(R.string.cache_manage_delete_remote_confirm, count)
            CacheDeleteTarget.BOTH -> getString(R.string.cache_manage_delete_both_confirm, count)
        }
        AndroidAlertBuilder(this).apply {
            setTitle(R.string.delete)
            setMessage(message)
            yesButton { onConfirmed() }
            noButton()
            show()
        }
    }

    private fun selectSyncStrategy(
        titleRes: Int,
        onSelected: (CacheSyncStrategy) -> Unit
    ) {
        val strategies = CacheSyncStrategy.entries
        showComposeChoiceListDialog(
            title = getString(titleRes),
            labels = strategies.map { getString(it.labelRes) }
        ) { index ->
            strategies.getOrNull(index)?.let(onSelected)
        }
    }

    private fun toastDeleteResult(result: CacheDeleteResult) {
        result.messageRes?.let {
            toastOnUi(it)
            return
        }
        toastOnUi(result.errorMessage ?: getString(R.string.error))
    }

    override fun onCacheChanged() {
        viewModel.load()
    }

    override fun openCacheChapter(book: Book, chapter: BookChapter) {
        val target = book.apply {
            durChapterIndex = chapter.index
            durChapterTitle = chapter.title
            durChapterPos = 0
        }
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                appDb.bookDao.update(target)
            }
            startActivityForBook(target)
        }
    }
}

private fun CacheTaskStatus.isTerminalForListRefresh(): Boolean {
    return this == CacheTaskStatus.COMPLETED ||
        this == CacheTaskStatus.PAUSED ||
        this == CacheTaskStatus.CANCELLED ||
        this == CacheTaskStatus.FAILED
}

private fun WebDavTaskStatus.isTerminalForListRefresh(): Boolean {
    return this == WebDavTaskStatus.COMPLETED ||
        this == WebDavTaskStatus.CANCELLED ||
        this == WebDavTaskStatus.FAILED
}

private const val MISSING_TASK_RELOAD_INTERVAL_MS = 2500L
private const val MISSING_TASK_RELOAD_DELAY_MS = 250L
private const val TERMINAL_TASK_RELOAD_DELAY_MS = 600L

enum class CacheManageSortMode(val titleRes: Int) {
    RECENT(R.string.cache_manage_sort_time),
    LAST_READ(R.string.cache_manage_sort_last_read),
    NAME(R.string.cache_manage_sort_name);

    fun comparator(): Comparator<CacheBookItem> {
        return when (this) {
            RECENT -> Comparator { o1, o2 ->
                o2.lastCacheUpdatedAt().compareTo(o1.lastCacheUpdatedAt()).takeIf { it != 0 }
                    ?: o1.book.name.cnCompare(o2.book.name).takeIf { it != 0 }
                    ?: o1.sourceName.cnCompare(o2.sourceName)
            }
            LAST_READ -> Comparator { o1, o2 ->
                o2.lastReadAt().compareTo(o1.lastReadAt()).takeIf { it != 0 }
                    ?: o1.book.name.cnCompare(o2.book.name).takeIf { it != 0 }
                    ?: o1.sourceName.cnCompare(o2.sourceName).takeIf { it != 0 }
                    ?: o2.lastCacheUpdatedAt().compareTo(o1.lastCacheUpdatedAt())
            }
            NAME -> Comparator { o1, o2 ->
                o1.book.name.cnCompare(o2.book.name).takeIf { it != 0 }
                    ?: o1.sourceName.cnCompare(o2.sourceName).takeIf { it != 0 }
                    ?: o2.lastCacheUpdatedAt().compareTo(o1.lastCacheUpdatedAt())
            }
        }
    }
}

private fun CacheBookItem.matchesSearch(key: String): Boolean {
    if (key.isBlank()) return true
    return book.name.contains(key, ignoreCase = true) ||
        book.author.contains(key, ignoreCase = true) ||
        sourceName.contains(key, ignoreCase = true) ||
        sourceVariants.any {
            it.book.name.contains(key, ignoreCase = true) ||
                it.book.author.contains(key, ignoreCase = true) ||
                it.sourceName.contains(key, ignoreCase = true)
        }
}

private fun CacheBookItem.lastCacheUpdatedAt(): Long {
    val variantTime = sourceVariants.maxOfOrNull {
        maxOf(it.localUpdatedAt, it.remoteUpdatedAt)
    } ?: 0L
    return maxOf(localUpdatedAt, remoteUpdatedAt, variantTime)
}

private fun CacheBookItem.lastReadAt(): Long {
    val variantTime = sourceVariants.maxOfOrNull { variant ->
        variant.book.durChapterTime.takeIf { variant.inBookshelf } ?: 0L
    } ?: 0L
    val itemTime = book.durChapterTime.takeIf { inBookshelf } ?: 0L
    return maxOf(itemTime, variantTime)
}

private fun CacheBookItem.hasLockedCacheTask(): Boolean {
    if (AudioCacheTaskManager.snapshot(book.bookUrl).locksCacheActions()) return true
    if (WebDavTaskManager.snapshot(cacheKey)?.active == true) return true
    return sourceVariants.any {
        AudioCacheTaskManager.snapshot(it.book.bookUrl).locksCacheActions() ||
            WebDavTaskManager.snapshot(it.cacheKey)?.active == true
    }
}

private fun AudioCacheTaskState?.locksCacheActions(): Boolean {
    return this?.active == true || this?.status == CacheTaskStatus.PAUSED
}

private fun CacheDeleteTarget.canDelete(item: CacheBookItem): Boolean {
    return when (this) {
        CacheDeleteTarget.LOCAL -> item.localCachedCount > 0
        CacheDeleteTarget.REMOTE -> item.hasRemoteCache()
        CacheDeleteTarget.BOTH -> item.localCachedCount > 0 || item.hasRemoteCache()
    }
}
