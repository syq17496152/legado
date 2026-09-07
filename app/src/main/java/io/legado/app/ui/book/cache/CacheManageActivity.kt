package io.legado.app.ui.book.cache

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.databinding.ActivityCacheManageBinding
import io.legado.app.help.AppCloudStorage
import io.legado.app.lib.cloud.CloudStorageType
import io.legado.app.lib.cloud.S3ContainerScope
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.cnCompare
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 缓存管理页（my-compose-full W4.1：composeHost + CacheManageScreen 全量重写）。
 *
 * ViewModel 复用（CacheManageActivityViewModel 零改动）；原 CacheManageAdapter 删除，
 * 列表渲染迁移至 [CacheManageScreen]；任务态经 SnapshotStateMap 定向写实现等效 payload 局部刷新。
 * 数据安全边界：删除/上传/恢复链路（含确认弹框与锁定任务门禁）逻辑原样保留在本 Activity。
 */
class CacheManageActivity :
    VMBaseActivity<ActivityCacheManageBinding, CacheManageActivityViewModel>(),
    CacheChapterDialog.Callback {

    companion object {
        const val EXTRA_INITIAL_SEARCH_KEY = "initialSearchKey"
    }

    override val binding by viewBinding(ActivityCacheManageBinding::inflate)
    override val viewModel by viewModels<CacheManageActivityViewModel>()

    private var audioTaskReloadJob: Job? = null
    private var lastMissingTaskReloadAt = 0L
    private val handledTerminalTaskReloads = hashSetOf<String>()
    private var cloudContainerId: String? = null
    private var rawItems: List<CacheBookItem> = emptyList()
    private var searchKey: String = ""
    private var sortMode: CacheManageSortMode = CacheManageSortMode.RECENT

    // W4.1 Compose 桥接状态（Activity 为 VM/任务流与 Screen 的唯一桥）
    private val audioStates = mutableStateMapOf<String, AudioCacheTaskState>()
    private val webDavStates = mutableStateMapOf<String, WebDavTaskState>()
    private var displayItems by mutableStateOf(listOf<CacheBookItem>())
    private var summaryTextState by mutableStateOf("")
    private var loadingState by mutableStateOf(false)
    private var modeState by mutableStateOf(CacheManageMode.BOOK)
    private var containerActionVisible by mutableStateOf(false)

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initComposeHost()
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

    // W4.1：全页 Compose 渲染（顶栏 AppManagementScaffold+tab/列表/批量按钮均在 Screen 内）
    private fun initComposeHost() {
        binding.composeHost.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeHost.setContent {
            LegadoTheme {
                CacheManageScreen(
                    mode = modeState,
                    items = displayItems,
                    summaryText = summaryTextState,
                    loading = loadingState,
                    audioTaskStates = audioStates,
                    webDavTaskStates = webDavStates,
                    containerVisible = containerActionVisible,
                    onModeSwitch = ::switchMode,
                    onSearch = ::showSearchDialog,
                    onSortSelect = ::showSortSelector,
                    onContainerSelect = ::showContainerSelector,
                    onUploadAll = ::uploadAll,
                    onDeleteAll = ::deleteAll,
                    onItemAction = ::dispatchItemAction
                )
            }
        }
    }

    // W4.1：原 8 个 Adapter.Callback 动作改为枚举分发（Screen → Activity 原方法链，确认弹框前置不变）
    private fun dispatchItemAction(item: CacheBookItem, action: CacheItemAction) {
        when (action) {
            CacheItemAction.OPEN_CHAPTERS -> openChapters(item)
            CacheItemAction.UPLOAD -> upload(item)
            CacheItemAction.DOWNLOAD -> download(item)
            CacheItemAction.SELECT_SYNC -> selectSyncAction(item)
            CacheItemAction.RESTORE_BOOKSHELF -> restoreToBookshelf(item)
            CacheItemAction.DELETE -> deleteBookCache(item)
            CacheItemAction.STOP_AUDIO -> stopAudioCache(item)
            CacheItemAction.SELECT_SOURCE -> selectSource(item)
        }
    }

    /** 搜索：弹出关键词输入框，就地过滤列表。 */
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
        if (AppCloudStorage.type != CloudStorageType.S3) {
            cloudContainerId = containers.firstOrNull()?.id
            containerActionVisible = false
            return
        }
        cloudContainerId = AppCloudStorage.selectedContainer(S3ContainerScope.CACHE)?.id
            ?: containers.firstOrNull()?.id
        containerActionVisible = true
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
            summaryTextState = getString(
                R.string.cache_manage_summary_state,
                summary.bookCount,
                summary.cachedChapterCount
            )
        }
        viewModel.loadingLiveData.observe(this) { loading ->
            loadingState = loading
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
        displayItems = rawItems
            .asSequence()
            .filter { it.matchesSearch(searchKey) }
            .sortedWith(sortMode.comparator())
            .toList()
    }

    private fun observeTasks() {
        lifecycleScope.launch {
            AudioCacheTaskManager.states.collectLatest { states ->
                // 定向 diff 写入（等效原 Adapter PAYLOAD_TASK_STATE 局部刷新，避免整表重组）
                val changed = (audioStates.keys + states.keys)
                    .filterTo(hashSetOf()) { audioStates[it] != states[it] }
                changed.forEach { key ->
                    states[key]?.let { audioStates[key] = it } ?: audioStates.remove(key)
                }
                if (viewModel.mode == CacheManageMode.AUDIO) {
                    reloadAudioItemsWhenNeeded(states)
                }
            }
        }
        lifecycleScope.launch {
            WebDavTaskManager.states.collectLatest { states ->
                val changed = (webDavStates.keys + states.keys)
                    .filterTo(hashSetOf()) { webDavStates[it] != states[it] }
                changed.forEach { key ->
                    states[key]?.let { webDavStates[key] = it } ?: webDavStates.remove(key)
                }
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
            displayItems.forEach { item ->
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
        modeState = mode
        rawItems = emptyList()
        applyFilters()
        viewModel.load(mode)
    }

    private fun openChapters(item: CacheBookItem) {
        if (item.localCachedCount <= 0) {
            toastOnUi(R.string.cache_manage_download_first)
            return
        }
        showDialogFragment(CacheChapterDialog.newInstance(item.book))
    }

    private fun upload(item: CacheBookItem) {
        selectSyncStrategy(R.string.cache_manage_upload_strategy_title) { strategy ->
            val queued = WebDavTaskManager.enqueueCacheUpload(item) {
                viewModel.uploadCacheItem(item, strategy)
            }
            toastOnUi(if (queued) R.string.cache_manage_upload_queued else R.string.cache_manage_webdav_task_duplicate)
        }
    }

    private fun download(item: CacheBookItem) {
        selectSyncStrategy(R.string.cache_manage_download_strategy_title) { strategy ->
            val queued = WebDavTaskManager.enqueueCacheDownload(item) {
                viewModel.downloadRemoteCache(item, strategy)
            }
            toastOnUi(if (queued) R.string.cache_manage_download_queued else R.string.cache_manage_webdav_task_duplicate)
        }
    }

    private fun selectSyncAction(item: CacheBookItem) {
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

    private fun restoreToBookshelf(item: CacheBookItem) {
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

    private fun deleteBookCache(item: CacheBookItem) {
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

    private fun stopAudioCache(item: CacheBookItem) {
        AudioCacheTaskManager.togglePause(item.book.bookUrl)
    }

    private fun selectSource(item: CacheBookItem) {
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
        val items = displayItems.filter { it.cachedCount > 0 && !it.hasLockedCacheTask() }
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
        val items = displayItems.filter {
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
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = message,
            dangerPositive = true,
            onPositive = onConfirmed
        )
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
