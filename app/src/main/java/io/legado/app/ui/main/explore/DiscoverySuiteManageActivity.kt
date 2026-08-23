package io.legado.app.ui.main.explore

import android.os.Bundle
import android.graphics.Color as AndroidColor
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import com.google.android.flexbox.FlexboxLayout
import io.legado.app.R
import io.legado.app.base.BaseActivity
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.rule.ExploreKind
import io.legado.app.databinding.ActivityThemeManageBinding
import io.legado.app.databinding.ItemFilletCompleteTextBinding
import io.legado.app.databinding.ItemFilletSelectorSingleBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.databinding.ItemFindBookBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.applyUiBodyTypefaceDeep
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.lib.theme.uiTypeface
import io.legado.app.help.source.exploreKinds
import io.legado.app.ui.main.bookshelf.compose.BookshelfListRenderConfig
import io.legado.app.ui.main.bookshelf.compose.rememberBookshelfListRenderConfig
import io.legado.app.ui.widget.compose.LegadoComposeTheme
import io.legado.app.ui.widget.compose.AppManagementListRow
import io.legado.app.ui.widget.compose.AppManagementMenuAction
import io.legado.app.ui.widget.compose.appSettingPanelBackground
import io.legado.app.ui.widget.compose.rememberAppManagementPalette
import io.legado.app.ui.widget.compose.showComposeConfirmDialog
import io.legado.app.ui.widget.compose.showComposeTextInputDialog
import io.legado.app.utils.dpToPx
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class DiscoverySuiteManageActivity : BaseActivity<ActivityThemeManageBinding>() {

    override val binding by viewBinding(ActivityThemeManageBinding::inflate)

    private var configState by mutableStateOf(DiscoverySuiteStore.load())
    private var selectedSuiteIdState by mutableStateOf(DiscoverySuiteStore.selectedSuiteId())
    private var sourceTagOptionsState by mutableStateOf<List<DiscoverySuiteSourceTagOptions>>(emptyList())
    private var loadingTagsState by mutableStateOf(false)
    private var loadingSourceTagUrlsState by mutableStateOf<Set<String>>(emptySet())
    private var loadedSourceTagUrlsState by mutableStateOf<Set<String>>(emptySet())
    private var screenModeState by mutableStateOf<DiscoverySuiteManageMode>(DiscoverySuiteManageMode.List)

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        binding.titleBar.setNavigationOnClickListener { handleBackNavigation() }
        onBackPressedDispatcher.addCallback(this) {
            handleBackNavigation()
        }
        initComposeContent()
        refreshConfig()
        updateTitleBar()
        loadSourceOptions()
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        when (val mode = screenModeState) {
            DiscoverySuiteManageMode.List -> {
                menu.add(0, MENU_CREATE_SUITE, 0, R.string.discovery_suite_create).apply {
                    setIcon(R.drawable.ic_add)
                    setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                }
            }
            is DiscoverySuiteManageMode.Detail -> {
                if (configState.suites.any { it.id == mode.suiteId }) {
                    menu.add(0, MENU_ADD_WIDGET, 0, R.string.discovery_suite_add_widget).apply {
                        setIcon(R.drawable.ic_add)
                        setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                    }
                }
            }
            is DiscoverySuiteManageMode.WidgetEditor -> Unit
        }
        return true
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            MENU_CREATE_SUITE -> {
                showCreateSuiteDialog()
                return true
            }
            MENU_ADD_WIDGET -> {
                val mode = screenModeState as? DiscoverySuiteManageMode.Detail ?: return true
                configState.suites.firstOrNull { it.id == mode.suiteId }?.let {
                    openWidgetEditor(it, null)
                }
                return true
            }
        }
        return super.onCompatOptionsItemSelected(item)
    }

    private fun initComposeContent() {
        val container = binding.recyclerView.parent as? ViewGroup ?: return
        val index = container.indexOfChild(binding.recyclerView)
        container.removeView(binding.recyclerView)
        container.removeView(binding.tabBar)
        container.removeView(binding.tvSummary)
        container.removeView(binding.btnAdd)
        val composeView = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setContent {
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
                                tagOptionCount = sourceTagOptionsState.sumOf { it.tags.size },
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
                                tagOptionCount = sourceTagOptionsState.sumOf { it.tags.size },
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
        container.addView(composeView, index.coerceAtMost(container.childCount))
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
        binding.titleBar.title = when (val mode = screenModeState) {
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
                appDb.bookSourceDao.allEnabledPart
                    .filter { it.enabledExplore && it.hasExploreUrl }
                    .take(MAX_MANAGER_SOURCES)
                    .map { source ->
                        DiscoverySuiteSourceTagOptions(
                            sourceName = source.bookSourceName,
                            sourceUrl = source.bookSourceUrl,
                            kinds = emptyList(),
                            tags = emptyList()
                        )
                    }
            }
            val loadedByUrl = sourceTagOptionsState.associateBy { it.sourceUrl }
            sourceTagOptionsState = options.map { option ->
                loadedByUrl[option.sourceUrl]?.takeIf { it.kinds.isNotEmpty() || it.tags.isNotEmpty() }
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
        lifecycleScope.launch {
            val option = withContext(IO) {
                appDb.bookSourceDao.allEnabledPart
                    .firstOrNull { it.bookSourceUrl == sourceUrl }
                    ?.takeIf { it.enabledExplore && it.hasExploreUrl }
                    ?.toSourceTagOptions()
                    ?: DiscoverySuiteSourceTagOptions(
                        sourceName = sourceName,
                        sourceUrl = sourceUrl,
                        kinds = emptyList(),
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

    private suspend fun BookSourcePart.toSourceTagOptions(): DiscoverySuiteSourceTagOptions {
        return runCatching {
            buildSourceTagOptions()
        }.getOrElse {
            DiscoverySuiteSourceTagOptions(
                sourceName = bookSourceName,
                sourceUrl = bookSourceUrl,
                kinds = emptyList(),
                tags = emptyList()
            )
        }
    }

    private suspend fun BookSourcePart.buildSourceTagOptions(): DiscoverySuiteSourceTagOptions {
        val result = ArrayList<DiscoverySuiteTagOption>()
        var currentGroup = ""
        val kinds = exploreKinds()
        kinds.forEachIndexed { index, kind ->
            if (index == 0 && kind.isSuiteLeadingBlankPlaceholder()) {
                return@forEachIndexed
            }
            val url = kind.normalizedSuiteDiscoverUrl()
            val action = kind.action?.takeIf { it.isNotBlank() }
            if (url.isNullOrBlank() && action.isNullOrBlank() && kind.isSuiteDiscoverGroupKind()) {
                currentGroup = kind.suiteDiscoverGroupTitle()
                return@forEachIndexed
            }
            if (!url.isNullOrBlank()) {
                result += DiscoverySuiteTagOption(
                    sourceName = bookSourceName,
                    sourceUrl = bookSourceUrl,
                    tagTitle = kind.suiteDiscoverTagText(),
                    tagUrl = url,
                    group = currentGroup
                )
            }
        }
        val tags = if (result.any { it.group.isNotBlank() }) {
            result.map {
                if (it.group.isBlank()) it.copy(group = getString(R.string.discover_group_other)) else it
            }
        } else {
            result
        }
        return DiscoverySuiteSourceTagOptions(
            sourceName = bookSourceName,
            sourceUrl = bookSourceUrl,
            kinds = kinds,
            tags = tags.distinctBy { it.key }
        )
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
            message = suite.displayName,
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

    private fun saveWidget(
        suiteId: String,
        oldWidget: DiscoverySuiteWidget?,
        title: String,
        type: String,
        targets: List<DiscoverySuiteWidgetTarget>
    ) {
        if (targets.isEmpty()) {
            toastOnUi(R.string.find_empty)
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
        if (cleanType in setOf(
                DiscoverySuiteWidgetType.RankButtons.value,
                DiscoverySuiteWidgetType.RankedList.value
            ) && cleanTargets.size !in 3..9
        ) {
            toastOnUi("排行榜控件需要选择 3-9 个 Tag")
            return
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
        closeWidgetEditor(suiteId)
    }

    private fun reorderWidgets(suite: DiscoverySuite, orderedWidgets: List<DiscoverySuiteWidget>) {
        val normalizedWidgets = orderedWidgets.keepWaterfallWidgetsAtBottom()
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

    companion object {
        private const val MAX_MANAGER_SOURCES = 200
        private const val MENU_CREATE_SUITE = 1
        private const val MENU_ADD_WIDGET = 2
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

private data class DiscoverySuiteSourceTagOptions(
    val sourceName: String,
    val sourceUrl: String,
    val kinds: List<ExploreKind>,
    val tags: List<DiscoverySuiteTagOption>
)

private data class DiscoverySuiteTagOption(
    val sourceName: String,
    val sourceUrl: String,
    val tagTitle: String,
    val tagUrl: String,
    val group: String = ""
) {
    val key: String
        get() = "$sourceUrl\n$tagUrl"

    fun toTarget(): DiscoverySuiteWidgetTarget {
        return DiscoverySuiteWidgetTarget(
            sourceUrl = sourceUrl,
            tagUrl = tagUrl,
            title = "$sourceName - $tagTitle"
        )
    }
}

@Composable
private fun DiscoverySuiteListScreen(
    config: DiscoverySuiteConfig,
    selectedSuiteId: String,
    loadingOptions: Boolean,
    sourceCount: Int,
    tagOptionCount: Int,
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
            Text(
                text = if (loadingOptions) {
                    "Tag 加载中..."
                } else {
                    "${sourceCount} 个书源 / ${tagOptionCount} 个 Tag"
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 14.dp, end = 18.dp),
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
        }
        items(config.suites, key = { it.id }) { suite ->
            val isCurrent = suite.id == selectedSuiteId
            AppManagementListRow(
                title = suite.displayName,
                subtitle = if (isCurrent) {
                    "当前套件 · ${suite.widgets.size} 个控件"
                } else {
                    "${suite.widgets.size} 个控件"
                },
                selected = isCurrent,
                selectionVisible = false,
                palette = managementPalette,
                minHeight = 58.dp,
                moreActions = buildList {
                    add(AppManagementMenuAction(text = stringResource(R.string.edit), onClick = { onOpenSuite(suite) }))
                    if (!isCurrent) {
                        add(AppManagementMenuAction(text = "设为当前", onClick = { onSetCurrentSuite(suite) }))
                    }
                    add(AppManagementMenuAction(text = "重命名", onClick = { onRenameSuite(suite) }))
                    add(AppManagementMenuAction(text = "别名", onClick = { onAliasSuite(suite) }))
                    add(AppManagementMenuAction(text = "删除", danger = true, onClick = { onDeleteSuite(suite) }))
                },
                onClick = { onOpenSuite(suite) }
            )
        }
        if (config.suites.isEmpty()) {
            item {
                EmptyManageState(renderConfig = renderConfig)
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
    tagOptionCount: Int,
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
                "Tag 加载中..."
            } else {
                "${sourceCount} 个书源 / ${tagOptionCount} 个 Tag"
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, top = 14.dp, end = 18.dp),
            fontSize = 13.sp,
            fontFamily = palette.bodyFontFamily,
            color = palette.secondaryText
        )
        if (suite == null) {
            Text(
                text = "套件不存在",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 48.dp),
                fontSize = 16.sp,
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
                    fontSize = 15.sp,
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
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = palette.bodyFontFamily,
                color = palette.primaryText
            )
            Text(
                text = "当前 ${"%.2f".format(value)}x，仅增强套件页面板不透明度",
                fontSize = 12.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        CompactAction(text = "-0.25", renderConfig = renderConfig) {
            onValueChange((value - 0.25f).coerceAtLeast(1f))
        }
        CompactAction(text = "+0.25", renderConfig = renderConfig) {
            onValueChange((value + 0.25f).coerceAtMost(4f))
        }
    }
}

@Composable
private fun DiscoverySuiteWidgetEditorScreen(
    suite: DiscoverySuite?,
    widget: DiscoverySuiteWidget?,
    sourceOptions: List<DiscoverySuiteSourceTagOptions>,
    loadingOptions: Boolean,
    loadingSourceUrls: Set<String>,
    loadedSourceUrls: Set<String>,
    onLoadSourceTags: (String) -> Unit,
    onSave: (String, String, List<DiscoverySuiteWidgetTarget>) -> Unit,
    onCancel: () -> Unit
) {
    val renderConfig = rememberBookshelfListRenderConfig()
    val palette = renderConfig.palette
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
    var selectedSourceUrl by remember(widget?.id) {
        mutableStateOf(widget?.targets?.firstOrNull()?.sourceUrl.orEmpty())
    }
    LaunchedEffect(sourceOptions, widget?.id) {
        if (sourceOptions.isEmpty()) return@LaunchedEffect
        if (sourceOptions.none { it.sourceUrl == selectedSourceUrl }) {
            selectedSourceUrl = widget?.targets
                ?.firstOrNull()
                ?.sourceUrl
                ?.takeIf { sourceUrl -> sourceOptions.any { it.sourceUrl == sourceUrl } }
                ?: sourceOptions.first().sourceUrl
        }
    }
    val selectedSource = sourceOptions.firstOrNull { it.sourceUrl == selectedSourceUrl }
        ?: sourceOptions.firstOrNull()
    val selectedSourceIsLoading = selectedSource?.sourceUrl in loadingSourceUrls
    val selectedSourceLoaded = selectedSource?.sourceUrl in loadedSourceUrls
    LaunchedEffect(selectedSource?.sourceUrl) {
        selectedSource?.sourceUrl
            ?.takeIf { it.isNotBlank() && it !in loadingSourceUrls && it !in loadedSourceUrls }
            ?.let(onLoadSourceTags)
    }
    var sourceQuery by remember(widget?.id) { mutableStateOf("") }
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
                        fontSize = 22.sp,
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        WidgetTypeChip(
                            text = "随机推荐",
                            selected = type == DiscoverySuiteWidgetType.RandomBooks.value,
                            renderConfig = renderConfig
                        ) {
                            type = DiscoverySuiteWidgetType.RandomBooks.value
                        }
                        WidgetTypeChip(
                            text = "Tag 按键栏",
                            selected = type == DiscoverySuiteWidgetType.TagBar.value,
                            renderConfig = renderConfig
                        ) {
                            type = DiscoverySuiteWidgetType.TagBar.value
                        }
                        WidgetTypeChip(
                            text = "排行榜按钮",
                            selected = type == DiscoverySuiteWidgetType.RankButtons.value,
                            renderConfig = renderConfig
                        ) {
                            type = DiscoverySuiteWidgetType.RankButtons.value
                            selectedKeys = selectedKeys.take(9).toSet()
                        }
                        WidgetTypeChip(
                            text = "排行榜列表",
                            selected = type == DiscoverySuiteWidgetType.RankedList.value,
                            renderConfig = renderConfig
                        ) {
                            type = DiscoverySuiteWidgetType.RankedList.value
                            selectedKeys = selectedKeys.take(9).toSet()
                        }
                        WidgetTypeChip(
                            text = "瀑布流",
                            selected = type == DiscoverySuiteWidgetType.WaterfallBooks.value,
                            renderConfig = renderConfig
                        ) {
                            type = DiscoverySuiteWidgetType.WaterfallBooks.value
                        }
                        WidgetTypeChip(
                            text = "横排滑动",
                            selected = type == DiscoverySuiteWidgetType.HorizontalBooks.value,
                            renderConfig = renderConfig
                        ) {
                            type = DiscoverySuiteWidgetType.HorizontalBooks.value
                            selectedKeys = selectedKeys.take(1).toSet()
                        }
                    }
                    Text(
                        text = when {
                            loadingOptions -> "书源加载中..."
                            sourceOptions.isEmpty() -> "没有可用书源"
                            selectedSourceIsLoading -> "正在加载当前书源 Tag；已选 ${selectedKeys.size} 个"
                            selectedSourceLoaded && selectedSource?.tags.isNullOrEmpty() -> "当前书源没有可用 Tag；已选 ${selectedKeys.size} 个"
                            type == DiscoverySuiteWidgetType.HorizontalBooks.value -> "横排滑动控件只能选择 1 个 Tag；已选 ${selectedKeys.size} 个"
                            type == DiscoverySuiteWidgetType.RankButtons.value -> "排行榜按钮需要选择 3-9 个 Tag；已选 ${selectedKeys.size} 个"
                            type == DiscoverySuiteWidgetType.RankedList.value -> "排行榜列表需要选择 3-9 个 Tag；已选 ${selectedKeys.size} 个"
                            type == DiscoverySuiteWidgetType.WaterfallBooks.value -> "瀑布流控件会固定在所有控件底部；已选 ${selectedKeys.size} 个 Tag"
                            else -> "先选书源，再选择该书源下的 Tag；已选 ${selectedKeys.size} 个"
                        },
                        fontSize = 14.sp,
                        fontFamily = palette.bodyFontFamily,
                        color = palette.secondaryText
                    )
                    if (sourceOptions.isNotEmpty()) {
                        SuiteEditorTextField(
                            value = sourceQuery,
                            onValueChange = { sourceQuery = it.take(40) },
                            label = "搜索书源",
                            renderConfig = renderConfig
                        )
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(filteredSources, key = { it.sourceUrl }) { source ->
                                SourceOptionChip(
                                    source = source,
                                    selected = source.sourceUrl == selectedSource?.sourceUrl,
                                    loading = source.sourceUrl in loadingSourceUrls,
                                    loaded = source.sourceUrl in loadedSourceUrls,
                                    renderConfig = renderConfig
                                ) {
                                    selectedSourceUrl = source.sourceUrl
                                    onLoadSourceTags(source.sourceUrl)
                                }
                            }
                        }
                        if (selectedKeys.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "已选 ${selectedKeys.size} 个",
                                    fontSize = 13.sp,
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
                        selectedSource?.let { source ->
                            when {
                                source.sourceUrl in loadingSourceUrls -> {
                                    SourceTagsStatePanel(
                                        text = "${source.sourceName} Tag 加载中...",
                                        renderConfig = renderConfig
                                    )
                                }
                                source.sourceUrl !in loadedSourceUrls -> {
                                    SourceTagsStatePanel(
                                        text = "${source.sourceName} 尚未加载 Tag",
                                        renderConfig = renderConfig,
                                        actionText = "加载",
                                        onAction = { onLoadSourceTags(source.sourceUrl) }
                                    )
                                }
                                source.tags.isEmpty() -> {
                                    SourceTagsStatePanel(
                                        text = "${source.sourceName} 没有可用 Tag",
                                        renderConfig = renderConfig
                                    )
                                }
                                else -> {
                                    ClassicDiscoverPreview(
                                        source = source,
                                        selectedKeys = selectedKeys,
                                        singleSelection = type == DiscoverySuiteWidgetType.HorizontalBooks.value,
                                        renderConfig = renderConfig,
                                        onSelectedKeysChange = {
                                            selectedKeys = if (type == DiscoverySuiteWidgetType.RankButtons.value ||
                                                type == DiscoverySuiteWidgetType.RankedList.value
                                            ) {
                                                it.take(9).toSet()
                                            } else {
                                                it
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
        EditorBottomBar(
            renderConfig = renderConfig,
            onCancel = onCancel,
            onSave = {
                val targets = selectedKeys
                    .mapNotNull { targetByKey[it] }
                onSave(title, type, targets)
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
            fontSize = 14.sp,
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
private fun SourceOptionChip(
    source: DiscoverySuiteSourceTagOptions,
    selected: Boolean,
    loading: Boolean,
    loaded: Boolean,
    renderConfig: BookshelfListRenderConfig,
    onClick: () -> Unit
) {
    val palette = renderConfig.palette
    SuiteThemeChipSurface(
        selected = selected,
        renderConfig = renderConfig,
        modifier = Modifier
            .widthIn(min = 118.dp, max = 172.dp),
        height = 48.dp,
        horizontalPadding = 16.dp,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = source.sourceName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                fontFamily = palette.bodyFontFamily,
                color = if (selected) palette.accent else palette.primaryText
            )
            Text(
                text = when {
                    loading -> "加载中"
                    loaded -> "${source.tags.size} 个 Tag"
                    else -> "未加载"
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 11.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
        }
    }
}

@Composable
private fun ClassicDiscoverPreview(
    source: DiscoverySuiteSourceTagOptions,
    selectedKeys: Set<String>,
    singleSelection: Boolean,
    renderConfig: BookshelfListRenderConfig,
    onSelectedKeysChange: (Set<String>) -> Unit
) {
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(palette.panelRadius))
            .appSettingPanelBackground(
                normalColor = palette.rowColor,
                panelImage = renderConfig.panelImage,
                borderColor = palette.borderColor,
                radiusPx = palette.panelRadiusPx
            )
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "${source.sourceName} · 经典发现预览 · ${source.tags.size} 个可选 Tag",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.bodyFontFamily,
            color = palette.primaryText
        )
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 520.dp),
            factory = { context -> FrameLayout(context) },
            update = { container ->
                renderClassicDiscoverPreview(
                    container = container,
                    source = source,
                    selectedKeys = selectedKeys,
                    singleSelection = singleSelection,
                    onSelectedKeysChange = onSelectedKeysChange
                )
            }
        )
    }
}

private fun renderClassicDiscoverPreview(
    container: FrameLayout,
    source: DiscoverySuiteSourceTagOptions,
    selectedKeys: Set<String>,
    singleSelection: Boolean,
    onSelectedKeysChange: (Set<String>) -> Unit
) {
    val context = container.context
    val inflater = LayoutInflater.from(context)
    val itemBinding = ItemFindBookBinding.inflate(inflater, container, false)
    val flexbox = itemBinding.flexbox
    itemBinding.root.setPadding(0, 0, 0, 0)
    itemBinding.llTitle.isClickable = false
    itemBinding.tvName.text = source.sourceName
    itemBinding.rotateLoading.visibility = View.GONE
    itemBinding.ivStatus.visibility = View.GONE
    flexbox.visibility = View.VISIBLE
    flexbox.removeAllViews()
    val tagsByUrl = source.tags.associateBy { it.tagUrl }
    source.kinds.forEachIndexed { index, kind ->
        if (index == 0 && kind.isSuiteLeadingBlankPlaceholder()) {
            return@forEachIndexed
        }
        when (kind.type) {
            ExploreKind.Type.url -> {
                val url = kind.normalizedSuiteDiscoverUrl()
                if (url.isNullOrBlank()) {
                    addClassicTextPreview(inflater, flexbox, kind, selected = false, enabled = false)
                } else {
                    val option = tagsByUrl[url] ?: DiscoverySuiteTagOption(
                        sourceName = source.sourceName,
                        sourceUrl = source.sourceUrl,
                        tagTitle = kind.suiteDiscoverTagText(),
                        tagUrl = url
                    )
                    addClassicUrlTagPreview(
                        inflater = inflater,
                        flexbox = flexbox,
                        kind = kind,
                        option = option,
                        selected = option.key in selectedKeys,
                        onClick = {
                            onSelectedKeysChange(
                                if (option.key in selectedKeys) {
                                    selectedKeys - option.key
                                } else if (singleSelection) {
                                    setOf(option.key)
                                } else {
                                    selectedKeys + option.key
                                }
                            )
                        }
                    )
                }
            }
            ExploreKind.Type.select -> addClassicSelectPreview(inflater, flexbox, kind)
            ExploreKind.Type.text,
            "password" -> addClassicInputPreview(inflater, flexbox, kind)
            else -> addClassicTextPreview(inflater, flexbox, kind, selected = false, enabled = false)
        }
    }
    val scrollView = NestedScrollView(context).apply {
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        isFillViewport = false
        addView(
            itemBinding.root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }
    container.removeAllViews()
    container.addView(
        scrollView,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    )
}

private fun addClassicUrlTagPreview(
    inflater: LayoutInflater,
    flexbox: FlexboxLayout,
    kind: ExploreKind,
    option: DiscoverySuiteTagOption,
    selected: Boolean,
    onClick: () -> Unit
) {
    val tv = ItemFilletTextBinding.inflate(inflater, flexbox, false).root
    flexbox.addView(tv)
    tv.text = if (selected) "✓ ${option.tagTitle}" else option.tagTitle
    tv.maxLines = 1
    tv.ellipsize = TextUtils.TruncateAt.END
    tv.setPadding(14.dpToPx(), 4.dpToPx(), 14.dpToPx(), 4.dpToPx())
    applyClassicDiscoverFlexStyle(tv, kind)
    applyClassicDiscoverTagSelectedStyle(tv, selected)
    tv.setOnClickListener { onClick() }
}

private fun addClassicTextPreview(
    inflater: LayoutInflater,
    flexbox: FlexboxLayout,
    kind: ExploreKind,
    selected: Boolean,
    enabled: Boolean
) {
    val tv = ItemFilletTextBinding.inflate(inflater, flexbox, false).root
    flexbox.addView(tv)
    tv.text = kind.suiteDiscoverTagText()
    tv.typeface = flexbox.context.uiTypeface()
    tv.maxLines = 1
    tv.ellipsize = TextUtils.TruncateAt.END
    tv.isEnabled = enabled
    tv.alpha = if (enabled) 1f else 0.78f
    applyClassicDiscoverFlexStyle(tv, kind)
    applyClassicDiscoverTagSelectedStyle(tv, selected)
}

private fun addClassicSelectPreview(
    inflater: LayoutInflater,
    flexbox: FlexboxLayout,
    kind: ExploreKind
) {
    val binding = ItemFilletSelectorSingleBinding.inflate(inflater, flexbox, false)
    flexbox.addView(binding.root)
    binding.spName.text = kind.suiteDiscoverTagText()
    binding.root.applyUiBodyTypefaceDeep(binding.root.context.uiTypeface())
    val chars = kind.chars?.filterNotNull().orEmpty()
    val adapter = ArrayAdapter(binding.root.context, R.layout.item_text_common, chars)
    adapter.setDropDownViewResource(R.layout.item_spinner_dropdown)
    binding.spType.adapter = adapter
    val selected = chars.indexOf(kind.default).coerceAtLeast(0)
    if (chars.isNotEmpty()) {
        binding.spType.setSelection(selected, false)
    }
    binding.spType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = Unit
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    applyClassicDiscoverFlexStyle(binding.root, kind)
}

private fun addClassicInputPreview(
    inflater: LayoutInflater,
    flexbox: FlexboxLayout,
    kind: ExploreKind
) {
    val input = ItemFilletCompleteTextBinding.inflate(inflater, flexbox, false).root
    flexbox.addView(input)
    input.hint = kind.suiteDiscoverTagText()
    input.setText(kind.default.orEmpty())
    input.typeface = input.context.uiTypeface()
    input.isFocusable = false
    input.isFocusableInTouchMode = false
    applyClassicDiscoverFlexStyle(input, kind)
}

private fun applyClassicDiscoverFlexStyle(view: View, kind: ExploreKind) {
    val style = kind.style()
    view.layoutParams = FlexboxLayout.LayoutParams(
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
    when (style.layout_justifySelf) {
        "flex_start" -> setClassicDiscoverGravity(view, Gravity.START)
        "flex_end", "right" -> setClassicDiscoverGravity(view, Gravity.END)
        else -> setClassicDiscoverGravity(view, Gravity.CENTER)
    }
}

private fun setClassicDiscoverGravity(view: View, gravity: Int) {
    when (view) {
        is TextView -> view.gravity = gravity
        is LinearLayout -> view.gravity = gravity
    }
}

private fun applyClassicDiscoverTagSelectedStyle(tv: TextView, selected: Boolean) {
    val context = tv.context
    if (!selected) {
        tv.background = ContextCompat.getDrawable(context, R.drawable.selector_fillet_btn_bg)
        tv.setTextColor(context.primaryTextColor)
        return
    }
    val accent = context.accentColor
    val bg = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 16.dpToPx().toFloat()
        setColor(
            AndroidColor.argb(
                34,
                AndroidColor.red(accent),
                AndroidColor.green(accent),
                AndroidColor.blue(accent)
            )
        )
        setStroke(1.dpToPx(), accent)
    }
    tv.background = bg
    tv.setTextColor(accent)
}

@Composable
private fun FilterChip(
    text: String,
    selected: Boolean,
    renderConfig: BookshelfListRenderConfig,
    onClick: () -> Unit
) {
    val palette = renderConfig.palette
    SuiteThemeChipSurface(
        selected = selected,
        renderConfig = renderConfig,
        height = 34.dp,
        horizontalPadding = 13.dp,
        onClick = onClick
    ) {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            fontFamily = palette.bodyFontFamily,
            color = if (selected) palette.accent else palette.primaryText
        )
    }
}

@Composable
private fun EmptyManageState(
    renderConfig: BookshelfListRenderConfig
) {
    val palette = renderConfig.palette
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 72.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "还没有套件",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = palette.titleFontFamily,
            color = palette.primaryText
        )
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
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = palette.bodyFontFamily,
                color = palette.primaryText
            )
            Text(
                text = "${widget.typeLabel()} · ${widget.targets.size} 个 Tag",
                fontSize = 13.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
        }
        CompactAction(text = "编辑", renderConfig = renderConfig, onClick = onEdit)
        Spacer(modifier = Modifier.width(8.dp))
        CompactAction(text = "删除", renderConfig = renderConfig, danger = true, onClick = onDelete)
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
            fontSize = 14.sp,
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
private fun TagOptionRow(
    option: DiscoverySuiteTagOption,
    checked: Boolean,
    renderConfig: BookshelfListRenderConfig,
    onClick: () -> Unit
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
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onClick() })
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.tagTitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = palette.bodyFontFamily,
                color = palette.primaryText
            )
            Text(
                text = listOf(option.group, option.sourceName)
                    .filter { it.isNotBlank() }
                    .joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                fontFamily = palette.bodyFontFamily,
                color = palette.secondaryText
            )
        }
    }
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
            fontSize = 15.sp,
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
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = palette.bodyFontFamily,
            color = if (danger) Color(0xFFD44848) else palette.accent
        )
    }
}

@Composable
private fun SuiteThemeChipSurface(
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
                normalColor = if (selected) palette.rowPressedColor else palette.rowColor,
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

private fun List<DiscoverySuiteWidget>.keepWaterfallWidgetsAtBottom(): List<DiscoverySuiteWidget> {
    return filterNot { it.type == DiscoverySuiteWidgetType.WaterfallBooks.value } +
        filter { it.type == DiscoverySuiteWidgetType.WaterfallBooks.value }
}

private fun ExploreKind.normalizedSuiteDiscoverUrl(): String? {
    return url?.trim()?.takeIf {
        it.isNotBlank() && !it.equals("null", ignoreCase = true)
    }
}

private fun ExploreKind.suiteDiscoverTagText(): String {
    val rawViewName = viewName
    if (!rawViewName.isNullOrBlank() &&
        rawViewName.length in 3..28 &&
        rawViewName.first() == '\'' &&
        rawViewName.last() == '\''
    ) {
        return rawViewName.substring(1, rawViewName.length - 1)
    }
    return title.ifBlank { type }
}

private fun ExploreKind.suiteDiscoverGroupTitle(): String {
    val raw = suiteDiscoverTagText().trim()
    if (raw.isBlank()) return "分类"
    val normalized = raw
        .replace(Regex("[\\p{So}\\p{Sk}\\uFE0F]+"), " ")
        .replace(Regex("[\\uFF1A:|/\\\\]+"), " ")
        .replace(Regex("\\s{2,}"), " ")
        .trim()
    return normalized.ifBlank { raw }
}

private fun ExploreKind.isSuiteDiscoverGroupKind(): Boolean {
    if (!normalizedSuiteDiscoverUrl().isNullOrBlank()) return false
    if (!action.isNullOrBlank()) return false
    if (!isSuiteFullWidthKind()) return false
    return type == ExploreKind.Type.toggle || action.isNullOrBlank()
}

private fun ExploreKind.isSuiteLeadingBlankPlaceholder(): Boolean {
    if (!isSuiteFullWidthKind()) return false
    if (!normalizedSuiteDiscoverUrl().isNullOrBlank()) return false
    if (!action.isNullOrBlank()) return false
    val text = suiteDiscoverTagText().trim()
    if (text.isNotBlank() && text != ExploreKind.Type.button) return false
    return type == ExploreKind.Type.button || action.isNullOrBlank()
}

private fun ExploreKind.isSuiteFullWidthKind(): Boolean {
    val style = style()
    return style.layout_flexBasisPercent >= 0.95f ||
        (style.layout_flexGrow >= 1f && style.layout_flexBasisPercent < 0f)
}
