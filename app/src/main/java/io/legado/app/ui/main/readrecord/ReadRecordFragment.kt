package io.legado.app.ui.main.readrecord

import android.app.DatePickerDialog
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.EventBus
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ActivityReadRecordBinding
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.TopBarConfig
import io.legado.app.lib.theme.UiCorner
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.widget.compose.showComposeChoiceListDialog
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.lib.theme.secondaryTextColor
import io.legado.app.lib.theme.themeCardColorOrDefault
import io.legado.app.ui.about.ReadHeatmapCell
import io.legado.app.ui.about.ReadRecordCoverRow
import io.legado.app.ui.about.ReadRecordCoverUi
import io.legado.app.ui.about.ReadRecordComponentConfigDialog
import io.legado.app.ui.about.ReadRecordComponentType
import io.legado.app.ui.about.ReadRecordComponents
import io.legado.app.ui.about.ReadRecordGoalConfig
import io.legado.app.ui.about.ReadRecordGoalCardContent
import io.legado.app.ui.about.ReadRecordGoalUi
import io.legado.app.ui.about.ReadRecordRankDialog
import io.legado.app.ui.about.ReadRecordRankItem
import io.legado.app.ui.about.ReadRecordWidgetStore
import io.legado.app.ui.about.ReadRecentVisualItem
import io.legado.app.ui.about.ReadRecordDailyList
import io.legado.app.ui.about.ReadRecordDayUi
import io.legado.app.ui.about.ReadRecordOverviewCard
import io.legado.app.ui.about.ReadRecordOverviewUi
import io.legado.app.ui.about.ReadRecordRankList
import io.legado.app.ui.about.ReadRecordRankUi
import io.legado.app.ui.about.ReadRecordRecentBookUi
import io.legado.app.ui.about.ReadRecordRecentBooksList
import io.legado.app.ui.about.openReadRecordBook
import io.legado.app.ui.about.showReadRecordBookActionDialog
import io.legado.app.ui.about.showReadRecordGoalDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.image.ImageCropContract
import io.legado.app.ui.main.MainFragmentInterface
import io.legado.app.ui.widget.RoundedTagBarView
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.ImageCropHelper
import io.legado.app.utils.applyMainBottomBarPadding
import io.legado.app.utils.dpToPx
import io.legado.app.utils.observeEvent
import io.legado.app.utils.registerForActivityResult
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

class ReadRecordFragment() : BaseFragment(R.layout.activity_read_record), MainFragmentInterface {

    constructor(position: Int) : this() {
        arguments = Bundle().apply {
            putInt("position", position)
        }
    }

    override val position: Int? get() = arguments?.getInt("position")

    private val binding by viewBinding(ActivityReadRecordBinding::bind)
    private val headlineFormatter by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.read_record_date_pattern), Locale.getDefault())
    }
    private val fullDayFormatter by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.read_record_date_pattern), Locale.getDefault())
    }
    private val monthFormatter by lazy {
        DateTimeFormatter.ofPattern(getString(R.string.read_record_month_pattern), Locale.getDefault())
    }
    private val lastOpenFormatter by lazy {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }

    private var currentHeatmapCells: List<ReadHeatmapCell> = emptyList()
    private var selectedDate: LocalDate = LocalDate.now()
    private var loadJob: Job? = null
    private var loadedDate: LocalDate? = null
    private var lastLoadTime = 0L
    private var lastRecentReadTime = 0L
    private var componentItems = ReadRecordComponents.load()
    private var currentRankItems: List<ReadRecordRankItem> = emptyList()
    private var currentGoalConfig: ReadRecordGoalConfig = ReadRecordWidgetStore.loadGoalConfig()
    private var currentTodayTime: Long = 0L
    private var currentTotalTime: Long = 0L
    private var currentReadBookCount: Int = 0
    private var currentDashboard: ReadRecordDashboard? = null
    private val overviewUiState = mutableStateOf<ReadRecordOverviewUi?>(null)
    private val recentBooksUiState = mutableStateOf<List<ReadRecordRecentBookUi>>(emptyList())
    private val dailyRecordsUiState = mutableStateOf<List<ReadRecordDayUi>>(emptyList())
    private val rankUiState = mutableStateOf<List<ReadRecordRankUi>>(emptyList())
    private val recentCoversUiState = mutableStateOf<List<ReadRecordCoverUi>>(emptyList())
    private val goalUiState = mutableStateOf<ReadRecordGoalUi?>(null)
    private var currentRecentBooks: List<RecentReadBook> = emptyList()
    private var currentDailyTimeline: List<DailyReadSummary> = emptyList()
    private var currentVisibleRankItems: List<ReadRecordRankItem> = emptyList()
    private var currentRecentCovers: List<ReadRecentVisualItem> = emptyList()
    // 顶栏第二行（年份入口 + 月份 chip）与内容区日期 chip 行的渲染态；
    // 唯一写入点 = writeFilterState（renderDateTopBar 与首帧预写入共用），避免双源
    private val filterYearText = mutableStateOf("")
    private val filterMonthItems = mutableStateOf<List<RoundedTagBarView.Item>>(emptyList())
    private val filterMonthSelected = mutableStateOf(RecyclerView.NO_POSITION)
    private val filterDayItems = mutableStateOf<List<RoundedTagBarView.Item>>(emptyList())
    private val filterDaySelected = mutableStateOf(RecyclerView.NO_POSITION)
    private var pendingAvatarUpdate: ((String) -> Unit)? = null
    private var pendingAvatarCropRequest: ImageCropHelper.Request? = null
    private val selectGoalAvatar = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            startAvatarCrop(uri)
        } ?: run {
            pendingAvatarUpdate = null
        }
    }
    private val cropGoalAvatar = registerForActivityResult(ImageCropContract()) { result ->
        val request = pendingAvatarCropRequest ?: return@registerForActivityResult
        pendingAvatarCropRequest = null
        if (result == null) {
            pendingAvatarUpdate = null
            return@registerForActivityResult
        }
        if (java.io.File(result.path).exists()) {
            pendingAvatarUpdate?.invoke(result.path)
        } else {
            toastOnUi(getString(R.string.image_crop_failed, getString(R.string.unknown)))
        }
        pendingAvatarUpdate = null
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        // read-record-header-unify AD-01：本页已改造为独立子页（ReadRecordStatsActivity 承载），
        // 顶栏统一为子页单源 GlassTopAppBar——运行时替换共享布局的 title_bar / top_bar 节点
        // （activity_read_record.xml 与 ReadRecordActivity 共用，故不改 XML 文件）
        installComposeTopBar()
        binding.scrollView.applyMainBottomBarPadding(withInitialPadding = true)
        installContentDayFilter()
        // 首帧即预写入筛选渲染态：否则第二行 items 为空、顶栏从 56dp 跳到完整高度，内容区可见跳动
        writeFilterState(selectedDate)
        binding.tvRecordDate.setOnClickListener {
            showDatePicker()
        }
        binding.panelOverview.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.panelOverview.setContent {
            LegadoComposeTheme {
                overviewUiState.value?.let { ui ->
                    ReadRecordOverviewCard(ui = ui)
                }
            }
        }
        binding.llRecentBooks.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.llRecentBooks.setContent {
            LegadoComposeTheme {
                ReadRecordRecentBooksList(
                    items = recentBooksUiState.value,
                    onClick = { index -> openRecentBook(index) },
                    onLongClick = { index -> showRecentBookActions(index) }
                )
            }
        }
        binding.llDailyRecords.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.llDailyRecords.setContent {
            LegadoComposeTheme {
                ReadRecordDailyList(
                    items = dailyRecordsUiState.value,
                    onLongClick = { index -> confirmDeleteDailyRecord(index) }
                )
            }
        }
        binding.llReadRank.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.llReadRank.setContent {
            LegadoComposeTheme {
                ReadRecordRankList(
                    items = rankUiState.value,
                    onClick = { index -> openRankBook(index) },
                    onLongClick = { index -> showRankBookActions(index) }
                )
            }
        }
        binding.ivRankMore.setOnClickListener {
            showDialogFragment(
                ReadRecordRankDialog.create(currentRankItems, ::formatDuring) { item ->
                    viewLifecycleOwner.lifecycleScope.launch {
                        withContext(IO) {
                            appDb.readRecordDao.deleteByName(item.displayName)
                        }
                        loadData(force = true)
                    }
                }
            )
        }
        binding.ivGoalEdit.setOnClickListener {
            requireContext().showReadRecordGoalDialog(
                initial = currentGoalConfig,
                onPickAvatarRequest = { update ->
                    pendingAvatarUpdate = update
                    selectGoalAvatar.launch {
                        mode = HandleFileContract.IMAGE
                        title = getString(R.string.read_record_goal_avatar)
                    }
                }
            ) { config ->
                currentGoalConfig = config
                ReadRecordWidgetStore.saveGoalConfig(config)
                renderGoalCard(currentTodayTime, currentTotalTime, currentReadBookCount)
            }
        }
        binding.rvRecentCovers.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.rvRecentCovers.setContent {
            LegadoComposeTheme {
                ReadRecordCoverRow(
                    items = recentCoversUiState.value,
                    onClick = { index -> openRecentCover(index) },
                    onLongClick = { index -> showRecentCoverActions(index) }
                )
            }
        }
        binding.goalCardContent.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        binding.goalCardContent.setContent {
            LegadoComposeTheme {
                goalUiState.value?.let { ui ->
                    ReadRecordGoalCardContent(ui = ui)
                }
            }
        }
        applyComponentLayout()
        preloadData()
    }

    /**
     * 顶栏单源安装（AD-01）：以 ComposeView + GlassTopAppBar 运行时替换共享布局的旧顶栏节点。
     *
     * 第一行：左侧返回箭头 + 标题「阅读记录」+ 右侧组件配置动作（一级直出）；
     * 第二行（secondRow）：年份入口 + 月份 chip，两段各显式 38dp；
     * 插入索引用 coerceAtMost(childCount) 兜底（容器替换后 addView 索引不得裸写）。
     */
    private fun installComposeTopBar() {
        val container = binding.root as? ViewGroup ?: return
        (binding.titleBar.parent as? ViewGroup)?.removeView(binding.titleBar)
        (binding.topBar.parent as? ViewGroup)?.removeView(binding.topBar)
        val topBarView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    GlassTopAppBar(
                        title = getString(R.string.read_record),
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { requireActivity().finish() },
                        barHeight = 56.dp,
                        actions = {
                            TopBarActionRow(
                                listOf(
                                    MenuAction(
                                        iconRes = R.drawable.ic_more_vert,
                                        title = getString(R.string.read_record_customize_components),
                                        alwaysShow = true,
                                        onClick = { showComponentConfigDialog() }
                                    )
                                )
                            )
                        },
                        secondRow = {
                            Column(
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // 年份入口：单项 chip 仅作入口（无选中底；传 NO_POSITION 避免字色走选中态色）
                                TagChipRow(
                                    items = listOf(RoundedTagBarView.Item(filterYearText.value)),
                                    selectedIndex = RecyclerView.NO_POSITION,
                                    selectedBackgroundVisible = false,
                                    onTagClick = { showYearSelector() }
                                )
                                TagChipRow(
                                    items = filterMonthItems.value,
                                    selectedIndex = filterMonthSelected.value,
                                    selectedBackgroundVisible = true,
                                    onTagClick = { index -> selectMonth(index + 1) }
                                )
                            }
                        }
                    )
                }
            }
        }
        container.addView(topBarView, 0.coerceAtMost(container.childCount))
    }

    /**
     * 内容区顶部日期 chip 行（AD-05）：由顶栏下移而来，随内容滚动、不占固定屏幅。
     * 同时完成旧头部清理：移除组件配置图标（入口已迁顶栏动作）、大字日期降为 20sp。
     */
    private fun installContentDayFilter() {
        val container = binding.llReadRecordContent as? ViewGroup ?: return
        val dayFilterView = ComposeView(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                LegadoComposeTheme {
                    TagChipRow(
                        items = filterDayItems.value,
                        selectedIndex = filterDaySelected.value,
                        selectedBackgroundVisible = true,
                        onTagClick = { index -> selectDay(index + 1) }
                    )
                }
            }
        }
        container.addView(dayFilterView, 0.coerceAtMost(container.childCount))
        (binding.ivComponentMenu.parent as? ViewGroup)?.removeView(binding.ivComponentMenu)
        binding.tvRecordDate.textSize = 20f
    }

    /**
     * 筛选 chip 行（复用既有 RoundedTagBarView，零视觉偏差；显式 38dp 防高度塌陷）。
     *
     * update 执行顺序固定（AD-03）：①先按顶栏包/主题签名强刷配色；②再按快照判断数据是否变化，
     * 未变化则跳过 submitItems（其内部会对选中项 scrollBy 自动居中，会拉回用户手动滚动位置）。
     */
    @Composable
    private fun TagChipRow(
        items: List<RoundedTagBarView.Item>,
        selectedIndex: Int,
        selectedBackgroundVisible: Boolean,
        onTagClick: (Int) -> Unit
    ) {
        val submitted = remember { mutableStateOf<Pair<List<RoundedTagBarView.Item>, Int>?>(null) }
        val styleSignature = remember { mutableStateOf<String?>(null) }
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .height(38.dp),
            factory = { context ->
                RoundedTagBarView(context).apply {
                    setDisplayMode(RoundedTagBarView.DisplayMode.CHIP)
                    setSelectedBackgroundVisible(selectedBackgroundVisible)
                    setOnTagClickListener { index -> onTagClick(index) }
                }
            },
            update = { view ->
                val signature = TopBarConfig.currentSignature(AppConfig.isNightTheme)
                if (styleSignature.value != signature) {
                    styleSignature.value = signature
                    // 强刷：applyTopBarStyle 同签名会早退，且宿主 Activity 不因日夜/顶栏包变更而重建
                    view.applyTopBarStyle(force = true)
                }
                val snapshot = items to selectedIndex
                if (submitted.value != snapshot) {
                    submitted.value = snapshot
                    view.submitItems(items, selectedIndex)
                }
            }
        )
    }

    /** 写入顶栏第二行与内容区日期行的渲染态（本页筛选态唯一写入点） */
    private fun writeFilterState(date: LocalDate) {
        filterYearText.value = getString(R.string.read_record_year_value, date.year)
        filterMonthItems.value = (1..12).map {
            RoundedTagBarView.Item(getString(R.string.read_record_month_value, it))
        }
        filterMonthSelected.value = date.monthValue - 1
        val month = YearMonth.from(date)
        filterDayItems.value = (1..month.lengthOfMonth()).map {
            RoundedTagBarView.Item(it.toString())
        }
        filterDaySelected.value = date.dayOfMonth - 1
    }

    /** 内容区日期 chip 点击：按当月天数钳制后重载（与原 tagsBar 点击行为一致） */
    private fun selectDay(dayValue: Int) {
        val month = YearMonth.from(selectedDate)
        val date = month.atDay(dayValue.coerceIn(1, month.lengthOfMonth()))
        if (selectedDate != date) {
            selectedDate = date
            loadData(force = true)
        }
    }

    override fun observeLiveBus() {
        observeEvent<Boolean>(EventBus.TOP_BAR_CHANGED) {
            if (it == AppConfig.isNightTheme) {
                // 顶栏取色与 chip 配色由 GlassTopAppBar / AndroidView.update 的签名强刷承担，
                // 此处只需重写筛选渲染态（不再依赖已移除的 MainTopBarView.refreshStyle）
                currentDashboard?.let(::renderDateTopBar)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewLifecycleOwner.lifecycleScope.launch {
            val latestRecentReadTime = withContext(IO) {
                appDb.readRecentBookDao.latestReadTime() ?: 0L
            }
            loadData(
                force = loadedDate == null ||
                    loadedDate != selectedDate ||
                    isDataStale() ||
                    latestRecentReadTime != lastRecentReadTime
            )
        }
    }

    private fun preloadData() {
        loadData(force = true)
    }

    private fun isDataStale(): Boolean {
        return System.currentTimeMillis() - lastLoadTime > DATA_STALE_MS
    }

    private fun loadData(force: Boolean = false) {
        if (!force && loadedDate == selectedDate) return
        if (loadJob?.isActive == true) {
            if (!force) return
            loadJob?.cancel()
        }
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val loadDate = selectedDate
            val dashboard = withContext(IO) {
                buildDashboard(loadDate)
            }
            if (loadDate != selectedDate) return@launch
            loadedDate = loadDate
            lastLoadTime = System.currentTimeMillis()
            renderDashboard(dashboard)
        }
    }

    private fun startAvatarCrop(uri: Uri) {
        val request = ImageCropHelper.buildRequest(
            context = requireContext(),
            sourceUri = uri,
            requestCode = requestGoalAvatar,
            aspectWidth = 1,
            aspectHeight = 1,
            dirName = "readRecordGoalAvatar",
            prefix = "avatar",
            targetWidth = 512
        )
        pendingAvatarCropRequest = request
        cropGoalAvatar.launch(request.params)
    }

    private fun buildDashboard(today: LocalDate): ReadRecordDashboard {
        val month = YearMonth.from(today)
        val readRecordMap = appDb.readRecordDao.allShow.associateBy { it.bookName }
        val totalTime = appDb.readRecordDao.allTime
        val dailyStats = appDb.readRecordDailyDao.allDesc.mapNotNull { record ->
            runCatching {
                DailyReadSummary(
                    date = LocalDate.parse(record.date),
                    readTime = record.readTime
                )
            }.getOrNull()
        }.sortedByDescending { it.date }
        val dailyMap = dailyStats.associate { it.date to it.readTime }
        val recentBooks = appDb.readRecentBookDao.recentBooks(6)
            .map { book ->
                RecentReadBook(
                    book = book,
                    totalReadTime = readRecordMap[book.name]?.readTime ?: 0L
                )
            }
        val heatmapStart = today.minusDays(111)
        val heatmapCells = (0L..111L).map { offset ->
            val date = heatmapStart.plusDays(offset)
            ReadHeatmapCell(date, dailyMap[date] ?: 0L)
        }
        return ReadRecordDashboard(
            today = today,
            todayTime = dailyMap[today] ?: 0L,
            monthTime = dailyStats.filter { YearMonth.from(it.date) == month }.sumOf { it.readTime },
            totalTime = totalTime,
            activeDays = dailyStats.count { it.readTime > 0L },
            heatmapCells = heatmapCells,
            recentBooks = recentBooks,
            dailyTimeline = dailyStats.take(14),
            hasDailyStats = dailyStats.isNotEmpty(),
            recentCoverItems = ReadRecordWidgetStore.loadRecentVisualItems(5),
            rankItems = ReadRecordWidgetStore.buildRankItems(),
            goalConfig = ReadRecordWidgetStore.loadGoalConfig(),
            readBookCount = appDb.readRecordDao.allShow.size,
            latestRecentReadTime = appDb.readRecentBookDao.latestReadTime() ?: 0L
        )
    }

    private fun renderDashboard(dashboard: ReadRecordDashboard) {
        currentDashboard = dashboard
        currentHeatmapCells = dashboard.heatmapCells
        binding.tvRecordDate.text = dashboard.today.format(headlineFormatter)
        binding.tvRecordDateHint.text = getString(
            if (dashboard.hasDailyStats) {
                R.string.read_record_stats_ready
            } else {
                R.string.read_record_stats_waiting
            }
        )
        overviewUiState.value = dashboard.toOverviewUi()

        val startDate = dashboard.heatmapCells.firstOrNull()?.date ?: dashboard.today
        val centerDate = dashboard.heatmapCells.getOrNull(dashboard.heatmapCells.size / 2)?.date
            ?: dashboard.today
        val endDate = dashboard.heatmapCells.lastOrNull()?.date ?: dashboard.today
        binding.tvHeatmapMonthStart.text = startDate.format(monthFormatter)
        binding.tvHeatmapMonthCenter.text = centerDate.format(monthFormatter)
        binding.tvHeatmapMonthEnd.text = endDate.format(monthFormatter)
        binding.tvHeatmapEmpty.isVisible = !dashboard.hasDailyStats
        currentTodayTime = dashboard.todayTime
        currentTotalTime = dashboard.totalTime
        currentReadBookCount = dashboard.readBookCount
        lastRecentReadTime = dashboard.latestRecentReadTime

        renderDateTopBar(dashboard)
        renderRecentBooks(dashboard.recentBooks)
        renderDailyTimeline(dashboard.dailyTimeline, dashboard.hasDailyStats)
        renderRecentCovers(dashboard.recentCoverItems)
        renderReadRank(dashboard.rankItems.take(5), dashboard.rankItems)
        currentGoalConfig = dashboard.goalConfig
        renderGoalCard(dashboard.todayTime, dashboard.totalTime, dashboard.readBookCount)
        applyPageChrome()
    }

    private fun ReadRecordDashboard.toOverviewUi(): ReadRecordOverviewUi {
        val placeholder = getString(R.string.read_record_placeholder)
        return ReadRecordOverviewUi(
            todayValue = if (hasDailyStats) formatDuring(todayTime) else placeholder,
            todayLabel = if (today == LocalDate.now()) {
                getString(R.string.read_record_today_label)
            } else {
                getString(R.string.read_record_selected_day_label)
            },
            monthValue = if (hasDailyStats) formatDuring(monthTime) else placeholder,
            monthLabel = getString(R.string.read_record_month_label),
            totalValue = formatDuring(totalTime),
            totalLabel = getString(R.string.read_record_total_label),
            activeDaysValue = getString(R.string.read_record_active_days_value, activeDays),
            activeDaysLabel = getString(R.string.read_record_active_days_label)
        )
    }

    /**
     * 刷新筛选区渲染态（本页筛选唯一写入点）。原 regular / 非 regular 双头部形态分叉
     * 已随顶栏单源（AD-01）取消——两种顶栏包样式下页面头部结构完全一致。
     */
    private fun renderDateTopBar(dashboard: ReadRecordDashboard) {
        writeFilterState(dashboard.today)
    }

    private fun selectMonth(monthValue: Int) {
        val targetMonth = YearMonth.of(selectedDate.year, monthValue)
        selectedDate = targetMonth.atDay(selectedDate.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
        loadData(force = true)
    }

    private fun showYearSelector() {
        val years = ((selectedDate.year - 5)..(selectedDate.year + 1)).toList()
        showComposeChoiceListDialog(
            title = getString(R.string.read_record_select_year),
            labels = years.map { getString(R.string.read_record_year_value, it) }
        ) { index ->
            val targetYear = years[index]
            val targetMonth = YearMonth.of(targetYear, selectedDate.monthValue)
            selectedDate = targetMonth.atDay(selectedDate.dayOfMonth.coerceAtMost(targetMonth.lengthOfMonth()))
            loadData(force = true)
        }
    }

    private fun showComponentConfigDialog() {
        showDialogFragment(
            ReadRecordComponentConfigDialog.create(componentItems) { items ->
                componentItems = items.toMutableList()
                ReadRecordComponents.save(componentItems)
                applyComponentLayout()
                applyPageChrome()
            }
        )
    }

    private fun applyComponentLayout() {
        if (componentItems.none { it.enabled }) {
            componentItems.firstOrNull()?.enabled = true
            ReadRecordComponents.save(componentItems)
        }
        val parent = binding.llReadRecordComponents
        val componentViews = linkedMapOf(
            ReadRecordComponentType.OVERVIEW to binding.panelOverview,
            ReadRecordComponentType.HEATMAP to binding.panelHeatmap,
            ReadRecordComponentType.RECENT_BOOKS to binding.panelRecentBooks,
            ReadRecordComponentType.DAILY_RECORDS to binding.panelDailyRecords,
            ReadRecordComponentType.RECENT_COVERS to binding.panelRecentCovers,
            ReadRecordComponentType.READ_RANK to binding.panelReadRank,
            ReadRecordComponentType.GOAL_CARD to binding.panelGoalCard
        )
        componentViews.values.forEach { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.isVisible = false
        }
        parent.removeAllViews()
        componentItems.filter { it.enabled }.forEachIndexed { index, item ->
            componentViews[item.type]?.let { view ->
                val lp = (view.layoutParams as? ViewGroup.MarginLayoutParams)
                    ?: ViewGroup.MarginLayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                lp.topMargin = if (index == 0) 0 else 16.dpToPx()
                view.layoutParams = lp
                view.isVisible = true
                parent.addView(view)
            }
        }
    }

    private fun showDatePicker() {
        val date = selectedDate
        DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                selectedDate = LocalDate.of(year, month + 1, dayOfMonth)
                loadData(force = true)
            },
            date.year,
            date.monthValue - 1,
            date.dayOfMonth
        ).show()
    }

    private fun renderRecentBooks(items: List<RecentReadBook>) {
        currentRecentBooks = items
        recentBooksUiState.value = items.map {
            ReadRecordRecentBookUi(
                name = it.book.name,
                meta = buildRecentBookMeta(it.book),
                readTime = formatDuring(it.totalReadTime)
            )
        }
        binding.tvRecentBooksEmpty.isVisible = items.isEmpty()
    }

    private fun renderDailyTimeline(items: List<DailyReadSummary>, hasDailyStats: Boolean) {
        currentDailyTimeline = items
        dailyRecordsUiState.value = if (hasDailyStats) {
            items.map {
                ReadRecordDayUi(
                    title = it.date.format(fullDayFormatter),
                    subtitle = buildDaySubtitle(it.date),
                    readTime = formatDuring(it.readTime)
                )
            }
        } else {
            emptyList()
        }
        binding.tvDailyRecordsEmpty.isVisible = !hasDailyStats || items.isEmpty()
    }

    private fun openRecentBook(index: Int) {
        currentRecentBooks.getOrNull(index)?.let {
            startActivityForBook(it.book)
        }
    }

    private fun showRecentBookActions(index: Int) {
        val item = currentRecentBooks.getOrNull(index) ?: return
        requireContext().showReadRecordBookActionDialog(item.book.name, item.book, item.book.name) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(IO) {
                    appDb.readRecentBookDao.deleteSameBook(item.book.name, item.book.author)
                    ReadRecordWidgetStore.removeRecentSnapshot(item.book)
                }
                loadData(force = true)
            }
        }
    }

    private fun confirmDeleteDailyRecord(index: Int) {
        val item = currentDailyTimeline.getOrNull(index) ?: return
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = item.date.format(fullDayFormatter),
            positiveText = getString(R.string.yes),
            negativeText = getString(R.string.no),
            dangerPositive = true,
            onPositive = {
                lifecycleScope.launch {
                    withContext(IO) {
                        appDb.readRecordDailyDao.delete(item.date.toString())
                    }
                    loadData(force = true)
                }
            }
        )
    }

    private fun renderRecentCovers(items: List<ReadRecentVisualItem>) {
        currentRecentCovers = items
        recentCoversUiState.value = items.map {
            ReadRecordCoverUi(book = it.book, snapshot = it.snapshot)
        }
        binding.tvRecentCoversEmpty.isVisible = items.isEmpty()
        binding.rvRecentCovers.isVisible = items.isNotEmpty()
    }

    private fun openRecentCover(index: Int) {
        currentRecentCovers.getOrNull(index)?.let {
            requireContext().openReadRecordBook(it.book, it.snapshot.name)
        }
    }

    private fun showRecentCoverActions(index: Int) {
        val item = currentRecentCovers.getOrNull(index) ?: return
        requireContext().showReadRecordBookActionDialog(
            title = item.book?.name ?: item.snapshot.name,
            book = item.book,
            fallbackName = item.snapshot.name
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(IO) {
                    item.book?.let { book ->
                        appDb.readRecentBookDao.deleteSameBook(book.name, book.author)
                        ReadRecordWidgetStore.removeRecentSnapshot(book)
                    } ?: run {
                        appDb.readRecentBookDao.delete(item.snapshot.bookUrl)
                        ReadRecordWidgetStore.removeRecentSnapshot(item.snapshot.bookUrl)
                    }
                }
                loadData(force = true)
            }
        }
    }

    private fun renderReadRank(items: List<ReadRecordRankItem>, allItems: List<ReadRecordRankItem>) {
        currentRankItems = allItems
        currentVisibleRankItems = items
        rankUiState.value = items.mapIndexed { index, item ->
            val name = item.book?.name ?: item.snapshot?.name ?: item.displayName
            val author = item.book?.author ?: item.snapshot?.author ?: item.displayAuthor
            ReadRecordRankUi(
                name = name,
                meta = if (author.isBlank()) {
                    getString(R.string.read_record_rank_number, index + 1)
                } else {
                    "${index + 1}. $author"
                },
                readTime = formatDuring(item.readTime),
                dimmed = item.book == null,
                book = item.book,
                snapshot = item.snapshot
            )
        }
        binding.tvReadRankEmpty.isVisible = items.isEmpty()
        binding.ivRankMore.isVisible = allItems.isNotEmpty()
    }

    private fun openRankBook(index: Int) {
        currentVisibleRankItems.getOrNull(index)?.let {
            requireContext().openReadRecordBook(it.book, it.displayName)
        }
    }

    private fun showRankBookActions(index: Int) {
        val item = currentVisibleRankItems.getOrNull(index) ?: return
        requireContext().showReadRecordBookActionDialog(
            title = item.book?.name ?: item.snapshot?.name ?: item.displayName,
            book = item.book,
            fallbackName = item.displayName
        ) {
            viewLifecycleOwner.lifecycleScope.launch {
                withContext(IO) {
                    appDb.readRecordDao.deleteByName(item.displayName)
                }
                loadData(force = true)
            }
        }
    }

    private fun renderGoalCard(todayTime: Long, totalTime: Long, readBookCount: Int) {
        val todayText = formatDuring(todayTime)
        val totalText = formatDuring(totalTime)
        val goalMs = currentGoalConfig.dailyGoalMinutes * 60L * 1000L
        val percent = if (goalMs <= 0L) 0 else ((todayTime * 100) / goalMs).toInt().coerceIn(0, 100)
        goalUiState.value = ReadRecordGoalUi(
            userName = currentGoalConfig.userName.orEmpty(),
            avatar = currentGoalConfig.avatar,
            todayText = getString(R.string.read_record_goal_today, todayText),
            totalText = getString(R.string.read_record_goal_total, totalText),
            booksText = getString(R.string.read_record_goal_books, readBookCount),
            progressText = getString(
                R.string.read_record_goal_target_progress,
                todayText,
                formatDuring(goalMs)
            ),
            progressPercent = percent
        )
    }

    private fun buildRecentBookMeta(book: Book): String {
        val parts = mutableListOf<String>()
        book.durChapterTitle?.trim()?.takeIf { it.isNotEmpty() }?.let {
            parts += getString(R.string.read_record_current_chapter, it)
        }
        parts += getString(
            R.string.read_record_last_open,
            lastOpenFormatter.format(Date(book.durChapterTime))
        )
        return parts.joinToString(" 路 ")
    }

    private fun buildDaySubtitle(date: LocalDate): String {
        val today = LocalDate.now()
        return when (date) {
            today -> getString(R.string.read_record_today_word)
            today.minusDays(1) -> getString(R.string.read_record_yesterday_word)
            else -> date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        }
    }

    private fun applyPageChrome() {
        val panelSurfaceColor = requireContext().themeCardColorOrDefault()

        binding.panelOverview.background = null
        listOf(
            binding.panelHeatmap,
            binding.panelRecentBooks,
            binding.panelDailyRecords
        ).forEach { panel ->
            panel.background = createSurfaceDrawable(panelSurfaceColor, 14f)
        }
        binding.tvRecordDate.setTextColor(primaryTextColor)
        binding.tvRecordDateHint.setTextColor(secondaryTextColor)
        binding.tvHeatmapSubtitle.setTextColor(secondaryTextColor)
        binding.tvHeatmapEmpty.setTextColor(secondaryTextColor)
        binding.tvRecentBooksEmpty.setTextColor(secondaryTextColor)
        binding.tvDailyRecordsEmpty.setTextColor(secondaryTextColor)
        binding.tvHeatmapMonthStart.setTextColor(secondaryTextColor)
        binding.tvHeatmapMonthCenter.setTextColor(secondaryTextColor)
        binding.tvHeatmapMonthEnd.setTextColor(secondaryTextColor)
        binding.panelRecentCovers.background =
            createSurfaceDrawable(panelSurfaceColor, 14f)
        binding.panelReadRank.background =
            createSurfaceDrawable(panelSurfaceColor, 14f)
        binding.panelGoalCard.background =
            createSurfaceDrawable(panelSurfaceColor, 14f)
        binding.ivRankMore.background = null
        binding.ivGoalEdit.background = null
        binding.ivRankMore.setColorFilter(secondaryTextColor)
        binding.ivGoalEdit.setColorFilter(secondaryTextColor)
        binding.heatmapView.submit(currentHeatmapCells, accentColor, panelSurfaceColor)
    }

    private fun createDivider(): View {
        return View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1.dpToPx()
            ).apply {
                marginStart = 15.dpToPx()
            }
            setBackgroundColor(ColorUtils.adjustAlpha(primaryTextColor, 0.08f))
        }
    }

    private fun createSurfaceDrawable(
        fillColor: Int,
        radiusDp: Float
    ): Drawable {
        return UiCorner.panelInsetStrokeDrawable(
            requireContext(),
            fillColor,
            UiCorner.scaledDp(radiusDp)
        )
    }

    private fun formatDuring(mss: Long): String {
        val days = mss / (1000 * 60 * 60 * 24)
        val hours = mss % (1000 * 60 * 60 * 24) / (1000 * 60 * 60)
        val minutes = mss % (1000 * 60 * 60) / (1000 * 60)
        val seconds = mss % (1000 * 60) / 1000
        val d = if (days > 0) getString(R.string.duration_day, days) else ""
        val h = if (hours > 0) getString(R.string.duration_hour, hours) else ""
        val m = if (minutes > 0) getString(R.string.duration_minute, minutes) else ""
        val s = if (seconds > 0 && days == 0L && hours == 0L) {
            getString(R.string.duration_second, seconds)
        } else {
            ""
        }
        val time = "$d$h$m$s"
        return if (time.isBlank()) getString(R.string.duration_zero) else time
    }

}

private const val DATA_STALE_MS = 60_000L
private const val requestGoalAvatar = 501
internal const val ARG_STANDALONE = "standalone"

private data class ReadRecordDashboard(
    val today: LocalDate,
    val todayTime: Long,
    val monthTime: Long,
    val totalTime: Long,
    val activeDays: Int,
    val heatmapCells: List<ReadHeatmapCell>,
    val recentBooks: List<RecentReadBook>,
    val dailyTimeline: List<DailyReadSummary>,
    val hasDailyStats: Boolean,
    val recentCoverItems: List<ReadRecentVisualItem>,
    val rankItems: List<ReadRecordRankItem>,
    val goalConfig: ReadRecordGoalConfig,
    val readBookCount: Int,
    val latestRecentReadTime: Long
)

private data class RecentReadBook(
    val book: Book,
    val totalReadTime: Long
)

private data class DailyReadSummary(
    val date: LocalDate,
    val readTime: Long
)
