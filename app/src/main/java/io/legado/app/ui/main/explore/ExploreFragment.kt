package io.legado.app.ui.main.explore

import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteBlobTooBigException
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.AutoCompleteTextView
import android.view.SubMenu
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.AppCompatSpinner
import androidx.core.widget.NestedScrollView
import com.google.android.flexbox.FlexboxLayout
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isGone
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import com.script.rhino.runScriptWithContext
import io.legado.app.R
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.base.VMBaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.Cache
import io.legado.app.data.entities.SearchBook
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ItemFilletCompleteTextBinding
import io.legado.app.databinding.ItemFilletSelectorSingleBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemFindBookBinding
import io.legado.app.databinding.FragmentExploreBinding
import io.legado.app.help.CoverDisplayResolver
import io.legado.app.help.CoverThumbnailCache
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.glide.OkHttpModelLoader
import io.legado.app.help.source.clearExploreKindsCache
import io.legado.app.help.source.exploreKinds
import io.legado.app.help.webView.WebViewPool
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.dialogs.selector
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.applyUiSectionTitleStyle
import io.legado.app.lib.theme.applyUiTitleTypeface
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.lib.theme.themeMutedColorOrDefault
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.lib.theme.UiCorner
import io.legado.app.model.webBook.WebBook
import io.legado.app.ui.book.explore.ExploreShowActivity
import io.legado.app.ui.book.explore.ExploreShowBookCallback
import io.legado.app.ui.book.explore.ExploreShowWaterfallAdapter
import io.legado.app.ui.book.SearchBookOpenHelper
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.book.source.edit.BookSourceEditActivity
import io.legado.app.ui.login.SourceLoginActivity
import io.legado.app.ui.login.SourceLoginJsExtensions
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.video.VideoBookPreloader
import io.legado.app.ui.widget.ModernActionPopup
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.ui.widget.SourceSelectDialog
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.showComposeActionListDialog
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeMultiChoiceDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.SearchBookMergeUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.applyStatusBarPadding
import io.legado.app.utils.applyTint
import io.legado.app.utils.dpToPx
import io.legado.app.utils.flowWithLifecycleAndDatabaseChange
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.gone
import io.legado.app.utils.InfoMap
import io.legado.app.utils.MD5Utils
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setSelectionSafely
import io.legado.app.utils.startActivity
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.transaction
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.windowSize
import io.legado.app.ui.widget.text.AccentTextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 发现页面
 */
class ExploreFragment() : VMBaseFragment<ExploreViewModel>(R.layout.fragment_explore),
    MainFragmentInterface,
    ExploreAdapter.CallBack,
    ExploreShowBookCallback {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    override val position: Int? get() = arguments?.getInt("position")

    override val viewModel by viewModels<ExploreViewModel>()
    private val binding by viewBinding(FragmentExploreBinding::bind)
    private val adapter by lazy { ExploreAdapter(requireContext(), this) }
    private val linearLayoutManager by lazy { LinearLayoutManager(context) }
    private var discoverBookAdapter: RecyclerAdapter<SearchBook, *>? = null
    private var discoverBookLayoutMode = 0
    private val searchView: SearchView? by lazy {
        binding.titleBar.findViewById<SearchView?>(R.id.search_view)
    }
    private val diffItemCallBack = ExploreDiffItemCallBack()
    private val groups = linkedSetOf<String>()
    private var exploreFlowJob: Job? = null
    private var groupsMenu: SubMenu? = null
    private var oldModeInitialized = false
    private var modernModeInitialized = false
    private var discoveryPageMode = AppConfig.DISCOVERY_PAGE_MODE_MODERN
    private var usingModernDiscovery = false
    private var usingSuiteDiscovery = false
    private var sourceMenuPopup: PopupWindow? = null
    private var tagFilterPopup: ModernActionPopup.Handle? = null
    private var discoverSourceFlowJob: Job? = null
    private var discoverBookshelfFlowJob: Job? = null
    private var discoverWarmupJob: Job? = null
    private var discoverLoadJob: Job? = null
    private var discoverActionJob: Job? = null
    private var suiteLoadJob: Job? = null
    private val discoverSources = mutableListOf<BookSourcePart>()
    private val discoverAllTagItems = mutableListOf<DiscoverTagItem>()
    private val discoverTagItems = mutableListOf<DiscoverTagItem>()
    private val discoverSelectItems = mutableListOf<DiscoverTagItem>()
    private val discoverMajorGroups = mutableListOf<String>()
    private val discoverBookshelf = linkedSetOf<String>()
    private val discoverBooks = linkedSetOf<SearchBook>()
    private val composeDiscoverBooks = mutableStateListOf<SearchBook>()
    private val composeDiscoverLoading = mutableStateOf(false)
    private val composeDiscoverHasMore = mutableStateOf(true)
    private val composeDiscoverBookshelfVersion = mutableIntStateOf(0)
    private val composeDiscoverTopPadding = mutableIntStateOf(0)
    private val composeDiscoverLayoutMode = mutableIntStateOf(AppConfig.discoveryPageLayout)
    private val composeDiscoverListStyle = mutableIntStateOf(AppConfig.bookshelfListItemStyle)
    private val composeDiscoverScrollToTopSignal = mutableIntStateOf(0)
    private val composeSuiteScrollToTopSignal = mutableIntStateOf(0)
    private val composeSuiteConfig = mutableStateOf(DiscoverySuiteStore.load())
    private val composeSelectedSuiteId = mutableStateOf(DiscoverySuiteStore.selectedSuiteId())
    private val composeSuiteWidgetBooks = mutableStateMapOf<String, List<SearchBook>>()
    private val composeSuiteRankedWidgetBooks = mutableStateMapOf<String, Map<String, List<SearchBook>>>()
    private val composeSuiteLoadingWidgets = mutableStateMapOf<String, Boolean>()
    private val suiteWidgetSignatures = hashMapOf<String, String>()
    private val suiteRandomDecks = hashMapOf<String, SuiteRandomDeck>()
    private val suitePreparedRandomBatches = hashMapOf<String, SuitePreparedBatch>()
    private val suiteRandomPrepareJobs = hashMapOf<String, Job>()
    private val suiteHorizontalPagingStates = hashMapOf<String, SuiteHorizontalPagingState>()
    private val suiteRankedPagingStates = hashMapOf<String, SuiteRankedPagingState>()
    private val suiteWidgetLoadSemaphore = Semaphore(SUITE_WIDGET_LOAD_PARALLELISM)
    private val suiteTargetLoadSemaphore = Semaphore(SUITE_TARGET_LOAD_PARALLELISM)
    private val suiteCoverPreloadSemaphore = Semaphore(SUITE_COVER_PRELOAD_PARALLELISM)
    private var composeDiscoverCanScrollBackward = false
    private var composeSuiteCanScrollBackward = false
    private var composeDiscoverBooksSignature = ""
    private val blockedButtonActions = hashMapOf<String, MutableSet<String>>()
    private var selectedDiscoverSourcePart: BookSourcePart? = null
    private var selectedDiscoverSource: BookSource? = null
    private var discoverCurrentUrl: String? = null
    private var discoverPage = 1
    private var discoverHasMore = true
    private var discoverLoading = false
    private var selectedDiscoverMajorGroup: String? = null
    private var selectedDiscoverTagIndex = -1
    private var selectedDiscoverUrlIndex = -1
    private var discoverRequestVersion = 0L
    private var discoverSourceVersion = 0L
    private var discoveryModeLoaded = false
    private var modernTopOverlaySpace = -1
    private var discoverDefaultFiltersAppliedKey: String? = null

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        discoveryPageMode = AppConfig.discoveryPageMode
        usingModernDiscovery = discoveryPageMode == AppConfig.DISCOVERY_PAGE_MODE_MODERN
        usingSuiteDiscovery = discoveryPageMode == AppConfig.DISCOVERY_PAGE_MODE_SUITE
        discoveryModeLoaded = false
        binding.swipeRefreshLayout.setColorSchemeColors(accentColor)
        binding.swipeRefreshLayout.setProgressViewOffset(true, (-28).dpToPx(), 56.dpToPx())
        binding.swipeRefreshLayout.setOnChildScrollUpCallback { _, _ ->
            if (usingModernDiscovery && binding.composeDiscoverBooks.isVisible) {
                composeDiscoverCanScrollBackward
            } else if (usingSuiteDiscovery && binding.composeDiscoverySuite.isVisible) {
                composeSuiteCanScrollBackward
            } else {
                currentDiscoverScrollTarget()?.canScrollVertically(-1) == true
            }
        }
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (usingModernDiscovery) {
                if (discoverTagItems.isEmpty()) {
                    refreshModernDiscoverKinds()
                } else {
                    loadDiscoverBooks(reset = true)
                }
            } else if (usingSuiteDiscovery) {
                binding.swipeRefreshLayout.isRefreshing = false
            } else {
                if (!adapter.refreshExpandedIfNoKinds()) {
                    upExploreData(searchView?.query?.toString())
                }
            }
        }
        binding.topBar.setMode(io.legado.app.ui.widget.MainTopBarView.Mode.DISCOVERY)
        binding.topBar.setSearchEntryVisible(true)
        binding.topBar.applyStatusBarPadding(withInitialPadding = true)
        binding.topBar.doOnLayout {
            updateModernTopBarOverlay()
        }
        binding.rvFind.clipToPadding = false
        binding.rvFind.applyMainBottomBarPadding()
        binding.rvDiscoverBooks.clipToPadding = false
        binding.rvDiscoverBooks.applyMainBottomBarPadding(withInitialPadding = true)
        binding.composeDiscoverBooks.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeDiscoverBooks.setContent {
            LegadoComposeTheme {
                ExploreModernListScreen(
                    books = composeDiscoverBooks,
                    layoutMode = composeDiscoverLayoutMode.intValue,
                    listItemStyle = composeDiscoverListStyle.intValue,
                    topPaddingPx = composeDiscoverTopPadding.intValue,
                    scrollToTopSignal = composeDiscoverScrollToTopSignal.intValue,
                    isLoading = composeDiscoverLoading.value,
                    hasMore = composeDiscoverHasMore.value,
                    isInBookshelf = { book ->
                        composeDiscoverBookshelfVersion.intValue
                        isInBookshelf(book)
                    },
                    onBookClick = ::showBookInfo,
                    onLoadMore = { loadDiscoverBooks(reset = false) },
                    onCanScrollBackwardChanged = { composeDiscoverCanScrollBackward = it },
                    fragment = this@ExploreFragment,
                    lifecycle = viewLifecycleOwner.lifecycle
                )
            }
        }
        binding.composeDiscoverySuite.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.composeDiscoverySuite.setContent {
            LegadoComposeTheme {
                val suiteConfig = composeSuiteConfig.value
                val selectedSuite = selectedSuite(suiteConfig)
                DiscoverySuiteHomeScreen(
                    selectedSuite = selectedSuite,
                    suites = suiteConfig.suites,
                    selectedSuiteId = composeSelectedSuiteId.value,
                    widgetBooks = composeSuiteWidgetBooks,
                    rankedWidgetBooks = composeSuiteRankedWidgetBooks,
                    loadingWidgetIds = composeSuiteLoadingWidgets
                        .filterValues { it }
                        .keys,
                    scrollToTopSignal = composeSuiteScrollToTopSignal.intValue,
                    onSearchClick = { SearchActivity.start(requireContext(), key = null) },
                    onSuiteClick = ::openSuiteManagePage,
                    onSuiteSelect = ::selectDiscoverySuite,
                    onBookClick = ::showBookInfo,
                    onBookPreviewOpen = ::showBookInfo,
                    onTagClick = ::openSuiteTarget,
                    onRefreshWidget = ::refreshSuiteWidget,
                    onHorizontalLoadMore = ::loadMoreSuiteHorizontalWidget,
                    onRankedLoadMore = ::loadMoreSuiteRankedWidget,
                    onCanScrollBackwardChanged = { composeSuiteCanScrollBackward = it },
                    fragment = this@ExploreFragment,
                    lifecycle = viewLifecycleOwner.lifecycle
                )
            }
        }
        applyDiscoveryMode(loadData = false)
        scheduleDiscoveryWarmup()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu) {
        super.onCompatCreateOptionsMenu(menu)
        if (usingModernDiscovery || usingSuiteDiscovery) {
            groupsMenu = null
            return
        }
        menuInflater.inflate(R.menu.main_explore, menu)
        groupsMenu = menu.findItem(R.id.menu_group)?.subMenu
        upGroupsMenu()
    }

    private fun applyDiscoveryMode(loadData: Boolean = true) {
        val mode = AppConfig.discoveryPageMode
        val modern = mode == AppConfig.DISCOVERY_PAGE_MODE_MODERN
        val suite = mode == AppConfig.DISCOVERY_PAGE_MODE_SUITE
        discoveryPageMode = mode
        usingModernDiscovery = modern
        usingSuiteDiscovery = suite
        binding.titleBar.isGone = modern || suite
        binding.llModernDiscovery.isVisible = modern
        binding.composeDiscoverySuite.isVisible = suite
        binding.rvFind.isGone = modern || suite
        binding.tvEmptyMsg.isGone = modern || suite
        searchView?.isGone = modern || suite
        if (modern) {
            binding.topBar.post {
                updateModernTopBarOverlay()
            }
        }
        if (!loadData) {
            activity?.invalidateOptionsMenu()
            return
        }
        if (modern) {
            exploreFlowJob?.cancel()
            stopSuiteMode()
            initModernMode()
        } else if (suite) {
            exploreFlowJob?.cancel()
            stopModernMode()
            initSuiteMode()
        } else {
            stopSuiteMode()
            stopModernMode()
            initClassicMode()
        }
        activity?.invalidateOptionsMenu()
    }

    private fun currentDiscoverScrollTarget(): View? {
        if (usingModernDiscovery && binding.composeDiscoverBooks.isVisible) {
            return if (composeDiscoverCanScrollBackward) binding.composeDiscoverBooks else null
        }
        if (usingSuiteDiscovery && binding.composeDiscoverySuite.isVisible) {
            return if (composeSuiteCanScrollBackward) binding.composeDiscoverySuite else null
        }
        return when {
            usingModernDiscovery -> binding.rvDiscoverBooks
            else -> binding.rvFind
        }
    }

    private fun scheduleDiscoveryWarmup() {
        discoverWarmupJob?.cancel()
        if (AppConfig.discoveryPageMode != AppConfig.DISCOVERY_PAGE_MODE_MODERN) return
        discoverWarmupJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(1800)
            if (
                !isAdded ||
                discoveryModeLoaded ||
                AppConfig.discoveryPageMode != AppConfig.DISCOVERY_PAGE_MODE_MODERN
            ) return@launch
            applyDiscoveryMode(loadData = true)
            discoveryModeLoaded = true
        }
    }

    private fun initClassicMode() {
        if (!oldModeInitialized) {
            oldModeInitialized = true
            initSearchView()
            initRecyclerView()
            initGroupData()
        }
        if (exploreFlowJob?.isActive != true) {
            upExploreData(searchView?.query?.toString())
        }
    }

    private fun initModernMode() {
        if (!modernModeInitialized) {
            modernModeInitialized = true
            initDiscoverRecycler()
            bindDiscoverSourceSelector()
            updateDiscoverLoginButtonState()
        }
        observeDiscoverSources()
        observeDiscoverBookshelf()
    }

    private fun stopModernMode() {
        sourceMenuPopup?.dismiss()
        sourceMenuPopup = null
        tagFilterPopup?.dismiss()
        tagFilterPopup = null
        discoverWarmupJob?.cancel()
        discoverWarmupJob = null
        discoverSourceFlowJob?.cancel()
        discoverSourceFlowJob = null
        discoverBookshelfFlowJob?.cancel()
        discoverBookshelfFlowJob = null
        discoverActionJob?.cancel()
        discoverActionJob = null
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverSourceVersion += 1
        discoverRequestVersion += 1
        discoverLoading = false
        syncDiscoverComposeState()
        binding.pbDiscoverLoading.gone()
        discoverAllTagItems.clear()
        discoverMajorGroups.clear()
        discoverTagItems.clear()
        selectedDiscoverMajorGroup = null
        selectedDiscoverTagIndex = -1
        selectedDiscoverUrlIndex = -1
    }

    private fun initSuiteMode() {
        refreshSuiteConfig()
        binding.swipeRefreshLayout.isRefreshing = false
    }

    private fun stopSuiteMode() {
        saveCurrentSuiteSnapshot()
        suiteLoadJob?.cancel()
        suiteLoadJob = null
        clearSuiteRuntimeState()
    }

    private fun clearSuiteRuntimeState() {
        composeSuiteLoadingWidgets.clear()
        composeSuiteWidgetBooks.clear()
        composeSuiteRankedWidgetBooks.clear()
        suiteWidgetSignatures.clear()
        synchronized(suiteRandomDecks) {
            suiteRandomDecks.clear()
        }
        suitePreparedRandomBatches.clear()
        suiteRandomPrepareJobs.values.forEach { it.cancel() }
        suiteRandomPrepareJobs.clear()
        suiteHorizontalPagingStates.clear()
        suiteRankedPagingStates.clear()
        composeSuiteCanScrollBackward = false
    }

    private fun selectedSuite(config: DiscoverySuiteConfig = composeSuiteConfig.value): DiscoverySuite? {
        val selectedId = composeSelectedSuiteId.value
        return config.suites.firstOrNull { it.id == selectedId }
            ?: config.suites.firstOrNull()
    }

    private fun isCurrentSuiteWidget(widget: DiscoverySuiteWidget): Boolean {
        if (!isAdded || !usingSuiteDiscovery) return false
        val currentWidget = selectedSuite()?.widgets?.firstOrNull { it.id == widget.id } ?: return false
        return currentWidget.cacheSignature() == widget.cacheSignature()
    }

    private fun refreshSuiteConfig() {
        val config = DiscoverySuiteStore.load()
        val selectedId = DiscoverySuiteStore.selectedSuiteId()
            .takeIf { id -> config.suites.any { it.id == id } }
            ?: config.suites.firstOrNull()?.id.orEmpty()
        if (selectedId != DiscoverySuiteStore.selectedSuiteId()) {
            DiscoverySuiteStore.setSelectedSuiteId(selectedId)
        }
        composeSuiteConfig.value = config
        composeSelectedSuiteId.value = selectedId
        restoreSuiteSnapshot(selectedSuite(config))
        loadSelectedSuiteWidgets()
    }

    private fun loadSelectedSuiteWidgets() {
        suiteLoadJob?.cancel()
        suiteLoadJob = null
        composeSuiteLoadingWidgets.clear()
        val suite = selectedSuite() ?: run {
            composeSuiteWidgetBooks.clear()
            composeSuiteRankedWidgetBooks.clear()
            suiteWidgetSignatures.clear()
            return
        }
        val widgetIds = suite.widgets.map { it.id }.toSet()
        composeSuiteWidgetBooks.keys
            .filterNot { it in widgetIds }
            .forEach { composeSuiteWidgetBooks.remove(it) }
        composeSuiteRankedWidgetBooks.keys
            .filterNot { it in widgetIds }
            .forEach { composeSuiteRankedWidgetBooks.remove(it) }
        suiteWidgetSignatures.keys
            .filterNot { it in widgetIds }
            .forEach { suiteWidgetSignatures.remove(it) }
        synchronized(suiteRandomDecks) {
            suiteRandomDecks.keys
                .filterNot { it in widgetIds }
                .forEach { suiteRandomDecks.remove(it) }
        }
        suitePreparedRandomBatches.keys
            .filterNot { it in widgetIds }
            .forEach { suitePreparedRandomBatches.remove(it) }
        suiteRandomPrepareJobs.keys
            .filterNot { it in widgetIds }
            .forEach { suiteRandomPrepareJobs.remove(it)?.cancel() }
        suiteHorizontalPagingStates.keys
            .filterNot { it in widgetIds }
            .forEach { suiteHorizontalPagingStates.remove(it) }
        suiteRankedPagingStates.keys
            .filterNot { key -> key.substringBefore('\n') in widgetIds }
            .forEach { suiteRankedPagingStates.remove(it) }
        val widgetsToLoad = suite.widgets.filterNot { widget ->
            val signature = widget.cacheSignature()
            when {
                widget.isSuiteButtonOnlyWidget() || widget.targets.isEmpty() -> {
                    composeSuiteWidgetBooks[widget.id] = emptyList()
                    composeSuiteRankedWidgetBooks.remove(widget.id)
                    suiteWidgetSignatures[widget.id] = signature
                    true
                }
                suiteWidgetSignatures[widget.id] == signature &&
                    composeSuiteWidgetBooks.containsKey(widget.id) &&
                    (widget.type != DiscoverySuiteWidgetType.RankedList.value ||
                        composeSuiteRankedWidgetBooks.containsKey(widget.id)) -> {
                    prefetchSuiteCovers(composeSuiteWidgetBooks[widget.id].orEmpty())
                    true
                }
                else -> false
            }
        }
        if (widgetsToLoad.isEmpty()) {
            suite.widgets.forEach { widget ->
                // 横排控件改为触底懒加载，不在首屏主动预取下一页，减少无用户行为的请求。
                if (widget.type != DiscoverySuiteWidgetType.HorizontalBooks.value) {
                    prepareSuiteNextRandomBatch(widget)
                }
            }
            saveCurrentSuiteSnapshot()
            return
        }
        suiteLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            coroutineScope {
                widgetsToLoad.map { widget ->
                    async {
                        suiteWidgetLoadSemaphore.withPermit {
                if (widget.isSuiteButtonOnlyWidget() || widget.targets.isEmpty()) {
                    composeSuiteWidgetBooks[widget.id] = emptyList()
                    composeSuiteRankedWidgetBooks.remove(widget.id)
                    suiteWidgetSignatures[widget.id] = widget.cacheSignature()
                    return@withPermit
                }
                composeSuiteLoadingWidgets[widget.id] = true
                if (widget.type == DiscoverySuiteWidgetType.RankedList.value) {
                    val rankedBooks = try {
                        withContext(IO) { loadSuiteRankedListWidgetBooks(widget) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        AppLog.put("套件发现排行榜控件加载失败", e)
                        emptyMap()
                    }
                    if (!isCurrentSuiteWidget(widget)) {
                        composeSuiteLoadingWidgets[widget.id] = false
                        return@async
                    }
                    composeSuiteRankedWidgetBooks[widget.id] = rankedBooks
                    composeSuiteWidgetBooks[widget.id] = rankedBooks.values.flatten()
                    suiteWidgetSignatures[widget.id] = widget.cacheSignature()
                    composeSuiteLoadingWidgets[widget.id] = false
                    prefetchSuiteCovers(rankedBooks.values.flatten())
                    return@withPermit
                }
                composeSuiteRankedWidgetBooks.remove(widget.id)
                val books = try {
                    withContext(IO) { loadSuiteWidgetBooks(widget) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("套件发现控件加载失败", e)
                    emptyList()
                }
                if (!isCurrentSuiteWidget(widget)) {
                    composeSuiteLoadingWidgets[widget.id] = false
                    return@async
                }
                composeSuiteWidgetBooks[widget.id] = books
                suiteWidgetSignatures[widget.id] = widget.cacheSignature()
                composeSuiteLoadingWidgets[widget.id] = false
                prefetchSuiteCovers(books)
                if (widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value) {
                    prefetchSuiteCovers(books.drop(HORIZONTAL_SUITE_VISIBLE_COVER_COUNT))
                    // 不主动预取下一页，交给横排触底 onHorizontalLoadMore 懒加载。
                } else {
                    prepareSuiteNextRandomBatch(widget)
                }
                        }
                    }
                }.awaitAll()
            }
            saveCurrentSuiteSnapshot()
        }
    }

    private fun restoreSuiteSnapshot(suite: DiscoverySuite?) {
        if (suite == null) return
        val snapshot = DiscoverySuitePageSnapshotStore.get(
            suiteId = suite.id,
            signature = suite.cacheSignature()
        )
        if (snapshot != null) {
            applySuiteSnapshot(snapshot)
            return
        }
        val suiteId = suite.id
        val signature = suite.cacheSignature()
        viewLifecycleOwner.lifecycleScope.launch {
            val diskSnapshot = withContext(IO) {
                readSuiteSnapshotCache(suiteId, signature)
            } ?: return@launch
            if (!isAdded ||
                !usingSuiteDiscovery ||
                selectedSuite()?.id != suiteId ||
                selectedSuite()?.cacheSignature() != signature
            ) {
                return@launch
            }
            applySuiteSnapshot(diskSnapshot)
            DiscoverySuitePageSnapshotStore.put(diskSnapshot)
        }
    }

    private fun applySuiteSnapshot(snapshot: DiscoverySuitePageSnapshot) {
        composeSuiteWidgetBooks.clear()
        composeSuiteWidgetBooks.putAll(snapshot.widgetBooks)
        composeSuiteRankedWidgetBooks.clear()
        composeSuiteRankedWidgetBooks.putAll(snapshot.rankedWidgetBooks)
        suiteWidgetSignatures.clear()
        suiteWidgetSignatures.putAll(snapshot.widgetSignatures)
        prefetchSuiteCovers(snapshot.widgetBooks.values.flatten())
    }

    private fun saveCurrentSuiteSnapshot() {
        val suite = selectedSuite() ?: return
        if (composeSuiteWidgetBooks.isEmpty() && composeSuiteRankedWidgetBooks.isEmpty()) return
        val snapshotWidgets = suite.widgets.take(DISCOVERY_SUITE_SNAPSHOT_WIDGET_LIMIT)
        val snapshot = DiscoverySuitePageSnapshot(
            suiteId = suite.id,
            signature = suite.cacheSignature(),
            widgetBooks = snapshotWidgets.associate { widget ->
                widget.id to composeSuiteWidgetBooks[widget.id]
                    .orEmpty()
                    .take(widget.snapshotBookLimit())
            }.filterValues { it.isNotEmpty() },
            rankedWidgetBooks = snapshotWidgets
                .filter { it.type == DiscoverySuiteWidgetType.RankedList.value }
                .associate { widget ->
                    widget.id to composeSuiteRankedWidgetBooks[widget.id]
                        .orEmpty()
                        .mapValues { entry -> entry.value.take(RANKED_SUITE_SNAPSHOT_BOOK_LIMIT) }
                }
                .filterValues { it.isNotEmpty() },
            widgetSignatures = suiteWidgetSignatures.filterKeys { key ->
                snapshotWidgets.any { it.id == key }
            }
        )
        saveSuiteSnapshotCacheAsync(snapshot)
    }

    private fun readSuiteSnapshotCache(
        suiteId: String,
        signature: String
    ): DiscoverySuitePageSnapshot? {
        val key = suiteSnapshotCacheKey(suiteId, signature)
        val raw = readBoundedDiscoveryCache(
            key = key,
            cacheName = "套件发现缓存"
        ) ?: return null
        val snapshot = GSON.fromJsonObject<DiscoverySuitePageSnapshot>(raw)
            .getOrNull()
            ?.takeIf { it.suiteId == suiteId && it.signature == signature }
            ?.compactForCache()
            ?.takeIf { it.hasBooks() }
        if (snapshot == null) {
            appDb.cacheDao.deleteIfValueMatches(key, raw)
            AppLog.put("套件发现缓存内容无效，已清理并重新加载")
        }
        return snapshot
    }

    private fun saveSuiteSnapshotCacheAsync(snapshot: DiscoverySuitePageSnapshot) {
        if (snapshot.widgetBooks.isEmpty() && snapshot.rankedWidgetBooks.isEmpty()) return
        DiscoveryCacheWriteScope.launchLatest { saveVersion ->
            runCatching {
                val compactSnapshot = snapshot.compactForCache()
                if (!compactSnapshot.hasBooks()) return@runCatching
                val value = DiscoveryCachePolicy.toBoundedJson(compactSnapshot)
                if (value == null) {
                    AppLog.put("套件发现缓存超过安全大小，跳过内存和磁盘快照")
                    return@runCatching
                }
                if (!DiscoveryCacheWriteScope.isLatest(saveVersion)) return@runCatching
                DiscoverySuitePageSnapshotStore.put(compactSnapshot)
                writeBoundedDiscoveryCache(
                    key = suiteSnapshotCacheKey(compactSnapshot.suiteId, compactSnapshot.signature),
                    value = value,
                    cacheName = "套件发现缓存"
                )
            }.onFailure {
                AppLog.put("套件发现缓存写入失败", it)
            }
        }
    }

    private fun suiteSnapshotCacheKey(suiteId: String, signature: String): String {
        return "$DISCOVERY_SUITE_CACHE_PREFIX${MD5Utils.md5Encode16("$suiteId\u001F$signature")}"
    }

    private suspend fun loadSuiteWidgetBooks(widget: DiscoverySuiteWidget): List<SearchBook> {
        if (widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value) {
            return loadSuiteHorizontalWidgetBooks(widget)
        }
        val bookCount = when (widget.type) {
            DiscoverySuiteWidgetType.WaterfallBooks.value -> WATERFALL_SUITE_BOOK_COUNT
            else -> RANDOM_SUITE_BOOK_COUNT
        }
        val deck = suiteRandomDeck(widget)
        val books = deck.mutex.withLock {
            if (deck.queue.size < bookCount) {
                fillSuiteRandomDeckLocked(
                    widget = widget,
                    deck = deck,
                    minSize = maxOf(bookCount, RANDOM_SUITE_PREFETCH_COUNT)
                )
            }
            val books = ArrayList<SearchBook>(bookCount)
            while (books.size < bookCount && deck.queue.isNotEmpty()) {
                books.add(deck.queue.removeFirst())
            }
            if (books.size < bookCount) {
                fillSuiteRandomDeckLocked(widget, deck, bookCount)
                while (books.size < bookCount && deck.queue.isNotEmpty()) {
                    books.add(deck.queue.removeFirst())
                }
            }
            books
        }
        if (books.isNotEmpty()) {
            appDb.searchBookDao.insert(*books.toTypedArray())
        }
        return books
    }

    private suspend fun loadSuiteHorizontalWidgetBooks(widget: DiscoverySuiteWidget): List<SearchBook> {
        val state = suiteHorizontalPagingState(widget)
        state.nextPage = 2
        state.exhausted = false
        val target = widget.validRandomTargets().firstOrNull() ?: return emptyList()
        val source = appDb.bookSourceDao.getBookSource(target.sourceUrl) ?: return emptyList()
        val books = loadSuiteTargetPage(source, target.tagUrl, 1)
            .distinctBy { it.suiteDeckKey() }
            .take(HORIZONTAL_SUITE_PAGE_BOOK_LIMIT)
        if (books.isNotEmpty()) {
            appDb.searchBookDao.insert(*books.toTypedArray())
        }
        state.exhausted = books.isEmpty()
        return books
    }

    private suspend fun loadSuiteRankedListWidgetBooks(
        widget: DiscoverySuiteWidget
    ): Map<String, List<SearchBook>> {
        val result = linkedMapOf<String, List<SearchBook>>()
        val entries = coroutineScope {
            widget.validRandomTargets()
            .take(RANKED_SUITE_TARGET_LIMIT)
                .map { target ->
                    async {
                        val source = appDb.bookSourceDao.getBookSource(target.sourceUrl)
                        val books = if (source == null) {
                            emptyList()
                        } else {
                            loadSuiteTargetPage(source, target.tagUrl, 1)
                                .distinctBy { it.suiteDeckKey() }
                        }
                        target to books
                    }
                }
                .awaitAll()
        }
        entries.forEach { (target, books) ->
            val state = suiteRankedPagingState(widget, target)
            state.nextPage = 2
            state.exhausted = books.isEmpty()
            result[target.deckKey()] = books
        }
        entries
            .flatMap { it.second }
            .takeIf { it.isNotEmpty() }
            ?.let { appDb.searchBookDao.insert(*it.toTypedArray()) }
        return result
    }

    private suspend fun fillSuiteRandomDeckLocked(
        widget: DiscoverySuiteWidget,
        deck: SuiteRandomDeck,
        minSize: Int
    ) {
        val targets = widget.validRandomTargets()
        if (targets.isEmpty()) return
        var attempts = 0
        var resetSeen = false
        while (deck.queue.size < minSize && attempts < RANDOM_SUITE_MAX_PREFETCH_ATTEMPTS) {
            val requests = mutableListOf<SuiteDeckPageRequest>()
            while (
                requests.size < SUITE_RANDOM_BATCH_PARALLELISM &&
                attempts < RANDOM_SUITE_MAX_PREFETCH_ATTEMPTS
            ) {
                attempts++
                val target = targets[deck.targetIndex % targets.size]
                deck.targetIndex += 1
                val targetKey = target.deckKey()
                val page = deck.nextPageByTarget[targetKey] ?: 1
                deck.nextPageByTarget[targetKey] = if (page >= RANDOM_SUITE_MAX_PAGE) 1 else page + 1
                val source = appDb.bookSourceDao.getBookSource(target.sourceUrl) ?: continue
                val seed = "${widget.id}|$targetKey|$page|${deck.seenKeys.size}".hashCode()
                requests += SuiteDeckPageRequest(
                    tagUrl = target.tagUrl,
                    page = page,
                    source = source,
                    seed = seed
                )
            }
            if (requests.isEmpty()) continue
            val loadedPages = coroutineScope {
                requests.map { request ->
                    async {
                        request to loadSuiteTargetPage(request.source, request.tagUrl, request.page)
                            .ifEmpty {
                                if (request.page == 1) {
                                    emptyList()
                                } else {
                                    loadSuiteTargetPage(request.source, request.tagUrl, 1)
                                }
                            }
                    }
                }.awaitAll()
            }
            var added = 0
            loadedPages.forEach { (request, pageBooks) ->
                pageBooks.shuffled(Random(request.seed)).forEach { book ->
                    if (deck.queue.size >= minSize) return@forEach
                    val key = book.suiteDeckKey()
                    if (deck.seenKeys.add(key)) {
                        deck.queue.addLast(book)
                        added += 1
                    }
                }
            }
            if (added == 0 && !resetSeen && deck.queue.isEmpty() && attempts >= targets.size) {
                deck.seenKeys.clear()
                resetSeen = true
            }
        }
    }

    private suspend fun loadSuiteTargetPage(
        source: BookSource,
        tagUrl: String,
        page: Int
    ): List<SearchBook> {
        return suiteTargetLoadSemaphore.withPermit {
            try {
                WebBook.exploreBookAwait(
                    source,
                    tagUrl,
                    page,
                    WebViewPool.Scope.DISCOVERY
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("濂椾欢鍙戠幇鍔犺浇鍒嗙被澶辫触", e)
                emptyList()
            }
        }
    }

    private fun prefetchSuiteWidgetDeck(widget: DiscoverySuiteWidget) {
        if (!usingSuiteDiscovery ||
            widget.isSuiteButtonOnlyWidget() ||
            widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value ||
            widget.type == DiscoverySuiteWidgetType.RankedList.value ||
            widget.type == DiscoverySuiteWidgetType.WaterfallBooks.value
        ) return
        val deck = suiteRandomDeck(widget)
        if (deck.prefetching || deck.queue.size >= RANDOM_SUITE_BOOK_COUNT) return
        viewLifecycleOwner.lifecycleScope.launch(IO) {
            deck.prefetching = true
            try {
                val queuedBooks = deck.mutex.withLock {
                    fillSuiteRandomDeckLocked(widget, deck, RANDOM_SUITE_PREFETCH_COUNT)
                    deck.queue.take(RANDOM_SUITE_COVER_PREFETCH_COUNT)
                }
                withContext(Main) {
                    prefetchSuiteCovers(queuedBooks)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("套件发现控件预加载失败", e)
            } finally {
                deck.prefetching = false
            }
        }
    }

    private fun suiteRandomDeck(widget: DiscoverySuiteWidget): SuiteRandomDeck {
        val signature = widget.deckSignature()
        return synchronized(suiteRandomDecks) {
            val current = suiteRandomDecks[widget.id]
            if (current != null && current.signature == signature) {
                return@synchronized current
            }
            val targets = widget.validRandomTargets()
            SuiteRandomDeck(
                signature = signature,
                targetIndex = if (targets.isEmpty()) {
                    0
                } else {
                    Random(System.nanoTime()).nextInt(targets.size)
                }
            ).also {
                suiteRandomDecks[widget.id] = it
            }
        }
    }

    private fun refreshSuiteWidget(widget: DiscoverySuiteWidget) {
        if (!usingSuiteDiscovery || widget.isSuiteButtonOnlyWidget()) return
        if (composeSuiteLoadingWidgets[widget.id] == true) return
        if (widget.type == DiscoverySuiteWidgetType.RankedList.value) {
            viewLifecycleOwner.lifecycleScope.launch {
                composeSuiteLoadingWidgets[widget.id] = true
                try {
                val rankedBooks = try {
                    withContext(IO) { loadSuiteRankedListWidgetBooks(widget) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    AppLog.put("套件发现排行榜控件刷新失败", e)
                    emptyMap()
                }
                if (!isCurrentSuiteWidget(widget)) return@launch
                if (rankedBooks.isNotEmpty()) {
                    composeSuiteRankedWidgetBooks[widget.id] = rankedBooks
                    composeSuiteWidgetBooks[widget.id] = rankedBooks.values.flatten()
                    suiteWidgetSignatures[widget.id] = widget.cacheSignature()
                    prefetchSuiteCovers(rankedBooks.values.flatten())
                    saveCurrentSuiteSnapshot()
                }
                } finally {
                    composeSuiteLoadingWidgets[widget.id] = false
                }
            }
            return
        }
        if (widget.type != DiscoverySuiteWidgetType.HorizontalBooks.value) {
            val signature = widget.deckSignature()
            val prepared = suitePreparedRandomBatches.remove(widget.id)
                ?.takeIf { it.signature == signature }
            if (prepared != null) {
                composeSuiteWidgetBooks[widget.id] = prepared.books
                suiteWidgetSignatures[widget.id] = widget.cacheSignature()
                prefetchSuiteCovers(prepared.books)
                saveCurrentSuiteSnapshot()
                prepareSuiteNextRandomBatch(widget)
                return
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            composeSuiteLoadingWidgets[widget.id] = true
            try {
            val books = try {
                withContext(IO) { loadSuiteWidgetBooks(widget) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("套件发现控件刷新失败", e)
                emptyList()
            }
            if (!isCurrentSuiteWidget(widget)) return@launch
            if (books.isNotEmpty()) {
                composeSuiteWidgetBooks[widget.id] = books
                suiteWidgetSignatures[widget.id] = widget.cacheSignature()
                prefetchSuiteCovers(books)
                saveCurrentSuiteSnapshot()
            }
            if (widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value) {
                prefetchSuiteCovers(books.drop(HORIZONTAL_SUITE_VISIBLE_COVER_COUNT))
                // 不主动预取下一页，交给横排触底 onHorizontalLoadMore 懒加载。
            } else {
                prepareSuiteNextRandomBatch(widget)
            }
            } finally {
                composeSuiteLoadingWidgets[widget.id] = false
            }
        }
    }

    private fun prepareSuiteNextRandomBatch(widget: DiscoverySuiteWidget) {
        if (!usingSuiteDiscovery || widget.isSuiteButtonOnlyWidget()) return
        if (widget.type == DiscoverySuiteWidgetType.HorizontalBooks.value ||
            widget.type == DiscoverySuiteWidgetType.RankedList.value ||
            widget.type == DiscoverySuiteWidgetType.WaterfallBooks.value
        ) return
        val signature = widget.deckSignature()
        if (suitePreparedRandomBatches[widget.id]?.signature == signature) return
        val runningJob = suiteRandomPrepareJobs[widget.id]
        if (runningJob?.isActive == true) return
        val appContext = requireContext().applicationContext
        suiteRandomPrepareJobs[widget.id] = viewLifecycleOwner.lifecycleScope.launch {
            try {
            val books = try {
                withContext(IO) {
                    loadSuiteWidgetBooks(widget).also {
                        preloadSuiteVisibleCovers(appContext, widget, it)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("套件发现控件预加载失败", e)
                emptyList()
            }
            if (!isCurrentSuiteWidget(widget)) return@launch
            if (books.isNotEmpty() && widget.deckSignature() == signature) {
                suitePreparedRandomBatches[widget.id] = SuitePreparedBatch(signature, books)
                prefetchSuiteCovers(books)
            }
            prefetchSuiteWidgetDeck(widget)
            } finally {
                suiteRandomPrepareJobs.remove(widget.id)
            }
        }
    }

    private fun loadMoreSuiteHorizontalWidget(widget: DiscoverySuiteWidget) {
        if (!usingSuiteDiscovery || widget.type != DiscoverySuiteWidgetType.HorizontalBooks.value) return
        val state = suiteHorizontalPagingState(widget)
        if (state.loading || state.exhausted) return
        val currentBooks = composeSuiteWidgetBooks[widget.id].orEmpty()
        if (currentBooks.size >= HORIZONTAL_SUITE_MAX_BOOKS) {
            state.exhausted = true
            return
        }
        val page = state.nextPage
        state.loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
            val books = try {
                withContext(IO) { loadSuiteHorizontalWidgetPage(widget, page) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("套件发现横排控件加载下一页失败", e)
                emptyList()
            }
            if (!isCurrentSuiteWidget(widget)) {
                state.loading = false
                return@launch
            }
            val latestState = suiteHorizontalPagingState(widget)
            if (latestState.signature != state.signature) {
                state.loading = false
                return@launch
            }
            val current = composeSuiteWidgetBooks[widget.id].orEmpty()
            val merged = (current + books)
                .distinctBy { it.suiteDeckKey() }
                .take(HORIZONTAL_SUITE_MAX_BOOKS)
            if (merged.size > current.size) {
                composeSuiteWidgetBooks[widget.id] = merged
                suiteWidgetSignatures[widget.id] = widget.cacheSignature()
                prefetchSuiteCovers(books)
                latestState.nextPage = page + 1
                latestState.exhausted = merged.size >= HORIZONTAL_SUITE_MAX_BOOKS
                saveCurrentSuiteSnapshot()
            } else {
                latestState.exhausted = true
            }
            latestState.loading = false
            } finally {
                state.loading = false
            }
        }
    }

    private fun loadMoreSuiteRankedWidget(
        widget: DiscoverySuiteWidget,
        target: DiscoverySuiteWidgetTarget
    ) {
        if (!usingSuiteDiscovery || widget.type != DiscoverySuiteWidgetType.RankedList.value) return
        if (target.sourceUrl.isBlank() || target.tagUrl.isBlank()) return
        val state = suiteRankedPagingState(widget, target)
        if (state.loading || state.exhausted) return
        val page = state.nextPage
        state.loading = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
            val books = try {
                withContext(IO) { loadSuiteRankedWidgetPage(target, page) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("套件发现排行榜控件加载下一页失败", e)
                emptyList()
            }
            if (!isCurrentSuiteWidget(widget)) {
                state.loading = false
                return@launch
            }
            val latestState = suiteRankedPagingState(widget, target)
            if (latestState.signature != state.signature) {
                state.loading = false
                return@launch
            }
            val targetKey = target.deckKey()
            val currentMap = composeSuiteRankedWidgetBooks[widget.id].orEmpty()
            val currentBooks = currentMap[targetKey].orEmpty()
            val mergedBooks = (currentBooks + books).distinctBy { it.suiteDeckKey() }
            if (mergedBooks.size > currentBooks.size) {
                val mergedMap = currentMap.toMutableMap().apply {
                    put(targetKey, mergedBooks)
                }
                composeSuiteRankedWidgetBooks[widget.id] = mergedMap
                composeSuiteWidgetBooks[widget.id] = mergedMap.values.flatten()
                suiteWidgetSignatures[widget.id] = widget.cacheSignature()
                prefetchSuiteCovers(books)
                latestState.nextPage = page + 1
                latestState.exhausted = books.isEmpty()
                saveCurrentSuiteSnapshot()
            } else {
                latestState.exhausted = true
            }
            latestState.loading = false
            } finally {
                state.loading = false
            }
        }
    }

    private suspend fun loadSuiteHorizontalWidgetPage(
        widget: DiscoverySuiteWidget,
        page: Int
    ): List<SearchBook> {
        val target = widget.validRandomTargets().firstOrNull() ?: return emptyList()
        val source = appDb.bookSourceDao.getBookSource(target.sourceUrl) ?: return emptyList()
        val books = loadSuiteTargetPage(source, target.tagUrl, page)
            .distinctBy { it.suiteDeckKey() }
            .take(HORIZONTAL_SUITE_PAGE_BOOK_LIMIT)
        if (books.isNotEmpty()) {
            appDb.searchBookDao.insert(*books.toTypedArray())
        }
        return books
    }

    private suspend fun loadSuiteRankedWidgetPage(
        target: DiscoverySuiteWidgetTarget,
        page: Int
    ): List<SearchBook> {
        val source = appDb.bookSourceDao.getBookSource(target.sourceUrl) ?: return emptyList()
        val books = loadSuiteTargetPage(source, target.tagUrl, page)
            .distinctBy { it.suiteDeckKey() }
        if (books.isNotEmpty()) {
            appDb.searchBookDao.insert(*books.toTypedArray())
        }
        return books
    }

    private fun suiteHorizontalPagingState(widget: DiscoverySuiteWidget): SuiteHorizontalPagingState {
        val signature = widget.horizontalPagingSignature()
        return synchronized(suiteHorizontalPagingStates) {
            suiteHorizontalPagingStates[widget.id]
                ?.takeIf { it.signature == signature }
                ?: SuiteHorizontalPagingState(signature = signature).also {
                    suiteHorizontalPagingStates[widget.id] = it
                }
        }
    }

    private fun suiteRankedPagingState(
        widget: DiscoverySuiteWidget,
        target: DiscoverySuiteWidgetTarget
    ): SuiteRankedPagingState {
        val key = widget.rankedPagingKey(target)
        val signature = widget.rankedPagingSignature(target)
        return synchronized(suiteRankedPagingStates) {
            suiteRankedPagingStates[key]
                ?.takeIf { it.signature == signature }
                ?: SuiteRankedPagingState(signature = signature).also {
                    suiteRankedPagingStates[key] = it
                }
        }
    }

    private fun prefetchSuiteCovers(books: List<SearchBook>) {
        if (!isAdded || books.isEmpty()) return
        val context = requireContext().applicationContext
        val options = RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .disallowHardwareConfig()
            .set(OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi)
        books.asSequence()
            .distinctBy { "${it.origin}|${it.coverUrl.orEmpty()}|${it.name}|${it.author}" }
            .take(RANDOM_SUITE_COVER_PREFETCH_COUNT)
            .forEach { book ->
                val display = CoverDisplayResolver.resolve(book)
                if (AppConfig.useDefaultCover && !display.forcePath) return@forEach
                val path = display.path?.takeIf { it.isNotBlank() } ?: return@forEach
                val requestOptions = options.clone()
                display.sourceOrigin?.let { origin ->
                    requestOptions.set(OkHttpModelLoader.sourceOriginOption, origin)
                }
                ImageLoader.load(context, path)
                    .apply(requestOptions)
                    .priority(Priority.LOW)
                    .override(240, 320)
                    .centerCrop()
                    .preload(240, 320)
            }
    }

    private suspend fun preloadSuiteVisibleCovers(
        context: Context,
        widget: DiscoverySuiteWidget,
        books: List<SearchBook>
    ) {
        if (books.isEmpty()) return
        val visibleCount = when (widget.type) {
            DiscoverySuiteWidgetType.HorizontalBooks.value -> HORIZONTAL_SUITE_VISIBLE_COVER_COUNT
            DiscoverySuiteWidgetType.RankedList.value -> RANKED_SUITE_VISIBLE_COVER_COUNT
            DiscoverySuiteWidgetType.WaterfallBooks.value -> WATERFALL_SUITE_VISIBLE_COVER_COUNT
            else -> RANDOM_SUITE_BOOK_COUNT
        }
        val visibleBooks = books.take(visibleCount)
        withTimeoutOrNull(SUITE_VISIBLE_COVER_PRELOAD_TIMEOUT_MS) {
            coroutineScope {
                visibleBooks.map { book ->
                    async(IO) {
                        suiteCoverPreloadSemaphore.withPermit {
                            preloadSuiteCoverBlocking(context, book)
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun preloadSuiteCoverBlocking(context: Context, book: SearchBook) {
        val display = CoverDisplayResolver.resolve(book)
        if (AppConfig.useDefaultCover && !display.forcePath) return
        val path = display.path?.takeIf { it.isNotBlank() } ?: return
        val cleanName = display.name?.replace(AppPattern.bdRegex, "")?.trim()
        val cleanAuthor = display.author?.replace(AppPattern.bdRegex, "")?.trim()
        val thumbKey = "${display.sourceOrigin}|$path|$cleanName|$cleanAuthor"
        val thumbFile = CoverThumbnailCache.existing(context, thumbKey)
        var options = RequestOptions()
            .format(DecodeFormat.PREFER_RGB_565)
            .disallowHardwareConfig()
            .set(OkHttpModelLoader.loadOnlyWifiOption, AppConfig.loadCoverOnlyWifi)
        display.sourceOrigin?.let { origin ->
            options = options.set(OkHttpModelLoader.sourceOriginOption, origin)
        }
        val request = if (thumbFile != null) {
            ImageLoader.load(context, thumbFile)
        } else {
            ImageLoader.load(context, path)
        }
        val target = request
            .apply(options)
            .priority(Priority.HIGH)
            .override(SUITE_COVER_THUMB_WIDTH, SUITE_COVER_THUMB_HEIGHT)
            .centerCrop()
            .submit(SUITE_COVER_THUMB_WIDTH, SUITE_COVER_THUMB_HEIGHT)
        try {
            val drawable = target.get(SUITE_SINGLE_COVER_PRELOAD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (thumbFile == null) {
                CoverThumbnailCache.saveBlocking(context, thumbKey, drawable)
            }
        } catch (_: Throwable) {
            // Network or malformed cover failures should not block the widget refresh.
        } finally {
            runCatching { Glide.with(context).clear(target) }
        }
    }

    private fun openSuiteManagePage() {
        startActivity<DiscoverySuiteManageActivity>()
    }

    private fun selectDiscoverySuite(suite: DiscoverySuite) {
        if (suite.id.isBlank() || suite.id == composeSelectedSuiteId.value) return
        saveCurrentSuiteSnapshot()
        DiscoverySuiteStore.setSelectedSuiteId(suite.id)
        refreshSuiteConfig()
    }

    private fun openSuiteTarget(target: DiscoverySuiteWidgetTarget) {
        if (target.sourceUrl.isBlank() || target.tagUrl.isBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", target.title.substringAfter(" - ", target.title))
            putExtra("sourceUrl", target.sourceUrl)
            putExtra("exploreUrl", target.tagUrl)
        }
    }

    private fun saveSuiteConfig(
        selectedId: String? = null,
        transform: (DiscoverySuiteConfig) -> DiscoverySuiteConfig
    ) {
        val current = DiscoverySuiteStore.load()
        DiscoverySuiteStore.save(transform(current))
        selectedId?.let(DiscoverySuiteStore::setSelectedSuiteId)
        refreshSuiteConfig()
    }

    private fun showSuiteSelector() {
        val config = composeSuiteConfig.value
        if (config.suites.isEmpty()) {
            showCreateSuiteDialog()
            return
        }
        val labels = buildList {
            add(getString(R.string.discovery_suite_manage))
            addAll(config.suites.map { it.displayName })
            add(getString(R.string.discovery_suite_create))
        }
        val selectedIndex = config.suites.indexOfFirst { it.id == composeSelectedSuiteId.value }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: -1
        showComposeChoiceListDialog(
            title = getString(R.string.discovery_page_mode_suite),
            labels = labels,
            selectedIndex = selectedIndex
        ) { index ->
            when {
                index == 0 -> selectedSuite(config)?.let(::showSuiteManageDialog)
                index == labels.lastIndex -> showCreateSuiteDialog()
                index > 0 -> {
                    config.suites.getOrNull(index - 1)?.let { suite ->
                        DiscoverySuiteStore.setSelectedSuiteId(suite.id)
                        refreshSuiteConfig()
                    }
                }
            }
        }
    }

    private fun showSuiteManageDialog(suite: DiscoverySuite) {
        val labels = listOf(
            getString(R.string.discovery_suite_rename),
            getString(R.string.discovery_suite_alias),
            getString(R.string.discovery_suite_add_widget),
            getString(R.string.discovery_suite_delete)
        )
        showComposeActionListDialog(
            title = suite.displayName,
            labels = labels,
            dangerIndices = setOf(3)
        ) { index ->
            when (index) {
                0 -> showRenameSuiteDialog(suite)
                1 -> showSuiteAliasDialog(suite)
                2 -> showAddSuiteWidgetDialog(suite)
                3 -> confirmDeleteSuite(suite)
            }
        }
    }

    private fun showCreateSuiteDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.discovery_suite_create),
            hint = getString(R.string.discovery_suite_name),
            validateInput = { it.trim().isNotEmpty() },
            onPositive = { name ->
                val suite = DiscoverySuiteStore.newSuite(name)
                saveSuiteConfig(selectedId = suite.id) { config ->
                    config.copy(suites = config.suites + suite)
                }
            }
        )
    }

    private fun showRenameSuiteDialog(suite: DiscoverySuite) {
        showComposeTextInputDialog(
            title = getString(R.string.discovery_suite_rename),
            hint = getString(R.string.discovery_suite_name),
            initialValue = suite.name,
            validateInput = { it.trim().isNotEmpty() },
            onPositive = { name ->
                updateSuite(suite.id) { it.copy(name = name.trim()) }
            }
        )
    }

    private fun showSuiteAliasDialog(suite: DiscoverySuite) {
        showComposeTextInputDialog(
            title = getString(R.string.discovery_suite_alias),
            hint = getString(R.string.discovery_suite_alias),
            initialValue = suite.alias,
            onPositive = { alias ->
                updateSuite(suite.id) { it.copy(alias = alias.trim()) }
            }
        )
    }

    private fun showAddSuiteWidgetDialog(suite: DiscoverySuite) {
        showSuiteWidgetTypeDialog(suite)
    }

    private fun showSuiteWidgetTypeDialog(suite: DiscoverySuite) {
        val types = listOf(
            Triple(
                DiscoverySuiteWidgetType.RandomBooks.value,
                getString(R.string.discovery_suite_widget_type_random_books),
                getString(R.string.discovery_suite_default_random_title)
            ),
            Triple(
                DiscoverySuiteWidgetType.TagBar.value,
                getString(R.string.discovery_suite_widget_type_tag_bar),
                getString(R.string.discovery_suite_default_tag_bar_title)
            ),
            Triple(
                DiscoverySuiteWidgetType.RankButtons.value,
                "排行榜按钮",
                "排行榜按钮"
            ),
            Triple(
                DiscoverySuiteWidgetType.RankedList.value,
                getString(R.string.discovery_suite_widget_type_ranked_list),
                getString(R.string.discovery_suite_widget_type_ranked_list)
            ),
            Triple(
                DiscoverySuiteWidgetType.WaterfallBooks.value,
                getString(R.string.discovery_suite_widget_type_waterfall_books),
                getString(R.string.discovery_suite_widget_type_waterfall_books)
            ),
            Triple(
                DiscoverySuiteWidgetType.HorizontalBooks.value,
                getString(R.string.discovery_suite_widget_type_horizontal_books),
                getString(R.string.discovery_suite_widget_type_horizontal_books)
            )
        )
        showComposeChoiceListDialog(
            title = getString(R.string.discovery_suite_widget_type),
            labels = types.map { it.second },
            selectedIndex = 0
        ) { index ->
            val selected = types.getOrNull(index) ?: types.first()
            showSuiteWidgetSourceDialog(suite, selected.third, selected.first)
        }
    }

    private fun showSuiteWidgetSourceDialog(
        suite: DiscoverySuite,
        title: String,
        widgetType: String
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val sources = withContext(IO) {
                appDb.bookSourceDao.allEnabledPart
                    .filter { it.enabledExplore && it.hasExploreUrl }
                    .take(MAX_SUITE_SELECTOR_SOURCES)
            }
            if (!canShowSuiteDialog()) return@launch
            if (sources.isEmpty()) {
                requireContext().toastOnUi(R.string.explore_empty)
                return@launch
            }
            showComposeMultiChoiceDialog(
                title = getString(R.string.screen_find),
                labels = sources.map { it.bookSourceName },
                checkedIndices = emptySet(),
                onPositive = { checked ->
                    val selected = sources.filterIndexed { index, _ ->
                        checked.getOrNull(index) == true
                    }
                    if (selected.isEmpty()) {
                        requireContext().toastOnUi(R.string.screen_find)
                    } else {
                        showSuiteWidgetTagDialog(suite, title, widgetType, selected)
                    }
                }
            )
        }
    }

    private fun showSuiteWidgetTagDialog(
        suite: DiscoverySuite,
        title: String,
        widgetType: String,
        sources: List<BookSourcePart>
    ) {
        viewLifecycleOwner.lifecycleScope.launch {
            val targets = withContext(IO) {
                sources.flatMap { source ->
                    runCatching {
                        source.exploreKinds()
                            .filter { it.type == ExploreKind.Type.url && !it.url.isNullOrBlank() }
                            .map { kind ->
                                DiscoverySuiteWidgetTarget(
                                    sourceUrl = source.bookSourceUrl,
                                    tagUrl = kind.url.orEmpty(),
                                    title = "${source.bookSourceName} - ${kind.title}"
                                )
                            }
                    }.getOrElse { emptyList() }
                }.take(MAX_SUITE_SELECTOR_TARGETS)
            }
            if (!canShowSuiteDialog()) return@launch
            if (targets.isEmpty()) {
                requireContext().toastOnUi(R.string.find_empty)
                return@launch
            }
            showComposeMultiChoiceDialog(
                title = getString(R.string.discovery_suite_add_widget),
                labels = targets.map { it.title },
                checkedIndices = emptySet(),
                onPositive = { checked ->
                    val selectedTargets = targets.filterIndexed { index, _ ->
                        checked.getOrNull(index) == true
                    }
                    if (selectedTargets.isEmpty()) {
                        requireContext().toastOnUi(R.string.find_empty)
                        return@showComposeMultiChoiceDialog
                    }
                    val baseWidget = DiscoverySuiteStore.newBookWidget(title, widgetType)
                    val cleanTargets = when (widgetType) {
                        DiscoverySuiteWidgetType.HorizontalBooks.value -> selectedTargets.take(1)
                        DiscoverySuiteWidgetType.RankButtons.value,
                        DiscoverySuiteWidgetType.RankedList.value -> selectedTargets.take(9)
                        else -> selectedTargets
                    }
                    if (widgetType in setOf(
                            DiscoverySuiteWidgetType.RankButtons.value,
                            DiscoverySuiteWidgetType.RankedList.value
                        ) && cleanTargets.size !in 3..9
                    ) {
                        requireContext().toastOnUi("排行榜控件需要选择 3-9 个 Tag")
                        return@showComposeMultiChoiceDialog
                    }
                    val widget = baseWidget.copy(
                        targets = cleanTargets,
                        displayLimit = when (widgetType) {
                            DiscoverySuiteWidgetType.HorizontalBooks.value -> DEFAULT_WIDGET_DISPLAY_LIMIT
                            DiscoverySuiteWidgetType.RankButtons.value -> cleanTargets.size.coerceIn(3, 9)
                            DiscoverySuiteWidgetType.RankedList.value -> DEFAULT_RANKED_WIDGET_BOOK_COUNT
                            DiscoverySuiteWidgetType.WaterfallBooks.value -> DEFAULT_WATERFALL_WIDGET_BOOK_COUNT
                            else -> baseWidget.displayLimit
                        }
                    )
                    updateSuite(suite.id) { it.copy(widgets = it.widgets + widget) }
                }
            )
        }
    }

    private fun canShowSuiteDialog(): Boolean {
        return isAdded &&
            usingSuiteDiscovery &&
            view != null &&
            viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun confirmDeleteSuite(suite: DiscoverySuite) {
        showComposeConfirmDialog(
            title = getString(R.string.discovery_suite_delete),
            message = suite.displayName,
            dangerPositive = true,
            onPositive = {
                saveSuiteConfig { config ->
                    val suites = config.suites.filterNot { it.id == suite.id }
                    val selectedId = if (composeSelectedSuiteId.value == suite.id) {
                        suites.firstOrNull()?.id.orEmpty()
                    } else {
                        composeSelectedSuiteId.value
                    }
                    DiscoverySuiteStore.setSelectedSuiteId(selectedId)
                    config.copy(suites = suites)
                }
            }
        )
    }

    private fun updateSuite(
        suiteId: String,
        transform: (DiscoverySuite) -> DiscoverySuite
    ) {
        saveSuiteConfig { config ->
            config.copy(
                suites = config.suites.map { suite ->
                    if (suite.id == suiteId) transform(suite) else suite
                }
            )
        }
    }

    private fun initSearchView() {
        val view = searchView ?: return
        view.applyTint(primaryTextColor)
        view.isSubmitButtonEnabled = true
        view.queryHint = getString(R.string.screen_find)
        view.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                upExploreData(newText)
                return false
            }
        })
    }

    private fun initDiscoverRecycler() {
        binding.topBar.tagsBar.setOnTagClickListener { index ->
            val item = discoverTagItems.getOrNull(index) ?: return@setOnTagClickListener
            if (item.role == DiscoverTagItem.Role.Toggle) {
                handleDiscoverToggleTag(index, item)
                return@setOnTagClickListener
            }
            if (item.isButton) {
                handleDiscoverButtonTag(item)
                return@setOnTagClickListener
            }
            selectDiscoverTag(index, item, selectTab = true)
        }
        binding.topBar.selectsBar.setOnTagClickListener { index ->
            val item = discoverSelectItems.getOrNull(index) ?: return@setOnTagClickListener
            showDiscoverSelectDialog(item)
        }
        binding.topBar.setOnHeightChangedListener {
            updateModernTopBarOverlay()
        }
        applyDiscoverBookLayout(force = true)
        binding.rvDiscoverBooks.setEdgeEffectColor(primaryColor)
        binding.rvDiscoverBooks.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                if (dy > 0 && !recyclerView.canScrollVertically(1)) {
                    loadDiscoverBooks(reset = false)
                }
            }
        })
        updateModernTopBarOverlay()
    }

    private fun updateModernTopBarOverlay() {
        if (!usingModernDiscovery || view == null) return
        val topSpace = binding.topBar.height
        // API>=33:顶栏背景毛玻璃,列表滚到栏下被磨砂;低版本:compose 列表内边距下移并裁剪,不滚到栏下。
        val canBlur = binding.topBar.supportsBackdropBlur()
        if (modernTopOverlaySpace != topSpace) {
            modernTopOverlaySpace = topSpace
            composeDiscoverTopPadding.intValue = if (canBlur) topSpace else 0
            binding.composeDiscoverBooks.setPadding(
                binding.composeDiscoverBooks.paddingLeft,
                if (canBlur) 0 else topSpace,
                binding.composeDiscoverBooks.paddingRight,
                binding.composeDiscoverBooks.paddingBottom
            )
            binding.rvDiscoverBooks.clipToPadding = true
            binding.rvDiscoverBooks.setPadding(
                binding.rvDiscoverBooks.paddingLeft,
                topSpace,
                binding.rvDiscoverBooks.paddingRight,
                binding.rvDiscoverBooks.paddingBottom
            )
            binding.swipeRefreshLayout.setProgressViewOffset(
                true,
                (topSpace - 28.dpToPx()).coerceAtLeast(0),
                topSpace + 56.dpToPx()
            )
        }
        binding.topBar.setBackdropBlur(null)
        binding.topBar.bringToFront()
    }

    private fun applyDiscoverBookLayout(force: Boolean = false) {
        val layoutMode = AppConfig.discoveryPageLayout
        composeDiscoverLayoutMode.intValue = layoutMode
        composeDiscoverListStyle.intValue = AppConfig.bookshelfListItemStyle
        val useComposeList = layoutMode != 2
        binding.composeDiscoverBooks.isVisible = useComposeList
        binding.rvDiscoverBooks.isGone = useComposeList
        applyDiscoverBookContainerMargins(useComposeList)
        if (useComposeList) {
            discoverBookLayoutMode = layoutMode
            discoverBookAdapter = null
            binding.rvDiscoverBooks.adapter = null
            syncDiscoverComposeState()
            return
        }
        if (!force && discoverBookLayoutMode == layoutMode && discoverBookAdapter != null) return
        discoverBookLayoutMode = layoutMode
        binding.rvDiscoverBooks.layoutManager =
            StaggeredGridLayoutManager(2, StaggeredGridLayoutManager.VERTICAL)
        discoverBookAdapter = ExploreShowWaterfallAdapter(requireContext(), this, 2).also { adapter ->
            binding.rvDiscoverBooks.adapter = adapter
            if (discoverBooks.isNotEmpty()) {
                adapter.setItems(discoverBooks.toList())
            }
        }
    }

    private fun applyDiscoverBookContainerMargins(useComposeList: Boolean) {
        val margin = if (useComposeList) 0 else resources.getDimensionPixelSize(R.dimen.bookshelf_tag_bar_margin_horizontal)
        val params = binding.flDiscoverBooks.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (params.marginStart == margin && params.marginEnd == margin) return
        params.marginStart = margin
        params.marginEnd = margin
        binding.flDiscoverBooks.layoutParams = params
    }

    private fun syncDiscoverComposeState(forceBooks: Boolean = false) {
        composeDiscoverLoading.value = discoverLoading
        composeDiscoverHasMore.value = discoverHasMore
        composeDiscoverLayoutMode.intValue = AppConfig.discoveryPageLayout
        composeDiscoverListStyle.intValue = AppConfig.bookshelfListItemStyle
        // SearchBook.equals 仅比较 bookUrl，直接用 != 无法识别封面/最新章节等内容更新，
        // 且 composeDiscoverBooks(List) 与 discoverBooks(Set) 类型不同，比较恒不相等。
        // 这里用渲染相关字段构造内容签名，只有真正变化时才重建列表，避免无谓重组 churn。
        val signature = discoverBooksSignature()
        if (forceBooks || signature != composeDiscoverBooksSignature) {
            composeDiscoverBooksSignature = signature
            composeDiscoverBooks.clear()
            composeDiscoverBooks.addAll(discoverBooks)
        }
    }

    private fun discoverBooksSignature(): String {
        return buildString {
            discoverBooks.forEach { book ->
                append(book.bookUrl).append('#')
                append(book.name).append('#')
                append(book.author).append('#')
                append(book.coverUrl ?: "").append('#')
                append(book.latestChapterTitle ?: "").append('#')
                append(book.kind ?: "").append('#')
                append(book.intro?.hashCode() ?: 0).append(';')
            }
        }
    }

    private fun restoreModernDiscoverCacheAsync(
        sourceUrl: String,
        tagUrl: String?,
        sourceVersion: Long = discoverSourceVersion,
        clearOnMiss: Boolean = false
    ) {
        val url = tagUrl?.takeIf { it.isNotBlank() } ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            val cache = withContext(IO) {
                readModernDiscoverCache(sourceUrl, url)
            }
            if (!isAdded ||
                !usingModernDiscovery ||
                sourceVersion != discoverSourceVersion ||
                selectedDiscoverSourcePart?.bookSourceUrl != sourceUrl ||
                discoverCurrentUrl != url
            ) {
                return@launch
            }
            if (cache != null && cache.books.isNotEmpty()) {
                applyModernDiscoverCache(cache)
            } else if (clearOnMiss) {
                discoverBooks.clear()
                syncDiscoverComposeState(forceBooks = true)
                discoverBookAdapter?.clearItems()
            }
        }
    }

    private fun applyModernDiscoverCache(cache: ModernDiscoverResultCache) {
        discoverBooks.clear()
        discoverBooks.addAll(cache.books.take(DISCOVERY_MODERN_CACHE_BOOK_LIMIT))
        discoverPage = cache.nextPage.coerceAtLeast(2)
        discoverHasMore = cache.hasMore
        syncDiscoverComposeState(forceBooks = true)
        discoverBookAdapter?.setItems(discoverBooks.toList())
        binding.tvDiscoverEmpty.gone()
    }

    private fun readModernDiscoverCache(
        sourceUrl: String,
        tagUrl: String
    ): ModernDiscoverResultCache? {
        val key = modernDiscoverCacheKey(sourceUrl, tagUrl)
        val raw = readBoundedDiscoveryCache(
            key = key,
            cacheName = "发现页面缓存"
        ) ?: return null
        val cache = GSON.fromJsonObject<ModernDiscoverResultCache>(raw)
            .getOrNull()
            ?.takeIf { it.sourceUrl == sourceUrl && it.tagUrl == tagUrl }
            ?.takeIf { it.books.isNotEmpty() }
            ?.let { parsed ->
                parsed.copy(books = parsed.books.mapNotNull(DiscoveryCachePolicy::compact))
            }
            ?.takeIf { it.books.isNotEmpty() }
        if (cache == null) {
            appDb.cacheDao.deleteIfValueMatches(key, raw)
            AppLog.put("发现页面缓存内容无效，已清理并重新加载")
        }
        return cache
    }

    private fun saveModernDiscoverCacheAsync(
        sourceUrl: String,
        tagUrl: String,
        books: List<SearchBook>,
        nextPage: Int,
        hasMore: Boolean
    ) {
        if (books.isEmpty()) return
        val snapshot = ModernDiscoverResultCache(
            sourceUrl = sourceUrl,
            tagUrl = tagUrl,
            books = books.take(DISCOVERY_MODERN_CACHE_BOOK_LIMIT)
                .mapNotNull(DiscoveryCachePolicy::compact),
            nextPage = nextPage.coerceAtLeast(2),
            hasMore = hasMore,
            savedAt = System.currentTimeMillis()
        )
        val key = modernDiscoverCacheKey(sourceUrl, tagUrl)
        if (snapshot.books.isEmpty()) return
        viewLifecycleOwner.lifecycleScope.launch(IO) {
            runCatching {
                val value = DiscoveryCachePolicy.toBoundedJson(snapshot)
                if (value == null) {
                    AppLog.put("发现页面缓存超过安全大小，跳过磁盘缓存")
                    return@runCatching
                }
                writeBoundedDiscoveryCache(
                    key = key,
                    value = value,
                    cacheName = "发现页面缓存"
                )
            }.onFailure {
                AppLog.put("发现页面缓存写入失败", it)
            }
        }
    }

    private fun modernDiscoverCacheKey(sourceUrl: String, tagUrl: String): String {
        return "$DISCOVERY_MODERN_CACHE_PREFIX${MD5Utils.md5Encode16("$sourceUrl\u001F$tagUrl")}"
    }

    private fun readBoundedDiscoveryCache(key: String, cacheName: String): String? {
        val now = System.currentTimeMillis()
        return try {
            val result = appDb.cacheDao.getBoundedValue(
                key = key,
                now = now,
                maxBytes = DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES
            ) ?: return null
            if (!DiscoveryCachePolicy.canRead(result.byteCount)) {
                appDb.cacheDao.deleteIfValueOversized(
                    key,
                    DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES
                )
                AppLog.put("$cacheName 已超过安全大小并清理：${result.byteCount} 字节")
                return null
            }
            if (result.value == null) {
                appDb.cacheDao.deleteIfValueMatches(key, null)
                AppLog.put("$cacheName 内容为空，已清理并重新加载")
                return null
            }
            result.value
        } catch (e: SQLiteBlobTooBigException) {
            runCatching {
                appDb.cacheDao.deleteIfValueOversized(
                    key,
                    DiscoveryCachePolicy.MAX_SQLITE_VALUE_BYTES
                )
            }
            AppLog.put("$cacheName 行过大，已清理并重新加载", e)
            null
        }
    }

    private fun writeBoundedDiscoveryCache(key: String, value: String, cacheName: String) {
        if (!DiscoveryCachePolicy.canStore(value)) {
            AppLog.put("$cacheName 超过安全大小，跳过磁盘缓存")
            return
        }
        appDb.cacheDao.insert(
            Cache(
                key = key,
                value = value,
                deadline = System.currentTimeMillis() + DISCOVERY_CACHE_TTL_MS
            )
        )
    }

    private fun bindDiscoverSourceSelector() {
        binding.topBar.titleText.applyUiTitleTypeface(requireContext())
        val updateSourceNameWidth = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateDiscoverSourceNameWidth()
        }
        binding.topBar.addOnLayoutChangeListener(updateSourceNameWidth)
        binding.topBar.post(::updateDiscoverSourceNameWidth)
        binding.topBar.titleSelect.setOnClickListener {
            showDiscoverSourceMenu()
        }
        binding.topBar.titleSelect.setOnLongClickListener {
            showDiscoverKindsDialog()
            true
        }
        binding.topBar.primaryBar.setOnTagClickListener { index ->
            discoverSources.getOrNull(index)?.let(::selectDiscoverSource)
        }
        binding.topBar.primaryBar.setOnTagLongClickListener { index ->
            discoverSources.getOrNull(index)?.let { source ->
                if (source.bookSourceUrl != selectedDiscoverSourcePart?.bookSourceUrl) {
                    selectDiscoverSource(source)
                }
                binding.topBar.post { showDiscoverKindsDialog() }
            }
            true
        }
        binding.topBar.searchEntry.setOnClickListener {
            openDiscoverSearch()
        }
        binding.topBar.loginButton.setOnClickListener {
            openSelectedSourceLogin()
        }
        binding.topBar.searchButton.setOnClickListener {
            openDiscoverSearch()
        }
        binding.topBar.filterButton.setOnClickListener {
            showDiscoverTagFilterMenu()
        }
        updateDiscoverTagFilterButtonState()
        updateDiscoverSearchButtonState()
    }

    private fun updateDiscoverSourceNameWidth() {
        val rowWidth = binding.topBar.width
        if (rowWidth <= 0) return
        val actionsWidth = listOf(
            binding.topBar.searchButton,
            binding.topBar.filterButton,
            binding.topBar.loginButton
        ).filter { it.isVisible }.sumOf { it.measuredWidth.takeIf { width -> width > 0 } ?: it.layoutParams.width }
        val spacing = 36.dpToPx()
        val maxWidth = (rowWidth - actionsWidth - spacing).coerceIn(96.dpToPx(), 190.dpToPx())
        binding.topBar.titleText.maxWidth = maxWidth
    }

    private fun openSelectedSourceLogin() {
        val source = selectedDiscoverSourcePart ?: return
        if (!source.hasLoginUrl) {
            context?.toastOnUi(R.string.source_no_login)
            return
        }
        startActivity<SourceLoginActivity> {
            putExtra("type", "bookSource")
            putExtra("key", source.bookSourceUrl)
        }
    }

    private fun updateDiscoverLoginButtonState() {
        val canLogin = selectedDiscoverSourcePart?.hasLoginUrl == true
        binding.topBar.loginButton.isEnabled = canLogin
        binding.topBar.loginButton.alpha = if (canLogin) 1f else 0.45f
    }

    private fun updateDiscoverSearchButtonState() {
        val canSearch = !selectedDiscoverSource?.searchUrl.isNullOrBlank()
        binding.topBar.searchButton.isVisible = canSearch && !binding.topBar.isRegularStyle()
        binding.topBar.searchButton.isEnabled = canSearch
        binding.topBar.searchButton.alpha = if (canSearch) 1f else 0.45f
        binding.topBar.searchEntry.isEnabled = canSearch
        binding.topBar.searchEntry.alpha = if (canSearch) 1f else 0.58f
        binding.topBar.post(::updateDiscoverSourceNameWidth)
    }

    private fun openDiscoverSearch() {
        val source = selectedDiscoverSource ?: return
        if (source.searchUrl.isNullOrBlank()) {
            context?.toastOnUi(R.string.search_book_key)
            return
        }
        startActivity<SearchActivity> {
            putExtra("searchScope", "${source.bookSourceName}::${source.bookSourceUrl}")
        }
    }

    private fun observeDiscoverSources() {
        if (discoverSourceFlowJob?.isActive == true) return
        discoverSourceFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExplore()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.STARTED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect { list ->
                    discoverSources.clear()
                    discoverSources.addAll(list)
                    if (discoverSources.isEmpty()) {
                        selectedDiscoverSourcePart = null
                        selectedDiscoverSource = null
                        AppConfig.modernDiscoverySourceUrl = null
                        discoverCurrentUrl = null
                        discoverAllTagItems.clear()
                        discoverMajorGroups.clear()
                        selectedDiscoverMajorGroup = null
                        clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
                        renderDiscoverTags(emptyList(), -1)
                        binding.topBar.setTitle(getString(R.string.explore_empty))
                        binding.topBar.setSearchHint(getString(R.string.search_book_key))
                        renderDiscoverSourceSelector()
                        updateDiscoverLoginButtonState()
                        updateDiscoverSearchButtonState()
                        updateDiscoverTagFilterButtonState()
                        binding.pbDiscoverLoading.gone()
                        return@collect
                    }
                    val keepSource = selectedDiscoverSourcePart?.bookSourceUrl
                        ?: AppConfig.modernDiscoverySourceUrl
                    val selected = discoverSources.firstOrNull { it.bookSourceUrl == keepSource }
                        ?: discoverSources.first()
                    if (selectedDiscoverSourcePart?.bookSourceUrl != selected.bookSourceUrl
                        || discoverTagItems.isEmpty()
                    ) {
                        selectDiscoverSource(selected)
                    } else {
                        updateDiscoverSourceTitle()
                        renderDiscoverSourceSelector()
                        updateDiscoverLoginButtonState()
                        updateDiscoverSearchButtonState()
                    }
                }
        }
    }

    private fun observeDiscoverBookshelf() {
        if (discoverBookshelfFlowJob?.isActive == true) return
        discoverBookshelfFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowShelfIdentities()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.STARTED,
                    AppDatabase.BOOK_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect { books ->
                    discoverBookshelf.clear()
                    books.filterNot { it.isNotShelf }
                        .forEach {
                            discoverBookshelf.add("${it.name}-${it.author}")
                            discoverBookshelf.add(it.name)
                            discoverBookshelf.add(it.bookUrl)
                        }
                    composeDiscoverBookshelfVersion.intValue += 1
                    val bookAdapter = discoverBookAdapter
                    if ((bookAdapter?.itemCount ?: 0) > 0) {
                        bookAdapter?.notifyItemRangeChanged(
                            0,
                            bookAdapter.itemCount,
                            bundleOf("isInBookshelf" to null)
                        )
                    }
                }
        }
    }

    private fun showDiscoverSourceMenu() {
        if (discoverSources.isEmpty()) return
        SourceSelectDialog.show(
            context = requireContext(),
            title = getString(R.string.book_source),
            items = discoverSources,
            selectedKey = selectedDiscoverSourcePart?.bookSourceUrl,
            displayName = { it.getDisPlayNameGroup() },
            searchTexts = {
                listOfNotNull(it.bookSourceName, it.bookSourceUrl, it.bookSourceGroup)
            },
            itemKey = { it.bookSourceUrl },
            showTitle = false
        ) {
            selectDiscoverSource(it)
        }
    }

    private fun showDiscoverKindsDialog() {
        val context = context ?: return
        val source = selectedDiscoverSource ?: return
        var dialog: AlertDialog? = null
        val screenSize = requireActivity().windowManager.windowSize
        val dialogWidth = (screenSize.widthPixels * DISCOVER_DIALOG_WIDTH_RATIO).toInt()
        val dialogHeight = (screenSize.heightPixels * DISCOVER_DIALOG_HEIGHT_RATIO).toInt()
        val itemBinding = ItemFindBookBinding.inflate(layoutInflater, null, false)
        val flexbox = itemBinding.flexbox
        val scrollView = NestedScrollView(context).apply {
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(
                itemBinding.root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        val refreshLayout = SwipeRefreshLayout(context).apply {
            setColorSchemeColors(accentColor)
            setOnChildScrollUpCallback { _, _ ->
                scrollView.canScrollVertically(-1)
            }
            setOnRefreshListener {
                refreshDiscoverKindsDialog(itemBinding, source, dialog, this, clearCache = true)
            }
            addView(
                scrollView,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        val dialogContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(dialogWidth, dialogHeight)
            background = UiCorner.opaqueRounded(
                context.themeCardColorOrDefault(),
                UiCorner.panelRadius(context)
            )
            setPadding(8.dpToPx(), 8.dpToPx(), 8.dpToPx(), 10.dpToPx())
            addView(
                refreshLayout,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
        }
        itemBinding.apply {
            root.setPadding(0, 0, 0, 0)
            root.background = null
            llTitle.isClickable = false
            llTitle.background = UiCorner.opaqueRounded(
                context.themeMutedColorOrDefault(),
                UiCorner.actionRadius(context)
            )
            tvName.text = source.bookSourceName
            ivStatus.gone()
            flexbox.visible()
            rotateLoading.visible()
        }
        dialog = AlertDialog.Builder(context)
            .setView(dialogContent)
            .create()
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.45f)
        dialog.window?.setLayout(dialogWidth, dialogHeight)
        refreshDiscoverKindsDialog(itemBinding, source, dialog, refreshLayout, clearCache = true)
    }

    private fun refreshDiscoverKindsDialog(
        itemBinding: ItemFindBookBinding,
        source: BookSource,
        dialog: AlertDialog?,
        refreshLayout: SwipeRefreshLayout?,
        clearCache: Boolean
    ) {
        itemBinding.rotateLoading.visible()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(IO) {
                    if (clearCache) {
                        source.clearExploreKindsCache()
                    }
                    source.exploreKinds()
                }
            }
            if (!isAdded || dialog?.isShowing != true) return@launch
            refreshLayout?.isRefreshing = false
            itemBinding.rotateLoading.gone()
            result.onSuccess { kinds ->
                if (kinds.isEmpty()) {
                    context?.toastOnUi(R.string.explore_empty)
                    return@onSuccess
                }
                renderDiscoverDialogKinds(itemBinding, source, kinds, dialog)
            }.onFailure {
                AppLog.put("完整发现刷新失败", it)
                context?.toastOnUi(it.localizedMessage ?: getString(R.string.unknown_error))
            }
        }
    }

    private fun filterDiscoverDialogItems(items: List<DiscoverTagItem>, key: String): List<DiscoverTagItem> {
        val query = key.trim()
        if (query.isBlank()) return items
        return items.filter { item ->
            listOfNotNull(
                item.text,
                item.group,
                item.kind.title,
                item.kind.url,
                item.kind.action
            ).any { it.contains(query, ignoreCase = true) }
        }
    }

    private fun renderDiscoverDialogKinds(
        itemBinding: ItemFindBookBinding,
        source: BookSource,
        kinds: List<ExploreKind>,
        dialog: AlertDialog?
    ) {
        val flexbox = itemBinding.flexbox
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val java = SourceLoginJsExtensions(
            activity as? AppCompatActivity,
            source,
            callback = object : SourceLoginJsExtensions.Callback {
                override fun upUiData(data: Map<String, Any?>?) = Unit
                override fun reUiView(deltaUp: Boolean) {
                    refreshDiscoverKindsDialog(itemBinding, source, dialog, null, clearCache = true)
                }
            }
        )
        flexbox.removeAllViews()
        kinds.forEach { kind ->
            when (kind.type) {
                ExploreKind.Type.url -> renderDiscoverDialogUrl(flexbox, source, kind, dialog, infoMap)
                ExploreKind.Type.button -> renderDiscoverDialogButton(flexbox, source, kind, infoMap, java)
                ExploreKind.Type.toggle -> renderDiscoverDialogToggle(flexbox, source, kind, infoMap, java)
                ExploreKind.Type.select -> renderDiscoverDialogSelect(flexbox, source, kind, infoMap, java)
                ExploreKind.Type.text -> renderDiscoverDialogTextInput(flexbox, source, kind, infoMap, java)
            }
        }
    }

    private fun renderDiscoverDialogUrl(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        dialog: AlertDialog?,
        infoMap: InfoMap
    ) {
        val tv = createDiscoverDialogTextView(flexbox)
        flexbox.addView(tv)
        applyDiscoverKindTextStyle(tv, kind)
        bindDiscoverDialogKindText(tv, kind, source, infoMap)
        tv.setOnClickListener {
            val url = kind.normalizedDiscoverUrl()?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            dialog?.dismiss()
            openDiscoverDialogTagInCurrentPage(source, kind, url)
        }
    }

    private fun openDiscoverDialogTagInCurrentPage(
        source: BookSource,
        kind: ExploreKind,
        url: String
    ) {
        if (selectedDiscoverSource?.bookSourceUrl != source.bookSourceUrl) {
            val sourcePart = discoverSources.firstOrNull { it.bookSourceUrl == source.bookSourceUrl }
            if (sourcePart != null) {
                selectDiscoverSource(sourcePart)
            }
        }
        val item = discoverAllTagItems.firstOrNull {
            it.role == DiscoverTagItem.Role.UrlTag && it.kind.url == url
        } ?: DiscoverTagItem(
            kind = kind.copy(url = url),
            text = resolveDiscoverTagText(kind).limitDiscoverText(6),
            role = DiscoverTagItem.Role.UrlTag,
            group = null
        )
        val index = discoverTagItems.indexOfFirst {
            it.role == DiscoverTagItem.Role.UrlTag && it.kind.url == url
        }
        if (index >= 0) {
            selectDiscoverTag(index, item, selectTab = true)
        } else {
            discoverCurrentUrl = url
            AppConfig.rememberModernDiscoveryTagUrl(source.bookSourceUrl, url)
            binding.topBar.tagsBar.setSelectedIndex(-1, smooth = false)
            loadDiscoverBooks(reset = true)
        }
    }

    private fun renderDiscoverDialogButton(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val tv = createDiscoverDialogTextView(flexbox)
        flexbox.addView(tv)
        applyDiscoverKindTextStyle(tv, kind)
        bindDiscoverDialogKindText(tv, kind, source, infoMap)
        tv.setOnClickListener {
            val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
            }
        }
    }

    private fun renderDiscoverDialogToggle(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val tv = createDiscoverDialogTextView(flexbox)
        flexbox.addView(tv)
        var left = true
        kind.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> tv.gravity = Gravity.START
                "flex_end" -> tv.gravity = Gravity.END
                "right" -> left = false
                else -> tv.gravity = Gravity.CENTER
            }
            apply(tv)
        }
        val chars = kind.chars?.filterNotNull() ?: listOf("chars", "is null")
        var newName = kind.title
        var char = infoMap[kind.title] ?: (kind.default ?: chars.firstOrNull().orEmpty()).also {
            infoMap[kind.title] = it
        }
        fun updateText() {
            tv.text = if (left) char + newName else newName + char
        }
        val viewName = kind.viewName
        if (viewName != null && viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
            newName = viewName.substring(1, viewName.length - 1)
        } else if (!viewName.isNullOrBlank()) {
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(IO) { evalDiscoverDialogUiJs(viewName, source, infoMap) }
                if (!name.isNullOrBlank()) {
                    newName = name
                    updateText()
                }
            }
        }
        updateText()
        tv.setOnClickListener {
            val currentIndex = chars.indexOf(char)
            char = chars.getOrNull(currentIndex + 1) ?: chars.firstOrNull().orEmpty()
            infoMap[kind.title] = char
            updateText()
            val action = kind.action?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
            }
        }
    }

    private fun renderDiscoverDialogSelect(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val binding = ItemFilletSelectorSingleBinding.inflate(layoutInflater, flexbox, false)
        val root = binding.root
        flexbox.addView(root)
        kind.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> root.gravity = Gravity.START
                "flex_end" -> root.gravity = Gravity.END
                else -> root.gravity = Gravity.CENTER
            }
            apply(root)
        }
        bindDiscoverDialogKindText(binding.spName, kind, source, infoMap)
        val chars = kind.chars?.filterNotNull() ?: listOf("chars", "is null")
        val adapter = ArrayAdapter(requireContext(), R.layout.item_text_common, chars)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spType.adapter = adapter
        val char = infoMap[kind.title] ?: (kind.default ?: chars.firstOrNull().orEmpty()).also {
            infoMap[kind.title] = it
        }
        binding.spType.setSelectionSafely(chars.indexOf(char))
        binding.spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initializing = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initializing) {
                    initializing = false
                    return
                }
                if (position !in chars.indices) return
                infoMap[kind.title] = chars[position]
                val action = kind.action?.takeIf { it.isNotBlank() } ?: return
                viewLifecycleOwner.lifecycleScope.launch(IO) {
                    evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun renderDiscoverDialogTextInput(
        flexbox: FlexboxLayout,
        source: BookSource,
        kind: ExploreKind,
        infoMap: InfoMap,
        java: SourceLoginJsExtensions
    ) {
        val input = ItemFilletCompleteTextBinding.inflate(layoutInflater, flexbox, false).root
        flexbox.addView(input)
        kind.style().apply {
            when (layout_justifySelf) {
                "center" -> input.gravity = Gravity.CENTER
                "flex_end" -> input.gravity = Gravity.END
                else -> input.gravity = Gravity.START
            }
            apply(input)
        }
        bindDiscoverDialogKindHint(input, kind, source, infoMap)
        input.setText(infoMap[kind.title])
        var actionJob: Job? = null
        input.addTextChangedListener(object : TextWatcher {
            var content: String? = null
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                content = s?.toString()
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

            override fun afterTextChanged(s: Editable?) {
                val value = s?.toString().orEmpty()
                infoMap[kind.title] = value
                val action = kind.action?.takeIf { it.isNotBlank() } ?: return
                if (value == content) return
                actionJob?.cancel()
                actionJob = viewLifecycleOwner.lifecycleScope.launch(IO) {
                    delay(600)
                    evalDiscoverDialogButtonClick(action, source, infoMap, kind.title, java)
                    content = value
                }
            }
        })
    }

    private fun createDiscoverDialogTextView(flexbox: FlexboxLayout): TextView {
        return ItemFilletTextBinding.inflate(layoutInflater, flexbox, false).root.apply {
            typeface = requireContext().uiTypeface()
        }
    }

    private fun applyDiscoverKindTextStyle(tv: TextView, kind: ExploreKind) {
        kind.style().apply {
            when (layout_justifySelf) {
                "flex_start" -> tv.gravity = Gravity.START
                "flex_end" -> tv.gravity = Gravity.END
                else -> tv.gravity = Gravity.CENTER
            }
            apply(tv)
        }
    }

    private fun bindDiscoverDialogKindText(
        tv: TextView,
        kind: ExploreKind,
        source: BookSource,
        infoMap: InfoMap
    ) {
        val viewName = kind.viewName
        if (viewName == null) {
            tv.text = kind.title
        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
            tv.text = viewName.substring(1, viewName.length - 1)
        } else {
            tv.text = kind.title
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(IO) { evalDiscoverDialogUiJs(viewName, source, infoMap) }
                tv.text = name ?: "err"
            }
        }
    }

    private fun bindDiscoverDialogKindHint(
        input: AutoCompleteTextView,
        kind: ExploreKind,
        source: BookSource,
        infoMap: InfoMap
    ) {
        val viewName = kind.viewName
        if (viewName == null) {
            input.hint = kind.title
        } else if (viewName.length in 3..19 && viewName.first() == '\'' && viewName.last() == '\'') {
            input.hint = viewName.substring(1, viewName.length - 1)
        } else {
            input.hint = kind.title
            viewLifecycleOwner.lifecycleScope.launch {
                val name = withContext(IO) { evalDiscoverDialogUiJs(viewName, source, infoMap) }
                input.hint = name ?: "err"
            }
        }
    }

    private suspend fun evalDiscoverDialogUiJs(
        jsStr: String,
        source: BookSource,
        infoMap: InfoMap
    ): String? {
        return try {
            runScriptWithContext {
                source.evalJS(jsStr) {
                    put("infoMap", infoMap)
                }.toString()
            }
        } catch (e: Throwable) {
            AppLog.put(source.getTag() + " exploreUi err:" + (e.localizedMessage ?: e.toString()), e)
            null
        }
    }

    private suspend fun evalDiscoverDialogButtonClick(
        jsStr: String,
        source: BookSource,
        infoMap: InfoMap,
        name: String,
        java: SourceLoginJsExtensions
    ) {
        try {
            runScriptWithContext {
                source.evalJS(normalizeDiscoverActionScript(jsStr)) {
                    put("java", java)
                    put("infoMap", infoMap)
                }
            }
        } catch (e: Throwable) {
            AppLog.put("ExploreUI Button $name JavaScript error", e)
        }
    }

    private fun createDiscoverDialogControl(
        item: DiscoverTagItem,
        dialog: AlertDialog?,
        refresh: () -> Unit
    ): View {
        return when (item.role) {
            DiscoverTagItem.Role.GlobalSelect -> createDiscoverDialogSelect(item)
            else -> createDiscoverDialogText(item, dialog, refresh)
        }
    }

    private fun createDiscoverDialogText(
        item: DiscoverTagItem,
        dialog: AlertDialog?,
        refresh: () -> Unit
    ): View {
        val binding = ItemFilletTextBinding.inflate(layoutInflater, null, false)
        binding.textView.text = discoverDialogLabel(item)
        binding.textView.maxLines = 1
        binding.textView.ellipsize = TextUtils.TruncateAt.END
        binding.textView.setPadding(16.dpToPx(), 4.dpToPx(), 16.dpToPx(), 4.dpToPx())
        applyDiscoverDialogFlexStyle(binding.root, item)
        binding.root.setOnClickListener {
            when (item.role) {
                DiscoverTagItem.Role.UrlTag -> {
                    dialog?.dismiss()
                    if (!item.group.isNullOrBlank()) {
                        selectedDiscoverMajorGroup = item.group
                        applyDiscoverTagFilterAndSelect(preferredUrl = item.kind.url)
                    } else {
                        val index = discoverTagItems.indexOfFirst {
                            it.role == DiscoverTagItem.Role.UrlTag && it.kind.url == item.kind.url
                        }
                        if (index >= 0) {
                            selectDiscoverTag(index, item, selectTab = true)
                        } else {
                            selectDiscoverTag(0, item, selectTab = false)
                        }
                    }
                }
                DiscoverTagItem.Role.Toggle -> handleDiscoverToggleFromDialog(item, refresh)
                DiscoverTagItem.Role.ActionButton,
                DiscoverTagItem.Role.ScriptUrl -> handleDiscoverButtonTag(item)
                DiscoverTagItem.Role.GlobalSelect -> Unit
            }
        }
        return binding.root
    }

    private fun createDiscoverDialogSelect(item: DiscoverTagItem): View {
        val binding = ItemFilletSelectorSingleBinding.inflate(layoutInflater, null, false)
        binding.spName.text = item.text
        val source = selectedDiscoverSource ?: return binding.root
        val key = item.kind.title
        val options = item.kind.chars?.filterNotNull() ?: emptyList()
        val adapter = ArrayAdapter(requireContext(), R.layout.item_text_common, options)
        adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
        binding.spType.adapter = adapter
        val current = currentDiscoverSelectValue(item)
        binding.spType.setSelection(options.indexOf(current).coerceAtLeast(0), false)
        binding.spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var initializing = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initializing) {
                    initializing = false
                    return
                }
                if (position !in options.indices || key.isBlank()) return
                val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
                infoMap[key] = options[position]
                viewLifecycleOwner.lifecycleScope.launch(IO) {
                    source.clearExploreKindsCache()
                    item.kind.action?.takeIf { it.isNotBlank() }?.let { action ->
                        val script = normalizeDiscoverActionScript(action)
                        runScriptWithContext {
                            source.evalJS(script) {
                                put("java", SourceLoginJsExtensions(activity as? AppCompatActivity, source))
                                put("infoMap", infoMap)
                            }
                        }
                    }
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        loadDiscoverKindsAndDefault()
                    }
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        applyDiscoverDialogFlexStyle(binding.root, item)
        return binding.root
    }

    private fun applyDiscoverDialogFlexStyle(view: View, item: DiscoverTagItem) {
        val style = item.kind.style()
        val params = FlexboxLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(3.dpToPx(), 3.dpToPx(), 3.dpToPx(), 3.dpToPx())
            flexGrow = style.layout_flexGrow
            flexShrink = style.layout_flexShrink
            alignSelf = style.alignSelf()
            flexBasisPercent = style.layout_flexBasisPercent
            isWrapBefore = style.layout_wrapBefore
        }
        view.layoutParams = params
    }

    private fun discoverDialogLabel(item: DiscoverTagItem): CharSequence {
        val prefix = when (item.role) {
            DiscoverTagItem.Role.UrlTag -> ""
            DiscoverTagItem.Role.GlobalSelect -> "选择 "
            DiscoverTagItem.Role.Toggle -> "开关 "
            DiscoverTagItem.Role.ActionButton,
            DiscoverTagItem.Role.ScriptUrl -> "操作 "
        }
        val group = item.group?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
        val value = if (item.role == DiscoverTagItem.Role.GlobalSelect) {
            ": ${currentDiscoverSelectValue(item)}"
        } else {
            ""
        }
        return "$prefix${item.text}$value$group"
    }

    private fun handleDiscoverToggleFromDialog(item: DiscoverTagItem, refresh: () -> Unit) {
        val source = selectedDiscoverSource ?: return
        val key = item.kind.title
        if (key.isBlank()) return
        val chars = item.kind.chars?.filterNotNull() ?: return
        if (chars.isEmpty()) return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val current = infoMap[key] ?: item.kind.default ?: chars.first()
        val currentIndex = chars.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = chars.getOrNull(currentIndex + 1) ?: chars.first()
        infoMap[key] = next
        refresh()
        handleDiscoverButtonTag(item.copy(text = resolveDiscoverToggleText(item.kind, next).limitDiscoverText(8)))
    }

    private fun selectDiscoverSource(source: BookSourcePart) {
        selectedDiscoverSourcePart = source
        AppConfig.modernDiscoverySourceUrl = source.bookSourceUrl
        renderDiscoverSourceSelector()
        updateDiscoverLoginButtonState()
        tagFilterPopup?.dismiss()
        tagFilterPopup = null
        discoverSourceVersion += 1
        val currentSourceVersion = discoverSourceVersion
        discoverRequestVersion += 1
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        binding.pbDiscoverLoading.gone()
        discoverCurrentUrl = AppConfig.modernDiscoveryTagUrl(source.bookSourceUrl)
        discoverBooks.clear()
        syncDiscoverComposeState()
        discoverBookAdapter?.clearItems()
        restoreModernDiscoverCacheAsync(
            sourceUrl = source.bookSourceUrl,
            tagUrl = discoverCurrentUrl,
            sourceVersion = currentSourceVersion,
            clearOnMiss = false
        )
        binding.tvDiscoverEmpty.gone()
        discoverAllTagItems.clear()
        discoverMajorGroups.clear()
        discoverSelectItems.clear()
        selectedDiscoverMajorGroup = null
        discoverDefaultFiltersAppliedKey = null
        renderDiscoverTags(emptyList(), -1)
        renderDiscoverSelects(emptyList())
        updateDiscoverTagFilterButtonState()
        viewLifecycleOwner.lifecycleScope.launch {
            val fullSource = withContext(IO) {
                appDb.bookSourceDao.getBookSource(source.bookSourceUrl)
            }
            if (currentSourceVersion != discoverSourceVersion || !isAdded) {
                return@launch
            }
            selectedDiscoverSource = fullSource
            updateDiscoverSourceTitle()
            updateDiscoverSearchButtonState()
            loadDiscoverKindsAndDefault()
        }
    }

    private fun updateDiscoverSourceTitle() {
        val name = selectedDiscoverSourcePart?.bookSourceName
            ?: getString(R.string.discovery)
        binding.topBar.setTitle(if (binding.topBar.isRegularStyle()) getString(R.string.discovery) else name)
        binding.topBar.setSearchHint(name)
        renderDiscoverSourceSelector()
        binding.topBar.post(::updateDiscoverSourceNameWidth)
    }

    private fun renderDiscoverSourceSelector() {
        binding.topBar.setPrimaryItems(
            discoverSources.map { RoundedTagBarView.Item(it.bookSourceName) },
            discoverSources.indexOfFirst { it.bookSourceUrl == selectedDiscoverSourcePart?.bookSourceUrl }
        )
    }

    private fun refreshModernDiscoverKinds() {
        val source = selectedDiscoverSource ?: run {
            binding.swipeRefreshLayout.isRefreshing = false
            return
        }
        discoverActionJob?.cancel()
        discoverActionJob = viewLifecycleOwner.lifecycleScope.launch {
            runCatching {
                withContext(IO) {
                    source.clearExploreKindsCache()
                }
                loadDiscoverKindsAndDefault()
            }.onFailure {
                AppLog.put("刷新发现分类失败", it)
                context?.toastOnUi(it.localizedMessage ?: getString(R.string.unknown_error))
            }
            binding.swipeRefreshLayout.isRefreshing = false
        }
    }

    private suspend fun loadDiscoverKindsAndDefault() {
        val source = selectedDiscoverSource ?: return
        val kinds = withContext(IO) {
            source.exploreKinds()
        }
        val items = buildDiscoverTagItems(source, kinds)
        discoverAllTagItems.clear()
        discoverAllTagItems.addAll(items)
        if (items.isEmpty()) {
            discoverMajorGroups.clear()
            selectedDiscoverMajorGroup = null
            renderDiscoverTags(emptyList(), -1)
            renderDiscoverSelects(emptyList())
            updateDiscoverTagFilterButtonState()
            clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
            return
        }
        applyDiscoverTagFilterAndSelect(
            preferredUrl = discoverCurrentUrl,
            restoreGroupForPreferredUrl = true
        )
    }

    private suspend fun buildDiscoverTagItems(
        source: BookSource,
        kinds: List<ExploreKind>
    ): List<DiscoverTagItem> {
        val blocked = blockedButtonActions[source.bookSourceUrl]
        var currentGroup: String? = null
        val result = mutableListOf<DiscoverTagItem>()
        kinds.forEachIndexed { index, kind ->
            if (index == 0 && isDiscoverLeadingBlankPlaceholder(kind)) {
                return@forEachIndexed
            }

            val action = kind.action?.takeIf { it.isNotBlank() }
            val url = kind.normalizedDiscoverUrl()
            if (isDiscoverModernIgnoredControl(kind, currentGroup, url, action)) {
                return@forEachIndexed
            }
            val isSelect = kind.type == ExploreKind.Type.select
            val isActionControl = kind.type == ExploreKind.Type.button ||
                kind.type == ExploreKind.Type.toggle ||
                isDiscoverInputKind(kind)

            if (action.isNullOrBlank() && isDiscoverMajorGroupKind(kind)) {
                currentGroup = resolveDiscoverGroupTitle(kind)
                return@forEachIndexed
            }

            if (isSelect) {
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.select),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    role = DiscoverTagItem.Role.GlobalSelect,
                    group = null
                )
                return@forEachIndexed
            }

            if (action.isNullOrBlank() && isDiscoverDecorativeGroupKind(kind)) {
                currentGroup = resolveDiscoverGroupTitle(kind)
                return@forEachIndexed
            }

            if (!url.isNullOrBlank() && isDiscoverScriptButtonUrl(url)) {
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.button, action = url),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    role = DiscoverTagItem.Role.ScriptUrl,
                    group = null
                )
                return@forEachIndexed
            }

            if (!action.isNullOrBlank() && isActionControl) {
                if (blocked?.contains(action) == true) return@forEachIndexed
                val role = if (kind.type == ExploreKind.Type.toggle) {
                    DiscoverTagItem.Role.Toggle
                } else {
                    DiscoverTagItem.Role.ActionButton
                }
                result += DiscoverTagItem(
                    kind = kind.copy(type = ExploreKind.Type.button),
                    text = resolveDiscoverControlText(kind).limitDiscoverText(8),
                    role = role,
                    group = currentGroup
                )
                return@forEachIndexed
            }

            if (!url.isNullOrBlank()) {
                result += DiscoverTagItem(
                    kind = kind.copy(url = url),
                    text = resolveDiscoverTagText(kind).limitDiscoverText(6),
                    role = DiscoverTagItem.Role.UrlTag,
                    group = currentGroup
                )
                return@forEachIndexed
            }
        }
        val hasMajorGroup = result.any { !it.group.isNullOrBlank() }
        val normalized = if (hasMajorGroup) {
            result
        } else {
            result.map { it.copy(group = getString(R.string.discover_group_other)) }
        }
        return normalized.distinctBy {
            "${it.group}|${it.role}|${it.kind.type}|${it.kind.title}|${it.kind.url}|${it.kind.action}|${it.kind.default}|${it.kind.viewName}|${it.kind.chars?.joinToString("\u001f")}"
        }
    }

    private fun isDiscoverMajorGroupKind(kind: ExploreKind): Boolean {
        if (!kind.normalizedDiscoverUrl().isNullOrBlank()) return false
        if (!kind.action.isNullOrBlank()) return false
        if (!isDiscoverFullWidthKind(kind)) return false
        if (kind.type == ExploreKind.Type.toggle) return true
        return kind.action.isNullOrBlank()
    }

    private fun isDiscoverInputKind(kind: ExploreKind): Boolean {
        return kind.type == ExploreKind.Type.text || kind.type == "password"
    }

    private fun isDiscoverModernIgnoredControl(
        kind: ExploreKind,
        currentGroup: String?,
        url: String?,
        action: String?
    ): Boolean {
        if (!url.isNullOrBlank()) return false
        val text = resolveDiscoverTagText(kind).trim()
        val compact = text
            .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F\\s]+"), "")
            .trim()
        if (compact.isBlank() || compact == "※" || compact.equals("null", ignoreCase = true)) {
            return true
        }
        if (isDiscoverInputKind(kind)) {
            return true
        }
        val actionValue = action.orEmpty()
        val viewName = kind.viewName.orEmpty()
        val isTopSearchControl = currentGroup.isNullOrBlank() && (
            compact.contains("搜索") ||
                viewName.contains("搜索") ||
                actionValue.contains("toSearch", ignoreCase = true)
            )
        return isTopSearchControl
    }

    private fun isDiscoverFullWidthKind(kind: ExploreKind): Boolean {
        val style = kind.style()
        return style.layout_flexBasisPercent >= 0.95f ||
            (style.layout_flexGrow >= 1f && style.layout_flexBasisPercent < 0f)
    }

    private fun isDiscoverLeadingBlankPlaceholder(kind: ExploreKind): Boolean {
        if (!isDiscoverFullWidthKind(kind)) return false
        if (!kind.normalizedDiscoverUrl().isNullOrBlank()) return false
        if (!kind.action.isNullOrBlank()) return false
        val text = resolveDiscoverTagText(kind).trim()
        if (text.isNotBlank() && text != ExploreKind.Type.button) return false
        return kind.type == ExploreKind.Type.button || kind.action.isNullOrBlank()
    }

    private fun isDiscoverDecorativeGroupKind(kind: ExploreKind): Boolean {
        if (!kind.normalizedDiscoverUrl().isNullOrBlank()) return false
        val text = resolveDiscoverTagText(kind).trim()
        if (text.isBlank()) return false
        if (isDiscoverFullWidthKind(kind)) return true
        val compact = text.replace("\\s+".toRegex(), "")
        val hasDecoration = compact.count { it == '◎' || it == '●' || it == '○' || it == '◆' || it == '◇' || it == '=' || it == '-' } >= 2
        return hasDecoration || compact.endsWith("分类") || compact.endsWith("排行") || compact.endsWith("排行榜")
    }

    private fun isDiscoverScriptButtonUrl(url: String): Boolean {
        val value = url.trim()
        if (value.startsWith("@js:", ignoreCase = true) || value.startsWith("<js>", ignoreCase = true)) {
            return false
        }
        if (value.contains("page", ignoreCase = true) || value.contains("{{") || value.contains("{\\{")) {
            return false
        }
        return value.contains("java.startBrowser", ignoreCase = true) ||
            value.contains("java.longToast", ignoreCase = true) ||
            value.contains("java.toast", ignoreCase = true) ||
            value.contains("java.open", ignoreCase = true) ||
            value.contains("source.setVariable", ignoreCase = true)
    }

    private fun resolveDiscoverGroupTitle(kind: ExploreKind): String {
        val raw = resolveDiscoverTagText(kind).trim()
        if (raw.isBlank()) return getString(R.string.discovery)
        val normalized = raw
            .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F]+"), " ")
            .replace(Regex("[\\uFF1A:|/\\\\]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        return normalized.ifBlank { raw }
    }

    private fun resolveDiscoverTagText(kind: ExploreKind): String {
        val viewName = kind.viewName
        if (!viewName.isNullOrBlank()
            && viewName.length in 3..28
            && viewName.first() == '\''
            && viewName.last() == '\''
        ) {
            return viewName.substring(1, viewName.length - 1)
        }
        return kind.title.ifBlank { kind.type }
    }

    private fun resolveDiscoverControlText(kind: ExploreKind): String {
        if (kind.type != ExploreKind.Type.toggle) {
            return resolveDiscoverTagText(kind)
        }
        val source = selectedDiscoverSource ?: return resolveDiscoverTagText(kind)
        val key = kind.title
        val value = if (key.isBlank()) {
            kind.default ?: kind.chars?.firstOrNull().orEmpty()
        } else {
            val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
            infoMap[key] ?: (kind.default ?: kind.chars?.firstOrNull().orEmpty()).also {
                infoMap[key] = it
            }
        }
        return resolveDiscoverToggleText(kind, value)
    }

    private fun ExploreKind.normalizedDiscoverUrl(): String? {
        return url?.trim()?.takeIf {
            it.isNotBlank() && !it.equals("null", ignoreCase = true)
        }
    }

    private fun applyDiscoverTagFilterAndSelect(
        preferredUrl: String?,
        restoreGroupForPreferredUrl: Boolean = false
    ) {
        val hasGroupedItems = discoverAllTagItems.any { !it.group.isNullOrBlank() }
        val groupList = discoverAllTagItems
            .mapNotNull { it.group?.takeIf { name -> name.isNotBlank() } }
            .filter { group ->
                discoverAllTagItems.any { it.group == group && isDiscoverVisibleGroupItem(it) }
            }
            .distinct()
        discoverMajorGroups.clear()
        discoverMajorGroups.addAll(groupList)

        if (discoverMajorGroups.isEmpty()) {
            selectedDiscoverMajorGroup = null
            if (hasGroupedItems) {
                renderDiscoverSelects(emptyList())
                renderDiscoverTags(emptyList(), -1)
                clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
                updateDiscoverTagFilterButtonState()
                return
            }
        } else {
            if (restoreGroupForPreferredUrl && !preferredUrl.isNullOrBlank()) {
                discoverAllTagItems.firstOrNull {
                    it.role == DiscoverTagItem.Role.UrlTag && it.kind.url == preferredUrl
                }?.group?.takeIf { it.isNotBlank() }?.let { group ->
                    selectedDiscoverMajorGroup = group
                }
            }
            if (selectedDiscoverMajorGroup !in discoverMajorGroups) {
                selectedDiscoverMajorGroup = discoverMajorGroups.first()
            }
        }

        var filtered = if (discoverMajorGroups.isEmpty()) {
            discoverAllTagItems.toList()
        } else {
            discoverAllTagItems.filter {
                it.group == selectedDiscoverMajorGroup || isDiscoverGlobalControl(it)
            }
        }
        if (filtered.isEmpty() && discoverMajorGroups.isNotEmpty()) {
            val fallbackGroup = discoverMajorGroups.firstOrNull { group ->
                discoverAllTagItems.any { it.group == group && isDiscoverVisibleGroupItem(it) }
            }
            selectedDiscoverMajorGroup = fallbackGroup
            filtered = if (fallbackGroup.isNullOrBlank()) {
                discoverAllTagItems.toList()
            } else {
                discoverAllTagItems.filter {
                    it.group == fallbackGroup || isDiscoverGlobalControl(it)
                }
            }
        }

        val selectItems = discoverAllTagItems.filter { it.role == DiscoverTagItem.Role.GlobalSelect }
        val tagItems = filtered.filter { it.role != DiscoverTagItem.Role.GlobalSelect }
        updateDiscoverTagFilterButtonState()
        renderDiscoverSelects(selectItems)
        val targetIndexByUrl = preferredUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { url ->
                tagItems.indexOfFirst { !it.isButton && it.kind.url == url }
                    .takeIf { idx -> idx >= 0 }
            }
        val targetIndex = targetIndexByUrl
            ?: tagItems.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        renderDiscoverTags(tagItems, targetIndex)
        applyDiscoverDefaultFilterExpansionOnce()
        if (targetIndex >= 0) {
            selectDiscoverTag(targetIndex, tagItems[targetIndex], selectTab = true)
        } else {
            clearDiscoverBooksToEmpty(getString(R.string.explore_empty))
        }
    }

    private fun isDiscoverVisibleGroupItem(item: DiscoverTagItem): Boolean {
        return item.isButton || !item.kind.url.isNullOrBlank()
    }

    private fun isDiscoverGlobalControl(item: DiscoverTagItem): Boolean {
        return item.group.isNullOrBlank() && item.role != DiscoverTagItem.Role.UrlTag
    }

    private fun updateDiscoverTagFilterButtonState() {
        val enabled = discoverMajorGroups.size > 1
        binding.topBar.filterButton.isVisible = enabled
        binding.topBar.filterButton.isEnabled = enabled
        binding.topBar.filterButton.alpha = if (enabled) 1f else 0.45f
    }

    private fun showDiscoverTagFilterMenu() {
        if (discoverMajorGroups.size <= 1) return
        val current = selectedDiscoverMajorGroup
        val actions = buildList {
            discoverMajorGroups.forEach { group ->
                add(
                    ModernActionPopup.Action(
                        title = group.limitDiscoverText(10),
                        checked = group == current
                    ) {
                        if (group == selectedDiscoverMajorGroup) return@Action
                        selectedDiscoverMajorGroup = group
                        applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
                    }
                )
            }
        }
        tagFilterPopup = ModernActionPopup.show(
            binding.topBar.filterButton,
            actions,
            tagFilterPopup
        )
    }

    private fun renderDiscoverTags(items: List<DiscoverTagItem>, selectedIndex: Int) {
        discoverTagItems.clear()
        discoverTagItems.addAll(items)
        if (items.isEmpty()) {
            binding.topBar.showTags(false)
            selectedDiscoverTagIndex = -1
            selectedDiscoverUrlIndex = -1
            binding.topBar.tagsBar.submitItems(emptyList(), -1)
            return
        }
        binding.topBar.showTags(true)
        selectedDiscoverTagIndex = selectedIndex.coerceIn(-1, items.lastIndex)
        selectedDiscoverUrlIndex = if (selectedDiscoverTagIndex in items.indices && !items[selectedDiscoverTagIndex].isButton) {
            selectedDiscoverTagIndex
        } else {
            items.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        }
        binding.topBar.tagsBar.submitItems(
            items.map { RoundedTagBarView.Item(it.text, if (it.isButton) 0.9f else 1f) },
            selectedDiscoverTagIndex
        )
    }

    private fun renderDiscoverSelects(items: List<DiscoverTagItem>) {
        discoverSelectItems.clear()
        discoverSelectItems.addAll(items)
        if (items.isEmpty()) {
            binding.topBar.showSelects(false)
            binding.topBar.selectsBar.submitItems(emptyList(), -1)
            return
        }
        binding.topBar.showSelects(true)
        binding.topBar.selectsBar.submitItems(
            items.map {
                val value = currentDiscoverSelectValue(it)
                RoundedTagBarView.Item("${it.text}: $value", 1f)
            },
            -1
        )
    }

    private fun applyDiscoverDefaultFilterExpansionOnce() {
        if (!usingModernDiscovery || !binding.topBar.isRegularStyle()) return
        val config = TopBarConfig.currentConfig(requireContext(), AppConfig.isNightTheme)
        if (!config.expandFiltersByDefault) return
        if (discoverSelectItems.isEmpty() && discoverTagItems.isEmpty()) return
        val key = "${TopBarConfig.currentSignature(AppConfig.isNightTheme)}|${selectedDiscoverSourcePart?.bookSourceUrl.orEmpty()}"
        if (discoverDefaultFiltersAppliedKey == key) return
        discoverDefaultFiltersAppliedKey = key
        binding.topBar.setFiltersExpanded(true)
    }

    private fun currentDiscoverSelectValue(item: DiscoverTagItem): String {
        val source = selectedDiscoverSource ?: return item.kind.default ?: ""
        val key = item.kind.title
        if (key.isBlank()) return item.kind.default ?: ""
        val info = getDiscoverInfoMap(source.bookSourceUrl)
        info[key]?.let { return it }
        val value = item.kind.default
            ?: item.kind.chars?.firstOrNull()
            ?: ""
        info[key] = value
        return value
    }

    private fun showDiscoverSelectDialog(item: DiscoverTagItem) {
        val source = selectedDiscoverSource ?: return
        val key = item.kind.title
        if (key.isBlank()) return
        val options = item.kind.chars?.filterNotNull() ?: emptyList()
        if (options.isEmpty()) return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        context?.selector(item.text, options) { _, value, _ ->
            infoMap[key] = value
            viewLifecycleOwner.lifecycleScope.launch(IO) {
                source.clearExploreKindsCache()
                val action = item.kind.action?.takeIf { it.isNotBlank() }
                if (!action.isNullOrBlank()) {
                    val script = normalizeDiscoverActionScript(action)
                    runScriptWithContext {
                        source.evalJS(script) {
                            put("java", SourceLoginJsExtensions(activity as? AppCompatActivity, source))
                            put("infoMap", infoMap)
                        }
                    }
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    loadDiscoverKindsAndDefault()
                }
            }
        }
    }

    private fun selectDiscoverTabByCode(index: Int, smooth: Boolean) {
        if (index !in discoverTagItems.indices) return
        binding.topBar.tagsBar.setSelectedIndex(index, smooth)
    }

    private fun selectDiscoverTag(index: Int, item: DiscoverTagItem, selectTab: Boolean) {
        val url = item.kind.url?.takeIf { it.isNotBlank() } ?: return
        selectedDiscoverTagIndex = index
        selectedDiscoverUrlIndex = index
        if (selectTab) {
            selectDiscoverTabByCode(index, smooth = true)
        }
        if (discoverCurrentUrl == url && discoverBooks.isNotEmpty()) {
            AppConfig.rememberModernDiscoveryTagUrl(selectedDiscoverSource?.bookSourceUrl, url)
            return
        }
        discoverCurrentUrl = url
        AppConfig.rememberModernDiscoveryTagUrl(selectedDiscoverSource?.bookSourceUrl, url)
        loadDiscoverBooks(reset = true)
    }

    private fun handleDiscoverButtonTag(item: DiscoverTagItem) {
        val source = selectedDiscoverSource ?: return
        val action = item.kind.action?.takeIf { it.isNotBlank() } ?: return
        val script = normalizeDiscoverActionScript(action)
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val actionLower = script.lowercase()
        val isNavigationAction = actionLower.contains("showbrowser(")
            || actionLower.contains("open(\"explore\"")
            || actionLower.contains("open('explore'")
            || actionLower.contains("startbrowser(")
        discoverActionJob?.cancel()
        discoverActionJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.pbDiscoverLoading.visible()
            val result = withContext(IO) {
                kotlin.runCatching {
                    var handledByAction = false
                    val java = SourceLoginJsExtensions(
                        activity as? AppCompatActivity,
                        source,
                        callback = object : SourceLoginJsExtensions.Callback {
                            override fun upUiData(data: Map<String, Any?>?) = Unit
                            override fun reUiView(deltaUp: Boolean) = Unit
                            override fun showBrowser(
                                url: String,
                                html: String?,
                                preloadJs: String?,
                                config: String?
                            ): Boolean {
                                return false
                            }

                            override fun open(
                                name: String,
                                url: String?,
                                title: String?,
                                origin: String?
                            ): Boolean {
                                if (!isAdded) return false
                                if (name != "explore") return false
                                handledByAction = true
                                val targetUrl = url?.takeIf { it.isNotBlank() } ?: return true
                                val targetSourceUrl = origin
                                    ?.takeIf { it.isNotBlank() }
                                    ?: selectedDiscoverSource?.bookSourceUrl
                                    ?: source.bookSourceUrl
                                val targetTitle = title ?: item.text
                                binding.root.post {
                                    openExplore(targetSourceUrl, targetTitle, targetUrl)
                                }
                                return true
                            }
                        }
                    )
                    runScriptWithContext {
                        source.evalJS(script) {
                            put("java", java)
                            put("infoMap", infoMap)
                        }
                    }
                    when {
                        handledByAction || isNavigationAction -> null
                        else -> {
                            source.clearExploreKindsCache()
                            source.exploreKinds()
                        }
                    }
                }
            }
            binding.pbDiscoverLoading.gone()
            if (!isAdded) return@launch
            result.onSuccess { kinds ->
                if (kinds == null) {
                    return@onSuccess
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    applyDiscoverButtonResult(source, script, kinds)
                }
            }.onFailure {
                AppLog.put("发现标签按钮执行失败", it)
                context?.toastOnUi(it.localizedMessage ?: getString(R.string.unknown_error))
            }
        }
    }

    private suspend fun applyDiscoverButtonResult(
        source: BookSource,
        action: String,
        kinds: List<ExploreKind>
    ) {
        val items = buildDiscoverTagItems(source, kinds)
        val firstUrlIndex = items.indexOfFirst { !it.isButton && !it.kind.url.isNullOrBlank() }
        if (firstUrlIndex >= 0) {
            discoverAllTagItems.clear()
            discoverAllTagItems.addAll(items)
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
            return
        }
        if (items.isNotEmpty()) {
            discoverAllTagItems.clear()
            discoverAllTagItems.addAll(items)
            applyDiscoverTagFilterAndSelect(preferredUrl = discoverCurrentUrl)
        }
        context?.toastOnUi(R.string.find_empty)
    }

    private fun getDiscoverInfoMap(sourceUrl: String): InfoMap {
        return ExploreAdapter.exploreInfoMapList[sourceUrl] ?: InfoMap(sourceUrl).also {
            ExploreAdapter.exploreInfoMapList.put(sourceUrl, it)
        }
    }

    private fun clearDiscoverBooksToEmpty(message: String) {
        discoverRequestVersion += 1
        discoverLoadJob?.cancel()
        discoverLoadJob = null
        discoverLoading = false
        binding.swipeRefreshLayout.isRefreshing = false
        binding.pbDiscoverLoading.gone()
        discoverCurrentUrl = null
        discoverHasMore = false
        discoverPage = 1
        discoverBooks.clear()
        syncDiscoverComposeState()
        discoverBookAdapter?.clearItems()
        binding.tvDiscoverEmpty.text = message
        binding.tvDiscoverEmpty.visible()
    }

    private fun loadDiscoverBooks(reset: Boolean) {
        if (!usingModernDiscovery) return
        val source = selectedDiscoverSource ?: return
        val sourceUrl = source.bookSourceUrl
        val url = discoverCurrentUrl?.takeIf { it.isNotBlank() } ?: return
        if (!reset && !discoverHasMore) return
        if (reset) {
            discoverLoadJob?.cancel()
        } else if (discoverLoading) {
            return
        }
        val requestVersion = if (reset) {
            discoverRequestVersion += 1
            discoverRequestVersion
        } else {
            discoverRequestVersion
        }
        discoverLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            if (reset) {
                discoverPage = 1
                discoverHasMore = true
                val cache = withContext(IO) {
                    readModernDiscoverCache(sourceUrl, url)
                }
                if (!isAdded || requestVersion != discoverRequestVersion || url != discoverCurrentUrl) {
                    return@launch
                }
                if (cache != null && cache.books.isNotEmpty()) {
                    applyModernDiscoverCache(cache)
                } else {
                    discoverBooks.clear()
                    syncDiscoverComposeState(forceBooks = true)
                    discoverBookAdapter?.clearItems()
                }
                binding.tvDiscoverEmpty.gone()
            }
            val pageToLoad = if (reset) 1 else discoverPage
            discoverLoading = true
            syncDiscoverComposeState()
            binding.pbDiscoverLoading.visible()
            try {
                val newBooks = withContext(IO) {
                    WebBook.exploreBookAwait(
                        source,
                        url,
                        pageToLoad,
                        WebViewPool.Scope.DISCOVERY
                    )
                }
                if (!isAdded || requestVersion != discoverRequestVersion || url != discoverCurrentUrl) {
                    return@launch
                }
                if (newBooks.isEmpty()) {
                    discoverHasMore = false
                    if (discoverBooks.isEmpty()) {
                        binding.tvDiscoverEmpty.text = getString(R.string.explore_empty)
                        binding.tvDiscoverEmpty.visible()
                    }
                } else {
                    val oldSize = discoverBooks.size
                    val mergedBooks = if (reset) {
                        SearchBookMergeUtils.appendReplacing(emptyList(), newBooks)
                    } else {
                        SearchBookMergeUtils.appendReplacing(discoverBooks, newBooks)
                    }
                    val hasNewBooks = if (reset) mergedBooks.isNotEmpty() else mergedBooks.size > oldSize
                    discoverBooks.clear()
                    discoverBooks.addAll(mergedBooks)
                    if (hasNewBooks) {
                        discoverPage = pageToLoad + 1
                    } else {
                        discoverHasMore = false
                    }
                    syncDiscoverComposeState()
                    discoverBookAdapter?.setItems(discoverBooks.toList())
                    binding.tvDiscoverEmpty.gone()
                    saveModernDiscoverCacheAsync(
                        sourceUrl = sourceUrl,
                        tagUrl = url,
                        books = discoverBooks.toList(),
                        nextPage = discoverPage,
                        hasMore = discoverHasMore
                    )
                    viewLifecycleOwner.lifecycleScope.launch(IO) {
                        runCatching {
                            appDb.searchBookDao.insert(*newBooks.toTypedArray())
                        }.onFailure {
                            AppLog.put("发现页面搜索缓存写入失败", it)
                        }
                    }
                    VideoBookPreloader.preloadSearchBooks(viewLifecycleOwner.lifecycleScope, newBooks)
                }
            } catch (_: CancellationException) {
                return@launch
            } catch (e: Throwable) {
                if (!isAdded || requestVersion != discoverRequestVersion || url != discoverCurrentUrl) {
                    return@launch
                }
                AppLog.put("发现页面加载失败", e)
                if (discoverBooks.isEmpty()) {
                    binding.tvDiscoverEmpty.text = e.localizedMessage ?: getString(R.string.unknown_error)
                    binding.tvDiscoverEmpty.visible()
                }
            } finally {
                if (isAdded && requestVersion == discoverRequestVersion && url == discoverCurrentUrl) {
                    binding.pbDiscoverLoading.gone()
                    binding.swipeRefreshLayout.isRefreshing = false
                    discoverLoading = false
                    syncDiscoverComposeState()
                }
            }
        }
    }

    private fun initRecyclerView() {
        binding.rvFind.setEdgeEffectColor(primaryColor)
        binding.rvFind.layoutManager = linearLayoutManager
        binding.rvFind.adapter = adapter
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                super.onItemRangeInserted(positionStart, itemCount)
                if (positionStart == 0) {
                    binding.rvFind.scrollToPosition(0)
                }
            }
        })
    }

    private fun initGroupData() {
        viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookSourceDao.flowExploreGroups()
                .flowWithLifecycleAndDatabaseChange(
                    viewLifecycleOwner.lifecycle,
                    Lifecycle.State.RESUMED,
                    AppDatabase.BOOK_SOURCE_TABLE_NAME
                )
                .conflate()
                .distinctUntilChanged()
                .collect {
                    groups.clear()
                    groups.addAll(it)
                    upGroupsMenu()
                    delay(DISCOVERY_CLASSIC_FLOW_COALESCE_DELAY_MS)
                }
        }
    }

    private fun upExploreData(searchKey: String? = null) {
        exploreFlowJob?.cancel()
        exploreFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            when {
                searchKey.isNullOrBlank() -> {
                    appDb.bookSourceDao.flowExplore()
                }

                searchKey.startsWith("group:") -> {
                    val key = searchKey.substringAfter("group:")
                    appDb.bookSourceDao.flowGroupExplore(key)
                }

                else -> {
                    appDb.bookSourceDao.flowExplore(searchKey)
                }
            }.flowWithLifecycleAndDatabaseChange(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_SOURCE_TABLE_NAME
            ).catch {
                AppLog.put("发现页面更新数据出错", it)
            }.conflate().flowOn(IO).collect {
                binding.swipeRefreshLayout.isRefreshing = false
                binding.tvEmptyMsg.isGone = it.isNotEmpty() || (searchView?.query?.isNotEmpty() == true)
                adapter.setItems(it, diffItemCallBack)
                delay(DISCOVERY_CLASSIC_FLOW_COALESCE_DELAY_MS)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (discoveryPageMode != AppConfig.discoveryPageMode || !discoveryModeLoaded) {
            applyDiscoveryMode(loadData = true)
            discoveryModeLoaded = true
        } else if (usingModernDiscovery) {
            applyDiscoverBookLayout()
            syncDiscoverComposeState()
        } else if (usingSuiteDiscovery) {
            refreshSuiteConfig()
        }
        if (!usingModernDiscovery && !usingSuiteDiscovery) {
            adapter.upResumed(true)
        }
    }

    override fun onPause() {
        if (!usingModernDiscovery && !usingSuiteDiscovery) {
            adapter.upResumed(false)
            searchView?.clearFocus()
            adapter.onPause()
        }
        if (usingModernDiscovery) {
            discoverLoadJob?.cancel()
            discoverLoadJob = null
            discoverLoading = false
            binding.pbDiscoverLoading.gone()
            binding.swipeRefreshLayout.isRefreshing = false
            discoverRequestVersion += 1
            syncDiscoverComposeState()
        }
        if (usingSuiteDiscovery) {
            saveCurrentSuiteSnapshot()
            suiteLoadJob?.cancel()
            suiteLoadJob = null
            binding.swipeRefreshLayout.isRefreshing = false
        }
        WebViewPool.scheduleDestroyScope(WebViewPool.Scope.DISCOVERY)
        super.onPause()
    }

    override fun onDestroyView() {
        stopModernMode()
        stopSuiteMode()
        WebViewPool.destroyScope(WebViewPool.Scope.DISCOVERY)
        oldModeInitialized = false
        modernModeInitialized = false
        groupsMenu = null
        super.onDestroyView()
    }

    private fun upGroupsMenu() = groupsMenu?.transaction { subMenu ->
        subMenu.removeGroup(R.id.menu_group_text)
        groups.forEach {
            subMenu.add(R.id.menu_group_text, Menu.NONE, Menu.NONE, it)
        }
    }

    override val scope: CoroutineScope
        get() = viewLifecycleOwner.lifecycleScope

    override fun onCompatOptionsItemSelected(item: MenuItem) {
        super.onCompatOptionsItemSelected(item)
        if (usingModernDiscovery || usingSuiteDiscovery) return
        if (item.groupId == R.id.menu_group_text) {
            searchView?.setQuery("group:${item.title}", true) ?: upExploreData("group:${item.title}")
        }
    }

    override fun scrollTo(pos: Int) {
        (binding.rvFind.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
    }

    override fun openExplore(sourceUrl: String, title: String, exploreUrl: String?) {
        if (exploreUrl.isNullOrBlank()) return
        startActivity<ExploreShowActivity> {
            putExtra("exploreName", title)
            putExtra("sourceUrl", sourceUrl)
            putExtra("exploreUrl", exploreUrl)
        }
    }

    override fun editSource(sourceUrl: String) {
        startActivity<BookSourceEditActivity> {
            putExtra("sourceUrl", sourceUrl)
        }
    }

    override fun toTop(source: BookSourcePart) {
        viewModel.topSource(source)
    }

    override fun deleteSource(source: BookSourcePart) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + source.bookSourceName)
            noButton()
            yesButton {
                viewModel.deleteSource(source)
            }
        }
    }

    override fun searchBook(bookSource: BookSourcePart) {
        SearchActivity.start(requireContext(), bookSource)
    }

    override fun isInBookshelf(book: SearchBook): Boolean {
        val key = if (book.author.isNotBlank()) "${book.name}-${book.author}" else book.name
        return discoverBookshelf.contains(key) || discoverBookshelf.contains(book.bookUrl)
    }

    override fun showBookInfo(book: SearchBook) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(IO) {
                appDb.searchBookDao.insert(book)
            }
            // 仅现代模式下所有书都来自同一 selectedDiscoverSource，才可用它作视频类型提示；
            // 套件模式的书来自各自 widget target 源，此处会残留上次现代会话的源类型，
            // 传 null 交给 isVideoResult 用 book.origin 逐本兜底，避免文本书被误判为视频。
            val sourceTypeHint = if (usingModernDiscovery) {
                selectedDiscoverSourcePart?.bookSourceType ?: selectedDiscoverSource?.bookSourceType
            } else {
                null
            }
            val isVideo = withContext(IO) {
                SearchBookOpenHelper.isVideoResult(book, sourceTypeHint)
            }
            // 两次 IO 挂起后 Fragment 可能已 detach，用可空 context 兜底避免 requireContext() 崩溃。
            val ctx = context ?: return@launch
            SearchBookOpenHelper.open(ctx, book, isVideo)
        }
    }

    private fun handleDiscoverToggleTag(index: Int, item: DiscoverTagItem) {
        val source = selectedDiscoverSource ?: return
        val key = item.kind.title
        if (key.isBlank()) return
        val chars = item.kind.chars?.filterNotNull() ?: return
        if (chars.isEmpty()) return
        val infoMap = getDiscoverInfoMap(source.bookSourceUrl)
        val current = infoMap[key] ?: item.kind.default ?: chars.first()
        val currentIndex = chars.indexOf(current).takeIf { it >= 0 } ?: 0
        val next = chars.getOrNull(currentIndex + 1) ?: chars.first()
        infoMap[key] = next
        val updated = item.copy(text = resolveDiscoverToggleText(item.kind, next).limitDiscoverText(8))
        if (index in discoverTagItems.indices) {
            discoverTagItems[index] = updated
            binding.topBar.tagsBar.submitItems(
                discoverTagItems.map { RoundedTagBarView.Item(it.text, if (it.isButton) 0.9f else 1f) },
                selectedDiscoverTagIndex
            )
        }
        handleDiscoverButtonTag(updated)
    }

    private fun resolveDiscoverToggleText(kind: ExploreKind, value: String): String {
        val label = resolveDiscoverTagText(kind)
        return when {
            label.isBlank() -> value
            value.isBlank() -> label
            else -> "$value$label"
        }
    }

    private fun normalizeDiscoverActionScript(action: String): String {
        var value = action.trim()
        if (value.startsWith("@js:", ignoreCase = true)) {
            return value.substring(4).trim()
        }
        if (value.startsWith("<js>", ignoreCase = true) && value.endsWith("</js>", ignoreCase = true)) {
            return value.substring(4, value.length - 5).trim()
        }
        value = when {
            value.startsWith("{\\{") && value.endsWith("}}") -> value.substring(3, value.length - 2)
            value.startsWith("{{") && value.endsWith("}}") -> value.substring(2, value.length - 2)
            value.startsWith("{") && value.endsWith("}") && isDiscoverExecutableObjectScript(value) ->
                value.substring(1, value.length - 1)
            else -> value
        }
        return value.trim()
    }

    private fun isDiscoverExecutableObjectScript(value: String): Boolean {
        return value.contains("java.", ignoreCase = true) ||
            value.contains("source.", ignoreCase = true) ||
            value.contains("infoMap", ignoreCase = true)
    }

    fun compressExplore() {
        if (usingModernDiscovery) {
            if (binding.composeDiscoverBooks.isVisible) {
                composeDiscoverScrollToTopSignal.intValue++
                return
            }
            if (binding.rvDiscoverBooks.canScrollVertically(-1)) {
                if (AppConfig.isEInkMode) {
                    binding.rvDiscoverBooks.scrollToPosition(0)
                } else {
                    binding.rvDiscoverBooks.smoothScrollToPosition(0)
                }
            }
            return
        }
        if (usingSuiteDiscovery) {
            composeSuiteScrollToTopSignal.intValue++
            return
        }
        if (!adapter.compressExplore()) {
            if (AppConfig.isEInkMode) {
                binding.rvFind.scrollToPosition(0)
            } else {
                binding.rvFind.smoothScrollToPosition(0)
            }
        }
    }

    private companion object {
        private const val DISCOVER_DIALOG_WIDTH_RATIO = 0.90f
        private const val DISCOVER_DIALOG_HEIGHT_RATIO = 0.72f
        private const val MAX_SUITE_SELECTOR_SOURCES = 80
        private const val MAX_SUITE_SELECTOR_TARGETS = 160
        private const val SUITE_WIDGET_LOAD_PARALLELISM = 4
        private const val SUITE_TARGET_LOAD_PARALLELISM = 4
        private const val SUITE_RANDOM_BATCH_PARALLELISM = 3
        private const val SUITE_COVER_PRELOAD_PARALLELISM = 4
        private const val RANDOM_SUITE_BOOK_COUNT = 6
        private const val RANDOM_SUITE_PREFETCH_COUNT = 18
        private const val RANDOM_SUITE_COVER_PREFETCH_COUNT = 18
        private const val RANDOM_SUITE_MAX_PAGE = 5
        private const val RANDOM_SUITE_MAX_PREFETCH_ATTEMPTS = 12
        private const val HORIZONTAL_SUITE_VISIBLE_COVER_COUNT = 3
        private const val HORIZONTAL_SUITE_PAGE_BOOK_LIMIT = 18
        private const val HORIZONTAL_SUITE_MAX_BOOKS = 72
        private const val RANKED_SUITE_TARGET_LIMIT = 9
        private const val RANKED_SUITE_VISIBLE_COVER_COUNT = 12
        private const val WATERFALL_SUITE_BOOK_COUNT = 24
        private const val WATERFALL_SUITE_VISIBLE_COVER_COUNT = 8
        private const val SUITE_COVER_THUMB_WIDTH = 240
        private const val SUITE_COVER_THUMB_HEIGHT = 320
        private const val SUITE_SINGLE_COVER_PRELOAD_TIMEOUT_MS = 1000L
        private const val SUITE_VISIBLE_COVER_PRELOAD_TIMEOUT_MS = 1800L
    }

}

private data class SuiteRandomDeck(
    val signature: String,
    val mutex: Mutex = Mutex(),
    val queue: ArrayDeque<SearchBook> = ArrayDeque(),
    val seenKeys: LinkedHashSet<String> = linkedSetOf(),
    val nextPageByTarget: MutableMap<String, Int> = linkedMapOf(),
    var targetIndex: Int = 0,
    // Main 线程读、IO 线程写，加 @Volatile 保证可见性，避免重复预取。
    @Volatile var prefetching: Boolean = false
)

private data class SuitePreparedBatch(
    val signature: String,
    val books: List<SearchBook>
)

private data class SuiteDeckPageRequest(
    val tagUrl: String,
    val page: Int,
    val source: BookSource,
    val seed: Int
)

private data class SuiteHorizontalPagingState(
    val signature: String,
    // nextPage/loading/exhausted 会被 Main(loadMore) 与 IO(首页加载) 访问，加 @Volatile 保证可见性。
    @Volatile var nextPage: Int = 2,
    @Volatile var loading: Boolean = false,
    @Volatile var exhausted: Boolean = false
)

private data class SuiteRankedPagingState(
    val signature: String,
    // nextPage/loading/exhausted 会被 Main(loadMore) 与 IO(首页加载) 访问，加 @Volatile 保证可见性。
    @Volatile var nextPage: Int = 2,
    @Volatile var loading: Boolean = false,
    @Volatile var exhausted: Boolean = false
)

private data class ModernDiscoverResultCache(
    val sourceUrl: String = "",
    val tagUrl: String = "",
    val books: List<SearchBook> = emptyList(),
    val nextPage: Int = 2,
    val hasMore: Boolean = true,
    val savedAt: Long = 0L
)

private data class DiscoverySuitePageSnapshot(
    val suiteId: String,
    val signature: String,
    val widgetBooks: Map<String, List<SearchBook>>,
    val rankedWidgetBooks: Map<String, Map<String, List<SearchBook>>>,
    val widgetSignatures: Map<String, String>
)

private fun DiscoverySuitePageSnapshot.compactForCache(): DiscoverySuitePageSnapshot {
    val incompleteWidgetIds = hashSetOf<String>()
    val compactWidgetBooks = linkedMapOf<String, List<SearchBook>>()
    widgetBooks.forEach { (widgetId, books) ->
        val compactBooks = books.mapNotNull(DiscoveryCachePolicy::compact)
        if (compactBooks.size != books.size) incompleteWidgetIds += widgetId
        if (compactBooks.isNotEmpty()) compactWidgetBooks[widgetId] = compactBooks
    }
    val compactRankedWidgetBooks = linkedMapOf<String, Map<String, List<SearchBook>>>()
    rankedWidgetBooks.forEach { (widgetId, rankedBooks) ->
        val compactRankedBooks = linkedMapOf<String, List<SearchBook>>()
        rankedBooks.forEach { (rank, books) ->
            val compactBooks = books.mapNotNull(DiscoveryCachePolicy::compact)
            if (compactBooks.size != books.size) incompleteWidgetIds += widgetId
            if (compactBooks.isNotEmpty()) compactRankedBooks[rank] = compactBooks
        }
        if (compactRankedBooks.isNotEmpty()) {
            compactRankedWidgetBooks[widgetId] = compactRankedBooks
        }
    }
    return copy(
        widgetBooks = compactWidgetBooks,
        rankedWidgetBooks = compactRankedWidgetBooks,
        widgetSignatures = widgetSignatures.filterKeys { it !in incompleteWidgetIds }
    )
}

private fun DiscoverySuitePageSnapshot.hasBooks(): Boolean {
    return widgetBooks.isNotEmpty() || rankedWidgetBooks.isNotEmpty()
}

private object DiscoverySuitePageSnapshotStore {
    private const val MAX_SNAPSHOTS = 2
    private val snapshots = object : LinkedHashMap<String, DiscoverySuitePageSnapshot>(
        MAX_SNAPSHOTS,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, DiscoverySuitePageSnapshot>
        ): Boolean {
            return size > MAX_SNAPSHOTS
        }
    }

    @Synchronized
    fun get(suiteId: String, signature: String): DiscoverySuitePageSnapshot? {
        return snapshots[suiteId]?.takeIf { it.signature == signature }
    }

    @Synchronized
    fun put(snapshot: DiscoverySuitePageSnapshot) {
        if (snapshot.suiteId.isBlank()) return
        snapshots[snapshot.suiteId] = snapshot
    }

}

private object DiscoveryCacheWriteScope {
    private val scope = CoroutineScope(SupervisorJob() + IO)
    private var suiteSaveJob: Job? = null
    private var suiteSaveVersion = 0L

    @Synchronized
    fun launchLatest(block: suspend CoroutineScope.(Long) -> Unit) {
        val saveVersion = ++suiteSaveVersion
        suiteSaveJob?.cancel()
        suiteSaveJob = scope.launch { block(saveVersion) }
    }

    @Synchronized
    fun isLatest(saveVersion: Long): Boolean = suiteSaveVersion == saveVersion
}

private const val DISCOVERY_SUITE_SNAPSHOT_RANDOM_LIMIT = 36
private const val DISCOVERY_SUITE_SNAPSHOT_HORIZONTAL_LIMIT = 72
private const val DISCOVERY_SUITE_SNAPSHOT_WATERFALL_LIMIT = 24
private const val DISCOVERY_SUITE_SNAPSHOT_RANKED_TOTAL_LIMIT = 72
private const val RANKED_SUITE_SNAPSHOT_BOOK_LIMIT = 24
private const val DISCOVERY_SUITE_SNAPSHOT_WIDGET_LIMIT = 20
private const val DISCOVERY_MODERN_CACHE_BOOK_LIMIT = 80
private const val DISCOVERY_CACHE_TTL_MS = 72L * 60L * 60L * 1000L
private const val DISCOVERY_CLASSIC_FLOW_COALESCE_DELAY_MS = 120L
private const val DISCOVERY_MODERN_CACHE_PREFIX = "discovery_modern_result_"
private const val DISCOVERY_SUITE_CACHE_PREFIX = "discovery_suite_snapshot_"

private fun String.limitDiscoverText(max: Int): String {
    return if (length <= max) this else "${take(max.coerceAtLeast(2) - 1)}..."
}

private fun DiscoverySuiteWidget.validRandomTargets(): List<DiscoverySuiteWidgetTarget> {
    return targets.filter { it.sourceUrl.isNotBlank() && it.tagUrl.isNotBlank() }
}

private fun DiscoverySuite.cacheSignature(): String {
    return widgets.joinToString(separator = "\u001D") { widget ->
        "${widget.order}\u001C${widget.cacheSignature()}"
    }
}

private fun DiscoverySuiteWidget.cacheSignature(): String {
    return listOf(
        id,
        type,
        displayLimit.toString(),
        validRandomTargets().joinToString(separator = "\u001C") { it.deckKey() }
    ).joinToString(separator = "\u001D")
}

private fun DiscoverySuiteWidget.snapshotBookLimit(): Int {
    return when (type) {
        DiscoverySuiteWidgetType.HorizontalBooks.value -> DISCOVERY_SUITE_SNAPSHOT_HORIZONTAL_LIMIT
        DiscoverySuiteWidgetType.WaterfallBooks.value -> DISCOVERY_SUITE_SNAPSHOT_WATERFALL_LIMIT
        DiscoverySuiteWidgetType.RankedList.value -> DISCOVERY_SUITE_SNAPSHOT_RANKED_TOTAL_LIMIT
        else -> DISCOVERY_SUITE_SNAPSHOT_RANDOM_LIMIT
    }
}

private fun DiscoverySuiteWidget.deckSignature(): String {
    return validRandomTargets().joinToString("|") { it.deckKey() } + "|$displayLimit|$type"
}

private fun DiscoverySuiteWidget.horizontalPagingSignature(): String {
    return validRandomTargets().firstOrNull()?.deckKey().orEmpty() + "|$type"
}

private fun DiscoverySuiteWidget.rankedPagingKey(target: DiscoverySuiteWidgetTarget): String {
    return "$id\n${target.deckKey()}"
}

private fun DiscoverySuiteWidget.rankedPagingSignature(target: DiscoverySuiteWidgetTarget): String {
    return "${target.deckKey()}|$type|$displayLimit"
}

private fun DiscoverySuiteWidgetTarget.deckKey(): String {
    return "$sourceUrl\n$tagUrl"
}

private fun DiscoverySuiteWidget.isSuiteButtonOnlyWidget(): Boolean {
    return type == DiscoverySuiteWidgetType.TagBar.value ||
        type == DiscoverySuiteWidgetType.RankButtons.value
}

private fun SearchBook.suiteDeckKey(): String {
    return when {
        bookUrl.isNotBlank() -> "$origin|$bookUrl"
        author.isNotBlank() -> "$origin|$name|$author"
        else -> "$origin|$name"
    }
}
