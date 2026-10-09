package io.legado.app.ui.main.explore

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import androidx.activity.addCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.constant.AppLog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.viewbinding.ViewBinding
import io.legado.app.base.attachComposeContent
import io.legado.app.base.composeShell
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.GlassTopAppBar
import io.legado.app.ui.widget.components.TopBarActionRow
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.databinding.ItemFilletCompleteTextBinding
import io.legado.app.databinding.ItemFilletSelectorSingleBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.lib.theme.UiCorner
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.components.MenuAction
import io.legado.app.ui.widget.components.installGlassTopBar
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.components.EmptyStateAction
import io.legado.app.ui.widget.components.EmptyStatePlaceholder
import io.legado.app.ui.widget.compose.AppManagementIconAction
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.AppSemanticColors
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.material3.MaterialTheme
import io.legado.app.ui.theme.bodyTertiary
import io.legado.app.ui.theme.bodySecondary
import io.legado.app.ui.theme.subtitleLarge
import io.legado.app.ui.theme.titleLargeX

class DiscoverySuiteManageActivity : BaseActivity<ViewBinding>() {

    // 原 activity_theme_manage.xml 已退役（CE-a #8）：composeShell 合成壳 + attachComposeContent 单源；
    // 顶栏由 installGlassTopBar 运行时注入改为**页内直接渲染**
    override val binding: ViewBinding by lazy { composeShell(this) }

    private var configState by mutableStateOf(DiscoverySuiteStore.load())
    private var selectedSuiteIdState by mutableStateOf(DiscoverySuiteStore.selectedSuiteId())
    private var sourceTagOptionsState by mutableStateOf<List<DiscoverySuiteSourceTagOptions>>(emptyList())
    private var loadingTagsState by mutableStateOf(false)
    private var loadingSourceTagUrlsState by mutableStateOf<Set<String>>(emptySet())
    private var loadedSourceTagUrlsState by mutableStateOf<Set<String>>(emptySet())
    private var screenModeState by mutableStateOf<DiscoverySuiteManageMode>(DiscoverySuiteManageMode.List)
    // 一键生成（A）：运行态 + 进度，供列表空态切换为进度面板并可取消
    private var starterRunningState by mutableStateOf(false)
    private var starterProgressState by mutableStateOf<StarterSuiteProgress?>(null)
    private var starterJob: Job? = null

    // ui-theme-governance-polish P6：管理族宿主接入背景透明度（1.5 封闭清单成员）
    override fun manageBackgroundAlphaEnabled(): Boolean = true

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        onBackPressedDispatcher.addCallback(this) {
            handleBackNavigation()
        }
        // C1 深链：从发现页胶囊「添加控件 / 编辑控件」直达编辑器（保存后才落盘）。
        // 参数非法由 refreshConfig() → validatedAgainst(config) 回落 List/Detail（spec E13）。
        intent?.getStringExtra(EXTRA_SUITE_ID)
            ?.takeIf { it.isNotBlank() }
            ?.let { suiteId ->
                screenModeState = DiscoverySuiteManageMode.WidgetEditor(
                    suiteId = suiteId,
                    widgetId = intent.getStringExtra(EXTRA_WIDGET_ID)
                )
            }
        initComposeContent()
        refreshConfig()
        updateTitleBar()
        loadSourceOptions()
    }

    // W7.2（Delta 3→1）：顶栏标题/动作随 screenModeState 动态桥接（原 MainTopBarView setTitle+actionsBar
    // 重建链改 Compose 状态驱动）；CE-a #8 后顶栏为**页内直接渲染**（见 initComposeContent）⇒
    // 原「只做状态准备、然后交给 installGlassTopBar 注入」的空 `initTopBar()` 已随之删除。
    private var topBarTitleState by mutableStateOf("")
    private var topBarActionsState by mutableStateOf(listOf<MenuAction>())

    @OptIn(ExperimentalMaterial3Api::class)
    private fun initComposeContent() {
        binding.root.attachComposeContent {
            // G-37：宿主内容根必须处于 LegadoTheme 作用域（原仅顶栏包 LegadoTheme，内容区裸奔 ⇒ 门禁拦下）
            LegadoTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    // ---- 顶栏（原 installGlassTopBar 注入的 GlassTopAppBar：标题/返回/动作逐项不变）----
                    GlassTopAppBar(
                        title = topBarTitleState,
                        navIcon = Icons.AutoMirrored.Filled.ArrowBack,
                        onNavClick = { handleBackNavigation() },
                        actions = { TopBarActionRow(topBarActionsState) }
                    )
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        LegadoComposeTheme {
                            when (val mode = screenModeState) {
                            is DiscoverySuiteManageMode.WidgetEditor -> {
                                val suite = configState.suites.firstOrNull { it.id == mode.suiteId }
                                val widget = suite?.widgets?.firstOrNull { it.id == mode.widgetId }
                                DiscoverySuiteWidgetEditorScreen(
                                    suite = suite,
                                    widget = widget,
                                    sourceOptions = sourceTagOptionsState,
                                    loadingOptions = loadingTagsState,
                                    loadingSourceUrls = loadingSourceTagUrlsState,
                                    loadedSourceUrls = loadedSourceTagUrlsState,
                                    onLoadSourceTags = ::loadSourceTags,
                                    validateTargets = ::widgetTargetsError,
                                    onSave = { title, type, targets ->
                                        saveWidget(mode.suiteId, widget, title, type, targets)
                                    },
                                    onCancel = { closeWidgetEditor(mode.suiteId) }
                                )
                            }
                            is DiscoverySuiteManageMode.Detail -> {
                                val suite = configState.suites.firstOrNull { it.id == mode.suiteId }
                                DiscoverySuiteDetailScreen(
                                    suite = suite,
                                    loadingOptions = loadingTagsState,
                                    sourceCount = sourceTagOptionsState.size,
                                    onOpacityMultiplierChange = ::updateSuiteOpacityMultiplier,
                                    onEditWidget = { targetSuite, targetWidget ->
                                        openWidgetEditor(targetSuite, targetWidget)
                                    },
                                    onDeleteWidget = ::confirmDeleteWidget,
                                    onReorderWidgets = ::reorderWidgets
                                )
                            }
                            DiscoverySuiteManageMode.List -> {
                                DiscoverySuiteListScreen(
                                    config = configState,
                                    selectedSuiteId = selectedSuiteIdState,
                                    loadingOptions = loadingTagsState,
                                    sourceCount = sourceTagOptionsState.size,
                                    starterRunning = starterRunningState,
                                    starterProgress = starterProgressState,
                                    onGenerateStarterSuite = ::generateStarterSuite,
                                    onCancelStarterSuite = ::cancelStarterSuite,
                                    onCreateSuite = ::showCreateSuiteDialog,
                                    onOpenSuite = ::openSuiteDetail,
                                    onSetCurrentSuite = ::selectSuite,
                                    onRenameSuite = ::showRenameSuiteDialog,
                                    onAliasSuite = ::showSuiteAliasDialog,
                                    onDeleteSuite = ::confirmDeleteSuite
                                )
                            }
                    }
                        }
                }
                }
            }
        }
    }

    private fun handleBackNavigation() {
        when (val mode = screenModeState) {
            is DiscoverySuiteManageMode.WidgetEditor -> {
                setScreenMode(DiscoverySuiteManageMode.Detail(mode.suiteId))
            }
            is DiscoverySuiteManageMode.Detail -> {
                setScreenMode(DiscoverySuiteManageMode.List)
            }
            DiscoverySuiteManageMode.List -> finish()
        }
    }

    private fun openSuiteDetail(suite: DiscoverySuite) {
        setScreenMode(DiscoverySuiteManageMode.Detail(suite.id))
    }

    private fun setScreenMode(mode: DiscoverySuiteManageMode) {
        screenModeState = mode.validatedAgainst(configState)
        updateTitleBar()
        invalidateOptionsMenu()
    }

    private fun updateTitleBar() {
        topBarTitleState = when (val mode = screenModeState) {
            DiscoverySuiteManageMode.List -> getString(R.string.discovery_suite_manage_title)
            is DiscoverySuiteManageMode.Detail -> getString(R.string.discovery_suite_manage)
            is DiscoverySuiteManageMode.WidgetEditor -> {
                val suite = configState.suites.firstOrNull { it.id == mode.suiteId }
                val widget = suite?.widgets?.firstOrNull { it.id == mode.widgetId }
                if (widget == null) {
                    getString(R.string.discovery_suite_add_widget)
                } else {
                    getString(R.string.edit)
                }
            }
        }
        topBarActionsState = when (val mode = screenModeState) {
            DiscoverySuiteManageMode.List -> {
                listOf(
                    MenuAction(
                        iconRes = R.drawable.ic_add,
                        title = getString(R.string.discovery_suite_create),
                        alwaysShow = true
                    ) { showCreateSuiteDialog() }
                )
            }
            is DiscoverySuiteManageMode.Detail -> {
                configState.suites.firstOrNull { it.id == mode.suiteId }?.let { suite ->
                    listOf(
                        MenuAction(
                            iconRes = R.drawable.ic_add,
                            title = getString(R.string.discovery_suite_add_widget),
                            alwaysShow = true
                        ) { openWidgetEditor(suite, null) }
                    )
                } ?: emptyList()
            }
            is DiscoverySuiteManageMode.WidgetEditor -> emptyList()
        }
    }

    private fun refreshConfig() {
        val config = DiscoverySuiteStore.load()
        val selectedId = DiscoverySuiteStore.selectedSuiteId()
            .takeIf { id -> config.suites.any { it.id == id } }
            ?: config.suites.firstOrNull()?.id.orEmpty()
        if (selectedId != DiscoverySuiteStore.selectedSuiteId()) {
            DiscoverySuiteStore.setSelectedSuiteId(selectedId)
        }
        configState = config
        selectedSuiteIdState = selectedId
        screenModeState = screenModeState.validatedAgainst(config)
        updateTitleBar()
        invalidateOptionsMenu()
    }

    private fun loadSourceOptions() {
        loadingTagsState = true
        lifecycleScope.launch {
            val options = withContext(IO) {
                // P2（2026-10-09 真实源真机实测铁证）：原实现 `.take(200)` 硬截断 —— 5540 个启用源里
                // 只有前 200 个可见可搜，用户几乎找不到想配的源（配合"搜索只在这 200 个里搜"更甚）。
                // 现放开为**防御性上限**（非正常截断），列表已改懒渲染 ⇒ 全量不影响帧率。
                // 顺序由 DAO 保证（`order by customOrder asc`，与书源管理页一致，用户可凭习惯定位）。
                appDb.bookSourceDao.allEnabledPart
                    .filter { it.enabledExplore && it.hasExploreUrl }
                    .take(MAX_MANAGER_SOURCES)
                    .map { source ->
                        DiscoverySuiteSourceTagOptions(
                            sourceName = source.bookSourceName,
                            sourceUrl = source.bookSourceUrl,
                            tags = emptyList()
                        )
                    }
            }
            val loadedByUrl = sourceTagOptionsState.associateBy { it.sourceUrl }
            sourceTagOptionsState = options.map { option ->
                loadedByUrl[option.sourceUrl]?.takeIf { it.tags.isNotEmpty() }
                    ?: option
            }
            loadingTagsState = false
        }
    }

    private fun loadSourceTags(sourceUrl: String) {
        if (sourceUrl.isBlank()) return
        if (sourceUrl in loadingSourceTagUrlsState || sourceUrl in loadedSourceTagUrlsState) return
        val sourceName = sourceTagOptionsState.firstOrNull { it.sourceUrl == sourceUrl }?.sourceName
            ?: return
        loadingSourceTagUrlsState = loadingSourceTagUrlsState + sourceUrl
        val otherGroupLabel = getString(R.string.discover_group_other)
        lifecycleScope.launch {
            val option = withContext(IO) {
                // 派生逻辑单源（DiscoverySuiteSourceOptions.kt）：与一键生成共用同一份规则，杜绝两处漂移
                appDb.bookSourceDao.allEnabledPart
                    .firstOrNull { it.bookSourceUrl == sourceUrl }
                    ?.takeIf { it.enabledExplore && it.hasExploreUrl }
                    ?.buildSourceTagOptions(otherGroupLabel)
                    ?: DiscoverySuiteSourceTagOptions(
                        sourceName = sourceName,
                        sourceUrl = sourceUrl,
                        tags = emptyList()
                    )
            }
            sourceTagOptionsState = sourceTagOptionsState.map {
                if (it.sourceUrl == sourceUrl) option else it
            }
            loadedSourceTagUrlsState = loadedSourceTagUrlsState + sourceUrl
            loadingSourceTagUrlsState = loadingSourceTagUrlsState - sourceUrl
        }
    }

    private fun saveConfig(transform: (DiscoverySuiteConfig) -> DiscoverySuiteConfig) {
        DiscoverySuiteStore.save(transform(DiscoverySuiteStore.load()))
        refreshConfig()
    }

    private fun selectSuite(suite: DiscoverySuite) {
        DiscoverySuiteStore.setSelectedSuiteId(suite.id)
        refreshConfig()
    }

    private fun showCreateSuiteDialog() {
        showComposeTextInputDialog(
            title = getString(R.string.discovery_suite_create),
            hint = getString(R.string.discovery_suite_name),
            validateInput = { it.trim().isNotEmpty() },
            onPositive = { name ->
                val suite = DiscoverySuiteStore.newSuite(name)
                DiscoverySuiteStore.setSelectedSuiteId(suite.id)
                saveConfig { config -> config.copy(suites = config.suites + suite) }
                setScreenMode(DiscoverySuiteManageMode.Detail(suite.id))
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

    private fun confirmDeleteSuite(suite: DiscoverySuite) {
        showComposeConfirmDialog(
            title = getString(R.string.discovery_suite_delete),
            // 删除影响范围：套件删除会连带其全部控件，必须在确认前说清（原仅显示套件名）
            message = getString(
                R.string.discovery_suite_delete_with_widgets,
                suite.displayName,
                suite.widgets.size
            ),
            dangerPositive = true,
            onPositive = {
                if (screenModeState.belongsToSuite(suite.id)) {
                    setScreenMode(DiscoverySuiteManageMode.List)
                }
                saveConfig { config ->
                    val suites = config.suites.filterNot { it.id == suite.id }
                    if (selectedSuiteIdState == suite.id) {
                        DiscoverySuiteStore.setSelectedSuiteId(suites.firstOrNull()?.id.orEmpty())
                    }
                    config.copy(suites = suites)
                }
            }
        )
    }

    private fun openWidgetEditor(suite: DiscoverySuite, widget: DiscoverySuiteWidget?) {
        setScreenMode(DiscoverySuiteManageMode.WidgetEditor(suite.id, widget?.id))
    }

    private fun closeWidgetEditor(suiteId: String) {
        setScreenMode(DiscoverySuiteManageMode.Detail(suiteId))
    }

    /**
     * 控件保存校验（单一判据）：编辑器的**内联错误**与 Activity 的**保存兜底 toast** 共用本函数，
     * 避免规则漂移（原先只有 Activity 侧 toast，编辑器中滚动后易错过）。
     * 返回 null = 通过。
     */
    private fun widgetTargetsError(
        type: String,
        targets: List<DiscoverySuiteWidgetTarget>
    ): String? {
        if (targets.isEmpty()) return getString(R.string.discovery_suite_widget_targets_empty)
        val cleanType = DiscoverySuiteWidgetType.sanitize(type)
        // 排行榜类强制 N-M 个标签：约束真值与编辑器徽标共用 widgetTargetsConstraint（2.1.2 单源），
        // 文案区间由约束推导（不再硬编码 3-9），避免两处规则漂移。
        if (cleanType == DiscoverySuiteWidgetType.RankButtons.value ||
            cleanType == DiscoverySuiteWidgetType.RankedList.value
        ) {
            val constraint = widgetTargetsConstraint(cleanType)
            if (!constraint.accepts(targets.take(constraint.max).size)) {
                return getString(
                    R.string.discovery_suite_widget_rank_targets_range,
                    constraint.min,
                    constraint.max
                )
            }
        }
        return null
    }

    private fun saveWidget(
        suiteId: String,
        oldWidget: DiscoverySuiteWidget?,
        title: String,
        type: String,
        targets: List<DiscoverySuiteWidgetTarget>
    ) {
        widgetTargetsError(type, targets)?.let {
            toastOnUi(it)
            return
        }
        val cleanType = DiscoverySuiteWidgetType.sanitize(type)
        val defaultTitle = when (cleanType) {
            DiscoverySuiteWidgetType.TagBar.value -> getString(R.string.discovery_suite_default_tag_bar_title)
            DiscoverySuiteWidgetType.RankButtons.value -> "排行榜按钮"
            DiscoverySuiteWidgetType.RankedList.value -> getString(R.string.discovery_suite_widget_type_ranked_list)
            DiscoverySuiteWidgetType.WaterfallBooks.value -> getString(R.string.discovery_suite_widget_type_waterfall_books)
            DiscoverySuiteWidgetType.HorizontalBooks.value -> getString(R.string.discovery_suite_widget_type_horizontal_books)
            else -> getString(R.string.discovery_suite_default_random_title)
        }
        val cleanTargets = when (cleanType) {
            DiscoverySuiteWidgetType.HorizontalBooks.value -> targets.take(1)
            DiscoverySuiteWidgetType.RankButtons.value,
            DiscoverySuiteWidgetType.RankedList.value -> targets.take(9)
            else -> targets
        }
        val submittedTitle = title.trim()
        val finalTitle = when {
            cleanType in setOf(
                DiscoverySuiteWidgetType.RankedList.value,
                DiscoverySuiteWidgetType.WaterfallBooks.value
            ) && (submittedTitle.isBlank() ||
                submittedTitle == getString(R.string.discovery_suite_default_random_title)) -> defaultTitle
            else -> submittedTitle.ifBlank { defaultTitle }
        }
        val widget = (oldWidget ?: DiscoverySuiteStore.newBookWidget(defaultTitle, cleanType)).copy(
            title = finalTitle,
            type = cleanType,
            targets = cleanTargets,
            displayLimit = when (cleanType) {
                DiscoverySuiteWidgetType.TagBar.value -> cleanTargets.size.coerceIn(1, 30)
                DiscoverySuiteWidgetType.RankButtons.value -> cleanTargets.size.coerceIn(3, 9)
                DiscoverySuiteWidgetType.HorizontalBooks.value -> DEFAULT_WIDGET_DISPLAY_LIMIT
                DiscoverySuiteWidgetType.RankedList.value -> DEFAULT_RANKED_WIDGET_BOOK_COUNT
                DiscoverySuiteWidgetType.WaterfallBooks.value -> DEFAULT_WATERFALL_WIDGET_BOOK_COUNT
                else -> DEFAULT_RANDOM_WIDGET_POOL_LIMIT
            }
        )
        updateSuite(suiteId) { suite ->
            val widgets = if (oldWidget == null) {
                suite.widgets + widget
            } else {
                suite.widgets.map { if (it.id == oldWidget.id) widget else it }
            }
            suite.copy(widgets = widgets)
        }
        // 保存反馈（2.5.2）：明确下一步（编辑器内无预览，真实效果在发现页）
        toastOnUi(R.string.discovery_suite_saved_hint)
        closeWidgetEditor(suiteId)
    }

    private fun reorderWidgets(suite: DiscoverySuite, orderedWidgets: List<DiscoverySuiteWidget>) {
        // 瀑布流置底单源（AD-06）：改用 DiscoverySuiteConfig 的共享扩展，三处语义归一
        val normalizedWidgets = orderedWidgets.withWaterfallPinnedBottom()
        val orderById = normalizedWidgets.mapIndexed { index, widget -> widget.id to index }.toMap()
        updateSuite(suite.id) { current ->
            current.copy(
                widgets = current.widgets
                    .map { widget ->
                        orderById[widget.id]?.let { order -> widget.copy(order = order) } ?: widget
                    }
                    .sortedBy { it.order }
            )
        }
    }

    private fun confirmDeleteWidget(suite: DiscoverySuite, widget: DiscoverySuiteWidget) {
        showComposeConfirmDialog(
            title = getString(R.string.delete),
            message = widget.title.ifBlank { getString(R.string.discovery_suite_add_widget) },
            dangerPositive = true,
            onPositive = {
                updateSuite(suite.id) { current ->
                    current.copy(widgets = current.widgets.filterNot { it.id == widget.id })
                }
            }
        )
    }

    private fun updateSuiteOpacityMultiplier(suite: DiscoverySuite, value: Float) {
        updateSuite(suite.id) { current ->
            current.copy(opacityMultiplier = value.coerceIn(1f, 4f))
        }
    }

    private fun updateSuite(
        suiteId: String,
        transform: (DiscoverySuite) -> DiscoverySuite
    ) {
        saveConfig { config ->
            config.copy(
                suites = config.suites.map { suite ->
                    if (suite.id == suiteId) transform(suite) else suite
                }
            )
        }
    }

    /** A：一键生成套件（列表空态入口；与发现页共用 runStarterSuite；spec E4 幂等）。 */
    private fun generateStarterSuite() {
        if (starterJob?.isActive == true) return
        val titles = DiscoverySuiteStarter.Titles(
            suiteName = getString(R.string.discovery_suite_default_name),
            tagBar = getString(R.string.discovery_suite_widget_type_tag_bar),
            horizontalBooks = getString(R.string.discovery_suite_widget_type_horizontal_books),
            waterfallBooks = getString(R.string.discovery_suite_widget_type_waterfall_books)
        )
        val otherGroupLabel = getString(R.string.discover_group_other)
        starterRunningState = true
        starterProgressState = null
        starterJob = lifecycleScope.launch {
            val result = try {
                runStarterSuite(titles, otherGroupLabel) { progress ->
                    starterProgressState = progress
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                AppLog.put("一键生成套件失败", e)
                null
            }
            starterRunningState = false
            starterProgressState = null
            if (isFinishing || isDestroyed) return@launch
            // spec E15：生成完成后主动刷新一次，保证管理页状态与写盘结果一致
            refreshConfig()
            when (result) {
                is StarterSuiteResult.Created -> {
                    val skipped = (result.probeTotal - result.probeUsable).coerceAtLeast(0)
                    toastOnUi(
                        if (skipped > 0) {
                            getString(R.string.discovery_suite_generate_done) + "\n" +
                                getString(R.string.discovery_suite_generate_skipped, skipped)
                        } else {
                            getString(R.string.discovery_suite_generate_done)
                        }
                    )
                }
                StarterSuiteResult.NoUsableSource -> toastOnUi(R.string.discovery_suite_generate_none)
                StarterSuiteResult.SuiteLimitReached -> toastOnUi(
                    getString(R.string.discovery_suite_generate_limit, MAX_DISCOVERY_SUITE_COUNT)
                )
                StarterSuiteResult.SaveFailed -> toastOnUi(
                    R.string.discovery_suite_generate_save_failed
                )
                null -> Unit
            }
        }
    }

    private fun cancelStarterSuite() {
        starterJob?.cancel()
        starterJob = null
        starterRunningState = false
        starterProgressState = null
    }

    companion object {
        /** 深链：目标套件 id（可选）。 */
        const val EXTRA_SUITE_ID = "discovery_suite_extra_suite_id"

        /** 深链：目标控件 id（null = 新建控件）。 */
        const val EXTRA_WIDGET_ID = "discovery_suite_extra_widget_id"

        // 候选源**防御上限**（非业务截断）：真实库实测 5701 源，留足余量；列表已懒渲染，全量不卡（P2）。
        private const val MAX_MANAGER_SOURCES = 8000
    }
}

private sealed class DiscoverySuiteManageMode {
    object List : DiscoverySuiteManageMode()
    data class Detail(val suiteId: String) : DiscoverySuiteManageMode()
    data class WidgetEditor(val suiteId: String, val widgetId: String?) : DiscoverySuiteManageMode()
}

private fun DiscoverySuiteManageMode.belongsToSuite(suiteId: String): Boolean {
    return when (this) {
        is DiscoverySuiteManageMode.Detail -> this.suiteId == suiteId
        is DiscoverySuiteManageMode.WidgetEditor -> this.suiteId == suiteId
        DiscoverySuiteManageMode.List -> false
    }
}

private fun DiscoverySuiteManageMode.validatedAgainst(
    config: DiscoverySuiteConfig
): DiscoverySuiteManageMode {
    return when (this) {
        is DiscoverySuiteManageMode.Detail -> {
            if (config.suites.any { it.id == suiteId }) this else DiscoverySuiteManageMode.List
        }
        is DiscoverySuiteManageMode.WidgetEditor -> {
            val suite = config.suites.firstOrNull { it.id == suiteId }
                ?: return DiscoverySuiteManageMode.List
            if (widgetId == null || suite.widgets.any { it.id == widgetId }) {
                this
            } else {
                DiscoverySuiteManageMode.Detail(suiteId)
            }
        }
        DiscoverySuiteManageMode.List -> this
    }
}

// DiscoverySuiteSourceTagOptions / DiscoverySuiteTagOption 已抽到 DiscoverySuiteSourceOptions.kt
// （与一键生成共用同一份「源 → 发现标签」派生，禁止第二实现源）

@Composable
private fun DiscoverySuiteListScreen(
    config: DiscoverySuiteConfig,
    selectedSuiteId: String,
    loadingOptions: Boolean,
    sourceCount: Int,
    starterRunning: Boolean,
    starterProgress: StarterSuiteProgress?,
    onGenerateStarterSuite: () -> Unit,
    onCancelStarterSuite: () -> Unit,
    onCreateSuite: () -> Unit,
    onOpenSuite: (DiscoverySuite) -> Unit,
    onSetCurrentSuite: (DiscoverySuite) -> Unit,
    onRenameSuite: (DiscoverySuite) -> Unit,
    onAliasSuite: (DiscoverySuite) -> Unit,
    onDeleteSuite: (DiscoverySuite) -> Unit
) {
    val renderConfig = rememberBookshelfListRenderConfig()
    val managementPalette = rememberAppManagementPalette()
    val palette = renderConfig.palette
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            // P7（2026-10-09 真机实测）：原文案「N 个书源 / M 个 Tag」中 M 取自"已加载标签累计"，
            // 实测随浏览过的源在 0→10→185 之间跳动，用户无法理解其含义 ⇒ 只保留稳定且有意义的
            // "候选书源数"，去掉会跳动的标签计数（该计数只在编辑器内有行动价值）。
            Text(
                text = if (loadingOptions) {
                    "书源加载中..."
                } else {
                    "候选书源 ${sourceCount} 个"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 14.dp, end = 18.dp),
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
        }
        items(config.suites, key = { it.id }) { suite ->
            val isCurrent = suite.id == selectedSuiteId
            // 副标题类型摘要：控件类型去重后取前 2 项（超过补省略号），零新增数据源
            val typeLabels = suite.widgets.map { it.typeLabel() }.distinct()
            val typeSummary = typeLabels.take(2).joinToString("/") +
                if (typeLabels.size > 2) "\u2026" else ""
            AppManagementListRow(
                title = suite.displayName,
                subtitle = buildString {
                    if (isCurrent) append("当前套件 · ")
                    append("${suite.widgets.size} 个控件")
                    if (typeSummary.isNotBlank()) {
                        append(" · ")
                        append(typeSummary)
                    }
                },
                selected = isCurrent,
                selectionVisible = false,
                palette = managementPalette,
                minHeight = 58.dp,
                // F21：最高频单动作前置——非当前套件行常驻一枚「设为当前」（accent 勾选图标），
                // 编辑/重命名/别名/删除仍收 ⋮（克制度：只前置 1 项，避免行变操作条与误触 danger）
                trailingBeforeSwitch = if (isCurrent) {
                    null
                } else {
                    {
                        AppManagementIconAction(
                            iconRes = R.drawable.ic_check,
                            contentDescription = stringResource(R.string.discovery_suite_set_current),
                            tint = managementPalette.settings.accent,
                            onClick = { onSetCurrentSuite(suite) }
                        )
                    }
                },
                moreActions = buildList {
                    add(
                        AppManagementMenuAction(
                            text = stringResource(R.string.edit),
                            onClick = { onOpenSuite(suite) }
                        )
                    )
                    add(
                        AppManagementMenuAction(
                            text = stringResource(R.string.discovery_suite_rename),
                            onClick = { onRenameSuite(suite) }
                        )
                    )
                    add(
                        AppManagementMenuAction(
                            text = stringResource(R.string.discovery_suite_alias),
                            onClick = { onAliasSuite(suite) }
                        )
                    )
                    add(
                        AppManagementMenuAction(
                            text = stringResource(R.string.delete),
                            danger = true,
                            onClick = { onDeleteSuite(suite) }
                        )
                    )
                },
                onClick = { onOpenSuite(suite) }
            )
        }
        if (config.suites.isEmpty()) {
            item {
                // 空态统一走共享 EmptyStatePlaceholder（components.md §六）；生成中替换为进度面板。
                // 用 fillParentMaxHeight 承载，避免 fillMaxSize 在 LazyColumn item 内塌陷为零高。
                Box(modifier = Modifier.fillParentMaxHeight(0.6f)) {
                    if (starterRunning) {
                        StarterSuiteProgressPanel(
                            progress = starterProgress,
                            onCancel = onCancelStarterSuite
                        )
                    } else {
                        EmptyStatePlaceholder(
                            icon = Icons.Filled.Widgets,
                            title = stringResource(R.string.discovery_suite_empty_title),
                            subtitle = stringResource(R.string.discovery_suite_empty_summary),
                            primaryAction = EmptyStateAction(
                                stringResource(R.string.discovery_suite_generate_once),
                                onGenerateStarterSuite
                            ),
                            secondaryActions = listOf(
                                EmptyStateAction(
                                    stringResource(R.string.discovery_suite_create),
                                    onCreateSuite
                                )
                            ),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(18.dp))
        }
    }
}

@Composable
private fun DiscoverySuiteDetailScreen(
    suite: DiscoverySuite?,
    loadingOptions: Boolean,
    sourceCount: Int,
    onOpacityMultiplierChange: (DiscoverySuite, Float) -> Unit,
    onEditWidget: (DiscoverySuite, DiscoverySuiteWidget) -> Unit,
    onDeleteWidget: (DiscoverySuite, DiscoverySuiteWidget) -> Unit,
    onReorderWidgets: (DiscoverySuite, List<DiscoverySuiteWidget>) -> Unit
) {
    val renderConfig = rememberBookshelfListRenderConfig()
    val palette = renderConfig.palette
    val listState = rememberLazyListState()
    val widgetSnapshot = suite?.widgets.orEmpty()
    val widgetSignature = widgetSnapshot.joinToString(separator = "\u001F") {
        listOf(it.id, it.type, it.title, it.order, it.targets.size).joinToString(separator = "\u001E")
    }
    var orderedWidgets by remember { mutableStateOf(widgetSnapshot, referentialEqualityPolicy()) }
    LaunchedEffect(suite?.id, widgetSignature) {
        orderedWidgets = widgetSnapshot
    }
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = from.index
        val toIndex = to.index
        if (fromIndex in orderedWidgets.indices && toIndex in orderedWidgets.indices) {
            orderedWidgets = orderedWidgets.toMutableList().apply {
                add(toIndex, removeAt(fromIndex))
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        Text(
            text = if (loadingOptions) {
                "书源加载中..."
            } else {
                "候选书源 ${sourceCount} 个"
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 14.dp, end = 18.dp),
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText
        )
        if (suite == null) {
            Text(
                text = "套件不存在",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 48.dp),
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
        } else {
            SuiteOpacityMultiplierRow(
                suite = suite,
                renderConfig = renderConfig,
                onValueChange = { value -> onOpacityMultiplierChange(suite, value) }
            )
            if (orderedWidgets.isEmpty()) {
                Text(
                    text = "还没有控件",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 24.dp),
                    fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                    fontFamily = palette.bodyFontFamily,
                    color = palette.secondaryText
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(orderedWidgets, key = { it.id }) { widget ->
                        ReorderableItem(reorderState, key = widget.id) {
                            WidgetManageRow(
                                widget = widget,
                                renderConfig = renderConfig,
                                onEdit = { onEditWidget(suite, widget) },
                                onDelete = { onDeleteWidget(suite, widget) },
                                dragHandle = {
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .size(42.dp)
                                            .draggableHandle(
                                                onDragStopped = {
                                                    onReorderWidgets(suite, orderedWidgets)
                                                }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_drag_handle),
                                            contentDescription = stringResource(R.string.sort),
                                            tint = palette.secondaryText,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
                            }
        }
    }
}

@Composable
private fun SuiteOpacityMultiplierRow(
    suite: DiscoverySuite,
    renderConfig: BookshelfListRenderConfig,
    onValueChange: (Float) -> Unit
) {
    val palette = renderConfig.palette
    val value = suite.opacityMultiplier.coerceIn(1f, 4f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(palette.panelRadius))
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "透明度倍率",
                fontSize = MaterialTheme.typography.bodySecondary.fontSize,
                fontWeight = FontWeight.Medium,
                fontFamily = palette.bodyFontFamily,
                color = palette.primaryText
            )
            Text(
                text = "当前 ${"%.2f".format(value)}x，仅增强套件页面板不透明度",
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // F20：调节即预览——mini 面板样例与真实面板**同源构建**（同 rowColor/贴图/边框/圆角），
        // 倍率通过 withAlphaMultiplier 与 panelImageDrawable(alphaMultiplier) 即时反映在本样例上
        val context = LocalContext.current
        val samplePanelImage = remember(context, palette.panelRadiusPx, value) {
            UiCorner.panelImageDrawable(context, palette.panelRadiusPx, alphaMultiplier = value)
        }
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(palette.panelRadius))
                .appSettingPanelBackground(
                    normalColor = palette.rowColor.withAlphaMultiplier(value),
                    panelImage = samplePanelImage,
                    borderColor = palette.borderColor?.withAlphaMultiplier(value),
                    radiusPx = palette.panelRadiusPx
                )
                // 样例轮廓：不透明纯色主题下样例填充与行面板同色（该主题本就不受倍率影响），
                // 描 1dp 次级文字色轮廓使样例可见、位置可辨（取色仍走主题 palette，不新增色值）
                .border(
                    1.dp,
                    palette.secondaryText.copy(alpha = 0.35f),
                    RoundedCornerShape(palette.panelRadius)
                )
        )
        CompactAction(text = "-0.25", renderConfig = renderConfig) {
            onValueChange((value - 0.25f).coerceAtLeast(1f))
        }
        CompactAction(text = "+0.25", renderConfig = renderConfig) {
            onValueChange((value + 0.25f).coerceAtMost(4f))
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DiscoverySuiteWidgetEditorScreen(
    suite: DiscoverySuite?,
    widget: DiscoverySuiteWidget?,
    sourceOptions: List<DiscoverySuiteSourceTagOptions>,
    loadingOptions: Boolean,
    loadingSourceUrls: Set<String>,
    loadedSourceUrls: Set<String>,
    onLoadSourceTags: (String) -> Unit,
    validateTargets: ((String, List<DiscoverySuiteWidgetTarget>) -> String?)? = null,
    onSave: (String, String, List<DiscoverySuiteWidgetTarget>) -> Unit,
    onCancel: () -> Unit
) {
    val renderConfig = rememberBookshelfListRenderConfig()
    val palette = renderConfig.palette
    // 内联校验错误（原为保存时 toast，编辑器中滚动后易错过）
    var inlineError by remember { mutableStateOf<String?>(null) }
    val initialType = widget?.type?.let(DiscoverySuiteWidgetType::sanitize)
        ?: DiscoverySuiteWidgetType.RandomBooks.value
    var title by remember(widget?.id) {
        mutableStateOf(
            widget?.title.orEmpty().ifBlank {
                when (initialType) {
                    DiscoverySuiteWidgetType.TagBar.value -> "Tag 导航"
                    DiscoverySuiteWidgetType.RankButtons.value -> "排行榜按钮"
                    DiscoverySuiteWidgetType.RankedList.value -> "排行榜列表"
                    DiscoverySuiteWidgetType.WaterfallBooks.value -> "瀑布流"
                    DiscoverySuiteWidgetType.HorizontalBooks.value -> "横排滑动"
                    else -> "随机推荐"
                }
            }
        )
    }
    var type by remember(widget?.id) { mutableStateOf(initialType) }
    var selectedKeys by remember(widget?.id) {
        mutableStateOf(widget?.targets.orEmpty().map { "${it.sourceUrl}\n${it.tagUrl}" }.toSet())
    }
    val allOptions = remember(sourceOptions) {
        sourceOptions.flatMap { it.tags }
    }
    val targetByKey = remember(sourceOptions, widget?.id) {
        linkedMapOf<String, DiscoverySuiteWidgetTarget>().apply {
            allOptions.forEach { option ->
                put(option.key, option.toTarget())
            }
            widget?.targets.orEmpty().forEach { target ->
                val key = "${target.sourceUrl}\n${target.tagUrl}"
                putIfAbsent(key, target)
            }
        }
    }
    // 选源（B1）：**编辑既有控件时回填其原源**（保证配置不丢）；新建时**不自动选中任何源、
    // 不自动联网**——原实现在进页面时替用户选中第一个源并立即拉取标签，既误导又白等网络（P3）。
    var selectedSourceUrl by remember(widget?.id) {
        mutableStateOf(widget?.targets?.firstOrNull()?.sourceUrl.orEmpty())
    }
    val selectedSource = sourceOptions.firstOrNull { it.sourceUrl == selectedSourceUrl }
    val selectedSourceIsLoading = selectedSource?.sourceUrl in loadingSourceUrls
    val selectedSourceLoaded = selectedSource?.sourceUrl in loadedSourceUrls
    LaunchedEffect(selectedSource?.sourceUrl) {
        selectedSource?.sourceUrl
            ?.takeIf { it.isNotBlank() && it !in loadingSourceUrls && it !in loadedSourceUrls }
            ?.let(onLoadSourceTags)
    }
    var sourceQuery by remember(widget?.id) { mutableStateOf("") }
    // P1/P2：源列表改懒列表后需要独立滚动状态 —— 供「搜索词变化即回到顶部」复位
    // （原实现滚动位置会残留，用户滚动过列表再改搜索词时会误以为"源顺序每次都在变"）。
    val sourceListState = rememberLazyListState()
    val filteredSources = remember(sourceOptions, sourceQuery) {
        val query = sourceQuery.trim()
        if (query.isBlank()) {
            sourceOptions
        } else {
            sourceOptions.filter {
                it.sourceName.contains(query, ignoreCase = true) ||
                    it.sourceUrl.contains(query, ignoreCase = true)
            }
        }
    }
    // 类型（B3/B4）：类型项 + 约束真值 + 裁剪提示文案（文案先于 lambda 取，避免在非组合 lambda 内调 stringResource）
    val typeOptions = listOf(
        DiscoverySuiteWidgetType.RandomBooks.value to
            stringResource(R.string.discovery_suite_widget_type_random_books),
        DiscoverySuiteWidgetType.TagBar.value to
            stringResource(R.string.discovery_suite_widget_type_tag_bar),
        DiscoverySuiteWidgetType.RankButtons.value to
            stringResource(R.string.discovery_suite_widget_type_rank_buttons),
        DiscoverySuiteWidgetType.RankedList.value to
            stringResource(R.string.discovery_suite_widget_type_ranked_list),
        DiscoverySuiteWidgetType.WaterfallBooks.value to
            stringResource(R.string.discovery_suite_widget_type_waterfall_books),
        DiscoverySuiteWidgetType.HorizontalBooks.value to
            stringResource(R.string.discovery_suite_widget_type_horizontal_books)
    )
    val typeLabelByValue = remember(typeOptions) { typeOptions.toMap() }
    val trimmedFormat = stringResource(R.string.discovery_suite_type_trimmed)
    var typeNotice by remember(widget?.id) { mutableStateOf<String?>(null) }
    val constraint = widgetTargetsConstraint(type)
    val managementPalette = rememberAppManagementPalette()
    val onTypeSelected: (String) -> Unit = { newType ->
        val newConstraint = widgetTargetsConstraint(newType)
        val kept = selectedKeys.take(newConstraint.max).toSet()
        val removed = selectedKeys.size - kept.size
        selectedKeys = kept
        typeNotice = if (removed > 0) {
            String.format(
                trimmedFormat,
                typeLabelByValue[newType] ?: newType,
                newConstraint.max,
                removed
            )
        } else {
            null
        }
        type = newType
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .clip(RoundedCornerShape(palette.panelRadius))
                        .appSettingPanelBackground(
                            normalColor = palette.rowColor,
                            panelImage = renderConfig.panelImage,
                            borderColor = palette.borderColor,
                            radiusPx = palette.panelRadiusPx
                        )
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = if (widget == null) {
                            "添加控件"
                        } else {
                            "编辑控件"
                        },
                        fontSize = MaterialTheme.typography.titleLargeX.fontSize,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = palette.titleFontFamily,
                        color = palette.primaryText
                    )
                    SuiteEditorTextField(
                        value = title,
                        onValueChange = { title = it.take(40) },
                        label = "控件标题",
                        renderConfig = renderConfig
                    )
                    Text(
                        text = stringResource(R.string.discovery_suite_widget_type),
                        fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                        fontWeight = FontWeight.Medium,
                        fontFamily = palette.bodyFontFamily,
                        color = palette.secondaryText
                    )
                    // P4（2026-10-09 真机实测）：原用横向滚动 Row —— 6 个类型里有 2 个
                    //（横排滑动 / 瀑布流，恰是最高频的两种）被藏在屏外，用户根本不知道还有得选。
                    // 改 FlowRow 自动换行 ⇒ 全部类型一屏可见，不再依赖"左右滑动去发现"。
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        typeOptions.forEach { entry ->
                            WidgetTypeChip(
                                text = entry.second,
                                selected = type == entry.first,
                                renderConfig = renderConfig
                            ) {
                                onTypeSelected(entry.first)
                            }
                        }
                    }
                    // B3：类型说明 + 约束徽标 + 静态骨架预览（原实现只有类型名，用户选型靠猜）
                    WidgetTypeInfoCard(type = type, renderConfig = renderConfig)
                    // B4：切类型裁剪已选时的显式提示（原实现静默丢弃）
                    typeNotice?.let { notice ->
                        Text(
                            text = notice,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize,
                            fontFamily = palette.bodyFontFamily,
                            color = palette.accent
                        )
                    }
                }
            }
            if (loadingOptions) {
                item(key = "source_loading") {
                    Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                        SourceTagsStatePanel(text = "书源加载中...", renderConfig = renderConfig)
                    }
                }
            } else if (sourceOptions.isEmpty()) {
                item(key = "source_empty") {
                    Box(modifier = Modifier.padding(horizontal = 18.dp)) {
                        SourceTagsStatePanel(
                            text = "没有可用书源（需在书源中启用发现规则）",
                            renderConfig = renderConfig
                        )
                    }
                }
            } else {
                // B1：书源改**纵向可搜索列表**（原横向 Chip 流，上百源只能左右滑），行副标题显示标签状态。
                // 🔴 修正（用户检查点 2 反馈「选完书源却没地方选标签」）：书源列表**不得**作为外层 LazyColumn
                // 的独立行铺开——原实现在 200 个源时会把「标签选择区」顶到几百行之外，用户点完源看不到标签
                // ⇒ 等价于"没法选"。此处改为**定高内滚列表**，标签选择区紧邻其下（保持旧版"选源即见标签"的相邻性）。
                item(key = "source_and_tags") {
                    Column(
                        modifier = Modifier.padding(horizontal = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "选择书源（${filteredSources.size}）",
                            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                            fontWeight = FontWeight.Medium,
                            fontFamily = palette.bodyFontFamily,
                            color = palette.secondaryText
                        )
                        SuiteEditorTextField(
                            value = sourceQuery,
                            onValueChange = { sourceQuery = it.take(40) },
                            label = "搜索书源",
                            renderConfig = renderConfig
                        )
                        // 🔴 P1 性能修复（2026-10-09 真实源真机实测铁证）：原实现是
                        // `Column + verticalScroll + forEach` **全量渲染** —— 5693 条真实源下滚动
                        // gfxinfo 实测 Janky 62/62 (100%)、50th=69ms（≈14fps），卡到不可用。
                        // 改 LazyColumn 只渲染可见行；`heightIn(max = …)` 提供**有限高度约束**
                        // （外层同为 LazyColumn，无限约束会抛异常），标签选择区仍紧邻其下（保持 A7 相邻性）。
                        LaunchedEffect(sourceQuery) {
                            sourceListState.scrollToItem(0)
                        }
                        LazyColumn(
                            state = sourceListState,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = SOURCE_PICKER_MAX_HEIGHT),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredSources, key = { it.sourceUrl }) { source ->
                                AppManagementListRow(
                                    title = source.sourceName,
                                    subtitle = sourceTagStatusText(
                                        source = source,
                                        loadingSourceUrls = loadingSourceUrls,
                                        loadedSourceUrls = loadedSourceUrls
                                    ),
                                    selected = source.sourceUrl == selectedSourceUrl,
                                    selectionVisible = false,
                                    palette = managementPalette,
                                    minHeight = 56.dp,
                                    // 选中态显式标记（模拟器实测 selected 的底色差过弱，用户看不出选了哪个源）
                                    trailingBeforeSwitch = if (source.sourceUrl == selectedSourceUrl) {
                                        {
                                            Icon(
                                                painter = painterResource(R.drawable.ic_check),
                                                contentDescription = null,
                                                tint = managementPalette.settings.accent,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        selectedSourceUrl = source.sourceUrl
                                        onLoadSourceTags(source.sourceUrl)
                                    }
                                )
                            }
                        }
                    }
                }
            }
            item(key = "tag_picker") {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (selectedKeys.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "已选 ${selectedKeys.size} 个",
                                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                                fontFamily = palette.bodyFontFamily,
                                color = palette.secondaryText
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                CompactAction(text = "清空当前源", renderConfig = renderConfig) {
                                    val currentKeys = selectedSource?.tags.orEmpty().map { it.key }.toSet()
                                    selectedKeys = selectedKeys - currentKeys
                                }
                                CompactAction(text = "清空全部", renderConfig = renderConfig, danger = true) {
                                    selectedKeys = emptySet()
                                }
                            }
                        }
                    }
                    when {
                        selectedSource == null -> Text(
                            text = "请先选择书源，再选择该书源下的标签",
                            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
                            fontFamily = palette.bodyFontFamily,
                            color = palette.secondaryText
                        )
                        selectedSourceIsLoading -> SourceTagsStatePanel(
                            text = "${selectedSource.sourceName} Tag 加载中...",
                            renderConfig = renderConfig
                        )
                        !selectedSourceLoaded -> SourceTagsStatePanel(
                            text = "${selectedSource.sourceName} 尚未加载 Tag",
                            renderConfig = renderConfig,
                            actionText = "加载",
                            onAction = { onLoadSourceTags(selectedSource.sourceUrl) }
                        )
                        selectedSource.tags.isEmpty() -> SourceTagsStatePanel(
                            text = "${selectedSource.sourceName} 没有可用 Tag",
                            renderConfig = renderConfig
                        )
                        else -> DiscoverySuiteTagPicker(
                            tags = selectedSource.tags,
                            selectedKeys = selectedKeys,
                            singleSelection = type == DiscoverySuiteWidgetType.HorizontalBooks.value,
                            renderConfig = renderConfig,
                            onToggle = { option ->
                                selectedKeys = toggleSuiteTargetKey(
                                    selectedKeys = selectedKeys,
                                    key = option.key,
                                    singleSelection =
                                        type == DiscoverySuiteWidgetType.HorizontalBooks.value,
                                    maxCount = constraint.max
                                )
                            }
                        )
                    }
                }
            }
        }
        // 内联校验错误条（danger 语义色；无错误时零占位）
        inlineError?.let { error ->
            Text(
                text = error,
                color = AppSemanticColors.Danger,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                fontFamily = palette.bodyFontFamily,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 8.dp, end = 18.dp)
            )
        }
        EditorBottomBar(
            renderConfig = renderConfig,
            onCancel = onCancel,
            onSave = {
                val targets = selectedKeys
                    .mapNotNull { targetByKey[it] }
                val error = validateTargets?.invoke(type, targets)
                inlineError = error
                if (error == null) onSave(title, type, targets)
            }
        )
    }
}

@Composable
private fun SourceTagsStatePanel(
    text: String,
    renderConfig: BookshelfListRenderConfig,
    actionText: String? = null,
    onAction: (() -> Unit)? = null
) {
    val palette = renderConfig.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.panelRadius))
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText,
            modifier = Modifier.weight(1f)
        )
        if (actionText != null && onAction != null) {
            Spacer(modifier = Modifier.width(8.dp))
            CompactAction(text = actionText, renderConfig = renderConfig, onClick = onAction)
        }
    }
}

@Composable
private fun WidgetTypeChip(
    text: String,
    selected: Boolean,
    renderConfig: BookshelfListRenderConfig,
    onClick: () -> Unit
) {
    val palette = renderConfig.palette
    SuiteThemeChipSurface(
        selected = selected,
        renderConfig = renderConfig,
        height = 38.dp,
        horizontalPadding = 16.dp,
        onClick = onClick
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = MaterialTheme.typography.bodyMedium.fontSize,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = palette.bodyFontFamily,
            color = if (selected) palette.accent else palette.primaryText
        )
    }
}

@Composable
private fun SuiteEditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    renderConfig: BookshelfListRenderConfig
) {
    val palette = renderConfig.palette
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(palette.actionRadius),
        label = {
            Text(
                text = label,
                fontFamily = palette.bodyFontFamily
            )
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.primaryText,
            unfocusedTextColor = palette.primaryText,
            cursorColor = palette.accent,
            focusedBorderColor = palette.accent,
            unfocusedBorderColor = palette.borderColor?.let { Color(it) }
                ?: palette.secondaryText.copy(alpha = 0.28f),
            focusedLabelColor = palette.accent,
            unfocusedLabelColor = palette.secondaryText
        )
    )
}


@Composable
private fun EditorBottomBar(
    renderConfig: BookshelfListRenderConfig,
    onCancel: () -> Unit,
    onSave: () -> Unit
) {
    val palette = renderConfig.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp)
            .clip(RoundedCornerShape(palette.panelRadius))
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            )
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PrimaryTextButton(
            text = "取消",
            renderConfig = renderConfig,
            modifier = Modifier.weight(1f),
            onClick = onCancel
        )
        PrimaryTextButton(
            text = "保存",
            renderConfig = renderConfig,
            modifier = Modifier.weight(1f),
            onClick = onSave
        )
    }
}

@Composable
private fun PrimaryTextButton(
    text: String,
    renderConfig: BookshelfListRenderConfig,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val palette = renderConfig.palette
    SuiteThemeChipSurface(
        selected = false,
        renderConfig = renderConfig,
        modifier = modifier.fillMaxWidth(),
        height = 44.dp,
        horizontalPadding = 12.dp,
        onClick = onClick
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = MaterialTheme.typography.bodySecondary.fontSize,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.bodyFontFamily,
            color = palette.accent
        )
    }
}

@Composable
private fun CompactAction(
    text: String,
    renderConfig: BookshelfListRenderConfig,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val palette = renderConfig.palette
    SuiteThemeChipSurface(
        selected = false,
        renderConfig = renderConfig,
        height = 32.dp,
        horizontalPadding = 10.dp,
        onClick = onClick
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.bodyFontFamily,
            // 语义色单源（AD-14）：danger 真值见 AppSemanticColors
            color = if (danger) AppSemanticColors.Danger else palette.accent
        )
    }
}

@Composable
internal fun SuiteThemeChipSurface(
    selected: Boolean,
    renderConfig: BookshelfListRenderConfig,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp,
    horizontalPadding: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val palette = renderConfig.palette
    val radiusPx = with(LocalDensity.current) { palette.actionRadius.toPx() }
    Box(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(palette.actionRadius))
            .appSettingPanelBackground(
                // P3（2026-10-09 真机实测）：原选中态取 palette.rowPressedColor —— 它与未选中的
                // rowColor 同出 `UiCorner.surfaceColor` 仅轻微加深，差异过弱 ⇒ 用户看不出选了哪个。
                // 改用强调色低透明度叠加，选中/未选中一眼可辨（文字侧仍为 accent，见 TagOptionChip）。
                normalColor = if (selected) {
                    palette.accent.copy(alpha = SUITE_CHIP_SELECTED_ALPHA).toArgb()
                } else {
                    palette.rowColor
                },
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = radiusPx
            )
            .clickable(onClick = onClick)
            .padding(horizontal = horizontalPadding),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 编辑器「选择书源」列表的最大可视高度：定高内滚，保证**标签选择区始终紧邻其下**（不被上百行源推远）。 */
private val SOURCE_PICKER_MAX_HEIGHT = 260.dp

/** 套件 chip（类型/标签）选中态的强调色叠加透明度：兼顾"看得见选中"与不抢内容（P3）。 */
private const val SUITE_CHIP_SELECTED_ALPHA = 0.18f

/** 编辑器「选择书源」行的副标题：把标签加载状态摊在源名旁，用户无需点开即可判断该源是否可用。 */
private fun sourceTagStatusText(
    source: DiscoverySuiteSourceTagOptions,
    loadingSourceUrls: Set<String>,
    loadedSourceUrls: Set<String>
): String {
    return when {
        source.sourceUrl in loadingSourceUrls -> "加载中"
        source.sourceUrl !in loadedSourceUrls -> "未加载"
        source.tags.isEmpty() -> "无发现标签"
        else -> "${source.tags.size} 个标签"
    }
}

@Composable
private fun WidgetManageRow(
    widget: DiscoverySuiteWidget,
    renderConfig: BookshelfListRenderConfig,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    dragHandle: (@Composable () -> Unit)? = null
) {
    val palette = renderConfig.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp)
            .clip(RoundedCornerShape(palette.panelRadius))
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            )
            .clickable(onClick = onEdit)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        dragHandle?.invoke()
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = widget.title.ifBlank {
                    when (widget.type) {
                        DiscoverySuiteWidgetType.TagBar.value -> "Tag 导航"
                        DiscoverySuiteWidgetType.RankButtons.value -> "排行榜按钮"
                        DiscoverySuiteWidgetType.RankedList.value -> "排行榜列表"
                        DiscoverySuiteWidgetType.WaterfallBooks.value -> "瀑布流"
                        DiscoverySuiteWidgetType.HorizontalBooks.value -> "横排滑动"
                        else -> "随机推荐"
                    }
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.bodyFontFamily,
                color = palette.primaryText
            )
            Text(
                text = "${widget.typeLabel()} · ${widget.targets.size} 个 Tag",
                fontSize = MaterialTheme.typography.bodyTertiary.fontSize,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
        }
        CompactAction(text = "编辑", renderConfig = renderConfig, onClick = onEdit)
        Spacer(modifier = Modifier.width(8.dp))
        CompactAction(text = "删除", renderConfig = renderConfig, danger = true, onClick = onDelete)
    }
}

private fun DiscoverySuiteWidget.typeLabel(): String {
    return when (type) {
        DiscoverySuiteWidgetType.TagBar.value -> "Tag 按键栏"
        DiscoverySuiteWidgetType.RankButtons.value -> "排行榜按钮"
        DiscoverySuiteWidgetType.RankedList.value -> "排行榜列表"
        DiscoverySuiteWidgetType.WaterfallBooks.value -> "瀑布流"
        DiscoverySuiteWidgetType.HorizontalBooks.value -> "横排滑动"
        else -> "随机推荐"
    }
}

// ExploreKind 套件派生扩展（normalizedSuiteDiscoverUrl / suiteDiscoverTagText / suiteDiscoverGroupTitle /
// isSuiteDiscoverGroupKind / isSuiteLeadingBlankPlaceholder / isSuiteFullWidthKind）已抽到
// DiscoverySuiteSourceOptions.kt —— 供编辑器与一键生成共用同一份判据。
